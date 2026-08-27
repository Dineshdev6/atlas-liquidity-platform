package com.atlas.liquidity.position.consumer;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.position.application.PositionUpdateService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
 * <p>Deliberately thin. It deserialises, delegates, and logs - all the
 * correctness lives in {@link PositionUpdateService}, which is testable without a
 * broker. A listener that contains business logic can only be tested by feeding
 * it real messages, which is slow, and by asserting on side effects, which is
 * vague.
 *
 * <p><b>Why it throws rather than catching.</b> Swallowing an exception here
 * would tell Kafka the message was handled, the offset would advance, and the
 * event would be lost silently. Letting it propagate is what hands control to the
 * configured error handler, which retries transient failures and dead-letters
 * permanent ones. In a Kafka consumer, catching everything is not defensive
 * programming - it is data loss with extra steps.
 */
@Component
public class LiquidityBufferChangedListener {

    private static final Logger log = LoggerFactory.getLogger(LiquidityBufferChangedListener.class);

    private final PositionUpdateService positions;
    private final ObjectMapper objectMapper;

    LiquidityBufferChangedListener(PositionUpdateService positions, ObjectMapper objectMapper) {
        this.positions = positions;
        this.objectMapper = objectMapper;
    }

    /**
     * Handles one record.
     *
     * <p>The {@code groupId} is what makes this a <b>consumer group</b> rather
     * than a lone consumer, and it is the single most important Kafka concept
     * here. Kafka assigns each partition to exactly one member of a group, so
     * running three instances of this service spreads the three partitions across
     * them and each event is handled once by the group. Change the group id and
     * you get a second, independent subscription that receives <em>every</em>
     * event from the beginning - which is how you add a new consumer without
     * disturbing an existing one, and also how you accidentally reprocess history.
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
            @Payload String payload,
            @Header(name = KafkaHeaders.RECEIVED_KEY, required = false) String key,
            @Header(KafkaHeaders.RECEIVED_TOPIC) String topic,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {

        LiquidityBufferChangedEvent event = deserialise(payload, partition, offset);

        PositionUpdateService.Outcome outcome = positions.apply(event, topic);

        log.info("Event {} for {} from {}-{}@{} with key {}: {}",
                event.eventId(), event.accountId(), topic, partition, offset, key, outcome);
    }

    private LiquidityBufferChangedEvent deserialise(String payload, int partition, long offset) {
        try {
            return objectMapper.readValue(payload, LiquidityBufferChangedEvent.class);
        } catch (JsonProcessingException e) {
            // Permanent, not transient. No number of retries will make this parse,
            // and every retry blocks the partition behind it. Straight to the dead
            // letter topic, where a human can look at it.
            throw new PoisonMessageException(
                    "Unparseable message at partition " + partition + " offset " + offset, e);
        }
    }
}
