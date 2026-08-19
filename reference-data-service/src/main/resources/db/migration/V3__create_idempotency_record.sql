-- V3: the idempotency key register.
--
-- WHY THIS TABLE EXISTS
--
-- A client sends "adjust this account's buffer by +5,000,000". The call times
-- out. The client has no idea whether the work happened - the request may have
-- died on the way out, or the response may have died on the way back.
--
-- If it does not retry, the adjustment may never have been applied.
-- If it does retry and the first attempt DID land, the adjustment is applied
-- twice. In a liquidity platform that is a reconciliation break, a regulatory
-- incident, and somebody's very bad week.
--
-- The resolution is that the client sends a unique key with the request, and the
-- server keeps a register of keys it has already executed. A second request with
-- the same key returns the first request's outcome and touches nothing. This is
-- how Stripe, the card networks and every payment rail on earth handle retries.
--
-- WHY THE FINGERPRINT COLUMN
--
-- A key alone is not enough. If a client reuses a key with a DIFFERENT payload -
-- "+5,000,000" the first time, "+50,000,000" the second - honouring the key
-- would silently return the wrong answer for the second request, and the caller
-- would believe its 50 million landed. So we store a hash of the request and
-- refuse a mismatch outright. That is a client bug, and telling them loudly is
-- kinder than hiding it.
--
-- WHY expires_at
--
-- This table grows forever otherwise. Keys are only useful for as long as a
-- client might retry - hours, not years. A scheduled cleanup deletes expired
-- rows; the index below makes that delete cheap. Unbounded growth in an
-- append-only table is a slow-motion outage.
--
-- ORACLE NOTE (ADR 0004): TEXT becomes CLOB, TIMESTAMPTZ becomes
-- TIMESTAMP WITH TIME ZONE, now() becomes SYSTIMESTAMP.

CREATE TABLE idempotency_record (
    -- The client's key IS the primary key. That is the whole mechanism: the
    -- database's unique index is what makes two concurrent requests with the
    -- same key impossible to both succeed. We are not checking-then-inserting
    -- in application code, which would have a race between the check and the
    -- insert; we are letting the one component that can do this atomically do it.
    idempotency_key     VARCHAR(128) NOT NULL,

    -- Which operation the key was used for. The same key value used against a
    -- different endpoint is a different thing, and mixing them would let one
    -- endpoint's key mask another's.
    operation           VARCHAR(64)  NOT NULL,

    -- SHA-256 of the canonical request, hex-encoded. 64 characters.
    request_fingerprint VARCHAR(64)  NOT NULL,

    -- Nullable, because the row is inserted to CLAIM the key before the work
    -- runs, and filled in once we know the outcome. Both happen inside one
    -- transaction, so a caller never observes a half-written record.
    response_status     INTEGER,
    response_body       TEXT,

    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at          TIMESTAMPTZ  NOT NULL,

    CONSTRAINT pk_idempotency_record PRIMARY KEY (idempotency_key),
    CONSTRAINT ck_idempotency_record_expiry CHECK (expires_at > created_at)
);

-- Makes the cleanup delete an index range scan rather than a full table scan.
CREATE INDEX idx_idempotency_record_expires_at ON idempotency_record (expires_at);

COMMENT ON TABLE idempotency_record IS
    'Register of client-supplied idempotency keys and the responses they produced';
COMMENT ON COLUMN idempotency_record.request_fingerprint IS
    'SHA-256 of the canonical request; a mismatch on key reuse is rejected';
