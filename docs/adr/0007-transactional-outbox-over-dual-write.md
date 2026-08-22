# ADR 0007 — Transactional outbox rather than publishing directly

**Status:** Accepted
**Date:** Layer 4

## Context

When an account's liquidity buffer changes, two things must happen: the database
records the new balance, and the rest of the platform is told. The position
engine, the alerting service and the regulatory feed all need to know.

The obvious implementation is two statements:

```java
accounts.updateLiquidityBuffer(accountId, newBuffer);   // database
kafka.send("buffer-changed", event);                    // network
```

This is a **dual write**, and it has no correct ordering.

Send first, then commit: the send succeeds, the transaction rolls back, and the
platform now believes in a change that never happened. Downstream balances are
wrong and nothing will ever correct them, because there is no second event
saying "actually, no".

Commit first, then send: the balance changes, the broker is momentarily
unreachable — or the pod is evicted between the two lines — and nobody is told.
Every downstream view is now silently stale. This failure is worse than the
first because there is no error anywhere: the API returned 200, the database is
correct, and the divergence only surfaces during reconciliation, days later.

`@Transactional` does not help. Kafka is not enlisted in the database
transaction; a rollback cannot un-send a message.

## Decision

**Domain events are written to an `outbox_event` table in the same transaction
as the business change.** A separate relay polls that table and publishes to
Kafka.

**`OutboxWriter.record` is annotated `@Transactional(propagation = MANDATORY)`,**
so calling it outside a transaction throws immediately rather than quietly
starting its own.

**The relay sends first and marks the row published afterwards**, in that order.

**Events are keyed by account id**, so Kafka's per-partition ordering guarantee
gives per-account ordering.

**Delivery is at-least-once and this is a published contract, not a defect.**

## Consequences

**The database and the event stream cannot disagree.** One transaction, one
outcome. Either the buffer moved and the event exists, or neither happened.
There is no interleaving of failures that produces one without the other, and
`OutboxIT.rejectedAdjustmentLeavesNoEvent` pins it down.

**Delivery becomes at-least-once, unavoidably.** There is a gap between Kafka
acknowledging a send and our transaction committing the row as published. A
crash in that gap means the next run publishes the event again. Closing the gap
would require the send and the database commit to be one atomic act — which is
the dual-write problem again, one level down. So we do not pretend: consumers
must deduplicate on `eventId`, which is the Layer 3 idempotency mechanism
applied to a different transport. This is why `IdempotencyService` was written
generic.

**Send-then-mark, not mark-then-send.** The reverse order risks marking an event
published that never left the process, and a lost event is unrecoverable while a
duplicate one is merely annoying. When exactly-once is unavailable, choose the
failure mode you can recover from.

**A broker outage costs latency, not data.** Events accumulate in the table and
drain when Kafka returns. The service keeps accepting writes throughout, because
nothing on the request path talks to Kafka at all. That is a genuine operational
property worth stating plainly: Kafka being down does not take this service
down.

**Ordering is preserved by stopping, not skipping.** The relay reads oldest-first
and halts at the first failure rather than moving on. Publishing event 5 after
event 4 failed would deliver an account's changes out of order, and a consumer
applying the older one last would end up with the wrong balance.

**Polling, and what we gave up.** The alternative is change data capture:
Debezium tails the database's write-ahead log and publishes outbox inserts with
no application code involved. CDC has lower latency, adds no query load, and is
what a large bank is more likely to run. It also adds a distributed system to
operate, connector configuration, and a replication slot that will fill the
database's disk if it ever stops being consumed. At this scale, polling a small
partially-indexed table once a second is the cheaper correct answer. Revisit at
Layer 11, where the cross-region story changes the calculation.

**Hot partitions are the price of ordering.** Keying by account means one very
busy account maps to one partition, and one partition is consumed by exactly one
member of a consumer group — so that account cannot be scaled out. We accept it
because ordering per account is a correctness requirement and parallelism within
an account is not. If it ever bites, the escape is a composite key or a
per-account sequence number that lets consumers reorder, both of which cost more
than they are worth today.

**The table grows forever unless something prunes it.** `deletePublishedBefore`
exists and nothing calls it; the scheduled job lands in Layer 7 with the other
operational concerns. Named here so it is a deferred decision rather than an
oversight.

**Multiple instances will publish duplicates.** Every instance runs the
scheduled relay, so three replicas means three relays reading the same backlog.
At-least-once already required consumers to deduplicate, so this is survivable
rather than broken — but the proper fixes are `SELECT ... FOR UPDATE SKIP
LOCKED`, a distributed lock, or leader election. Layer 7.

**Interview relevance.** "How do you keep your database and your message broker
consistent?" is one of the highest-signal questions in event-driven interviews,
and the dual write is the trap it is testing for. A weak answer publishes inside
`@Transactional` and believes that is enough. A strong answer names the dual
write, explains why both orderings fail, describes the outbox, concedes that
delivery is at-least-once, and says what the consumer therefore has to do. The
follow-up is usually "why not two-phase commit?" — because XA across a database
and Kafka means a blocking protocol, a transaction coordinator to operate, and
recovery semantics nobody on the team will understand at 3am, in exchange for
solving a problem that idempotent consumers solve for free.
