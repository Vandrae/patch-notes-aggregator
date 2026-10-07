package com.vandrae.patchnotes.catalog.internal;

import com.vandrae.patchnotes.catalog.AgeRating;
import com.vandrae.patchnotes.catalog.Genre;
import com.vandrae.patchnotes.catalog.SourceType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface GameRepository extends JpaRepository<Game, Long> {

    /** Score boost for a name that is exactly the query. See {@link #search}. */
    String EXACT_BONUS = "3.0";
    /** Score boost for a name that starts with the query. See {@link #search}. */
    String PREFIX_BONUS = "1.5";

    /**
     * The optional filters shared by every query below. {@code anyGenre = true} means "no genre filter" (an empty
     * {@code IN} list is not portable, so callers pass a placeholder list then); {@code minRating = 0} means "any rating";
     * {@code anyAge = true} means "any age rating" (likewise with a placeholder list).
     * A game matches when it has AT LEAST ONE of the genres, a review level of at least {@code minRating}, and one of the
     * age ratings.
     */
    String FILTERS = " and g.reviewScore >= :minRating and (:anyGenre = true "
            + "or exists (select 1 from g.genres ge where ge in :genres))"
            + " and (:anyAge = true or g.ageRating in :ages)";

    /**
     * Games whose normalized name matches {@code contains}, best first, ranked by ONE blended score:
     * <pre>  score = relevance bonus + log10(1 + popularity)</pre>
     * where the bonus is {@value #EXACT_BONUS} for the exact name, {@value #PREFIX_BONUS} for names starting with the
     * query, and 0 for names merely containing it. Popularity is on a log scale (a thousand-fold difference is worth 3
     * points), so:
     * <ul>
     *   <li>an exact name beats a prefix match of similar popularity ("Portal" before "Portal 2"), and a prefix match
     *       beats a "contains" match of similar popularity;</li>
     *   <li>but a game roughly 100x more popular can overtake an exact match, so an obscure game that happens to be
     *       called "WAR!" doesn't sit above War Thunder, Warframe and WARDOGS when you search "war";</li>
     *   <li>with equal popularity (e.g. no details yet) it reduces to exact, then prefix, then contains, alphabetical.</li>
     * </ul>
     * The inputs are already normalized (letters, digits, spaces only), so they can't contain LIKE wildcards.
     */
    @Query(value = "select g from Game g where g.nameSearch like :contains" + FILTERS
            + " order by (case when g.nameSearch = :exact then " + EXACT_BONUS
            + " when g.nameSearch like :prefix then " + PREFIX_BONUS
            + " else 0.0 end + log10(g.popularity + 1)) desc, g.name",
            countQuery = "select count(g) from Game g where g.nameSearch like :contains" + FILTERS)
    Page<Game> search(@Param("exact") String exact, @Param("prefix") String prefix,
                      @Param("contains") String contains,
                      @Param("minRating") int minRating, @Param("anyGenre") boolean anyGenre,
                      @Param("genres") Collection<Genre> genres,
                      @Param("anyAge") boolean anyAge, @Param("ages") Collection<AgeRating> ages, Pageable pageable);

    /** Browsing without a query: the whole catalog, most popular first. */
    @Query(value = "select g from Game g where true" + FILTERS + " order by g.popularity desc, g.name",
            countQuery = "select count(g) from Game g where true" + FILTERS)
    Page<Game> browse(@Param("minRating") int minRating, @Param("anyGenre") boolean anyGenre,
                      @Param("genres") Collection<Genre> genres,
                      @Param("anyAge") boolean anyAge, @Param("ages") Collection<AgeRating> ages, Pageable pageable);

    /** Which of {@code ids} pass the filters (used to narrow a user's own watched games). */
    @Query("select g.id from Game g where g.id in :ids" + FILTERS)
    List<Long> matching(@Param("ids") Collection<Long> ids,
                        @Param("minRating") int minRating, @Param("anyGenre") boolean anyGenre,
                        @Param("genres") Collection<Genre> genres,
                        @Param("anyAge") boolean anyAge, @Param("ages") Collection<AgeRating> ages);

    boolean existsBySteamAppId(Long steamAppId);

    Optional<Game> findFirstByNameAndSourceType(String name, SourceType sourceType);

    /** Description and ordering weight for a game that is not on Steam (the details job only ever touches Steam games). */
    @Modifying
    @Query(value = "UPDATE game SET short_description = :description, popularity = :popularity WHERE id = :id", nativeQuery = true)
    void describeCustomGame(@Param("id") long id, @Param("description") String description, @Param("popularity") long popularity);
}
