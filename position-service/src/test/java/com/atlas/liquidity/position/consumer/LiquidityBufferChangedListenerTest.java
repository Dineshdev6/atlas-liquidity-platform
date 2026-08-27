package com.atlas.liquidity.position.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.position.application.PositionUpdateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * The listener, with no broker.
 *
 * <p>It has exactly three jobs - parse, delegate, and fail loudly - and all three
 * are testable in milliseconds. The value of keeping a listener this thin is
 * precisely that: business logic behind a {@code @KafkaListener} can only be
 * tested by feeding it real messages and asserting on side effects.
 */
@ExtendWith(MockitoExtension.class)
class LiquidityBufferChangedListenerTest {

    private static final String TOPIC = "atlas.liquidity.buffer-changed.v1";

    @Mock
    private PositionUpdateService positions;

    private LiquidityBufferChangedListener listener() {
        // findAndRegisterModules picks up jackson-datatype-jsr310 so Instant
        // parses from ISO-8601, matching what Spring Boot's own ObjectMapper does.
        return new LiquidityBufferChangedListener(positions, new ObjectMapper().findAndRegisterModules());
    }

    private static String payload(String eventId, String accountId, String newBuffer, String occurredAt) {
        return "{\"eventId\":\"" + eventId + "\","
                + "\"accountId\":\"" + accountId + "\","
                + "\"currencyCode\":\"GBP\","
                + "\"previousBuffer\":\"15000000.00\","
                + "\"newBuffer\":\"" + newBuffer + "\","
                + "\"changeType\":\"ADJUSTMENT\","
                + "\"reason\":null,"
                + "\"occurredAt\":\"" + occurredAt + "\"}";
    }

    @Test
    @DisplayName("parses the payload and hands the event to the service")
    void parsesAndDelegates() {
        given(positions.apply(any(LiquidityBufferChangedEvent.class), eq(TOPIC)))
                .willReturn(PositionUpdateService.Outcome.APPLIED);

        listener().onBufferChanged(
                payload("e-1", "ACC-GB-0001", "20000000.00", "2026-08-22T03:24:20.961082900Z"),
                "ACC-GB-0001", TOPIC, 2, 41L);

        ArgumentCaptor<LiquidityBufferChangedEvent> captor =
                ArgumentCaptor.forClass(LiquidityBufferChangedEvent.class);
        verify(positions).apply(captor.capture(), eq(TOPIC));

        LiquidityBufferChangedEvent event = captor.getValue();
        assertThat(event.eventId()).isEqualTo("e-1");
        assertThat(event.accountId()).isEqualTo("ACC-GB-0001");
        assertThat(event.newBuffer()).isEqualTo("20000000.00");

        // Deserialising a record with no Jackson annotations works only because
        // the build compiles with -parameters. Fourth time that Layer 1 flag has
        // paid for itself.
        assertThat(event.occurredAt()).isEqualTo(Instant.parse("2026-08-22T03:24:20.961082900Z"));
    }

    @Test
    @DisplayName("a null key is tolerated rather than fatal")
    void nullKeyIsTolerated() {
        given(positions.apply(any(LiquidityBufferChangedEvent.class), eq(TOPIC)))
                .willReturn(PositionUpdateService.Outcome.APPLIED);

        // Our producer always sets a key, but a consumer that dies on a null one
        // is a consumer that dies the first time anyone else publishes to this
        // topic. Be strict about what you send and lenient about what you accept.
        listener().onBufferChanged(
                payload("e-2", "ACC-GB-0001", "20000000.00", "2026-08-22T03:24:20Z"),
                null, TOPIC, 0, 1L);

        verify(positions).apply(any(LiquidityBufferChangedEvent.class), eq(TOPIC));
    }

    @Test
    @DisplayName("an unparseable payload is poison, not a retry candidate")
    void unparseablePayloadIsPoison() {
        assertThatThrownBy(() -> listener().onBufferChanged(
                "{ this is not json", "ACC-GB-0001", TOPIC, 1, 7L))
                .isInstanceOf(PoisonMessageException.class)
                .hasMessageContaining("partition 1")
                .hasMessageContaining("offset 7");

        // The distinction that matters: this will never parse, so retrying it
        // only blocks its partition while reaching the conclusion we already have.
        // The error handler is configured to send this exception straight to the
        // dead letter topic with no retries.
        verifyNoInteractions(positions);
    }

    @Test
    @DisplayName("a failure in the service propagates so the error handler can see it")
    void serviceFailurePropagates() {
        given(positions.apply(any(LiquidityBufferChangedEvent.class), eq(TOPIC)))
                .willThrow(new IllegalStateException("database unreachable"));

        // Catching here would tell Kafka the message was handled, advance the
        // offset, and lose the event silently. Throwing is what hands control to
        // the error handler, which retries transient failures like this one. In a
        // Kafka consumer, catching everything is data loss with extra steps.
        assertThatThrownBy(() -> listener().onBufferChanged(
                payload("e-3", "ACC-GB-0001", "20000000.00", "2026-08-22T03:24:20Z"),
                "ACC-GB-0001", TOPIC, 0, 2L))
                .isInstanceOf(IllegalStateException.class);
    }
}
