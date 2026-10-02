-- Full Steam catalog support.

-- name_search is the game's name lower-cased with punctuation and trademark symbols stripped, so that
-- "half life" finds "Half-Life 2: Episode One(tm)". The DEFAULT only exists to add the column to a table that
-- already has rows; the next catalog sync recomputes the real value (and the UPDATE below is a first approximation).
ALTER TABLE game ADD COLUMN name_search VARCHAR(255) NOT NULL DEFAULT '';
ALTER TABLE game ADD COLUMN steam_last_modified BIGINT;
UPDATE game SET name_search = LOWER(name);
CREATE INDEX idx_game_name_search ON game (name_search);

-- A single row remembering when the catalog was last synchronised, so later runs only fetch what changed.
CREATE TABLE catalog_sync_state (
    id                  INT         NOT NULL PRIMARY KEY,
    last_sync_started_at DATETIME(6),
    last_full_sync_at   DATETIME(6)
);
