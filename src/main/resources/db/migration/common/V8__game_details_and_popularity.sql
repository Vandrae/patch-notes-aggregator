-- Store details and a popularity score per game, filled in by a background job after the catalog import.
ALTER TABLE game ADD COLUMN short_description VARCHAR(600);
-- Relative to Steam's asset CDN (the host is configuration, not data), e.g. steam/apps/1422450/<hash>/capsule_231x87.jpg?t=1
ALTER TABLE game ADD COLUMN image_path VARCHAR(300);
ALTER TABLE game ADD COLUMN review_count INT NOT NULL DEFAULT 0;
-- Highest concurrent players in the last day, for games on Steam's most-played chart (0 otherwise). Covers games that
-- are hugely played but have few or no reviews yet, which review counts alone would rank at the bottom.
ALTER TABLE game ADD COLUMN peak_players INT NOT NULL DEFAULT 0;
-- review_count + 10 * peak_players. Search orders equally-relevant matches by this, highest first.
ALTER TABLE game ADD COLUMN popularity BIGINT NOT NULL DEFAULT 0;
-- NULL = details never fetched (or the game changed on Steam and needs a refresh).
ALTER TABLE game ADD COLUMN metadata_synced_at DATETIME(6);
CREATE INDEX idx_game_metadata_synced ON game (metadata_synced_at);
