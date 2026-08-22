package com.atlas.liquidity.refdata.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records a domain event for publication, inside the caller's transaction.
 *
 * <p>This class is three lines of real work and one very deliberate annotation.
 *
 * <p><b>{@code Propagation.MANDATORY} is the important part.</b> It means "join
 * an existing transaction, and throw if there isn't one". Not {@code REQUIRED},
 * which would quietly start its own - and a private transaction here would
 * destroy the entire point of the pattern: the event would commit independently
 * of the business change, and you would be back to a dual write with extra
 * steps, silently.
 *
 * <p>So the failure mode is made impossible rather than documented. Call this
 * from outside a transaction and you get {@code IllegalTransactionStateException}
 * immediately, in a test, rather than a data-integrity incident six months
 * later. When you can convert a subtle runtime bug into a loud startup or
 * first-call failure, do it.
 *
 * <p><b>Why serialise here rather than at publication time.</b> The payload is
 * frozen at the moment the business change happened. If the relay serialised
 * instead, it would be serialising whatever the object looks like later, and a
 * deployment that changed the event class between write and publish would emit
 * something that never corresponded to a real state. Events are facts about the
 * past; they should be immutable from the instant they are recorded.
 */
@Component
public class OutboxWriter {

    private static final Logger log = LoggerFactory.getLogger(OutboxWriter.class);

    private final OutboxEventJpaRepository outbox;
    private final ObjectMapper objectMapper;
    private final String defaultTopic;

    OutboxWriter(OutboxEventJpaRepository outbox,
                 ObjectMapper objectMapper,
                 @Value("${atlas.outbox.topic}") String defaultTopic) {
        this.outbox = outbox;
        this.objectMapper = objectMapper;
        this.defaultTopic = defaultTopic;
    }

    /**
     * Generates an event id. Exposed so a caller can put the same id inside the
     * payload it hands to {@link #record}, which is what lets a consumer
     * deduplicate on a value it can actually see.
     */
    public String newEventId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Records an event to be published.
     *
     * @param eventId       id carried inside the payload; consumers deduplicate on it
     * @param aggregateType the kind of thing that changed, e.g. SettlementAccount
     * @param aggregateId   which one changed
     * @param eventType     what happened, past tense
     * @param partitionKey  the Kafka message key - everything sharing a key keeps its order
     * @param payload       the event object, serialised to JSON here and never again
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void record(String eventId, String aggregateType, String aggregateId,
                       String eventType, String partitionKey, Object payload) {

        OutboxEventEntity event = new OutboxEventEntity(
                eventId, aggregateType, aggregateId, eventType,
                defaultTopic, partitionKey, serialise(payload));

        outbox.save(event);

        // Debug rather than info: one of these per business write would drown
        // the log at any real volume. The event itself is the record; the log
        // line is only for a developer watching a single request go through.
        log.debug("Recorded {} for {} {} in the outbox", eventType, aggregateType, aggregateId);
    }

    private String serialise(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Deliberately fatal. A payload we cannot serialise is a programming
            // error, and failing the whole transaction means the business change
            // rolls back too - which is right, because committing a change
            // nobody can be told about is the exact failure this table exists to
            // prevent.
            throw new IllegalStateException(
                    "Could not serialise outbox payload for " + aggregateTypeOf(payload), e);
        }
    }

    private static String aggregateTypeOf(Object payload) {
        return payload == null ? "null" : payload.getClass().getSimpleName();
    }
}
