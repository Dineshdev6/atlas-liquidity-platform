package com.atlas.liquidity.refdata.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * One domain event, written in the same transaction as the change it describes.
 *
 * <p>This row is the reason the database and the event stream cannot disagree.
 * See {@code V4__create_outbox_event.sql} for the full argument and ADR 0007 for
 * the decision.
 *
 * <p><b>Note the identifier strategy, and contrast it with
 * {@code IdempotencyRecordEntity}.</b> There the client chose the key, so it was
 * never null, so Spring Data's {@code save()} took the {@code merge} branch and
 * silently dropped a later update - the bug that made every idempotent retry
 * return 409. Here the database generates the id, so a new instance genuinely
 * has a null id, {@code save()} calls {@code persist}, and the instance stays
 * managed. Same framework, same method, opposite behaviour; the deciding factor
 * is who assigns the identifier.
 *
 * <p>{@code IDENTITY} rather than a sequence, deliberately. A Hibernate sequence
 * generator with an {@code allocationSize} above 1 hands out ids in blocks,
 * which is faster but makes the ids non-monotonic across application instances -
 * and this id is our ordering column. {@code IDENTITY} costs a round trip per
 * insert and disables JDBC insert batching (Hibernate cannot batch inserts whose
 * keys it must read back), which is a real throughput ceiling worth naming. We
 * take it because a handful of outbox rows per business operation is nothing,
 * and correct ordering is everything.
 */
@Entity
@Table(name = "outbox_event")
public class OutboxEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", nullable = false, updatable = false)
    private Long id;

    @Column(name = "event_id", nullable = false, updatable = false, length = 36)
    private String eventId;

    @Column(name = "aggregate_type", nullable = false, updatable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false, length = 64)
    private String aggregateId;

    @Column(name = "event_type", nullable = false, updatable = false, length = 64)
    private String eventType;

    @Column(name = "topic", nullable = false, updatable = false, length = 128)
    private String topic;

    @Column(name = "partition_key", nullable = false, updatable = false, length = 64)
    private String partitionKey;

    @Column(name = "payload", nullable = false, updatable = false)
    private String payload;

    @Column(name = "created_at", insertable = false, updatable = false)
    private OffsetDateTime createdAt;

    /**
     * The only mutable column. NULL means the relay has not yet had an
     * acknowledgement from Kafka for this row.
     */
    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    /** Required by JPA. Not for application use. */
    protected OutboxEventEntity() {
    }

    OutboxEventEntity(String eventId, String aggregateType, String aggregateId, String eventType,
                      String topic, String partitionKey, String payload) {
        this.eventId = eventId;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventType = eventType;
        this.topic = topic;
        this.partitionKey = partitionKey;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getAggregateType() {
        return aggregateType;
    }

    public String getAggregateId() {
        return aggregateId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public String getPayload() {
        return payload;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public OffsetDateTime getPublishedAt() {
        return publishedAt;
    }

    public boolean isPublished() {
        return publishedAt != null;
    }

    /**
     * Records that Kafka acknowledged this event.
     *
     * <p>No {@code save()} call follows this anywhere, and that is not an
     * omission. The relay loads these entities inside a transaction, so they are
     * managed; Hibernate compares them against their loaded state at commit and
     * writes the UPDATE itself. Automatic dirty checking is one of the things
     * JPA genuinely gives you, and "why is there no save() here" is a fair
     * question to be asked.
     */
    void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt.atOffset(ZoneOffset.UTC);
    }
}
