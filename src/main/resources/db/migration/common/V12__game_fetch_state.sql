-- When each watched game was last checked for patch notes and when to check it next (see PollSchedule).
-- Rows exist only for games somebody watches: the poller adds them when a game is first watched and removes them when
-- nobody watches it any more.
CREATE TABLE game_fetch_state (
    game_id              BIGINT      NOT NULL PRIMARY KEY,
    -- the poller picks games whose next_poll_at has passed, oldest first
    next_poll_at         DATETIME(6) NOT NULL,
    last_polled_at       DATETIME(6),
    -- publication time of the game's newest patch note seen so far: the wait between checks follows its age
    latest_patch_at      DATETIME(6),
    consecutive_failures INT         NOT NULL DEFAULT 0,
    last_error           VARCHAR(200),
    CONSTRAINT fk_fetch_state_game FOREIGN KEY (game_id) REFERENCES game (id) ON DELETE CASCADE
);

CREATE INDEX idx_fetch_state_next_poll ON game_fetch_state (next_poll_at);
