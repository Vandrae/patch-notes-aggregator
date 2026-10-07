package com.vandrae.patchnotes.fetch.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;
import java.util.List;

/**
 * The games that are not on Steam and where their patch notes come from, written in {@code application.yml}. Adding a game
 * that publishes an RSS or Atom feed (or a Zendesk help centre) takes only an entry here: no code.
 *
 * @param enabled false to register none of them (the test profile does, so the catalog holds only what a test puts there)
 * @param games   the games to register at startup
 */
@ConfigurationProperties("app.custom")
public record CustomGamesProperties(@DefaultValue("true") boolean enabled, List<CustomGame> games) {

    public CustomGamesProperties {
        games = games == null ? List.of() : List.copyOf(games);
    }

    /**
     * @param name        the game's name as shown; it is also how the game is found again on the next start, so renaming
     *                    one here creates a new game
     * @param description one or two plain sentences for the game's page
     * @param popularity  where it sorts when browsing Discover, among Steam games ordered by reviews and chart players; a
     *                    game that is not on Steam has neither, so this stands in (it orders, it does not measure)
     * @param sources     where its patch notes are read from; the results of all of them are combined
     */
    public record CustomGame(String name, String description, @DefaultValue("0") long popularity, List<Source> sources) {

        /** The size of the game.short_description column. */
        public static final int MAX_DESCRIPTION = 600;

        public CustomGame {
            if (name == null || name.isBlank()) {
                throw new IllegalArgumentException("app.custom.games: every game needs a name");
            }
            if (sources == null || sources.isEmpty()) {
                throw new IllegalArgumentException("app.custom.games: '" + name + "' needs at least one source");
            }
            if (description != null && description.length() > MAX_DESCRIPTION) {
                throw new IllegalArgumentException("app.custom.games: the description of '" + name + "' is longer than " + MAX_DESCRIPTION + " characters");
            }
            name = name.strip();
            sources = List.copyOf(sources);
        }
    }

    /**
     * @param url         must be https (see PublisherFeedClient)
     * @param minInterval the least time between two requests to this address, whatever the poller or the number of followers:
     *                    inside it the previous answer is reused and nothing is sent. For a feed the publisher offers on
     *                    purpose it can be zero (the poller's own schedule is gentle enough); for a page that is read without
     *                    the publisher having asked for it, keep it long
     */
    public record Source(Kind kind, String url, @DefaultValue("0s") Duration minInterval) {

        public Source {
            if (kind == null || url == null || url.isBlank()) {
                throw new IllegalArgumentException("app.custom.games: a source needs a kind and a url");
            }
            if (minInterval == null) {
                minInterval = Duration.ZERO;
            }
            if (minInterval.isNegative()) {
                throw new IllegalArgumentException("app.custom.games: min-interval of " + url + " cannot be negative");
            }
        }
    }

    public enum Kind {
        /** An RSS 2.0 or Atom feed, including a Discourse forum category's {@code .rss}. */
        RSS,
        /** A Zendesk help centre's public article list (a section's {@code articles.json}). */
        HELP_CENTER,
        /** A Riot Games news page (leagueoflegends.com, playvalorant.com), whose article list is data inside the page. */
        RIOT_NEWS
    }
}
