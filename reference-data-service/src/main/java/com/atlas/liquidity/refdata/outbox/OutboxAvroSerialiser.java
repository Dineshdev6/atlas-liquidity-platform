package com.atlas.liquidity.refdata.outbox;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.common.events.avro.LiquidityBufferChanged;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

/**
 * Turns a stored outbox payload into the Avro record that goes on the wire.
 *
 * <p><b>Why there are two representations of the same event, deliberately.</b>
 * {@link LiquidityBufferChangedEvent} is a domain record: it is what the
 * application service produces and what sits in the outbox as readable JSON.
 * {@link LiquidityBufferChanged} is the wire contract, generated from a
 * {@code .avsc} file and governed by the schema registry.
 *
 * <p>Keeping them separate costs this one small class and buys something worth
 * more: the wire format cannot leak into the domain. If they were one type, then
 * renaming a field for clarity inside the service would silently be a breaking
 * change to every consumer, and adding a field for a consumer's benefit would put
 * a wire concern into the domain model. The mapping being explicit is what makes
 * both changes visible.
 *
 * <p><b>Why this runs at publish time rather than when the event was recorded.</b>
 * Serialising with the registry means an HTTP call to it the first time a schema
 * is seen. Doing that inside the transaction that writes the business change
 * would make a registry outage fail payments - a new external dependency on the
 * write path, which is exactly what the outbox exists to avoid. The relay already
 * talks to Kafka; talking to Kafka's registry belongs in the same place. The cost,
 * named honestly: the payload is now serialised twice, and the outbox holds the
 * domain shape rather than the exact bytes that were sent.
 *
 * <p>A pleasant side effect: the outbox stays human-readable. Debugging a stuck
 * event by running one SELECT beats decoding base64 Avro at 3am.
 */
@Component
public class OutboxAvroSerialiser {

    private final ObjectMapper objectMapper;

    OutboxAvroSerialiser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * Converts one outbox row's payload into its Avro form.
     *
     * @param eventType the row's event type, which selects the mapping
     * @param payload   the JSON stored when the event was recorded
     * @throws IllegalStateException if the event type has no mapping, which is a
     *                               programming error rather than a data problem -
     *                               something wrote an event nobody can publish
     */
    public Object toAvro(String eventType, String payload) {
        if (LiquidityBufferChangedEvent.EVENT_TYPE.equals(eventType)) {
            return toAvro(read(payload));
        }
        // One event type today. When there are five, this becomes a map of
        // converters keyed by event type, injected as a List - the point being
        // that the relay itself stays generic and knows nothing about liquidity.
        throw new IllegalStateException("No Avro mapping for outbox event type: " + eventType);
    }

    private LiquidityBufferChanged toAvro(LiquidityBufferChangedEvent event) {
        return LiquidityBufferChanged.newBuilder()
                .setEventId(event.eventId())
                .setAccountId(event.accountId())
                .setCurrencyCode(event.currencyCode())
                .setPreviousBuffer(event.previousBuffer())
                .setNewBuffer(event.newBuffer())
                .setChangeType(event.changeType())
                .setReason(event.reason())
                .setOccurredAt(event.occurredAt())
                .build();
    }

    private LiquidityBufferChangedEvent read(String payload) {
        try {
            return objectMapper.readValue(payload, LiquidityBufferChangedEvent.class);
        } catch (JsonProcessingException e) {
            // The outbox wrote this JSON itself, so it being unreadable means the
            // event class changed shape after the row was written. Fatal rather
            // than skippable: silently dropping an event is the one outcome the
            // outbox exists to prevent.
            throw new IllegalStateException("Unreadable outbox payload for a " + "recorded event", e);
        }
    }
}
