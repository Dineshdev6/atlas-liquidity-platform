package com.atlas.liquidity.position.consumer;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.common.events.avro.LiquidityBufferChanged;
import com.atlas.liquidity.position.application.PositionUpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/**
 * Receives buffer-changed events from Kafka.
 *
 * <p>Deliberately thin: convert, delegate, log. All the correctness lives in
 * {@link PositionUpdateService}, which is testable without a broker. Business
 * logic behind a {@code @KafkaListener} can only be tested by feeding it real
 * messages, which is slow, and by asserting on side effects, which is vague.
 *
 * <p><b>What changed in part 3.</b> The payload arriving here is now a generated
 * Avro class rather than a JSON string, and it is already deserialised - the
 * Kafka client did it, using the schema the message carries an id for, resolved
 * against the schema this service was compiled with. That resolution is the whole
 * point of the registry: a producer that has added a field can keep publishing,
 * and this consumer keeps working unchanged, because Avro maps the writer's
 * schema onto the reader's.
 *
 * <p><b>Why the Avro type is converted immediately.</b> {@code LiquidityBufferChanged}
 * is a wire type, generated from a file and shaped by what other services need.
 * {@link LiquidityBufferChangedEvent} is the shape this service wants to work
 * with. Converting at the edge means the generated class appears in exactly one
 * place - so changing the wire format cannot ripple through the service, and this
 * service's own model is not hostage to somebody else's schema.
 *
 * <p><b>Why it throws rather than catching.</b> Swallowing an exception here would
 * tell Kafka the message was handled, advance the offset, and lose the event
 * silently. Letting it propagate hands control to the configured error handler,
 * which retries transient failures and dead-letters permanent ones. In a Kafka
 * consumer, catching everything is not defensive programming - it is data loss
 * with extra steps.
 */
@Component
public class LiquidityBufferChangedListener {

    private static final Logger log = LoggerFactory.getLogger(LiquidityBufferChangedListener.class);

    private final PositionUpdateService positions;

    LiquidityBufferChangedListener(PositionUpdateService positions) {
        this.positions = positions;
    }

    /**
     * Handles one record.
     *
     * <p>The {@code groupId} is what makes this a <b>consumer group</b> rather
     * than a lone consumer. Kafka assigns each partition to exactly one member of
     * a group, so three instances of this service spread the three partitions
     * between them and each event is handled once by the group. Change the group
     * id and you get a second, independent subscription that receives
     * <em>every</em> event from the beginning - which is how you add a new
     * consumer without disturbing an existing one, and also how you accidentally
     * reprocess history.
     *
     * <p>Partition and offset are logged because together they are the message's
     * address. "It didn't arrive" is an unanswerable complaint; "partition 2,
     * offset 41" is something you can go and look at.
     */
    @KafkaListener(
            topics = "${atlas.events.buffer-changed-topic}",
            groupId = "${atlas.events.consumer-group}",
            containerFactory = "kafkaListenerContainerFactory")
    public void onBufferChanged(
            @Payload LiquidityBufferChanged message,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        LiquidityBufferChangedEvent event = toDomain(message, partition, offset);

        PositionUpdateService.Outcome outcome = positions.apply(event, topic);

        log.info("Event {} for {} from {}-{}@{} with key {}: {}",
                event.eventId(), event.accountId(), topic, partition, offset, key, outcome);
    }

    /**
     * Maps the wire type onto this service's own event record.
     *
     * <p>A null message means {@code ErrorHandlingDeserializer} caught a
     * deserialisation failure and handed us nothing. Throwing
     * {@link PoisonMessageException} sends it straight to the dead letter topic
     * with no retries, because a message that would not deserialise once will not
     * deserialise on the third attempt either - it would simply block its
     * partition for three seconds first.
     */
    private LiquidityBufferChangedEvent toDomain(LiquidityBufferChanged message, int partition, long offset) {
        if (message == null) {
            throw new PoisonMessageException(
                    "Undeserialisable message at partition " + partition + " offset " + offset, null);
        }
        return new LiquidityBufferChangedEvent(
                message.getEventId(),
                message.getAccountId(),
                message.getCurrencyCode(),
                message.getPreviousBuffer(),
                message.getNewBuffer(),
                message.getChangeType(),
                message.getReason(),
                message.getOccurredAt());
    }
}
