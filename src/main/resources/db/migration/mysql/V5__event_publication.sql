-- Spring Modulith event publication registry (persisted, so unprocessed events survive a crash)
CREATE TABLE event_publication (
    id                     BINARY(16)    NOT NULL PRIMARY KEY,
    listener_id            VARCHAR(512)  NOT NULL,
    event_type             VARCHAR(512)  NOT NULL,
    serialized_event       VARCHAR(4000) NOT NULL,
    publication_date       DATETIME(6)   NOT NULL,
    completion_date        DATETIME(6),
    last_resubmission_date DATETIME(6),
    completion_attempts    INT,
    status                 VARCHAR(20)
);
