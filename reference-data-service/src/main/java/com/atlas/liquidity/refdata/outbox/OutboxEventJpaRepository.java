package com.atlas.liquidity.refdata.outbox;

import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data access to the outbox. */
interface OutboxEventJpaRepository extends JpaRepository<OutboxEventEntity, Long> {

    /**
     * The relay's only read: the oldest unpublished events, in order.
     *
     * <p><b>Ordering by id is the point.</b> Kafka guarantees ordering within a
     * partition, so as long as we publish in id order and key by account, a
     * consumer sees one account's changes in the order they happened. Publish in
     * an arbitrary order and "buffer set to 20m" can overtake "buffer set to
     * 15m" - and the last write wins, wrongly.
     *
     * <p>The {@code Top100} cap is a deliberate bound, not a guess. Without it a
     * backlog of a million rows would be loaded into one transaction and one
     * heap. Bounded batches mean a backlog drains steadily instead of taking the
     * service down while it tries to drain it all at once. The
     * {@code idx_outbox_event_unpublished} partial index makes this query touch
     * only the backlog rather than the whole table.
     *
     * <p>Not locked. With a single instance that is fine. Part 2 covers what
     * changes when three instances run this at once - the short answer is
     * {@code SELECT ... FOR UPDATE SKIP LOCKED}, and the shorter answer is that
     * at-least-once delivery means duplicate publishing is survivable anyway.
     */
    List<OutboxEventEntity> findTop100ByPublishedAtIsNullOrderByIdAsc();

    /** All events for one aggregate, oldest first. Used by tests and support tooling. */
    List<OutboxEventEntity> findByAggregateIdOrderByIdAsc(String aggregateId);

    long countByPublishedAtIsNull();

    /**
     * Deletes events published before {@code cutoff}.
     *
     * <p>Nothing calls this yet - the scheduled job belongs in Layer 7 with the
     * other operational concerns. It exists now so that
     * {@code idx_outbox_event_published_at} has an obvious purpose, and so the
     * question "what stops this table growing forever?" has an answer in the
     * code rather than only in a comment.
     */
    @Modifying
    @Query("delete from OutboxEventEntity e where e.publishedAt is not null and e.publishedAt < :cutoff")
    int deletePublishedBefore(OffsetDateTime cutoff);
}
