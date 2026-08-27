package com.atlas.liquidity.position.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.position.projection.AccountPositionEntity;
import com.atlas.liquidity.position.projection.AccountPositionJpaRepository;
import com.atlas.liquidity.position.projection.ProcessedEventEntity;
import com.atlas.liquidity.position.projection.ProcessedEventJpaRepository;
import com.atlas.liquidity.position.support.AbstractPostgresIntegrationTest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The consumer's correctness, against a real database and no broker.
 *
 * <p>Everything that makes an idempotent consumer correct lives in the service,
 * not in the listener - so it can be tested directly, deterministically, and in
 * about a second. Feeding real messages through Kafka to assert on the same
 * properties would be slower, flakier, and would prove nothing extra: Kafka's job
 * is delivering bytes, and we are not testing Kafka.
 *
 * <p>What this cannot prove is that the listener is wired to the right topic with
 * the right group. That is what the walkthrough is for, and it is honest to say
 * so rather than let a green build imply more than it means.
 */
@SpringBootTest
class PositionUpdateServiceIT extends AbstractPostgresIntegrationTest {

    private static final String ACCOUNT = "ACC-GB-0001";
    private static final String TOPIC = "atlas.liquidity.buffer-changed.v1";

    @Autowired
    private PositionUpdateService service;

    @Autowired
    private AccountPositionJpaRepository positions;

    @Autowired
    private ProcessedEventJpaRepository processedEvents;

    @BeforeEach
    void clean() {
        positions.deleteAll();
        processedEvents.deleteAll();
    }

    private static LiquidityBufferChangedEvent event(String eventId, String buffer, Instant at) {
        return new LiquidityBufferChangedEvent(
                eventId, ACCOUNT, "GBP", "0.00", buffer,
                LiquidityBufferChangedEvent.CHANGE_TYPE_ADJUSTMENT, null, at);
    }

    private static String newId() {
        return UUID.randomUUID().toString();
    }

    // --- the core behaviour ----------------------------------------------

    @Test
    @DisplayName("the first event for an account creates the position")
    void firstEventCreatesPosition() {
        Instant now = Instant.now();
        LiquidityBufferChangedEvent e = event(newId(), "15000000.00", now);

        assertThat(service.apply(e, TOPIC)).isEqualTo(PositionUpdateService.Outcome.APPLIED);

        AccountPositionEntity position = positions.findById(ACCOUNT).orElseThrow();
        assertThat(position.getCurrentBuffer()).isEqualByComparingTo("15000000.00");
        assertThat(position.getCurrencyCode()).isEqualTo("GBP");
        assertThat(position.getLastEventId()).isEqualTo(e.eventId());
        assertThat(position.getAppliedCount()).isEqualTo(1);
    }

    /**
     * The test this half of the layer exists for.
     *
     * <p>Kafka delivers at least once and part 1's relay produces duplicates by
     * contract, so this is not a hypothetical - it is the normal case after any
     * crash or rebalance. Without the register, the second delivery would apply
     * the change again and the projection would drift from reality with nothing
     * to indicate it.
     */
    @Test
    @DisplayName("the same event delivered twice is applied exactly once")
    void duplicateDeliveryIsIgnored() {
        LiquidityBufferChangedEvent e = event(newId(), "15000000.00", Instant.now());

        assertThat(service.apply(e, TOPIC)).isEqualTo(PositionUpdateService.Outcome.APPLIED);
        assertThat(service.apply(e, TOPIC)).isEqualTo(PositionUpdateService.Outcome.DUPLICATE);

        AccountPositionEntity position = positions.findById(ACCOUNT).orElseThrow();

        // appliedCount is the honest witness. The buffer alone would look correct
        // even if the event had been applied twice, because the event carries an
        // absolute value rather than a delta - so a test asserting only on the
        // balance would pass against a broken consumer.
        assertThat(position.getAppliedCount()).isEqualTo(1);
        assertThat(processedEvents.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("ten redeliveries still apply once")
    void manyRedeliveriesApplyOnce() {
        LiquidityBufferChangedEvent e = event(newId(), "15000000.00", Instant.now());

        for (int i = 0; i < 10; i++) {
            service.apply(e, TOPIC);
        }

        assertThat(positions.findById(ACCOUNT).orElseThrow().getAppliedCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a later event updates the position")
    void laterEventUpdates() {
        Instant first = Instant.now().minus(1, ChronoUnit.MINUTES);
        service.apply(event(newId(), "15000000.00", first), TOPIC);

        LiquidityBufferChangedEvent later = event(newId(), "20000000.00", first.plusSeconds(30));
        assertThat(service.apply(later, TOPIC)).isEqualTo(PositionUpdateService.Outcome.APPLIED);

        AccountPositionEntity position = positions.findById(ACCOUNT).orElseThrow();
        assertThat(position.getCurrentBuffer()).isEqualByComparingTo("20000000.00");
        assertThat(position.getAppliedCount()).isEqualTo(2);
        assertThat(position.getLastEventId()).isEqualTo(later.eventId());
    }

    // --- ordering ---------------------------------------------------------

    /**
     * Kafka guarantees order within a partition and the producer keys by account,
     * so this should not happen in the normal case. It stops being true after a
     * retry, after a replay from the dead letter topic, or if anyone ever changes
     * the partition key - and an out-of-order event applied blindly walks the
     * balance backwards with no error anywhere.
     */
    @Test
    @DisplayName("an out-of-order event is discarded rather than applied backwards")
    void staleEventIsDiscarded() {
        Instant now = Instant.now();
        service.apply(event(newId(), "20000000.00", now), TOPIC);

        LiquidityBufferChangedEvent stale = event(newId(), "15000000.00", now.minusSeconds(60));
        assertThat(service.apply(stale, TOPIC)).isEqualTo(PositionUpdateService.Outcome.STALE);

        assertThat(positions.findById(ACCOUNT).orElseThrow().getCurrentBuffer())
                .isEqualByComparingTo("20000000.00");
    }

    @Test
    @DisplayName("a discarded event is still recorded as processed, marked as not applied")
    void staleEventIsStillRecorded() {
        Instant now = Instant.now();
        service.apply(event(newId(), "20000000.00", now), TOPIC);

        LiquidityBufferChangedEvent stale = event(newId(), "15000000.00", now.minusSeconds(60));
        service.apply(stale, TOPIC);

        // "We saw it and decided it changed nothing" has to stay distinguishable
        // from "it never arrived". During an investigation that is the difference
        // between a five-minute answer and an afternoon.
        ProcessedEventEntity record = processedEvents.findById(stale.eventId()).orElseThrow();
        assertThat(record.isApplied()).isFalse();
        assertThat(record.getAggregateId()).isEqualTo(ACCOUNT);
        assertThat(record.getSourceTopic()).isEqualTo(TOPIC);
    }

    @Test
    @DisplayName("an event with an identical timestamp is treated as stale, not applied again")
    void identicalTimestampIsStale() {
        Instant now = Instant.now();
        service.apply(event(newId(), "20000000.00", now), TOPIC);

        // Same instant, different event id - so the duplicate register does not
        // catch it. Two events can genuinely share a millisecond, which is why a
        // production system would prefer a monotonic sequence number from the
        // producer over a wall-clock timestamp.
        assertThat(service.apply(event(newId(), "25000000.00", now), TOPIC))
                .isEqualTo(PositionUpdateService.Outcome.STALE);

        assertThat(positions.findById(ACCOUNT).orElseThrow().getCurrentBuffer())
                .isEqualByComparingTo("20000000.00");
    }

    // --- isolation --------------------------------------------------------

    @Test
    @DisplayName("accounts are independent")
    void accountsAreIndependent() {
        Instant now = Instant.now();
        service.apply(event(newId(), "15000000.00", now), TOPIC);
        service.apply(new LiquidityBufferChangedEvent(
                newId(), "ACC-US-0001", "USD", "0.00", "25000000.00",
                LiquidityBufferChangedEvent.CHANGE_TYPE_ABSOLUTE_SET, null, now), TOPIC);

        assertThat(positions.count()).isEqualTo(2);
        assertThat(positions.findById("ACC-US-0001").orElseThrow().getCurrencyCode()).isEqualTo("USD");
        assertThat(positions.findById(ACCOUNT).orElseThrow().getCurrentBuffer())
                .isEqualByComparingTo("15000000.00");
    }

    @Test
    @DisplayName("the projection is rebuildable: wipe it and replay")
    void projectionIsRebuildable() {
        Instant now = Instant.now();
        LiquidityBufferChangedEvent e = event(newId(), "15000000.00", now);
        service.apply(e, TOPIC);

        // What "reset the consumer group and replay" looks like from the outside:
        // both tables go, and the same events produce the same result. A
        // projection that cannot be rebuilt from its source events is not a
        // projection, it is a second source of truth pretending to be one.
        positions.deleteAll();
        processedEvents.deleteAll();

        assertThat(service.apply(e, TOPIC)).isEqualTo(PositionUpdateService.Outcome.APPLIED);
        assertThat(positions.findById(ACCOUNT).orElseThrow().getCurrentBuffer())
                .isEqualByComparingTo("15000000.00");
    }
}
