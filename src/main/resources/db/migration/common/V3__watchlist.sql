CREATE TABLE watchlist_entry (
    id       BIGINT      NOT NULL AUTO_INCREMENT PRIMARY KEY,
    user_id  BIGINT      NOT NULL,
    game_id  BIGINT      NOT NULL,
    added_at DATETIME(6) NOT NULL,
    CONSTRAINT uq_watchlist_user_game UNIQUE (user_id, game_id),
    CONSTRAINT fk_watchlist_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_watchlist_game FOREIGN KEY (game_id) REFERENCES game (id)
);

CREATE INDEX idx_watchlist_game ON watchlist_entry (game_id);
