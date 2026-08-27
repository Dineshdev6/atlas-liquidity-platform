# ADR 0008 — Idempotent consumers, and a dead letter topic for poison messages

**Status:** Accepted
**Date:** Layer 4, part 2

## Context

Kafka delivers at least once. ADR 0007 made that worse on purpose: the outbox
relay publishes, waits for the acknowledgement, and only then commits the row as
published — so a crash in that gap republishes the event. Duplicates are not an
edge case here, they are a documented contract.

A consumer that applies an event twice corrupts its own state. On a liquidity
projection that means a balance that no longer matches the source of truth, with
nothing anywhere to indicate it.

Separately, a consumer that keeps failing on the same message stops making
progress. Kafka does not skip a record because you could not handle it; it
redelivers it. The offset never advances, the partition never moves, and every
well-formed message behind that one waits indefinitely. The only symptom is
consumer lag that will not come down.

And events can arrive out of order. Kafka guarantees ordering only *within a
partition*, and the producer keys by account so that holds in the normal case —
but a retry, a dead-letter replay, or a change to the partition key all break it.

## Decision

**The consumer records every event id it has handled in a `processed_event`
table, whose primary key is the event id.** A second delivery is detected and
discarded.

**Applying the event and recording it as processed happen in one transaction.**

**The consumer ignores events older than the state it already holds**, comparing
the event's own `occurredAt` against the projection's `last_event_at`. A
discarded event is still recorded as processed, marked `applied = false`.

**Failures are classified.** Transient failures are retried twice, one second
apart. `PoisonMessageException` is never retried. After retries are exhausted the
record is published to `<topic>.DLT`.

**Retries block.** We accept a stalled partition during retry rather than
reordering the stream.

**`position-service` owns its own database.** No shared schema with the producer,
and no synchronous call between the two.

## Consequences

**Double application becomes impossible rather than unlikely.** The primary key
is the guarantee; the `existsById` check in the service is only an optimisation
to avoid throwing on the common path. This is the same distinction ADR 0006 drew
about idempotency keys, and it matters for the same reason: an application-level
check has a window between the check and the insert, and a consumer rebalance is
precisely when concurrent duplicates arrive.

**One transaction, for the same reason as the outbox.** If the projection
committed and the register did not, redelivery would apply the change twice. If
the register committed and the projection did not, the change would be lost and
never retried. Both or neither.

**A wall-clock timestamp is a weak ordering key, and we know it.** Two events can
share a millisecond, and clocks drift. The right answer is a monotonic sequence
number per aggregate, issued by the producer — the outbox's generated id is
already exactly that and simply is not carried in the payload yet. Deliberate
debt, worth naming before an interviewer finds it.

**Blocking retries preserve order and cost throughput.** While a record is being
retried, its partition makes no progress. Two retries a second apart is a
three-second ceiling, chosen so the stall stays smaller than the problem. The
alternative — Spring Kafka's `@RetryableTopic`, which forwards the failure to a
separate retry topic and moves on — keeps the partition flowing but delivers the
retried message after messages that came later, losing ordering. Neither is
correct in general; the choice depends on whether ordering or throughput is the
requirement. Here ordering is.

**The dead letter topic is an operational commitment, not a dustbin.** A message
on `.DLT` has not been processed and never will be unless someone acts. That
means it needs monitoring and an owner, and a runbook for replaying a corrected
message. A DLT nobody watches is a silent data-loss mechanism with good branding.
Layer 7 adds the alert; the replay procedure is documented in the layer notes.

**Not-retryable classification is where most of the value is.** Retrying a
malformed payload wastes three seconds of a partition's life to reach a
conclusion already available. Retrying a database blip is exactly right. Systems
that treat all failures identically get one of those two badly wrong, and which
one depends on which default they picked.

**Database-per-service is what makes this a microservice.** Two modules that
share a schema must be deployed together, and a column change breaks both — a
distributed monolith, with every operational cost of a distributed system and
none of the independence. Because `position-service` learns everything from the
topic, `reference-data-service` can be stopped entirely and reads keep working
from the projection. That is demonstrated in the walkthrough rather than claimed.

**The projection is disposable, and that is a feature.** It holds nothing that did
not arrive on the topic, so if it is ever wrong you delete it, reset the consumer
group, and rebuild from history. `PositionUpdateServiceIT.projectionIsRebuildable`
pins that property down. A projection that cannot be rebuilt is a second source
of truth wearing a disguise.

**We still share a jar for the event definition.** `liquidity-common` holds
`LiquidityBufferChangedEvent`, so a breaking change needs a coordinated release —
the coupling event-driven architecture is meant to remove. Acceptable in a
two-service monorepo, and part 3 replaces it with a schema registry where the
contract lives independently of anyone's code.

**Interview relevance.** "Kafka is at-least-once — how do you get exactly-once
processing?" is a question with a trap in it. The strong answer is that
exactly-once *delivery* is not achievable across a broker and a database, so you
stop trying and make *processing* idempotent instead: a natural business key
(here the event id) recorded in the same transaction as the effect. The
follow-ups are what separates candidates: why the database constraint rather than
an application check, why one transaction, what happens on a rebalance, what you
do with a message you can never process, and why retrying everything forever is
worse than dead-lettering. Every one of those has an answer in this codebase that
can be pointed at.
