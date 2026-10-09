CREATE TABLE outbox (
    id           BIGINT       NOT NULL AUTO_INCREMENT,
    event_id     CHAR(36)     NOT NULL,
    event_name   VARCHAR(100) NOT NULL,
    payload      JSON         NOT NULL,
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_event_id (event_id),
    KEY idx_outbox_unpublished (published_at, id)
);

CREATE TABLE processed_event (
    event_id     CHAR(36)    NOT NULL,
    processed_at DATETIME(6) NOT NULL,
    PRIMARY KEY (event_id)
);
