-- Copied from idempotency-spring-boot-starter (jdbc/schema.sql) so Flyway owns the schema.
-- Idempotency keys. Portable across PostgreSQL and H2 (MODE=PostgreSQL).
-- Times are epoch milliseconds written by the application.
CREATE TABLE IF NOT EXISTS idempotency_keys (
    scope            VARCHAR(255) NOT NULL,
    idem_key         VARCHAR(255) NOT NULL,
    fingerprint      CHAR(64)     NOT NULL,
    state            VARCHAR(16)  NOT NULL,
    lock_token       VARCHAR(36),
    locked_until     BIGINT       NOT NULL,
    expires_at       BIGINT       NOT NULL,
    created_at       BIGINT       NOT NULL,
    status_code      INTEGER,
    response_headers TEXT,
    response_body    BYTEA,
    PRIMARY KEY (scope, idem_key)
);

CREATE INDEX IF NOT EXISTS idempotency_keys_expires_at ON idempotency_keys (expires_at);

-- Webhook inbox: one row per provider event that was processed.
CREATE TABLE IF NOT EXISTS webhook_inbox (
    provider     VARCHAR(64)  NOT NULL,
    event_id     VARCHAR(255) NOT NULL,
    received_at  BIGINT       NOT NULL,
    PRIMARY KEY (provider, event_id)
);
