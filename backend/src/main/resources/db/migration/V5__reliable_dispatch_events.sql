CREATE TABLE outbox_event (
    event_id VARCHAR(64) COLLATE utf8mb4_bin PRIMARY KEY,
    event_type VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    dedupe_key VARCHAR(160) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    payload_json JSON NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','PUBLISHING','PUBLISHED','FAILED')),
    attempt INT NOT NULL DEFAULT 0 CHECK (attempt BETWEEN 0 AND 5),
    next_attempt_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    claim_token VARCHAR(64) COLLATE utf8mb4_bin,
    lease_until DATETIME(3),
    last_error VARCHAR(300),
    occurred_at DATETIME(3) NOT NULL,
    published_at DATETIME(3),
    INDEX ix_outbox_due(status,next_attempt_at),
    INDEX ix_outbox_lease(status,lease_until)
);

CREATE TABLE consumer_inbox (
    consumer_name VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    event_id VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    processed_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(consumer_name,event_id)
);
