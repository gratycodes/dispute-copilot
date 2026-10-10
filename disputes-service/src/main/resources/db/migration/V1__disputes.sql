CREATE TABLE customers (
    id            TEXT PRIMARY KEY,
    name          TEXT        NOT NULL,
    email         TEXT        NOT NULL,
    country       CHAR(2)     NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL
);

CREATE TABLE merchants (
    id    TEXT PRIMARY KEY,
    name  TEXT    NOT NULL,
    mcc   CHAR(4) NOT NULL
);

CREATE TABLE transactions (
    id                TEXT PRIMARY KEY,
    customer_id       TEXT        NOT NULL REFERENCES customers (id),
    merchant_id       TEXT        NOT NULL REFERENCES merchants (id),
    amount_minor      BIGINT      NOT NULL CHECK (amount_minor > 0),
    currency          CHAR(3)     NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL,
    three_ds          TEXT        NOT NULL CHECK (three_ds IN ('NONE', 'FRICTIONLESS', 'CHALLENGE')),
    device_id         TEXT        NOT NULL,
    billing_address   TEXT        NOT NULL,
    shipping_address  TEXT        NOT NULL,
    order_ref         TEXT        NOT NULL,
    recurring         BOOLEAN     NOT NULL
);
CREATE INDEX transactions_customer ON transactions (customer_id, created_at DESC);

CREATE TABLE merchant_evidence (
    id              TEXT PRIMARY KEY,
    transaction_id  TEXT        NOT NULL REFERENCES transactions (id),
    kind            TEXT        NOT NULL,
    payload         JSONB       NOT NULL,
    merchant_note   TEXT,
    submitted_at    TIMESTAMPTZ NOT NULL
);
CREATE INDEX merchant_evidence_txn ON merchant_evidence (transaction_id);

CREATE TABLE disputes (
    id                  TEXT PRIMARY KEY,
    transaction_id      TEXT        NOT NULL REFERENCES transactions (id),
    reason_code         TEXT        NOT NULL,
    amount_minor        BIGINT      NOT NULL,
    currency            CHAR(3)     NOT NULL,
    cardholder_message  TEXT        NOT NULL,
    opened_at           TIMESTAMPTZ NOT NULL,
    status              TEXT        NOT NULL,
    version             INTEGER     NOT NULL DEFAULT 0,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX disputes_status ON disputes (status, opened_at);
CREATE INDEX disputes_txn ON disputes (transaction_id);

-- Agent output. Never changes a dispute beyond AWAITING_REVIEW.
CREATE TABLE recommendations (
    id             TEXT PRIMARY KEY,
    dispute_id     TEXT          NOT NULL REFERENCES disputes (id),
    agent_version  TEXT          NOT NULL,
    model          TEXT,
    decision       TEXT          NOT NULL CHECK (decision IN ('ACCEPT', 'REPRESENT', 'REQUEST_INFO')),
    confidence     NUMERIC(3, 2) NOT NULL CHECK (confidence BETWEEN 0 AND 1),
    policy_rule    TEXT,
    rationale      TEXT          NOT NULL,
    citations      JSONB         NOT NULL,
    flags          JSONB         NOT NULL,
    usage          JSONB,
    trace_id       TEXT,
    trace          JSONB,
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX recommendations_dispute ON recommendations (dispute_id, created_at DESC);

-- Human decisions. A decision that differs from the recommendation is an override
-- and doubles as a training/eval label.
CREATE TABLE decisions (
    id                 TEXT PRIMARY KEY,
    dispute_id         TEXT        NOT NULL REFERENCES disputes (id),
    recommendation_id  TEXT        NOT NULL REFERENCES recommendations (id),
    agent_decision     TEXT        NOT NULL,
    final_decision     TEXT        NOT NULL CHECK (final_decision IN ('ACCEPT', 'REPRESENT', 'REQUEST_INFO')),
    agreed             BOOLEAN     NOT NULL,
    reason             TEXT,
    reviewer           TEXT        NOT NULL,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (agreed OR length(trim(reason)) >= 10)
);
CREATE INDEX decisions_dispute ON decisions (dispute_id);

-- Append-only event log per dispute, ledger style: contiguous sequence numbers
-- and balanced postings where money moves.
CREATE TABLE dispute_events (
    dispute_id  TEXT        NOT NULL REFERENCES disputes (id),
    seq         INTEGER     NOT NULL,
    type        TEXT        NOT NULL,
    payload     JSONB       NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (dispute_id, seq)
);

-- Transactional outbox: written in the same transaction as the event, relayed to Kafka.
CREATE TABLE outbox (
    id            BIGSERIAL PRIMARY KEY,
    topic         TEXT        NOT NULL,
    message_key   TEXT        NOT NULL,
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ,
    attempts      INTEGER     NOT NULL DEFAULT 0,
    last_error    TEXT
);
CREATE INDEX outbox_unpublished ON outbox (id) WHERE published_at IS NULL;
