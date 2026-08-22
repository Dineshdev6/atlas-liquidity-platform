package com.atlas.liquidity.refdata.application;

import com.atlas.liquidity.common.events.LiquidityBufferChangedEvent;
import com.atlas.liquidity.common.money.Money;
import com.atlas.liquidity.refdata.api.AccountNotFoundException;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import com.atlas.liquidity.refdata.outbox.OutboxWriter;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.NoSuchElementException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The first application service in this codebase, and it exists for a reason.
 *
 * <p>Layers 2 and 3 both documented a defect on the PUT endpoint: the controller
 * read an account and then updated it in two separate transactions, so another
 * request could change or delete it in between. The fix was always "one transaction
 * spanning both operations", which means something above the repository has to own
 * the boundary. Until now there was nothing for such a class to do, so adding one
 * would have been ceremony. An adjustment - read the current buffer, add a delta,
 * write it back - is genuinely a multi-step operation, so the service earns its
 * place.
 *
 * <p><b>Why an adjustment and not another absolute set.</b> The PUT endpoint says
 * "make the buffer exactly this", which is naturally idempotent - send it ten times
 * and the state is the same. An adjustment says "add this much", which is
 * emphatically <em>not</em> idempotent: applied twice, the money is wrong. That is
 * precisely why this operation needs an idempotency key and the PUT does not, and
 * it is the honest way to demonstrate the mechanism rather than bolting it onto an
 * operation that never needed it.
 *
 * <p><b>Note where {@code @Transactional} now sits.</b> On this method, not on the
 * repository. The repository adapter is still marked
 * {@code @Transactional(readOnly = true)}, and that annotation is ignored here:
 * with the default {@code REQUIRED} propagation the adapter's methods join this
 * transaction instead of starting their own, and read-only is a property of a
 * transaction rather than of a method. So the read and the write are atomic, and
 * the race is gone on this path. The PUT endpoint still has it - fixing that is a
 * separate, small change and a good exercise.
 */
@Service
public class LiquidityBufferAdjustmentService {

    private static final Logger log = LoggerFactory.getLogger(LiquidityBufferAdjustmentService.class);

    private final SettlementAccountRepository accounts;
    private final OutboxWriter outbox;

    LiquidityBufferAdjustmentService(SettlementAccountRepository accounts, OutboxWriter outbox) {
        this.accounts = accounts;
        this.outbox = outbox;
    }

    /**
     * Adds {@code delta} to an account's liquidity buffer.
     *
     * <p>The delta is interpreted in the account's own currency - the caller cannot
     * supply one, so a mismatched adjustment is not expressible.
     *
     * @param delta signed amount; negative reduces the buffer
     * @throws NoSuchElementException   if no such account exists
     * @throws IllegalArgumentException if the result would be negative
     */
    @Transactional
    public SettlementAccount adjustBy(String accountId, BigDecimal delta) {
        SettlementAccount account = accounts.findByAccountId(accountId)
                .orElseThrow(() -> new NoSuchElementException(
                        "No settlement account found with id: " + accountId));

        Money adjustment = Money.of(account.liquidityBuffer().currency(), delta);
        Money newBuffer = account.liquidityBuffer().plus(adjustment);

        // A buffer is a floor, so it cannot be negative. The database has a CHECK
        // constraint saying the same thing, and that is not redundant - the schema
        // is the last line of defence and the only one a batch job or a DBA script
        // cannot bypass. But catching it here gives the caller a 400 with a useful
        // message instead of a 409 from a constraint name.
        //
        // Note this throws IllegalArgumentException, which the adapter deliberately
        // does NOT do. The difference is that this class is a @Service, not a
        // @Repository, so Spring's persistence exception translation never sees it
        // and cannot rewrite it into InvalidDataAccessApiUsageException. Same
        // exception type, different layer, different outcome - which is exactly the
        // Layer 2 bug, understood.
        if (newBuffer.isNegative()) {
            throw new IllegalArgumentException(
                    "Adjustment of " + adjustment + " would take the buffer below zero"
                            + " (current: " + account.liquidityBuffer() + ")");
        }

        log.info("Adjusting buffer for {} by {} -> {}", accountId, adjustment, newBuffer);
        SettlementAccount updated = accounts.updateLiquidityBuffer(accountId, newBuffer);

        // LAYER 4. The event is written to the outbox INSIDE this transaction,
        // which is the whole pattern in one line. If the update below had already
        // committed and a Kafka send then failed, the money would have moved with
        // nobody told. Here there is no send: the event is a row, and it commits
        // or rolls back with the balance it describes.
        recordChange(account, updated, LiquidityBufferChangedEvent.CHANGE_TYPE_ADJUSTMENT, null);

        return updated;
    }

    /**
     * Sets the buffer to an absolute value, atomically.
     *
     * <p>This closes the last deliberate defect carried since Layer 2. The
     * controller used to do the read and the write itself, which meant two calls
     * into the repository and therefore <b>two transactions</b> - each repository
     * method starting and committing its own. Between them another request could
     * change the account's currency or delete it outright, and nothing would
     * throw; the row would simply end up wrong.
     *
     * <p>With {@code @Transactional} here, both repository calls join <em>this</em>
     * transaction (default {@code REQUIRED} propagation), so the read and the write
     * are one atomic unit. Note this is the same reason the adjustment path was
     * already safe - it is not a property of the endpoint, it is a property of
     * where the transaction boundary sits.
     *
     * <p><b>Still not a substitute for optimistic locking.</b> One transaction
     * makes the read-modify-write atomic on this path; the {@code @Version} column
     * is what catches a conflicting write from a different transaction. They solve
     * different halves of the problem, and knowing which is which is worth a mark
     * in an interview.
     */
    @Transactional
    public SettlementAccount setTo(String accountId, BigDecimal amount) {
        SettlementAccount account = accounts.findByAccountId(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));

        // The currency comes from the account, never the caller, so a mismatched
        // buffer is not expressible.
        Money newBuffer = Money.of(account.liquidityBuffer().currency(), amount);

        log.info("Setting buffer for {} to {}", accountId, newBuffer);
        SettlementAccount updated = accounts.updateLiquidityBuffer(accountId, newBuffer);

        recordChange(account, updated, LiquidityBufferChangedEvent.CHANGE_TYPE_ABSOLUTE_SET, null);

        return updated;
    }

    /**
     * Records the change as a domain event, in the caller's transaction.
     *
     * <p>Note what the event carries and what it does not. It carries the account
     * id, both buffer values and when it happened - everything a consumer needs
     * to act without calling back to ask. It does not carry the whole account:
     * an event describing a change should describe the change, and a consumer
     * that needs the account's BIC can look it up. Fat events couple every
     * consumer to every field you ever add.
     *
     * <p><b>Both the previous and the new value are included</b> so that each
     * message stands alone. A consumer replaying the topic from the beginning, or
     * joining after a gap, can reason about one message without having seen the
     * one before it - and can notice that it missed something, which a
     * new-value-only event makes impossible.
     */
    private void recordChange(SettlementAccount before, SettlementAccount after,
                              String changeType, String reason) {

        // The id is generated first so that the SAME value goes into the outbox
        // row and into the payload. The row's copy is ours, for support queries;
        // the payload's copy is the one that travels, and it is the value a
        // consumer will store to recognise a redelivery of this exact event.
        String eventId = outbox.newEventId();

        LiquidityBufferChangedEvent event = new LiquidityBufferChangedEvent(
                eventId,
                after.accountId(),
                after.liquidityBuffer().currencyCode(),
                before.liquidityBuffer().amount().toPlainString(),
                after.liquidityBuffer().amount().toPlainString(),
                changeType,
                reason,
                Instant.now());

        outbox.record(
                eventId,
                LiquidityBufferChangedEvent.AGGREGATE_TYPE,
                after.accountId(),
                LiquidityBufferChangedEvent.EVENT_TYPE,
                // THE PARTITION KEY. Every event for one account goes to one
                // partition, and Kafka guarantees order within a partition - so a
                // consumer sees this account's changes in the order they happened.
                // Key by something else, or leave it null, and "set to 20m" can
                // arrive after "set to 15m" and the last write wins, wrongly.
                //
                // The trade-off, which you should raise before an interviewer
                // does: a single very busy account becomes a hot partition, and
                // one partition is handled by exactly one consumer in a group, so
                // that account's throughput cannot be scaled out. You accept it
                // when ordering matters more than parallelism, which here it does.
                after.accountId(),
                event);
    }
}
