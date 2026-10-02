package com.vandrae.patchnotes.catalog;

import com.vandrae.patchnotes.catalog.internal.CatalogProperties;
import com.vandrae.patchnotes.catalog.internal.GameMetadataStore;
import com.vandrae.patchnotes.catalog.internal.GameMetadataStore.Details;
import com.vandrae.patchnotes.catalog.internal.GameMetadataStore.Target;
import com.vandrae.patchnotes.externalapi.SteamApiException;
import com.vandrae.patchnotes.externalapi.SteamChartEntry;
import com.vandrae.patchnotes.externalapi.SteamRateLimitedException;
import com.vandrae.patchnotes.externalapi.SteamStoreClient;
import com.vandrae.patchnotes.externalapi.SteamStoreItem;
import org.jsoup.Jsoup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fills in what the catalog list doesn't have: a short description, a cover image and a popularity score for each
 * game, fetched from Steam 200 games per request.
 *
 * <p><b>Pacing.</b> Steam throttles this endpoint hard (measured: after a short burst, about one request per three
 * seconds; anything faster is answered with HTTP 429, and the rejected request still counts). So this is ONE worker
 * that spaces its requests, and when Steam does say "too many requests" it waits (doubling each time) and retries
 * <i>the same batch</i> rather than skipping it. The whole ~190,000-game catalog is therefore about a thousand
 * requests, roughly 50 minutes in the background on a first run; later runs only touch games that are new, changed on
 * Steam, or stale. Search works throughout and improves as details arrive.
 *
 * <p><b>Order.</b> Most useful first: games on Steam's most-played chart, then the most recently updated, then the rest.
 *
 * <p><b>Genre and rating.</b> The same request also returns each game's top store tags (the standard genres are among
 * them) and Steam's review level, which is what Discover and the feed filter on.
 *
 * * <p><b>Popularity</b> is {@code reviews + 10 * peak players on the most-played chart}. Reviews cover almost every
 * game; the chart covers hugely played games with few or no reviews yet (Valve's Deadlock has none), which reviews
 * alone would rank at the bottom. It is a ranking heuristic, not a statistic.
 */
@Service
public class CatalogMetadataService {

    private static final Logger log = LoggerFactory.getLogger(CatalogMetadataService.class);
    static final int DESCRIPTION_MAX_CHARS = 300;
    private static final int LOG_EVERY_REQUESTS = 25;

    private final SteamStoreClient steam;
    private final GameMetadataStore store;
    private final CatalogProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    CatalogMetadataService(SteamStoreClient steam, GameMetadataStore store, CatalogProperties properties) {
        this.steam = steam;
        this.store = store;
        this.properties = properties;
    }

    public boolean isConfigured() {
        return steam.isConfigured();
    }

    public boolean isEnriching() {
        return running.get();
    }

    /** How many games have had their details fetched (including those Steam has no store page for). */
    public long detailsLoaded() {
        return store.countWithDetails();
    }

    /** Games still waiting for details (never fetched, changed on Steam, or stale). */
    public long pending() {
        return store.countPending(staleBefore(Instant.now()));
    }

    /** Runs unless a run is already in progress (then returns empty). */
    public Optional<MetadataResult> tryEnrich() {
        if (!running.compareAndSet(false, true)) {
            log.info("Game details refresh requested but one is already running");
            return Optional.empty();
        }
        try {
            return Optional.of(enrich());
        } finally {
            running.set(false);
        }
    }

    private MetadataResult enrich() {
        var config = properties.metadata();
        Instant started = Instant.now();
        refreshChartQuietly();
        Instant staleBefore = staleBefore(started);
        long pendingAtStart = store.countPending(staleBefore);
        log.info("Game details refresh starting: {} games pending (~{} requests, one every {}+)",
                pendingAtStart, pendingAtStart / SteamStoreClient.MAX_ITEMS_PER_REQUEST + 1, config.minRequestInterval());

        Set<Long> skippedThisRun = new HashSet<>(); // batches that failed outright: the NEXT run retries them
        Duration interval = config.minRequestInterval();
        Duration backoff = config.rateLimitBackoff();
        long lastRequestNanos = 0;
        int requests = 0;
        int processed = 0;
        int withDetails = 0;
        int failedBatches = 0;
        int failedInARow = 0;
        int rateLimited = 0;
        int rateLimitedInARow = 0;

        while (true) {
            // fetch a little extra so skipped games can be filtered out without ending the run early
            List<Target> batch = store.nextTargets(staleBefore, SteamStoreClient.MAX_ITEMS_PER_REQUEST + skippedThisRun.size()).stream()
                    .filter(t -> !skippedThisRun.contains(t.steamAppId()))
                    .limit(SteamStoreClient.MAX_ITEMS_PER_REQUEST)
                    .toList();
            if (batch.isEmpty()) {
                break;
            }
            if (lastRequestNanos != 0) {
                sleepUntil(lastRequestNanos, interval);
            }
            lastRequestNanos = System.nanoTime();
            try {
                Map<Long, SteamStoreItem> items = steam.getStoreItems(batch.stream().map(Target::steamAppId).toList());
                store.saveDetails(toDetails(batch, items), Instant.now());
                processed += batch.size();
                withDetails += items.size();
                failedInARow = 0;
                rateLimitedInARow = 0;
                backoff = config.rateLimitBackoff();
                interval = max(config.minRequestInterval(), interval.multipliedBy(9).dividedBy(10)); // recover speed after a slowdown
                if (++requests % LOG_EVERY_REQUESTS == 0) {
                    log.info("Game details refresh: {} games done, {} left", processed, store.countPending(staleBefore));
                }
            } catch (SteamRateLimitedException e) {
                rateLimited++;
                if (++rateLimitedInARow >= config.maxRateLimitedInARow()) {
                    throw new SteamApiException("Giving up the game details refresh: Steam throttled us " + rateLimitedInARow
                            + " times in a row. Progress is kept; the next run resumes.", null);
                }
                interval = min(config.maxRateLimitBackoff(), interval.multipliedBy(3).dividedBy(2)); // stay a bit slower from now on
                log.warn("Steam asked us to slow down: waiting {} then retrying the same batch (request spacing now {})", backoff, interval);
                sleep(backoff);
                backoff = min(config.maxRateLimitBackoff(), backoff.multipliedBy(2));
                lastRequestNanos = System.nanoTime();
            } catch (SteamApiException e) {
                failedBatches++;
                batch.forEach(t -> skippedThisRun.add(t.steamAppId())); // stay pending for the next run
                log.warn("Game details batch failed: {}", e.getMessage());
                if (++failedInARow >= config.maxFailedBatches()) {
                    throw new SteamApiException("Giving up the game details refresh: " + failedInARow
                            + " batches in a row failed (Steam unavailable?). Progress is kept; the next run resumes.", null);
                }
            }
        }
        MetadataResult result = new MetadataResult(processed, withDetails, failedBatches, rateLimited, Duration.between(started, Instant.now()));
        log.info("Game details refresh finished in {} s: {}", result.took().toSeconds(), result);
        return result;
    }

    /** Refreshes the "most played" chart: best effort, because popularity falls back to review counts without it. */
    private void refreshChartQuietly() {
        try {
            List<SteamChartEntry> chart = steam.getMostPlayedGames();
            store.applyChart(chart.stream().map(e -> new long[]{e.appId(), e.peakPlayers()}).toList());
            log.info("Most-played chart refreshed ({} games)", chart.size());
        } catch (SteamApiException e) {
            log.warn("Could not refresh the most-played chart, keeping the previous one: {}", e.getMessage());
        }
    }

    private static List<Details> toDetails(List<Target> group, Map<Long, SteamStoreItem> items) {
        List<Details> rows = new ArrayList<>(group.size());
        for (Target target : group) {
            SteamStoreItem item = items.get(target.steamAppId());
            // no store page: still stamp the game as fetched so it isn't re-requested on every run
            rows.add(item == null
                    ? new Details(target.steamAppId(), null, null, null, 0, 0, null, Set.of(), null)
                    : new Details(target.steamAppId(), cleanDescription(item.shortDescription()), item.imagePath(),
                            item.iconPath(), item.reviewCount(), item.reviewScore(), item.percentPositive(),
                            genresOf(item.tagIds()), AgeRating.fromSteam(item.ageRating()).orElse(null)));
        }
        return rows;
    }

    /** Keeps only the tags that are one of the standard genres. */
    static Set<Genre> genresOf(List<Long> tagIds) {
        Set<Genre> genres = EnumSet.noneOf(Genre.class);
        for (long tagId : tagIds) {
            Genre.fromSteamTag(tagId).ifPresent(genres::add);
        }
        return genres;
    }

    /** Steam's descriptions can contain HTML/entities; show plain text, a few lines at most, cut on a word boundary. */
    static String cleanDescription(String raw) {
        if (raw == null) {
            return null;
        }
        String text = Jsoup.parse(raw).text().replaceAll("\\s+", " ").strip();
        if (text.isEmpty()) {
            return null;
        }
        if (text.length() <= DESCRIPTION_MAX_CHARS) {
            return text;
        }
        int cut = text.lastIndexOf(' ', DESCRIPTION_MAX_CHARS);
        return text.substring(0, cut > DESCRIPTION_MAX_CHARS / 2 ? cut : DESCRIPTION_MAX_CHARS).strip() + "…";
    }

    private Instant staleBefore(Instant now) {
        return now.minus(properties.metadata().staleAfter());
    }

    /** Waits until {@code interval} has passed since the last request started. */
    private static void sleepUntil(long lastRequestNanos, Duration interval) {
        long remainingNanos = interval.toNanos() - (System.nanoTime() - lastRequestNanos);
        if (remainingNanos > 0) {
            sleep(Duration.ofNanos(remainingNanos));
        }
    }

    private static void sleep(Duration duration) {
        if (duration.isZero() || duration.isNegative()) {
            return;
        }
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SteamApiException("Game details refresh interrupted", null);
        }
    }

    private static Duration max(Duration a, Duration b) {
        return a.compareTo(b) >= 0 ? a : b;
    }

    private static Duration min(Duration a, Duration b) {
        return a.compareTo(b) <= 0 ? a : b;
    }
}
