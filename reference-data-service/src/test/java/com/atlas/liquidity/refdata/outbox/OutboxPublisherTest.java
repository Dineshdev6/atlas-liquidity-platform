package com.atlas.liquidity.refdata.outbox;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

/**
 * The publisher's logic, with no broker and no database.
 *
 * <p>Everything here is a decision the publisher makes: what order to send in, what
 * to use as the message key, when to stop, and what to do when Kafka does not
 * answer. None of that needs a real Kafka, and testing it against one would make
 * these tests slower and less certain without making them prove more.
 *
 * <p>What this deliberately does <em>not</em> prove is that a message actually
 * reaches a broker and comes back out the other side. A mocked
 * {@code KafkaTemplate} cannot tell you that, and pretending otherwise is how
 * suites end up green against a broker that was never running. Part 2 has real
 * consumers and proves it end to end; until then the manual walkthrough with
 * {@code kafka-console-consumer} is the honest check.
 */
@ExtendWith(MockitoExtension.class)
class OutboxPublisherTest {

    private static final String TOPIC = "atlas.liquidity.buffer-changed.v1";

    @Mock
    private OutboxEventJpaRepository outbox;

    @Mock
    private KafkaTemplate<String, String> kafka;

    private OutboxPublisher publisher() {
        return new OutboxPublisher(outbox, kafka);
    }

    private static OutboxEventEntity event(String eventId, String accountId, String payload) {
        return new OutboxEventEntity(
                eventId, "SettlementAccount", accountId, "LiquidityBufferChanged",
                TOPIC, accountId, payload);
    }

    private void givenKafkaAccepts() {
        CompletableFuture<SendResult<String, String>> ack = CompletableFuture.completedFuture(null);
        given(kafka.send(anyString(), anyString(), anyString())).willReturn(ack);
    }

    // --- the happy path ---------------------------------------------------

    @Test
    @DisplayName("publishes the backlog and marks each event published")
    void publishesAndMarks() {
        OutboxEventEntity first = event("e1", "ACC-GB-0001", "{\"a\":1}");
        OutboxEventEntity second = event("e2", "ACC-GB-0001", "{\"a\":2}");
        given(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of(first, second));
        givenKafkaAccepts();

        int published = publisher().publishPending();

        Assertions.assertThat(published).isEqualTo(2);
        Assertions.assertThat(first.isPublished()).isTrue();
        Assertions.assertThat(second.isPublished()).isTrue();

        // No save() anywhere. In production these entities are managed by the
        // persistence context and Hibernate writes the UPDATE at commit through
        // dirty checking. Verifying no write call is how this test pins that
        // down - and it is the exact opposite of the trap in Layer 3, where the
        // entity was detached and the update silently never happened.
        verify(outbox).findTop100ByPublishedAtIsNullOrderByIdAsc();
        verifyNoMoreInteractions(outbox);
    }

    @Test
    @DisplayName("sends to the event's own topic, keyed by the partition key")
    void usesTopicAndPartitionKeyFromTheEvent() {
        OutboxEventEntity e = event("e1", "ACC-JP-0001", "{\"amount\":\"1\"}");
        given(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of(e));
        givenKafkaAccepts();

        publisher().publishPending();

        // The key is what buys per-account ordering: Kafka routes by hash of the
        // key, ordering is guaranteed within a partition, so one account's
        // changes can never overtake each other. A null key would round-robin
        // and the guarantee would quietly disappear.
        verify(kafka).send(TOPIC, "ACC-JP-0001", "{\"amount\":\"1\"}");
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
        OutboxEventEntity first = event("e1", "ACC-GB-0001", "{\"a\":1}");
        OutboxEventEntity second = event("e2", "ACC-GB-0001", "{\"a\":2}");
        given(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of(first, second));
        given(kafka.send(TOPIC, "ACC-GB-0001", "{\"a\":1}"))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        int published = publisher().publishPending();

        // This is the assertion that matters. Carrying on to the second event
        // would deliver this account's changes out of order, and a consumer
        // applying the older one last ends up with the wrong balance. A stalled
        // queue is recoverable; a reordered one may not be.
        Assertions.assertThat(published).isZero();
        Assertions.assertThat(first.isPublished()).isFalse();
        Assertions.assertThat(second.isPublished()).isFalse();
        verify(kafka).send(TOPIC, "ACC-GB-0001", "{\"a\":1}");
        verifyNoMoreInteractions(kafka);
    }

    @Test
    @DisplayName("a failed event stays unpublished, so the next run retries it")
    void failedEventIsRetriable() {
        OutboxEventEntity e = event("e1", "ACC-GB-0001", "{\"a\":1}");
        given(outbox.findTop100ByPublishedAtIsNullOrderByIdAsc()).willReturn(List.of(e));
        given(kafka.send(anyString(), anyString(), anyString()))
                .willReturn(CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        publisher().publishPending();

        // Nothing was lost and nothing needs cleaning up. The row is still in the
        // table, still unpublished, and the next tick will send it. That property
        // - a broker outage costs latency and not data - is the entire reason the
        // outbox exists.
        Assertions.assertThat(e.getPublishedAt()).isNull();
    }

}
