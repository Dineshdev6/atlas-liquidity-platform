package com.atlas.liquidity.refdata.outbox;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes recorded events to Kafka, oldest first, inside one transaction.
 *
 * <p>The second half of the outbox pattern. {@link OutboxWriter} guarantees the
 * event exists if and only if the business change happened; this class
 * guarantees it eventually reaches Kafka.
 *
 * <p><b>Why this is a separate bean from {@link OutboxRelay} rather than a method
 * on it.</b> Spring implements {@code @Transactional} with a proxy: the container
 * hands your dependents a wrapper that starts a transaction and then calls the
 * real object. A call from one method of a bean to another method of the <em>same
 * instance</em> never goes through that wrapper - it is a plain {@code this.}
 * call - so the annotation is <b>silently ignored</b>.
 *
 * <p>Had the scheduled method and this one lived on the same class, the
 * consequences would have been precise and invisible: no transaction, so the
 * entities loaded below would be detached, so {@code markPublished} would mutate
 * objects Hibernate was not watching, so every event would be published again on
 * every tick, forever, with no error anywhere. That is the same failure shape as
 * the Layer 3 merge-versus-persist bug, arriving by a completely different route.
 *
 * <p>"Why doesn't my {@code @Transactional} work?" is one of the most common
 * Spring interview questions, and self-invocation is the answer roughly half the
 * time. The alternatives - injecting the bean into itself, or
 * {@code AopContext.currentProxy()} - both work and both look like what they are.
 * Two beans with one job each is simply better design, and the transaction
 * boundary becomes visible in the type rather than hidden in a call.
 */
@Component
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    /**
     * How long to wait for Kafka to acknowledge one event.
     *
     * <p>The producer runs {@code acks=all}, so an acknowledgement means every
     * in-sync replica holds the record - not merely that it reached a socket. On
     * a single-broker laptop that distinction is invisible; on a real cluster it
     * is the difference between durable and probably durable.
     */
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final OutboxEventJpaRepository outbox;
    private final KafkaTemplate<String, String> kafka;

    OutboxPublisher(OutboxEventJpaRepository outbox, KafkaTemplate<String, String> kafka) {
        this.outbox = outbox;
        this.kafka = kafka;
    }

    /**
     * Publishes the current backlog.
     *
     * <p>Public and directly callable so tests can drive it deterministically
     * instead of waiting for a timer. Tests that depend on wall-clock scheduling
     * are the flakiest tests there are.
     *
     * <p><b>Note that the Kafka send happens inside the transaction.</b> That is
     * not ideal in the abstract - holding a database transaction open across a
     * network call is normally something to avoid, and at high volume this is
     * where you would look first. It is deliberate here: the alternative is to
     * commit the "published" flag before the send, which risks marking an event
     * sent that never left. Batches are bounded at 100 and the send timeout is 10
     * seconds, so the transaction has a hard ceiling.
     *
     * @return the number of events acknowledged by Kafka
     */
    @Transactional
    public int publishPending() {
        List<OutboxEventEntity> pending = outbox.findTop100ByPublishedAtIsNullOrderByIdAsc();
        if (pending.isEmpty()) {
            return 0;
        }

        int published = 0;
        for (OutboxEventEntity event : pending) {
            if (!send(event)) {
                // Stop at the first failure rather than skipping past it. Sending
                // event 5 after event 4 failed would deliver this account's
                // changes out of order, and a consumer applying the older one
                // last ends up with the wrong balance. A stalled queue is
                // recoverable; a reordered one may not be.
                break;
            }
            // Managed entity inside a transaction: Hibernate writes the UPDATE at
            // commit through dirty checking. No save() call, and none needed -
            // which is only true because we really are in a transaction here.
            event.markPublished(Instant.now());
            published++;
        }

        if (published > 0) {
            log.info("Published {} outbox event(s) to Kafka", published);
        }
        return published;
    }

    private boolean send(OutboxEventEntity event) {
        try {
            // The KEY is the partition key. This single argument is what gives
            // per-account ordering: Kafka hashes the key to choose a partition and
            // guarantees order within a partition. A null key round-robins and the
            // guarantee quietly disappears.
            kafka.send(event.getTopic(), event.getPartitionKey(), event.getPayload())
                    .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return true;
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it. Something above may be
            // shutting the service down, and an interrupt that gets eaten turns a
            // graceful shutdown into a hang.
            Thread.currentThread().interrupt();
            log.warn("Interrupted while publishing outbox event {}", event.getEventId());
            return false;
        } catch (ExecutionException | TimeoutException e) {
            // Not an error. The event is still in the table, still unpublished,
            // and the next tick will try again - which is exactly the property the
            // outbox exists to provide. Logging it as a catastrophe would teach
            // whoever is on call the wrong lesson.
            log.warn("Kafka did not acknowledge outbox event {} ({}); leaving it for the next run",
                    event.getEventId(), e.getMessage());
            return false;
        }
    }
}
