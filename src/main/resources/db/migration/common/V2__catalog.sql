CREATE TABLE game (
    id           BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    name         VARCHAR(255) NOT NULL,
    steam_app_id BIGINT,
    source_type  VARCHAR(20)  NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    CONSTRAINT uq_game_steam_app_id UNIQUE (steam_app_id)
);

-- First-pass search index (see design note #9). Revisit with full-text once the catalog is ~150k rows.
CREATE INDEX idx_game_name ON game (name);
