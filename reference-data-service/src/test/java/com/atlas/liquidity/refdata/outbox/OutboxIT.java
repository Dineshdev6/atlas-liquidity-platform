package com.atlas.liquidity.refdata.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.refdata.application.LiquidityBufferAdjustmentService;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import com.atlas.liquidity.refdata.support.AbstractPostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;

/**
 * The outbox against a real database.
 *
 * <p>These tests exist to prove one property, and it is the only property that
 * matters: <b>the event and the business change share a fate</b>. Either the
 * buffer moved and an event was recorded, or neither happened. There is no
 * arrangement of failures that produces one without the other.
 *
 * <p>That cannot be tested with mocks. A mocked repository has no transaction to
 * roll back, so a test using one would pass whether or not the production code
 * was correct - which is the same lesson Layer 3's merge-versus-persist bug
 * taught the expensive way.
 *
 * <p>The relay is disabled here (see {@code application-it.yml}) so the tests can
 * look at outbox rows without racing a background timer.
 */
@SpringBootTest
class OutboxIT extends AbstractPostgresIntegrationTest {

    private static final String ACCOUNT = "ACC-GB-0001";
    private static final BigDecimal BASELINE = new BigDecimal("15000000.00");

    @Autowired
    private LiquidityBufferAdjustmentService adjustments;

    @Autowired
    private OutboxEventJpaRepository outbox;

    @Autowired
    private OutboxWriter outboxWriter;

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private SettlementAccountRepository accounts;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void clearOutbox() {
        outbox.deleteAll();
    }

    @AfterEach
    void restoreBaseline() {
        adjustments.setTo(ACCOUNT, BASELINE);
        outbox.deleteAll();
    }

    private String currentBuffer() {
        return accounts.findByAccountId(ACCOUNT).orElseThrow()
                .liquidityBuffer().amount().toPlainString();
    }

    private LiquidityBufferChangedEvent payloadOf(OutboxEventEntity row) throws Exception {
        return objectMapper.readValue(row.getPayload(), LiquidityBufferChangedEvent.class);
    }

    // --- the core property ------------------------------------------------

    @Test
    @DisplayName("an adjustment records exactly one event, unpublished, keyed by account")
    void adjustmentWritesOneEvent() {
        adjustments.adjustBy(ACCOUNT, new BigDecimal("5000000.00"));

        List<OutboxEventEntity> events = outbox.findByAggregateIdOrderByIdAsc(ACCOUNT);
        assertThat(events).hasSize(1);

        OutboxEventEntity row = events.get(0);
        assertThat(row.getAggregateType()).isEqualTo("SettlementAccount");
        assertThat(row.getEventType()).isEqualTo("LiquidityBufferChanged");
        assertThat(row.getTopic()).isEqualTo("atlas.liquidity.buffer-changed.v1");

        // The partition key IS the account id, which is what gives a consumer
        // ordering per account. If this ever changes, ordering silently breaks
        // and nothing else in the suite would notice.
        assertThat(row.getPartitionKey()).isEqualTo(ACCOUNT);

        // Unpublished, because the relay is switched off in this profile. In
        // production this row would live for about a second.
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getCreatedAt()).isNotNull();
    }

    /**
     * The test the whole layer exists for.
     *
     * <p>The adjustment is refused, so the transaction rolls back - and the event
     * rolls back with it, because it was written inside that transaction rather
     * than sent over a network beside it. Had this been a dual write, the send
     * would already have happened and the platform would now believe in a change
     * that was rejected.
     */
    @Test
    @DisplayName("a rejected adjustment leaves NO event behind - the whole point of the pattern")
    void rejectedAdjustmentLeavesNoEvent() {
        assertThatThrownBy(() -> adjustments.adjustBy(ACCOUNT, new BigDecimal("-99000000.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("below zero");

        assertThat(outbox.findByAggregateIdOrderByIdAsc(ACCOUNT)).isEmpty();
        assertThat(currentBuffer()).isEqualTo("15000000.00");
    }

    @Test
    @DisplayName("an adjustment to an unknown account records nothing")
    void unknownAccountRecordsNothing() {
        assertThatThrownBy(() -> adjustments.adjustBy("ACC-NOPE", new BigDecimal("1000.00")))
                .isInstanceOf(RuntimeException.class);

        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    // --- what the event says ----------------------------------------------

    @Test
    @DisplayName("the payload describes both sides of the change and carries the row's event id")
    void payloadDescribesTheChange() throws Exception {
        adjustments.adjustBy(ACCOUNT, new BigDecimal("5000000.00"));

        OutboxEventEntity row = outbox.findByAggregateIdOrderByIdAsc(ACCOUNT).get(0);
        LiquidityBufferChangedEvent event = payloadOf(row);

        // Same id in the row and in the payload. The row's copy is ours, for
        // support queries; the payload's copy is the one that travels and that a
        // consumer stores in order to recognise a redelivery.
        assertThat(event.eventId()).isEqualTo(row.getEventId());

        assertThat(event.accountId()).isEqualTo(ACCOUNT);
        assertThat(event.currencyCode()).isEqualTo("GBP");

        // Both sides, so the message stands alone. A consumer replaying from the
        // start of the topic can reason about this event without having seen the
        // one before it.
        assertThat(event.previousBuffer()).isEqualTo("15000000.00");
        assertThat(event.newBuffer()).isEqualTo("20000000.00");

        assertThat(event.changeType()).isEqualTo(LiquidityBufferChangedEvent.CHANGE_TYPE_ADJUSTMENT);
        assertThat(event.occurredAt()).isNotNull();

        // Deserialising a record with no Jackson annotations works only because
        // the build compiles with -parameters. Third time that Layer 1 flag has
        // earned its place.
    }

    @Test
    @DisplayName("an absolute set is distinguishable from a delta")
    void absoluteSetIsADifferentChangeType() throws Exception {
        adjustments.setTo(ACCOUNT, new BigDecimal("21000000.00"));

        LiquidityBufferChangedEvent event =
                payloadOf(outbox.findByAggregateIdOrderByIdAsc(ACCOUNT).get(0));

        // A consumer must be able to tell "add five million" from "make it
        // twenty-one million" - they are different facts, and a consumer that
        // confuses them corrupts its own view. Encoding it in the event is
        // cheaper than every consumer inferring it from the numbers.
        assertThat(event.changeType()).isEqualTo(LiquidityBufferChangedEvent.CHANGE_TYPE_ABSOLUTE_SET);
        assertThat(event.previousBuffer()).isEqualTo("15000000.00");
        assertThat(event.newBuffer()).isEqualTo("21000000.00");
    }

    @Test
    @DisplayName("each change gets its own event, in order")
    void repeatedChangesAccumulateInOrder() throws Exception {
        adjustments.adjustBy(ACCOUNT, new BigDecimal("1000000.00"));
        adjustments.adjustBy(ACCOUNT, new BigDecimal("2000000.00"));

        List<OutboxEventEntity> events = outbox.findByAggregateIdOrderByIdAsc(ACCOUNT);
        assertThat(events).hasSize(2);

        // The generated id is the ordering column, and the relay reads in this
        // order. Publish out of order and a consumer applying the older change
        // last ends up with the wrong balance.
        assertThat(events.get(0).getId()).isLessThan(events.get(1).getId());
        assertThat(payloadOf(events.get(0)).newBuffer()).isEqualTo("16000000.00");
        assertThat(payloadOf(events.get(1)).previousBuffer()).isEqualTo("16000000.00");
        assertThat(payloadOf(events.get(1)).newBuffer()).isEqualTo("18000000.00");
    }

    // --- the guard rails --------------------------------------------------

    @Test
    @DisplayName("recording an event outside a transaction is refused outright")
    void writerRefusesToRunWithoutATransaction() {
        // Propagation.MANDATORY turns the one way this pattern can be silently
        // defeated - recording the event in its own transaction, so that it can
        // commit while the business change rolls back - into an immediate,
        // obvious failure. A dual write with extra steps would otherwise be
        // indistinguishable from correct code until the day it mattered.
        assertThatThrownBy(() -> outboxWriter.record(
                "some-id", "SettlementAccount", ACCOUNT, "LiquidityBufferChanged", ACCOUNT, "{}"))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outbox.countByPublishedAtIsNull()).isZero();
    }

    @Test
    @DisplayName("the publisher is wired and no-ops on an empty backlog without contacting Kafka")
    void publisherIsWiredAndHandlesAnEmptyBacklog() {
        // Deliberately the only publisher assertion here: it proves the bean
        // exists with its real dependencies, and it needs no broker because an
        // empty backlog never sends. Whether a message survives a round trip
        // through Kafka is part 2's job, with a real consumer to observe it.
        assertThat(publisher.publishPending()).isZero();
    }
}
