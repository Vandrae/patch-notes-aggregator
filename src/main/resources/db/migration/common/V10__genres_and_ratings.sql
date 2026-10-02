-- Genre and review rating per game, so Discover and the feed can be filtered by them.
--
-- review_score is Steam's own 0-9 rating (0 = no user reviews yet, 1 = Overwhelmingly Negative ... 5 = Mixed ...
-- 8 = Very Positive, 9 = Overwhelmingly Positive); percent_positive is the share of positive reviews, for display.
ALTER TABLE game ADD COLUMN review_score INT NOT NULL DEFAULT 0;
ALTER TABLE game ADD COLUMN percent_positive INT;

-- A game can have several genres. Only Steam's ten standard genres are kept (see the Genre enum), by name.
CREATE TABLE game_genre (
    game_id BIGINT      NOT NULL,
    genre   VARCHAR(30) NOT NULL,
    PRIMARY KEY (game_id, genre),
    CONSTRAINT fk_game_genre_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
);
CREATE INDEX idx_game_genre_genre ON game_genre (genre, game_id);

-- Details fetched before this migration have neither. Marking every game pending makes the details job fetch them
-- again (most-played chart first, then recently updated games, about one request per three seconds). Until a game's
-- turn comes it simply has no genre and no rating, so it does not appear when a filter is on and nothing breaks.
UPDATE game SET metadata_synced_at = NULL WHERE metadata_synced_at IS NOT NULL;
