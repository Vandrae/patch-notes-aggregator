package com.vandrae.patchnotes.catalog.internal;

import com.vandrae.patchnotes.catalog.CatalogService;
import com.vandrae.patchnotes.catalog.GameFilter;
import com.vandrae.patchnotes.catalog.GameSummary;
import com.vandrae.patchnotes.catalog.Genre;
import org.springframework.data.web.PagedModel;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Set;

@RestController
@RequestMapping("/api/games")
class GameController {

    private final CatalogService catalog;

    GameController(CatalogService catalog) {
        this.catalog = catalog;
    }

    @GetMapping
    PagedModel<GameSummary> search(@RequestParam(required = false) String q,
                                   @RequestParam(required = false) Set<Genre> genre,
                                   @RequestParam(defaultValue = "0") int minRating,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        return new PagedModel<>(catalog.search(q, new GameFilter(genre, minRating), page, size));
    }

    @GetMapping("/{id}")
    GameSummary get(@PathVariable long id) {
        return catalog.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Game not found"));
    }
}
