package com.atlas.liquidity.position.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.common.events.avro.LiquidityBufferChanged;
import com.atlas.liquidity.position.application.PositionUpdateService;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The listener, with no broker and no registry.
 *
 * <p>It has three jobs - convert, delegate, fail loudly - and all three are
 * testable in milliseconds. Building the Avro object directly needs nothing but
 * the generated class: the registry is involved in turning bytes into this
 * object, which is the Kafka client's problem, not ours.
 */
@ExtendWith(MockitoExtension.class)
class LiquidityBufferChangedListenerTest {

    private static final String TOPIC = "atlas.liquidity.buffer-changed.v2";
    private static final Instant WHEN = Instant.parse("2026-08-27T01:49:17.845175Z");

    @Mock
    private PositionUpdateService positions;

    private LiquidityBufferChangedListener listener() {
        return new LiquidityBufferChangedListener(positions);
    }

    private static LiquidityBufferChanged message(String eventId, String accountId, String newBuffer) {
        return LiquidityBufferChanged.newBuilder()
                .setEventId(eventId)
                .setAccountId(accountId)
                .setCurrencyCode("GBP")
                .setPreviousBuffer("15000000.00")
                .setNewBuffer(newBuffer)
                .setChangeType("ADJUSTMENT")
                .setReason(null)
                .setOccurredAt(WHEN)
                .build();
    }

    @Test
    @DisplayName("converts the Avro message to the service's own event type and delegates")
    void convertsAndDelegates() {
        given(positions.apply(any(LiquidityBufferChangedEvent.class), eq(TOPIC)))
                .willReturn(PositionUpdateService.Outcome.APPLIED);

        listener().onBufferChanged(
                message("e-1", "ACC-GB-0001", "20000000.00"), "ACC-GB-0001", TOPIC, 2, 41L);

        ArgumentCaptor<LiquidityBufferChangedEvent> captor =
                ArgumentCaptor.forClass(LiquidityBufferChangedEvent.class);
        verify(positions).apply(captor.capture(), eq(TOPIC));

        // The generated Avro class appears in exactly one place in this service.
        // Converting at the edge is what stops somebody else's wire format from
        // rippling through code that has no business knowing about it.
        LiquidityBufferChangedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("e-1");
        assertThat(event.accountId()).isEqualTo("ACC-GB-0001");
        assertThat(event.newBuffer()).isEqualTo("20000000.00");
        assertThat(event.occurredAt()).isEqualTo(WHEN);
    }

    @Test
    @DisplayName("a null key is tolerated rather than fatal")
    void nullKeyIsTolerated() {
        given(positions.apply(any(LiquidityBufferChangedEvent.class), eq(TOPIC)))
                .willReturn(PositionUpdateService.Outcome.APPLIED);

        // Our producer always sets a key, but a consumer that dies on a null one
        // dies the first time anyone else publishes to this topic. Be strict about
        // what you send and lenient about what you accept.
        listener().onBufferChanged(message("e-2", "ACC-GB-0001", "20000000.00"), null, TOPIC, 0, 1L);

        verify(positions).apply(any(LiquidityBufferChangedEvent.class), eq(TOPIC));
    }

    @Test
    @DisplayName("a null payload means deserialisation failed, and that is poison")
    void nullPayloadIsPoison() {
        // ErrorHandlingDeserializer caught the failure inside the Kafka client and
        // handed us nothing rather than letting the container spin forever on a
        // record it cannot read. Throwing PoisonMessageException sends it straight
        // to the dead letter topic with no retries - it would not deserialise on
        // the third attempt either, it would just block its partition first.
        assertThatThrownBy(() -> listener().onBufferChanged(null, "ACC-GB-0001", TOPIC, 1, 7L))
                .isInstanceOf(PoisonMessageException.class)
                .hasMessageContaining("partition 1")
                .hasMessageContaining("offset 7");

        verifyNoInteractions(positions);
    }

    @Test
    @DisplayName("a failure in the service propagates so the error handler can see it")
    void serviceFailurePropagates() {
        given(positions.apply(any(LiquidityBufferChangedEvent.class), eq(TOPIC)))
                .willThrow(new IllegalStateException("database unreachable"));

        // Catching here would tell Kafka the message was handled, advance the
        // offset, and lose the event silently. Throwing hands control to the error
        // handler, which retries transient failures like this one.
        assertThatThrownBy(() -> listener().onBufferChanged(
                message("e-3", "ACC-GB-0001", "20000000.00"), "ACC-GB-0001", TOPIC, 0, 2L))
                .isInstanceOf(IllegalStateException.class);
    }
}
