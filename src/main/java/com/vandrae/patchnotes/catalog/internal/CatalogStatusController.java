package com.vandrae.patchnotes.catalog.internal;

import com.vandrae.patchnotes.catalog.CatalogStatus;
import com.vandrae.patchnotes.catalog.CatalogSyncService;
import com.vandrae.patchnotes.catalog.Genre;
import com.vandrae.patchnotes.catalog.Rating;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

@RestController
@RequestMapping("/api/catalog")
class CatalogStatusController {

    /** The review levels worth offering as "this good or better"; below Mostly Positive nobody filters for them. */
    private static final int LOWEST_RATING_OFFERED = 6;

    private final CatalogSyncService sync;

    CatalogStatusController(CatalogSyncService sync) {
        this.sync = sync;
    }

    /** Lets the UI say "the catalog is still being imported" instead of showing a mysteriously empty search. */
    @GetMapping("/status")
    CatalogStatus status() {
        return sync.status();
    }

    public record GenreOption(Genre code, String label) {
    }

    public record RatingOption(int minRating, String label) {
    }

    public record Filters(List<GenreOption> genres, List<RatingOption> ratings) {
    }

    /** What the genre and rating filters offer, so the UI doesn't hard-code the lists. */
    @GetMapping("/filters")
    Filters filters() {
        return new Filters(
                Arrays.stream(Genre.values()).map(g -> new GenreOption(g, g.label())).toList(),
                IntStream.rangeClosed(LOWEST_RATING_OFFERED, Rating.MAX_SCORE)
                        .mapToObj(score -> new RatingOption(score, Rating.label(score)))
                        .toList());
    }
}
