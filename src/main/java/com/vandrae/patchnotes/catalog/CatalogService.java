package com.vandrae.patchnotes.catalog;

import com.vandrae.patchnotes.catalog.internal.CatalogProperties;
import com.vandrae.patchnotes.catalog.internal.Game;
import com.vandrae.patchnotes.catalog.internal.GameRepository;
import com.vandrae.patchnotes.catalog.internal.NameSearch;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class CatalogService {

    static final int MAX_PAGE_SIZE = 50;
    /** Below this many characters a search matches names that START with the text only, not names that contain it. */
    static final int MIN_CONTAINS_LENGTH = 3;

    private final GameRepository games;
    private final String imageBaseUrl;
    private final String iconBaseUrl;

    CatalogService(GameRepository games, CatalogProperties properties) {
        this.games = games;
        this.imageBaseUrl = properties.imageBaseUrl();
        this.iconBaseUrl = properties.iconBaseUrl();
    }

    /**
     * Name search that ignores case, accents, punctuation and trademark symbols. Results are ranked by relevance (the
     * exact name, then names starting with the query, then names containing it, the last only for queries of 3+
     * characters so one or two letters don't match half the catalog) and, within the same relevance, by popularity.
     * A blank query lists the whole catalog, most popular first.
     */
    public Page<GameSummary> search(String query, int page, int size) {
        return search(query, GameFilter.NONE, page, size);
    }

    /** As {@link #search(String, int, int)}, narrowed to games with one of the filter's genres and at least its rating. */
    public Page<GameSummary> search(String query, GameFilter filter, int page, int size) {
        var f = Criteria.of(filter);
        Pageable pageable = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE)); // ordering is in the queries
        if (query == null || query.isBlank()) {
            return games.browse(f.minRating, f.anyGenre, f.genres, f.anyAge, f.ages, pageable).map(this::toSummary);
        }
        String normalized = NameSearch.normalize(query);
        if (normalized.isEmpty()) {
            // something was typed but it is all punctuation ("%", "!!!"): that matches nothing, not everything
            return Page.empty(pageable);
        }
        String prefix = normalized + "%";
        String contains = normalized.length() >= MIN_CONTAINS_LENGTH ? "%" + normalized + "%" : prefix;
        return games.search(normalized, prefix, contains, f.minRating, f.anyGenre, f.genres, f.anyAge, f.ages, pageable).map(this::toSummary);
    }

    /** The subset of {@code ids} that passes the filter, in no particular order. */
    public List<Long> filterIds(Collection<Long> ids, GameFilter filter) {
        if (ids.isEmpty()) {
            return List.of();
        }
        if (filter.isEmpty()) {
            return List.copyOf(ids);
        }
        var f = Criteria.of(filter);
        return games.matching(ids, f.minRating, f.anyGenre, f.genres, f.anyAge, f.ages);
    }

    /**
     * Makes sure a game that is not on Steam exists in the catalog, and returns its id. Safe to call on every start: an
     * existing game is kept (so people's watchlists stay valid) and only its description, ordering weight and art are refreshed.
     *
     * @param image      the cover and icon: files in this site's own {@code /art/} folder (or null for none), kept as the path they are
     *                   served from, where Steam's are paths under Steam's image servers
     * @param popularity where it sorts among Steam games when browsing, whose own weight is reviews plus ten times the
     *                   peak players on Steam's charts; a game that is not on Steam has no such number, so one is given
     */
    @Transactional
    public long ensureCustomGame(String name, String shortDescription, long popularity, String image, String icon) {
        Game game = games.findFirstByNameAndSourceType(name, SourceType.CUSTOM)
                .orElseGet(() -> games.saveAndFlush(new Game(name, null, SourceType.CUSTOM)));
        games.describeCustomGame(game.getId(), shortDescription, popularity, image, icon);
        return game.getId();
    }

    public Optional<GameSummary> findById(long id) {
        return games.findById(id).map(this::toSummary);
    }

    public boolean exists(long id) {
        return games.existsById(id);
    }

    public Map<Long, GameSummary> findAllById(Collection<Long> ids) {
        return games.findAllById(ids).stream()
                .map(this::toSummary)
                .collect(Collectors.toMap(GameSummary::id, Function.identity()));
    }

    /** A filter in the form the queries take: an empty genre list is not portable in JPQL, so a placeholder stands in. */
    private record Criteria(int minRating, boolean anyGenre, Collection<Genre> genres,
                            boolean anyAge, Collection<AgeRating> ages) {
        static Criteria of(GameFilter filter) {
            boolean anyGenre = filter.genres().isEmpty();
            boolean anyAge = filter.ageRatings().isEmpty();
            return new Criteria(filter.minRating(), anyGenre, anyGenre ? List.of(Genre.ACTION) : filter.genres(),
                    anyAge, anyAge ? List.of(AgeRating.EVERYONE) : filter.ageRatings());
        }
    }

    /** Steam's art is stored as a path under Steam's image servers; a game that is not on Steam has its own art on this site, kept as a path starting with "/" and used as it is. */
    private static String resolve(String base, String path) {
        if (path == null) {
            return null;
        }
        return path.startsWith("/") ? path : base + path;
    }

    private GameSummary toSummary(Game game) {
        String imageUrl = resolve(imageBaseUrl, game.getImagePath());
        String iconUrl = resolve(iconBaseUrl, game.getIconPath());
        return new GameSummary(game.getId(), game.getName(), game.getSourceType(), game.getSteamAppId(),
                game.getShortDescription(), imageUrl, iconUrl,
                game.getGenres().stream().sorted().toList(),
                Rating.of(game.getReviewScore(), game.getPercentPositive()), game.getAgeRating());
    }
}
