package com.atlas.liquidity.position.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * One account's position, derived entirely from events.
 *
 * <p>A projection, and worth being deliberate about what that means: this table
 * holds no information that did not arrive on the topic, so it is
 * <b>disposable</b>. If it is ever wrong, you delete it, reset the consumer group
 * to the beginning, and rebuild it from history. Being able to say that - and
 * mean it - is what separates a system that is event-driven from one that merely
 * has a message broker bolted on.
 */
@Entity
@Table(name = "account_position")
public class AccountPositionEntity {

    /**
     * The account id, assigned upstream.
     *
     * <p>Assigned, not generated - which means Spring Data's {@code save()} sees a
     * non-null id and takes the {@code merge} branch, returning a managed
     * <em>copy</em> and leaving the instance you passed detached. Third appearance
     * of that behaviour in this codebase, after {@code IdempotencyRecordEntity}
     * (where it was a bug) and {@code OutboxEventEntity} (where the generated id
     * avoided it).
     *
     * <p>Here {@code merge} is actually the right semantics - it is an upsert, and
     * this row genuinely may or may not exist. The rule that keeps it safe is
     * simply to use the instance {@code save()} hands back, which
     * {@code PositionUpdateService} does.
     */
    @Id
    @Column(name = "account_id", nullable = false, updatable = false, length = 64)
    private String accountId;

    @Column(name = "currency_code", nullable = false, length = 3)
    private String currencyCode;

    @Column(name = "current_buffer", nullable = false, precision = 38, scale = 4)
    private BigDecimal currentBuffer;

    @Column(name = "last_event_id", nullable = false, length = 36)
    private String lastEventId;

    /**
     * When the change actually happened, from the event payload.
     *
     * <p>Not when we processed it. The distinction is what lets us recognise an
     * event that arrives out of order - after a retry, or a replay from the dead
     * letter topic - and decline to apply it over newer state.
     */
    @Column(name = "last_event_at", nullable = false)
    private OffsetDateTime lastEventAt;

    @Column(name = "applied_count", nullable = false)
    private long appliedCount;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    /** Required by JPA. Not for application use. */
    protected AccountPositionEntity() {
    }

    public AccountPositionEntity(String accountId, String currencyCode, BigDecimal currentBuffer,
                                 String lastEventId, Instant lastEventAt) {
        this.accountId = accountId;
        this.currencyCode = currencyCode;
        this.currentBuffer = currentBuffer;
        this.lastEventId = lastEventId;
        this.lastEventAt = lastEventAt.atOffset(ZoneOffset.UTC);
        this.appliedCount = 1;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }

    public String getAccountId() {
        return accountId;
    }

    public String getCurrencyCode() {
        return currencyCode;
    }

    public BigDecimal getCurrentBuffer() {
        return currentBuffer;
    }

    public String getLastEventId() {
        return lastEventId;
    }

    public OffsetDateTime getLastEventAt() {
        return lastEventAt;
    }

    public long getAppliedCount() {
        return appliedCount;
    }

    public OffsetDateTime getUpdatedAt() {
        return updatedAt;
    }

    /**
     * True if {@code occurredAt} is not newer than what we have already applied.
     *
     * <p>Kafka guarantees ordering within a partition and the producer keys by
     * account, so in the normal case events arrive in order and this is always
     * false. It stops being true after a retry, after a dead-letter replay, or if
     * anyone ever changes the partition key - and an event applied out of order
     * silently sets the balance backwards.
     *
     * <p>Note {@code !isAfter} rather than {@code isBefore}: an event with exactly
     * the same timestamp is not newer, and applying it again would double-count.
     * In production you would prefer a monotonic sequence number from the
     * producer over a wall-clock timestamp, because two events can share a
     * millisecond and clocks are not to be trusted - worth saying out loud.
     */
    public boolean isStale(Instant occurredAt) {
        return !occurredAt.isAfter(lastEventAt.toInstant());
    }

    /**
     * Applies a newer event.
     *
     * <p>No {@code save()} follows this. The entity is managed inside the
     * service's transaction, so Hibernate writes the UPDATE at commit through
     * dirty checking.
     */
    public void apply(BigDecimal newBuffer, String eventId, Instant occurredAt) {
        this.currentBuffer = newBuffer;
        this.lastEventId = eventId;
        this.lastEventAt = occurredAt.atOffset(ZoneOffset.UTC);
        this.appliedCount++;
        this.updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
}
