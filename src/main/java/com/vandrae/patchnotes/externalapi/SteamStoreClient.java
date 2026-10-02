package com.vandrae.patchnotes.externalapi;

import com.vandrae.patchnotes.externalapi.internal.SteamAppListEnvelope;
import com.vandrae.patchnotes.externalapi.internal.SteamChartsEnvelope;
import com.vandrae.patchnotes.externalapi.internal.SteamHttp;
import com.vandrae.patchnotes.externalapi.internal.SteamStoreItemsEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.retry.RetryException;
import org.springframework.core.retry.Retryable;
import org.springframework.core.retry.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Instant;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The catalog source: Steam's list of games, plus the store details and popularity data used to present and rank
 * them. Needs the API key, which goes in the query string, so every failure is reported by kind only; the key must
 * never reach a log or an exception message.
 */
@Component
public class SteamStoreClient {

    private static final Logger log = LoggerFactory.getLogger(SteamStoreClient.class);

    /** The most ids {@code GetItems} accepts in one request: ids travel in the URL, which Steam caps (~8 KB). */
    public static final int MAX_ITEMS_PER_REQUEST = 200;

    /** How many of a game's top store tags to ask for; the genre tags are nearly always among the first few. */
    private static final int TAGS_PER_ITEM = 30;

    private static final String FILENAME_PLACEHOLDER = "${FILENAME}";
    /** Relative asset paths come from Steam, but they end up in an {@code <img src>}: allow only plain path characters. */
    private static final Pattern SAFE_ASSET_PATH = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9/_.\\-]*(\\?t=\\d+)?$");

    private static final Pattern ICON_HASH = Pattern.compile("^[0-9a-f]{40}$");

    private final RestClient rest;
    private final RetryTemplate retry;
    /** For the details endpoint: no immediate retry on 429, the caller paces itself (see CatalogMetadataService). */
    private final RetryTemplate retryWithoutRateLimit;
    private final String apiKey;
    private final int pageSize;

    @Autowired
    public SteamStoreClient(SteamProperties props) {
        this(props, SteamHttp.restClient(props, props.storeReadTimeout()));
    }

    SteamStoreClient(SteamProperties props, RestClient rest) {
        this.rest = rest;
        this.retry = SteamHttp.retry(props, log);
        this.retryWithoutRateLimit = SteamHttp.retry(props, log, false);
        this.apiKey = props.apiKey();
        this.pageSize = Math.clamp(props.appListPageSize(), 1, 50_000);
    }

    public boolean isConfigured() {
        return apiKey != null && !apiKey.isBlank();
    }

    /**
     * One page of games (DLC, software, videos and hardware are excluded), ordered by app id.
     *
     * @param lastAppId     0 for the first page, then the previous page's {@code lastAppId}
     * @param modifiedSince if set, only apps changed since then (incremental sync)
     */
    public SteamAppPage getAppList(long lastAppId, Instant modifiedSince) {
        SteamAppListEnvelope envelope = call("app list", retry, () -> rest.get()
                .uri(uri -> {
                    uri.path("/IStoreService/GetAppList/v1/")
                            .queryParam("key", apiKey)
                            .queryParam("include_games", true)
                            .queryParam("include_dlc", false)
                            .queryParam("include_software", false)
                            .queryParam("include_videos", false)
                            .queryParam("include_hardware", false)
                            .queryParam("max_results", pageSize);
                    if (lastAppId > 0) {
                        uri.queryParam("last_appid", lastAppId);
                    }
                    if (modifiedSince != null) {
                        uri.queryParam("if_modified_since", modifiedSince.getEpochSecond());
                    }
                    return uri.build();
                })
                .retrieve()
                .body(SteamAppListEnvelope.class));

        if (envelope == null || envelope.response() == null) {
            return new SteamAppPage(List.of(), false, lastAppId);
        }
        var response = envelope.response();
        List<SteamApp> apps = response.apps() == null ? List.<SteamApp>of() : response.apps().stream()
                .map(a -> new SteamApp(a.appid(), a.name(), a.lastModified()))
                .toList();
        boolean hasMore = Boolean.TRUE.equals(response.haveMoreResults()) && !apps.isEmpty();
        return new SteamAppPage(apps, hasMore, response.lastAppId() == null ? lastAppId : response.lastAppId());
    }

    /**
     * Store details (description, cover image, review count) for up to {@link #MAX_ITEMS_PER_REQUEST} games at once.
     * Games without a store page (delisted, never released...) are simply absent from the result.
     */
    public Map<Long, SteamStoreItem> getStoreItems(Collection<Long> appIds) {
        if (appIds.isEmpty()) {
            return Map.of();
        }
        if (appIds.size() > MAX_ITEMS_PER_REQUEST) {
            throw new IllegalArgumentException("At most " + MAX_ITEMS_PER_REQUEST + " apps per request, got " + appIds.size());
        }
        String ids = appIds.stream().map(id -> "{\"appid\":" + id + "}").collect(Collectors.joining(","));
        String input = "{\"ids\":[" + ids + "],\"context\":{\"language\":\"english\",\"country_code\":\"US\",\"steam_realm\":1},"
                + "\"data_request\":{\"include_assets\":true,\"include_basic_info\":true,\"include_reviews\":true,"
                + "\"include_tag_count\":" + TAGS_PER_ITEM + "}}";

        SteamStoreItemsEnvelope envelope = call("store items", retryWithoutRateLimit, () -> rest.get()
                .uri(uri -> uri.path("/IStoreBrowseService/GetItems/v1/")
                        .queryParam("key", apiKey)
                        .queryParam("input_json", "{input}") // a template variable, so the JSON is URL-encoded, not parsed
                        .build(Map.of("input", input)))
                .retrieve()
                .body(SteamStoreItemsEnvelope.class));

        Map<Long, SteamStoreItem> result = new HashMap<>();
        if (envelope == null || envelope.response() == null || envelope.response().storeItems() == null) {
            return result;
        }
        for (var item : envelope.response().storeItems()) {
            Long appId = item.appid() != null ? item.appid() : item.id();
            if (appId == null || item.success() == null || item.success() != 1) {
                continue; // no store page for this app
            }
            String description = item.basicInfo() == null ? null : item.basicInfo().shortDescription();
            var summary = item.reviews() == null ? null : item.reviews().summaryFiltered();
            int reviews = summary == null || summary.reviewCount() == null ? 0 : summary.reviewCount();
            int score = summary == null || summary.reviewScore() == null ? 0 : summary.reviewScore();
            Integer percent = summary == null || summary.percentPositive() == null || reviews == 0 ? null
                    : Math.clamp(summary.percentPositive(), 0, 100);
            result.put(appId, new SteamStoreItem(appId, description, imagePath(item.assets()),
                    iconPath(appId, item.assets()), Math.max(reviews, 0), Math.clamp(score, 0, 9), percent,
                    item.tagIds() == null ? List.of() : item.tagIds().stream().filter(Objects::nonNull).toList()));
        }
        return result;
    }

    /** Steam's most-played chart: the top 100 games by peak concurrent players over the last day. */
    public List<SteamChartEntry> getMostPlayedGames() {
        SteamChartsEnvelope envelope = call("most played chart", retry, () -> rest.get()
                .uri(uri -> uri.path("/ISteamChartsService/GetMostPlayedGames/v1/").queryParam("key", apiKey).build())
                .retrieve()
                .body(SteamChartsEnvelope.class));
        if (envelope == null || envelope.response() == null || envelope.response().ranks() == null) {
            return List.of();
        }
        return envelope.response().ranks().stream()
                .filter(r -> r.appid() != null && r.peakInGame() != null)
                .map(r -> new SteamChartEntry(r.appid(), Math.max(r.peakInGame(), 0)))
                .toList();
    }

    /** Builds the relative image path from Steam's template, or null if there is no usable (and safe) image. */
    static String imagePath(SteamStoreItemsEnvelope.Assets assets) {
        if (assets == null || assets.assetUrlFormat() == null || !assets.assetUrlFormat().contains(FILENAME_PLACEHOLDER)) {
            return null;
        }
        String file = assets.smallCapsule() != null ? assets.smallCapsule() : assets.header();
        if (file == null || file.isBlank()) {
            return null;
        }
        String path = assets.assetUrlFormat().replace(FILENAME_PLACEHOLDER, file);
        return path.length() <= 300 && SAFE_ASSET_PATH.matcher(path).matches() && !path.contains("..") ? path : null;
    }

    /**
     * The square community icon as {@code {appid}/{hash}.jpg}, or null. The hash is always 40 lowercase hex characters;
     * anything else is refused, because it ends up in an {@code <img src>}.
     */
    static String iconPath(long appId, SteamStoreItemsEnvelope.Assets assets) {
        if (assets == null || assets.communityIcon() == null || !ICON_HASH.matcher(assets.communityIcon()).matches()) {
            return null;
        }
        return appId + "/" + assets.communityIcon() + ".jpg";
    }

    /** Runs a Steam call with retry; failures are described by kind only, never with a message or cause (the key is in the URL). */
    private <T> T call(String what, RetryTemplate template, Retryable<T> request) {
        if (!isConfigured()) {
            throw new IllegalStateException("A Steam API key (STEAM_API_KEY) is required to read the " + what);
        }
        try {
            return template.execute(request);
        } catch (RetryException e) {
            if (SteamHttp.isRateLimit(e.getCause())) {
                throw new SteamRateLimitedException("Steam " + what + " request was rate limited (HTTP 429)");
            }
            throw new SteamApiException("Steam " + what + " request failed after retries (" + SteamHttp.describe(e.getCause()) + ")", null);
        } catch (RuntimeException e) {
            throw new SteamApiException("Steam " + what + " request failed (" + SteamHttp.describe(e) + ")", null);
        }
    }
}
