package com.atlas.liquidity.refdata.idempotency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.Instant;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Persistable;

/**
 * One client-supplied idempotency key and the response it produced.
 *
 * <p>The client's key is the primary key, and that is the entire mechanism. Two
 * concurrent requests carrying the same key cannot both insert this row - the
 * database's unique index forbids it. We are deliberately not writing
 * "check whether the key exists, then insert if it does not" in application code,
 * because that has a race between the check and the insert that shows up exactly
 * when it hurts most: under the retry storm that follows a timeout.
 *
 * <p>Delegating uniqueness to the one component that can enforce it atomically is
 * the whole idea. Be ready to explain why an application-level check is not
 * enough - it is a good discriminator between someone who has read about
 * idempotency and someone who has implemented it.
 *
 * <p><b>Why this entity implements {@link Persistable}, which is not obvious.</b>
 * Spring Data's {@code save()} has to decide between {@code EntityManager.persist}
 * (INSERT) and {@code EntityManager.merge} (SELECT, then INSERT or UPDATE). By
 * default it decides by looking at the identifier: null means new, non-null means
 * existing. That heuristic is right for generated identifiers and <b>wrong for
 * assigned ones</b> - and ours is assigned, because the client chooses it. So
 * {@code save()} would call {@code merge}, and {@code merge} returns a <em>copy</em>
 * that the persistence context manages while leaving the object you passed in
 * detached. Subsequent changes to your object are then silently discarded at
 * commit. That is not a hypothetical: it is the bug that made every idempotent
 * retry return 409, because the response was recorded on a detached instance and
 * never reached the database.
 *
 * <p>Implementing {@code Persistable} lets the entity answer "am I new?" itself, so
 * {@code save()} calls {@code persist}, the instance stays managed, and the extra
 * SELECT that {@code merge} performs disappears - which also matters here, because
 * a claim that SELECTs before it INSERTs is quietly reintroducing the
 * check-then-insert we are trying to avoid.
 */
@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecordEntity implements Persistable<String> {

    @Id
    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "operation", nullable = false, length = 64)
    private String operation;

    @Column(name = "request_fingerprint", nullable = false, length = 64)
    private String requestFingerprint;

    @Column(name = "response_status")
    private Integer responseStatus;

    @Column(name = "response_body")
    private String responseBody;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private OffsetDateTime expiresAt;

    /**
     * Whether this instance has yet to be written.
     *
     * <p>{@code @Transient} because it is not a column. The field initialiser runs
     * for every instantiation - including the one Hibernate performs when loading a
     * row - so {@code @PostLoad} exists to correct it for loaded entities. Without
     * that callback a loaded record would claim to be new and a later {@code save()}
     * would try to INSERT a row that already exists.
     */
    @Transient
    private boolean isNew = true;

    /** Required by JPA. Not for application use. */
    protected IdempotencyRecordEntity() {
    }

    /**
     * Claims a key. The response fields are filled in later, in the same
     * transaction, once the work has run.
     */
    IdempotencyRecordEntity(String idempotencyKey, String operation, String requestFingerprint,
                            Instant expiresAt) {
        this.idempotencyKey = idempotencyKey;
        this.operation = operation;
        this.requestFingerprint = requestFingerprint;
        this.expiresAt = expiresAt.atOffset(java.time.ZoneOffset.UTC);
    }

    /**
     * The identifier, as {@link Persistable} requires. Same value as
     * {@link #getIdempotencyKey()}; the two names exist because one is Spring
     * Data's contract and the other is the domain's word for it.
     */
    @Override
    public String getId() {
        return idempotencyKey;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @PostPersist
    @PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getOperation() {
        return operation;
    }

    public String getRequestFingerprint() {
        return requestFingerprint;
    }

    public Integer getResponseStatus() {
        return responseStatus;
    }

    public String getResponseBody() {
        return responseBody;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getExpiresAt() {
        return expiresAt;
    }

    /** True once the work has run and the outcome has been recorded. */
    public boolean isComplete() {
        return responseStatus != null && responseBody != null;
    }

    void recordResponse(int status, String body) {
        this.responseStatus = status;
        this.responseBody = body;
    }
}
