package com.atlas.liquidity.refdata.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.common.events.avro.LiquidityBufferChanged;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * The publisher's logic, with no broker, no database and no schema registry.
 *
 * <p>Everything here is a decision the publisher makes: what order to send in,
 * what to use as the message key, when to stop, and what to do when Kafka does
 * not answer. None of it needs a real Kafka, and testing against one would make
 * these tests slower and less certain without proving anything more.
 *
 * <p>The Avro mapping is exercised for real - {@link OutboxAvroSerialiser} is a
 * pure function from JSON to a generated class and needs no registry, because the
 * registry is only involved when the <em>serialiser</em> turns that object into
 * bytes, which is the mocked {@code KafkaTemplate}'s job.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final String TOPIC = "atlas.liquidity.buffer-changed.v2";

    @Mock
    private OutboxEventJpaRepository outbox;

    @Mock
    private KafkaTemplate<String, Object> kafka;

    private OutboxPublisher publisher() {
        return new OutboxPublisher(outbox, kafka,
                new OutboxAvroSerialiser(new ObjectMapper().findAndRegisterModules()));
    }

    private static String payloadJson(String eventId, String accountId, String newBuffer) {
        return "{\"eventId\":\"" + eventId + "\","
                + "\"accountId\":\"" + accountId + "\","
                + "\"currencyCode\":\"GBP\","
                + "\"previousBuffer\":\"15000000.00\","
                + "\"newBuffer\":\"" + newBuffer + "\","
                + "\"changeType\":\"ADJUSTMENT\","
                + "\"reason\":null,"
                + "\"occurredAt\":\"2026-08-27T01:49:17.845175Z\"}";
    }

    private static OutboxEventEntity event(String eventId, String accountId, String newBuffer) {
        return new OutboxEventEntity(
                eventId,
                LiquidityBufferChangedEvent.AGGREGATE_TYPE,
                accountId,
                LiquidityBufferChangedEvent.EVENT_TYPE,
                TOPIC,
                accountId,
                payloadJson(eventId, accountId, newBuffer));
    }

    private void givenKafkaAccepts() {
        CompletableFuture<SendResult<String, Object>> ack = CompletableFuture.completedFuture(null);
        given(kafka.send(anyString(), anyString(), any())).willReturn(ack);
    }

    private void givenKafkaRefuses() {
        given(kafka.send(anyString(), anyString(), any()))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));
    }

    // --- the happy path ---------------------------------------------------

    @Test
    @DisplayName("publishes the backlog and marks each event published")
    void publishesAndMarks() {
        OutboxEventEntity first = event("e1", "ACC-GB-0001", "20000000.00");
        OutboxEventEntity second = event("e2", "ACC-GB-0001", "25000000.00");
        given(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of(first, second));
        givenKafkaAccepts();

        int published = publisher().publishPending();

        Assertions.assertThat(published).isEqualTo(2);
        Assertions.assertThat(first.isPublished()).isTrue();
        Assertions.assertThat(second.isPublished()).isTrue();

        // No save() anywhere. In production these entities are managed by the
        // persistence context and Hibernate writes the UPDATE at commit through
        // dirty checking. Verifying no write call pins that down - and it is the
        // exact opposite of the Layer 3 trap, where the entity was detached and
        // the update silently never happened.
        verify(outbox).findTop100ByPublishedAtIsNullOrderByIdAsc();
        verifyNoMoreInteractions(outbox);
    }

    @Test
    @DisplayName("converts the stored JSON into the Avro wire type, keyed by the partition key")
    void sendsAvroKeyedByPartitionKey() {
        given(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc())
                .willReturn(List.of(event("e1", "ACC-JP-0001", "500000000")));
        givenKafkaAccepts();

        publisher().publishPending();

        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(kafka).send(eq(TOPIC), eq("ACC-JP-0001"), payload.capture());

        // The outbox holds domain JSON; what leaves is the generated Avro type.
        // Keeping those separate is what stops the wire format leaking into the
        // domain, and vice versa.
        Assertions.assertThat(payload.getValue()).isInstanceOf(LiquidityBufferChanged.class);
        LiquidityBufferChanged sent = (LiquidityBufferChanged) payload.getValue();
        Assertions.assertThat(sent.getEventId()).isEqualTo("e1");
        Assertions.assertThat(sent.getAccountId()).isEqualTo("ACC-JP-0001");
        Assertions.assertThat(sent.getNewBuffer()).isEqualTo("500000000");

        // The KEY stays a String. Keys and values are serialised independently,
        // and a readable key is worth far more than the few bytes an Avro key
        // would save - it is the thing you grep for.
    }

    @Test
    @DisplayName("an empty backlog does not touch Kafka")
    void emptyBacklogDoesNothing() {
        given(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of());

        Assertions.assertThat(publisher().publishPending()).isZero();

        verifyNoInteractions(kafka);
    }

    // --- failure behaviour ------------------------------------------------

    @Test
    @DisplayName("stops at the first failure rather than skipping past it")
    void stopsAtFirstFailure() {
        OutboxEventEntity first = event("e1", "ACC-GB-0001", "20000000.00");
        OutboxEventEntity second = event("e2", "ACC-GB-0001", "25000000.00");
        given(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of(first, second));
        givenKafkaRefuses();

        int published = publisher().publishPending();

        // Carrying on to the second event would deliver this account's changes out
        // of order, and a consumer applying the older one last ends up with the
        // wrong balance. A stalled queue is recoverable; a reordered one may not be.
        Assertions.assertThat(published).isZero();
        Assertions.assertThat(first.isPublished()).isFalse();
        Assertions.assertThat(second.isPublished()).isFalse();
        verify(kafka).send(anyString(), anyString(), any());
        verifyNoMoreInteractions(kafka);
    }

    @Test
    @DisplayName("a failed event stays unpublished, so the next run retries it")
    void failedEventIsRetriable() {
        OutboxEventEntity e = event("e1", "ACC-GB-0001", "20000000.00");
        given(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of(e));
        givenKafkaRefuses();

        publisher().publishPending();

        // Nothing lost, nothing to clean up. The row is still in the table, still
        // unpublished, and the next tick sends it. A broker outage costing latency
        // and not data is the entire reason the outbox exists.
        Assertions.assertThat(e.getPublishedAt()).isNull();
    }

    @Test
    @DisplayName("an event type with no Avro mapping is left unpublished rather than dropped")
    void unmappableEventTypeIsNotDropped() {
        OutboxEventEntity odd = new OutboxEventEntity(
                "e9", "SettlementAccount", "ACC-GB-0001", "SomethingNobodyMapped",
                TOPIC, "ACC-GB-0001", "{}");
        given(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of(odd));

        Assertions.assertThat(publisher().publishPending()).isZero();

        // Deliberately not marked published and deliberately not thrown away. A
        // retry will not fix it, so it needs a human - but silently dropping an
        // event is the one outcome the outbox exists to prevent, so it stays in
        // the table where a support query will find it.
        Assertions.assertThat(odd.getPublishedAt()).isNull();
        verifyNoInteractions(kafka);
    }
}
