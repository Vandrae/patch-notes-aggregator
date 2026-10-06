package com.vandrae.patchnotes.web;

import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * The React app does its own routing ({@code /feed}, {@code /discover}, ...), so loading or refreshing one of those
 * URLs must return the app shell and let the browser-side router take over.
 *
 * <p>Only single-segment paths without a dot are forwarded. That deliberately leaves out {@code /api/...} (an unknown
 * API route must stay a 404, not turn into an HTML page), static files such as {@code /favicon.svg}, and
 * {@code /assets/...}. Nested routes are listed here explicitly: a game's page, {@code /games/42}.
 */
@Controller
class SpaController {

    @GetMapping({"/", "/{route:[^.]*}", "/games/{id:[0-9]+}"})
    String app() {
        return "forward:/index.html";
    }
}
