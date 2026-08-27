package com.atlas.liquidity.position.projection;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.data.domain.Persistable;

/**
 * Record that this consumer has already handled an event.
 *
 * <p>Kafka delivers at least once, and part 1's relay is at-least-once by
 * contract, so the same event will eventually arrive twice. This table is how the
 * consumer notices. It is the Layer 3 idempotency key mechanism, arriving over a
 * different transport - which is exactly why {@code IdempotencyService} was
 * written generic.
 *
 * <p><b>The event id is the primary key</b>, and that is the guarantee. The
 * {@code existsById} check in the service is only an optimisation to avoid
 * throwing in the common case; an application-level check on its own has a window
 * between the check and the insert, and a consumer rebalance is precisely when
 * concurrent duplicates turn up. Only the database settles it atomically.
 *
 * <p><b>{@code Persistable} for the same reason as
 * {@code IdempotencyRecordEntity}.</b> The id is assigned - it comes from the
 * event - so Spring Data's default "is the id null?" test would say "not new" and
 * call {@code merge}, which issues a SELECT before every INSERT and quietly
 * reintroduces the check-then-insert this design exists to avoid. Saying "I am
 * new" explicitly makes {@code save()} call {@code persist}, so a duplicate
 * collides with the primary key instead of being silently turned into an update.
 * That collision is a feature: it is how we find out.
 */
@Entity
@Table(name = "processed_event")
public class ProcessedEventEntity implements Persistable<String> {

    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Column(name = "aggregate_id", nullable = false, updatable = false, length = 64)
    private String aggregateId;

    @Column(name = "source_topic", nullable = false, updatable = false, length = 128)
    private String sourceTopic;

    @Column(name = "applied", nullable = false, updatable = false)
    private boolean applied;

    @Column(name = "processed_at", insertable = false, updatable = false)
    private OffsetDateTime processedAt;

    /**
     * Transient, because it is not a column. The field initialiser runs for every
     * instantiation including the one Hibernate performs when loading a row, so
     * {@code markNotNew} corrects it for loaded entities.
     */
    @Transient
    private boolean isNew = true;

    /** Required by JPA. Not for application use. */
    protected ProcessedEventEntity() {
    }

    public ProcessedEventEntity(String eventId, String aggregateId, String sourceTopic, boolean applied) {
        this.eventId = eventId;
        this.aggregateId = aggregateId;
        this.sourceTopic = sourceTopic;
        this.applied = applied;
    }

    @Override
    public String getId() {
        return eventId;
    }

    @Override
    public boolean isNew() {
        return isNew;
    }

    @jakarta.persistence.PostPersist
    @jakarta.persistence.PostLoad
    void markNotNew() {
        this.isNew = false;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getSourceTopic() {
        return sourceTopic;
    }

    public boolean isApplied() {
        return applied;
    }

    public OffsetDateTime getProcessedAt() {
        return processedAt;
    }

    /** Kept so the field is used consistently; UTC everywhere, no local zones. */
    static OffsetDateTime nowUtc() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
