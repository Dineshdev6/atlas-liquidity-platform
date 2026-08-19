package com.atlas.liquidity.refdata.idempotency;

/**
 * The two ways an idempotency key can go wrong, and why they are different
 * failures with different status codes.
 *
 * <p>Held in one file because they are a pair and neither means anything without
 * the other.
 */
public final class IdempotencyExceptions {

    private IdempotencyExceptions() {
    }

    /**
     * The key has been used before, for a <em>different</em> request.
     *
     * <p>This is a client bug and must be reported as one. Consider what happens
     * if we honour the key instead: the client sends "+5,000,000" with key K, then
     * later sends "+50,000,000" with the same K by mistake. We return the first
     * response, the client sees success, and believes fifty million was applied
     * when five million was. The money is not lost, but the client's picture of
     * reality is wrong, silently, and it will stay wrong.
     *
     * <p><b>422 Unprocessable Content, not 400.</b> The request is syntactically
     * perfect - well-formed JSON, valid amount, valid key format. It is
     * semantically impossible, because that key already means something else. That
     * distinction is exactly what 422 is for, and being able to draw it is a good
     * signal.
     */
    public static class KeyReuseException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final String idempotencyKey;

        public KeyReuseException(String idempotencyKey) {
            super("Idempotency key '" + idempotencyKey
                    + "' was already used for a different request. Use a new key.");
            this.idempotencyKey = idempotencyKey;
        }

        public String idempotencyKey() {
            return idempotencyKey;
        }
    }

    /**
     * A request with this key is already in flight, or lost a race to claim it.
     *
     * <p><b>409 Conflict, and the client should simply retry.</b> Two concurrent
     * requests carrying the same key both try to insert the same primary key; the
     * database lets exactly one through. The loser rolls back - including any
     * business work it had already done, which is the point - and gets this.
     *
     * <p>On retry, the winner has committed, so the loser now finds the completed
     * record and receives the original response. The end state is correct: applied
     * once, reported consistently to both callers.
     *
     * <p>This is worth stating plainly in an interview: <b>an application-level
     * "check whether the key exists, then insert" is not enough.</b> There is a
     * window between the check and the insert, and a retry storm after a timeout
     * is precisely when concurrent duplicates arrive. Only the database can settle
     * it atomically, which is why the key is the primary key.
     */
    public static class KeyInFlightException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final String idempotencyKey;

        public KeyInFlightException(String idempotencyKey) {
            super("A request with idempotency key '" + idempotencyKey
                    + "' is already being processed. Retry shortly.");
            this.idempotencyKey = idempotencyKey;
        }

        public String idempotencyKey() {
            return idempotencyKey;
        }
    }
}
