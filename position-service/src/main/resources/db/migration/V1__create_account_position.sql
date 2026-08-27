-- V1 (position-service): the projection this service maintains from the event
-- stream, and the register of events it has already applied.
--
-- NOTE THE VERSION NUMBER. This is V1, not V5, because every service owns its
-- own schema and its own migration history. reference-data-service is at V4 in a
-- different database with its own flyway_schema_history table. If these two ever
-- shared a database they would share a migration timeline, and deploying one
-- service would mean coordinating with the other - which is precisely the
-- coupling a separate service is meant to remove.
--
-- ORACLE NOTE (ADR 0004): TIMESTAMPTZ becomes TIMESTAMP WITH TIME ZONE,
-- NUMERIC(38,4) becomes NUMBER(38,4), now() becomes SYSTIMESTAMP.

-- ---------------------------------------------------------------------------
-- The projection
-- ---------------------------------------------------------------------------
-- A read model, derived entirely from events. Nothing writes to it except the
-- consumer, and it holds no information that did not arrive on the topic.
--
-- That is worth being deliberate about: a projection is DISPOSABLE. If it is
-- ever wrong, you delete it, reset the consumer group to the start of the topic,
-- and rebuild from history. Being able to say that - and to mean it - is what
-- separates an event-driven system from one that merely uses a message broker.
CREATE TABLE account_position (
    -- The account id, assigned by the upstream service. Not generated here: this
    -- table does not own the identity of an account, it borrows it. A projection
    -- inventing its own keys would be unable to correlate with anything.
    account_id      VARCHAR(64)     NOT NULL,

    -- VARCHAR(3), never CHAR(3). CHAR blank-pads to its full width, so a value
    -- read back through JDBC carries trailing spaces - and Hibernate's schema
    -- validation rejects the mapping outright, because Postgres reports the type
    -- as bpchar (Types#CHAR) while a String field expects varchar (Types#VARCHAR).
    -- The failure is at startup, not at runtime, which is at least loud.
    -- CHAR buys nothing on Postgres: it is stored identically to VARCHAR.
    -- The same note is in reference-data-service's V1, which is where this should
    -- have been copied from.
    currency_code   VARCHAR(3)      NOT NULL,

    -- The buffer as of the last event applied. NUMERIC, never a floating point
    -- type: 0.1 + 0.2 is not 0.3 in binary floating point, and a liquidity
    -- platform that rounds is a liquidity platform that reconciles by hand.
    current_buffer  NUMERIC(38,4)   NOT NULL,

    -- Which event produced the current value, and when that event happened.
    -- last_event_at is the ORIGINAL occurrence time from the payload, not the
    -- time we processed it - which is what lets us recognise and ignore an event
    -- that arrives out of order after a retry or a dead-letter replay.
    last_event_id   VARCHAR(36)     NOT NULL,
    last_event_at   TIMESTAMPTZ     NOT NULL,

    -- How many events have been applied. Not business data; it is here so that
    -- "did anything actually happen?" has an answer during the walkthrough, and
    -- so a test can assert that a duplicate did NOT increment it.
    applied_count   BIGINT          NOT NULL DEFAULT 0,

    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT pk_account_position PRIMARY KEY (account_id),
    CONSTRAINT ck_account_position_non_negative CHECK (current_buffer >= 0)
);

COMMENT ON TABLE account_position IS
    'Read model of intraday liquidity buffers, derived entirely from Kafka events';
COMMENT ON COLUMN account_position.last_event_at IS
    'Occurrence time from the event payload, used to discard out-of-order events';

-- ---------------------------------------------------------------------------
-- The consumer's idempotency register
-- ---------------------------------------------------------------------------
-- Kafka delivers at least once, and part 1's relay is at-least-once by contract:
-- it publishes, Kafka acknowledges, and only then commits the row as published.
-- Crash in that gap and the same event is sent again on restart.
--
-- So this consumer must be able to recognise an event it has already applied.
-- Exactly the same problem as Layer 3's Idempotency-Key header, arriving over a
-- different transport - which is why IdempotencyService was written generic and
-- why this table looks so familiar.
--
-- THE EVENT ID IS THE PRIMARY KEY, for the same reason the idempotency key was:
-- the database's unique index is what makes double application impossible. An
-- application-level "have I seen this?" check has a window between the check and
-- the insert, and a rebalance or a restart is exactly when concurrent duplicates
-- turn up. The check in the service code is an OPTIMISATION; this constraint is
-- the guarantee.
CREATE TABLE processed_event (
    event_id     VARCHAR(36)  NOT NULL,

    -- Which aggregate it concerned, so a support query can answer "what has this
    -- account received?" without joining against the producer's database.
    aggregate_id VARCHAR(64)  NOT NULL,

    -- Where it came from, so a replay from a dead letter topic is
    -- distinguishable from a first delivery when someone is investigating.
    source_topic VARCHAR(128) NOT NULL,

    -- Whether the event changed anything. An event can be legitimately processed
    -- and applied to nothing - a stale event superseded by a later one is the
    -- normal case. Recording that is how "we ignored it" stays distinguishable
    -- from "we never saw it".
    applied      BOOLEAN      NOT NULL,

    processed_at TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT pk_processed_event PRIMARY KEY (event_id)
);

-- Supports the eventual retention job. Same argument as the outbox: an
-- append-only table with no pruning is a slow-motion outage. Layer 7.
CREATE INDEX idx_processed_event_processed_at ON processed_event (processed_at);

COMMENT ON TABLE processed_event IS
    'Events this consumer has already handled; the primary key makes double application impossible';
