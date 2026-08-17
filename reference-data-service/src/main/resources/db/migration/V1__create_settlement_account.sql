-- V1: the settlement_account table.
--
-- LAYER 2 TEACHING NOTES
--
-- Flyway naming is strict and meaningful:
--     V<version>__<description>.sql      (two underscores, not one)
-- Flyway records each applied file plus a CHECKSUM in flyway_schema_history.
-- If you EDIT this file after it has run anywhere, the checksum no longer
-- matches and every future migration fails with "Migration checksum mismatch".
-- That is the feature, not a bug: a migration that has run in production is
-- history, and history is immutable. To change the schema, add V3.
--
-- NUMERIC(23,4) for money. Never FLOAT or DOUBLE PRECISION - the same binary
-- floating point problem that Money guards against in Java exists in the
-- database too. 23 total digits with 4 after the point holds any realistic
-- intraday position with room for currencies that have 3 minor units.
--
-- TIMESTAMPTZ, not TIMESTAMP. A liquidity platform spans New York, London,
-- Singapore and Hong Kong; a timestamp without a zone is ambiguous the moment
-- it crosses a border, and catastrophically so twice a year at DST boundaries.
--
-- ORACLE DIFFERENCES (see ADR 0004) - be ready to name these:
--   * VARCHAR(n) here would be VARCHAR2(n CHAR) on Oracle
--   * TIMESTAMPTZ becomes TIMESTAMP WITH TIME ZONE
--   * now() becomes SYSTIMESTAMP
--   * Oracle historically treats '' as NULL, Postgres does not
--   * Identity columns exist in Oracle 12c+, but sequences are still common

CREATE TABLE settlement_account (
    account_id              VARCHAR(32)   NOT NULL,
    account_number          VARCHAR(64)   NOT NULL,
    legal_entity            VARCHAR(64)   NOT NULL,
    -- VARCHAR(3) rather than CHAR(3): CHAR blank-pads to its full width, so a
    -- CHAR(3) read back through JDBC can carry trailing spaces, and Hibernate's
    -- schema validator treats bpchar and varchar as different types. Fixed-width
    -- CHAR buys nothing on Postgres.
    currency_code           VARCHAR(3)    NOT NULL,
    jurisdiction            VARCHAR(8)    NOT NULL,
    bic                     VARCHAR(11),
    liquidity_buffer_amount NUMERIC(23,4) NOT NULL DEFAULT 0,

    -- Optimistic locking. Hibernate increments this on every UPDATE and adds
    -- "AND version = ?" to the WHERE clause. If another transaction got there
    -- first, zero rows update and Hibernate throws
    -- OptimisticLockingFailureException instead of silently discarding that
    -- other transaction's work. This is the "lost update" problem, and it is
    -- the single most common concurrency bug in CRUD applications.
    version                 BIGINT        NOT NULL DEFAULT 0,

    created_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_settlement_account PRIMARY KEY (account_id),

    -- Constraints in the schema, not only in Java. Application code is one of
    -- several routes into this table; a batch job, a DBA script or a future
    -- service can all write here. The database is the last line of defence and
    -- the only one that cannot be bypassed.
    CONSTRAINT ck_settlement_account_currency CHECK (currency_code ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_settlement_account_buffer_non_negative CHECK (liquidity_buffer_amount >= 0)
);

-- Indexes exist because of the queries we actually run - findByCurrency and
-- findByJurisdiction. Do not add indexes speculatively: each one costs write
-- throughput and storage, and on a high-write table that cost is real.
CREATE INDEX idx_settlement_account_currency ON settlement_account (currency_code);
CREATE INDEX idx_settlement_account_jurisdiction ON settlement_account (jurisdiction);

-- A natural business key: the same entity cannot hold the same account number
-- twice. The primary key is our internal identifier; this protects the real
-- world's uniqueness rule.
CREATE UNIQUE INDEX uq_settlement_account_entity_number
    ON settlement_account (legal_entity, account_number);

COMMENT ON TABLE settlement_account IS
    'Nostro and settlement accounts through which cash physically moves';
COMMENT ON COLUMN settlement_account.liquidity_buffer_amount IS
    'Minimum intraday cash the account must retain, in the account currency';
COMMENT ON COLUMN settlement_account.jurisdiction IS
    'Regulatory jurisdiction; determines the data residency region';
