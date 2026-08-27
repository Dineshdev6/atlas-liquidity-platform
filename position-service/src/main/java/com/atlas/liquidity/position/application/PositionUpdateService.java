package com.atlas.liquidity.position.application;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.position.projection.AccountPositionEntity;
import com.atlas.liquidity.position.projection.AccountPositionJpaRepository;
import com.atlas.liquidity.position.projection.ProcessedEventEntity;
import com.atlas.liquidity.position.projection.ProcessedEventJpaRepository;
import java.math.BigDecimal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies a buffer-changed event to the projection, at most once.
 *
 * <p>This is the whole consumer-side correctness argument, and it is three
 * properties that have to hold together.
 *
 * <p><b>1. At most once, over an at-least-once transport.</b> Kafka delivers
 * duplicates by design, and part 1's relay produces them by contract. The event
 * id is the primary key of {@code processed_event}, so a second application is
 * impossible - not unlikely, impossible. The {@code existsById} check below is an
 * optimisation to avoid throwing in the common case; the constraint is the
 * guarantee. That distinction is the same one Layer 3 made about idempotency
 * keys, and it is the difference between having read about idempotent consumers
 * and having built one.
 *
 * <p><b>2. One transaction.</b> Updating the projection and recording the event
 * as processed both happen here, together. If the projection committed and the
 * record did not, redelivery would apply the change twice. If the record
 * committed and the projection did not, the change would be lost and never
 * retried. Both or neither - exactly the argument from ADR 0006, on the other
 * side of the wire.
 *
 * <p><b>3. Newer events only.</b> Kafka guarantees ordering within a partition
 * and the producer keys by account, so in the normal case events arrive in order.
 * That stops being true after a retry, after a dead-letter replay, or if anyone
 * changes the partition key. An out-of-order event applied blindly sets the
 * balance backwards, silently, so we compare occurrence times and decline.
 *
 * <p>Note that a declined event is still recorded as processed. We did handle it;
 * we decided it changed nothing. Keeping "ignored" distinguishable from "never
 * arrived" is what makes the register usable during an investigation.
 */
@Service
public class PositionUpdateService {

    private static final Logger log = LoggerFactory.getLogger(PositionUpdateService.class);

    private final AccountPositionJpaRepository positions;
    private final ProcessedEventJpaRepository processedEvents;

    PositionUpdateService(AccountPositionJpaRepository positions,
                          ProcessedEventJpaRepository processedEvents) {
        this.positions = positions;
        this.processedEvents = processedEvents;
    }

    /**
     * Applies an event.
     *
     * @param event       the event to apply
     * @param sourceTopic where it arrived from, recorded for support queries
     * @return what happened, so the caller can log it honestly
     */
    @Transactional
    public Outcome apply(LiquidityBufferChangedEvent event, String sourceTopic) {

        if (processedEvents.existsById(event.eventId())) {
            // The duplicate that at-least-once delivery guarantees will happen.
            // Not an error, not worth a warning - the system working as designed.
            log.debug("Ignoring duplicate delivery of event {}", event.eventId());
            return Outcome.DUPLICATE;
        }

        BigDecimal newBuffer = new BigDecimal(event.newBuffer());
        AccountPositionEntity position = positions.findById(event.accountId()).orElse(null);

        Outcome outcome;
        if (position == null) {
            // First event for this account. save() with an assigned identifier
            // goes through merge, which returns a managed COPY - so we keep the
            // returned instance rather than the one we passed in. Third time this
            // has mattered in this codebase; the first time it was a bug.
            positions.save(new AccountPositionEntity(
                    event.accountId(), event.currencyCode(), newBuffer,
                    event.eventId(), event.occurredAt()));
            outcome = Outcome.APPLIED;

        } else if (position.isStale(event.occurredAt())) {
            // Older than what we already hold. Applying it would set the balance
            // backwards, and nothing downstream would ever tell you.
            log.warn("Discarding out-of-order event {} for {}: occurred {} but position is at {}",
                    event.eventId(), event.accountId(), event.occurredAt(), position.getLastEventAt());
            outcome = Outcome.STALE;

        } else {
            // Managed entity in a transaction: Hibernate writes the UPDATE at
            // commit through dirty checking. No save() needed.
            position.apply(newBuffer, event.eventId(), event.occurredAt());
            outcome = Outcome.APPLIED;
        }

        // Same transaction as the change above. This is the line that makes the
        // whole thing at-most-once rather than merely usually-once.
        processedEvents.save(new ProcessedEventEntity(
                event.eventId(), event.accountId(), sourceTopic, outcome == Outcome.APPLIED));

        return outcome;
    }

    /** What happened to an event, for honest logging and for tests to assert on. */
    public enum Outcome {
        /** The projection changed. */
        APPLIED,
        /** Already processed; nothing was done. */
        DUPLICATE,
        /** Older than the state we hold; recorded as processed, applied to nothing. */
        STALE
    }
}
