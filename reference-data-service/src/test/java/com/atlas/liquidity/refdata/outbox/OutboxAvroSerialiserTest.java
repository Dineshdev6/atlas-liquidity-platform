package com.atlas.liquidity.refdata.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.common.events.avro.LiquidityBufferChanged;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The mapping from the stored domain payload to the Avro wire type.
 *
 * <p>Pure, and worth testing precisely because it is pure: no registry, no
 * broker, no Spring. The registry is only involved when the Kafka serialiser
 * turns this object into bytes.
 */
class OutboxAvroSerialiserTest {

    private final OutboxAvroSerialiser serialiser =
            new OutboxAvroSerialiser(new ObjectMapper().findAndRegisterModules());

    private static String payload(String reason) {
        return "{\"eventId\":\"e-1\",\"accountId\":\"ACC-GB-0001\",\"currencyCode\":\"GBP\","
                + "\"previousBuffer\":\"15000000.00\",\"newBuffer\":\"20000000.00\","
                + "\"changeType\":\"ADJUSTMENT\",\"reason\":" + reason + ","
                + "\"occurredAt\":\"2026-08-27T01:49:17.845175Z\"}";
    }

    @Test
    @DisplayName("maps every field onto the Avro record")
    void mapsAllFields() {
        LiquidityBufferChanged avro = (LiquidityBufferChanged) serialiser.toAvro(
                LiquidityBufferChangedEvent.EVENT_TYPE, payload("\"CLS window top-up\""));

        assertThat(avro.getEventId()).isEqualTo("e-1");
        assertThat(avro.getAccountId()).isEqualTo("ACC-GB-0001");
        assertThat(avro.getCurrencyCode()).isEqualTo("GBP");
        assertThat(avro.getPreviousBuffer()).isEqualTo("15000000.00");
        assertThat(avro.getNewBuffer()).isEqualTo("20000000.00");
        assertThat(avro.getChangeType()).isEqualTo("ADJUSTMENT");
        assertThat(avro.getReason()).isEqualTo("CLS window top-up");

        // A logical type: the wire format is a plain long, and the schema tells
        // Avro to hand us an Instant. Microsecond precision, so this value
        // survives exactly - a nanosecond-precision Instant would not, which is
        // worth knowing before someone asserts on equality in anger.
        assertThat(avro.getOccurredAt()).isEqualTo(Instant.parse("2026-08-27T01:49:17.845175Z"));
    }

    @Test
    @DisplayName("a null reason survives the round trip")
    void nullReasonIsAllowed() {
        LiquidityBufferChanged avro = (LiquidityBufferChanged) serialiser.toAvro(
                LiquidityBufferChangedEvent.EVENT_TYPE, payload("null"));

        // The field is a union of null and string with a default of null. Both
        // halves matter: the union makes null representable, and the default is
        // what lets a consumer reading with an older or newer schema fill the gap.
        assertThat(avro.getReason()).isNull();
    }

    @Test
    @DisplayName("an unknown event type fails loudly rather than publishing nonsense")
    void unknownEventTypeThrows() {
        assertThatThrownBy(() -> serialiser.toAvro("SomethingNobodyMapped", "{}"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("No Avro mapping");
    }
}
