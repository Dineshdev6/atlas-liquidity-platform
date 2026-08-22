package com.atlas.liquidity.common.events;

import java.time.Instant;

/**
 * A settlement account's intraday liquidity buffer changed.
 *
 * <p><b>Why this lives in {@code liquidity-common} rather than in the service
 * that publishes it.</b> An event is a contract between services, and a contract
 * with one signature is not a contract. Putting it in the shared library means
 * the consumer written in part 2 compiles against the same definition the
 * producer serialises, so a field rename breaks the build rather than
 * production.
 *
 * <p>That convenience has a real cost, and you should be able to name it: every
 * consumer is now coupled to a shared jar, so upgrading the event means
 * coordinating releases - which is exactly what event-driven architecture is
 * supposed to avoid. In a large organisation the alternatives are a schema
 * registry holding the contract independently of any code (part 3), or each
 * consumer owning a hand-written copy of only the fields it needs. A monorepo
 * with two services makes the shared type the sensible starting point; be clear
 * that it is a starting point.
 *
 * <p><b>Why the amounts are Strings.</b> Same reason as the REST responses: JSON
 * has one numeric type and most clients parse it as an IEEE-754 double, which
 * silently loses precision on large values. Money crosses a wire as text or it
 * eventually crosses it wrongly.
 *
 * <p><b>Why past tense.</b> {@code LiquidityBufferChanged}, not
 * {@code ChangeLiquidityBuffer}. An event is a fact that has already happened
 * and cannot be refused; a command is a request that can be. Naming them the
 * same way is how systems end up with consumers that think they may veto
 * history.
 *
 * <p><b>Why {@code previousBuffer} is included</b> even though a consumer could
 * in principle track it. It makes each event self-describing, so a consumer
 * joining late, or replaying from the start of the topic, can reason about a
 * single message without having seen every earlier one. It also lets a consumer
 * detect that it missed something.
 *
 * <p>No Jackson annotations. Deserialising a record by constructor parameter
 * names works only because the build compiles with {@code -parameters} - the
 * Layer 1 flag, earning its keep for a third time.
 *
 * @param eventId        unique id of this event, used by consumers to deduplicate
 * @param accountId      the settlement account affected; also the partition key
 * @param currencyCode   ISO-4217 code of the account
 * @param previousBuffer buffer before the change, as a decimal string
 * @param newBuffer      buffer after the change, as a decimal string
 * @param changeType     {@code ADJUSTMENT} (signed delta) or {@code ABSOLUTE_SET}
 * @param reason         free-text audit note, may be null
 * @param occurredAt     when the change was applied
 */
public record LiquidityBufferChangedEvent(
        String eventId,
        String accountId,
        String currencyCode,
        String previousBuffer,
        String newBuffer,
        String changeType,
        String reason,
        Instant occurredAt) {

    /** The event type name that travels in the outbox row and on the topic. */
    public static final String EVENT_TYPE = "LiquidityBufferChanged";

    /** The aggregate this event belongs to. */
    public static final String AGGREGATE_TYPE = "SettlementAccount";

    public static final String CHANGE_TYPE_ADJUSTMENT = "ADJUSTMENT";
    public static final String CHANGE_TYPE_ABSOLUTE_SET = "ABSOLUTE_SET";
}
