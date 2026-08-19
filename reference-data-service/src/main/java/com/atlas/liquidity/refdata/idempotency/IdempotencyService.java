package com.atlas.liquidity.refdata.idempotency;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Runs an operation at most once per client-supplied idempotency key.
 *
 * <p><b>The problem, stated precisely.</b> A client sends "adjust this buffer by
 * +5,000,000" and the call times out. The client cannot distinguish "the request
 * never arrived" from "the request succeeded but the response was lost". If it does
 * not retry, the work may never have happened. If it does retry and the first
 * attempt landed, the work happens twice. There is no client-side answer to this -
 * the server has to remember.
 *
 * <p><b>The mechanism.</b> The client sends a unique key. We record it, run the
 * work, and store the response against the key, all in one transaction. A second
 * request with the same key finds the record and gets the original response back
 * without touching anything.
 *
 * <p><b>Why one transaction and not two.</b> If the business work committed and
 * then recording the key failed, a retry would execute the work again - exactly the
 * bug we are preventing. If we recorded the key and then the work failed, the key
 * would be burned and the client could never succeed. Both halves must commit or
 * neither. That is not a refinement; it is the whole correctness argument.
 *
 * <p><b>Why the key is the primary key.</b> Two concurrent requests with the same
 * key both try to insert the same row and the database lets exactly one through.
 * Writing "check whether it exists, then insert" in Java instead leaves a window
 * between the check and the insert - and a retry storm after a timeout is precisely
 * when concurrent duplicates show up. Only the database settles this atomically.
 *
 * <p><b>Why the fingerprint.</b> A key on its own would let a client reuse a key
 * with a different payload and receive the wrong answer, believing it succeeded.
 * We hash the request and refuse a mismatch - see
 * {@link IdempotencyExceptions.KeyReuseException}.
 *
 * <p>This class is deliberately generic and knows nothing about liquidity buffers.
 * Layer 4 will wrap Kafka consumers in the same mechanism, because a message
 * broker's at-least-once delivery poses the identical problem.
 */
@Service
public class IdempotencyService {

    /** The header clients send. Named as Stripe and the IETF draft name it. */
    public static final String HEADER = "Idempotency-Key";

    /**
     * How long a key is honoured.
     *
     * <p>Long enough to cover any realistic retry - a client backing off over
     * minutes, an operator re-running a failed batch the same day. Not so long that
     * the table grows without bound; unbounded growth in an append-only table is a
     * slow-motion outage. 24 hours is the common industry choice.
     */
    private static final Duration RETENTION = Duration.ofHours(24);

    private static final int MAX_KEY_LENGTH = 128;

    private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

    private final IdempotencyRecordJpaRepository records;
    private final ObjectMapper objectMapper;

    IdempotencyService(IdempotencyRecordJpaRepository records, ObjectMapper objectMapper) {
        this.records = records;
        this.objectMapper = objectMapper;
    }

    /**
     * Executes {@code action} at most once for the given key.
     *
     * <p>{@code @Transactional} here is the transaction that everything else joins.
     * {@code action} typically calls a repository whose adapter is annotated
     * {@code @Transactional(readOnly = true)} - and that annotation is <b>ignored</b>,
     * because with the default {@code REQUIRED} propagation the inner call
     * participates in this existing transaction rather than starting its own, and
     * read-only is a property of the transaction, not of the method. That is worth
     * knowing: "I marked it read-only and it still wrote" has this as its answer.
     *
     * <p>A useful side effect: because the read and the write inside {@code action}
     * now share one transaction, the read-modify-write race that Layers 2 and 3
     * documented on the PUT endpoint does not exist on this path.
     *
     * @param key              the client's key, from the {@value #HEADER} header
     * @param operation        stable name of the operation, so the same key value
     *                         used against a different endpoint cannot collide
     * @param fingerprintInput a stable string uniquely describing this request
     * @param responseType     type to deserialise a replayed response into
     * @param action           the work to perform, exactly once
     */
    @Transactional
    public <T> IdempotentResult<T> execute(
            String key,
            String operation,
            String fingerprintInput,
            Class<T> responseType,
            Supplier<T> action) {

        validateKey(key);
        String fingerprint = sha256Hex(operation + '|' + fingerprintInput);

        Optional<IdempotencyRecordEntity> existing = records.findById(key);
        if (existing.isPresent()) {
            return replay(existing.get(), key, operation, fingerprint, responseType);
        }

        // Claim the key. saveAndFlush - not save - because we want the INSERT to
        // hit the database NOW, so a concurrent request with the same key collides
        // here rather than at commit, after both have done the work.
        IdempotencyRecordEntity claim = new IdempotencyRecordEntity(
                key, operation, fingerprint, Instant.now().plus(RETENTION));

        // Use the instance save() HANDS BACK, never the one passed in. They are the
        // same object when Spring Data persists, and different objects when it
        // merges - and only the returned one is guaranteed to be managed, so only
        // changes to that one are written at commit. Getting this wrong is silent:
        // no exception, no log line, just an UPDATE that never happens. It is what
        // made every retry return 409 here, because the response was recorded on a
        // detached copy and the stored record stayed forever incomplete.
        // IdempotencyRecordEntity implements Persistable so that persist is chosen,
        // which makes the two the same object; assigning the result anyway means
        // this code stays correct even if that ever changes.
        IdempotencyRecordEntity managed;
        try {
            managed = records.saveAndFlush(claim);
        } catch (DataIntegrityViolationException e) {
            // Someone else claimed it between our findById and this insert. We
            // cannot recover inside this transaction - Hibernate has marked the
            // session for rollback - so we surrender, roll back (undoing nothing,
            // since we have not done the work yet) and tell the client to retry.
            // On retry it will find the completed record and get the original
            // response. Worth knowing: a constraint violation is not something you
            // can catch and carry on from within the same transaction.
            log.info("Lost the race to claim idempotency key {}", key);
            throw new IdempotencyExceptions.KeyInFlightException(key);
        }

        T result = action.get();

        // Recording the response is an UPDATE on a managed entity, so Hibernate
        // writes it at commit via dirty checking - in the same transaction as the
        // business work. Both commit together or neither does.
        managed.recordResponse(200, serialise(result));

        log.debug("Executed operation {} under idempotency key {}", operation, key);
        return new IdempotentResult<>(result, false);
    }

    private <T> IdempotentResult<T> replay(
            IdempotencyRecordEntity record,
            String key,
            String operation,
            String fingerprint,
            Class<T> responseType) {

        if (!record.getOperation().equals(operation)
                || !record.getRequestFingerprint().equals(fingerprint)) {
            log.warn("Idempotency key {} reused with a different request", key);
            throw new IdempotencyExceptions.KeyReuseException(key);
        }

        if (!record.isComplete()) {
            // The row exists but has no response, which means another transaction
            // claimed it and has not committed - or died. Either way this request
            // must not proceed.
            throw new IdempotencyExceptions.KeyInFlightException(key);
        }

        log.info("Replaying stored response for idempotency key {}", key);
        return new IdempotentResult<>(deserialise(record.getResponseBody(), responseType), true);
    }

    private static void validateKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException(HEADER + " must not be blank");
        }
        if (key.length() > MAX_KEY_LENGTH) {
            throw new IllegalArgumentException(
                    HEADER + " must not exceed " + MAX_KEY_LENGTH + " characters");
        }
    }

    /**
     * Hashes the request so we can detect key reuse with a different payload.
     *
     * <p>Note we hash an explicitly constructed string, not "the JSON of the
     * request". Hashing JSON sounds obvious and is a trap: {@code {"a":1,"b":2}}
     * and {@code {"b":2,"a":1}} are the same request and different bytes, so key
     * reuse detection would fire on requests that are actually identical.
     * Canonical JSON is a genuinely hard problem (key order, number formatting,
     * whitespace, Unicode normalisation). Building a stable string from the fields
     * we care about sidesteps all of it.
     *
     * <p>SHA-256 rather than the full request: fixed 64 characters regardless of
     * payload size, and it keeps request contents out of a table that other
     * operators can read.
     */
    private static String sha256Hex(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            // Every JVM is required to provide SHA-256. If this happens the
            // platform is broken, not the request.
            throw new IllegalStateException("SHA-256 unavailable in this JVM", e);
        }
    }

    private String serialise(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store idempotent response", e);
        }
    }

    /**
     * Reconstructs a stored response.
     *
     * <p>This works on records without any Jackson annotations only because the
     * build compiles with {@code -parameters}, so the constructor parameter names
     * survive into the bytecode for Jackson to read. That is the same compiler flag
     * we had to add in Layer 1 when {@code @RequestParam} binding broke - the flag
     * earns its keep twice.
     */
    private <T> T deserialise(String body, Class<T> type) {
        try {
            return objectMapper.readValue(body, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not replay stored idempotent response", e);
        }
    }

    /**
     * The outcome, plus whether it came from the store rather than fresh work.
     *
     * <p>Surfacing {@code replayed} lets the API tell the caller honestly what
     * happened, which is far kinder than an indistinguishable 200 - a client
     * debugging a retry loop wants to know its duplicate was recognised.
     */
    public record IdempotentResult<T>(T value, boolean replayed) {
    }
}
