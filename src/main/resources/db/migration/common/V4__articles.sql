CREATE TABLE article (
    id           BIGINT        NOT NULL AUTO_INCREMENT PRIMARY KEY,
    game_id      BIGINT        NOT NULL,
    external_id  VARCHAR(64)   NOT NULL,
    title        VARCHAR(500)  NOT NULL,
    url          VARCHAR(1000) NOT NULL,
    summary      VARCHAR(1000) NOT NULL,
    article_type VARCHAR(30)   NOT NULL,
    published_at DATETIME(6)   NOT NULL,
    fetched_at   DATETIME(6)   NOT NULL,
    content_hash VARCHAR(64)   NOT NULL,
    -- Dedup key is the source's own id, not the URL: the same URL can legitimately change content (note #6).
    CONSTRAINT uq_article_game_external UNIQUE (game_id, external_id),
    CONSTRAINT fk_article_game FOREIGN KEY (game_id) REFERENCES game (id)
);

CREATE INDEX idx_article_game_published ON article (game_id, published_at);
