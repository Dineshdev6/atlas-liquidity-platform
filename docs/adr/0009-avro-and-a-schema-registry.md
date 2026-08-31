# ADR 0009 — Avro with a schema registry, on a new topic version

**Status:** Accepted
**Date:** Layer 4, part 3a

## Context

`LiquidityBufferChangedEvent` lives in `liquidity-common` and both services
compile against it. That works, and it misrepresents how the two services relate:
adding a field means rebuilding and redeploying both, together, in the right
order — the coordination event-driven architecture exists to remove. Two services
that must ship together are one service with extra network calls.

JSON offers nothing to prevent a breaking change either. Rename `newBuffer` and
the producer, the broker and the consumer are all perfectly happy; the consumer
simply reads `null` from then on. **The contract is enforced by nobody.**

JSON also repeats every field name in every message. On a high-volume payment feed
that is a large, permanent overhead in storage and network.

## Decision

**Events are published as Avro, with schemas held in Confluent Schema Registry.**

**The `.avsc` file is the source of truth**; the Java class is generated at build
time into `target/generated-sources` and is neither edited nor committed.

**Registry compatibility is `BACKWARD`** — a new schema must be able to read data
written by the old one.

**The Avro conversion happens in the outbox relay, at publish time**, not in
`OutboxWriter` when the event is recorded.

**A new topic, `…buffer-changed.v2`.** v1 keeps its JSON history untouched.

**Amounts stay decimal strings; `changeType` stays a string rather than an enum.**

## Consequences

**Compatibility becomes enforceable rather than hoped for.** The registry refuses
to register an incompatible schema, so the breakage happens at deploy time on a
developer's machine instead of at 3am in production. Add a field with a default
and existing consumers keep working untouched, because Avro resolves the writer's
schema against the reader's.

**The shared jar stops being the contract.** `liquidity-common` still carries the
generated class for convenience, and that is now a build-time convenience rather
than the thing that keeps the two services compatible. A consumer that never
upgrades the jar still reads messages written by a newer producer.

**Messages get smaller.** Field names live in the registry; the wire carries one
magic byte, a four-byte schema id, and the values.

**You can no longer read the topic with `kafka-console-consumer`.** It prints
bytes. `kafka-avro-console-consumer` asks the registry and prints JSON. This
surprises people and is worth knowing before an incident, not during one.

**A new runtime dependency, with a mild failure mode.** Producers need the registry
to serialise; consumers need it once per schema id, then cache. A registry outage
therefore stops *new* schema versions being read rather than stopping consumption
dead — better than it first sounds, and still a dependency to monitor.

**Converting in the relay keeps the registry off the business write path.** Calling
it inside the transaction that writes the outbox row would mean a registry outage
failing payments — a new external dependency on the write path, which is exactly
what the outbox exists to avoid. The cost, named honestly: the payload is
serialised twice, and the outbox holds the domain shape rather than the exact
bytes sent. The compensation is that the outbox stays human-readable, which is
worth a great deal when something is stuck.

**A version in the topic name and a registry are for different jobs.** The registry
governs *compatible* evolution within one topic. A topic version is for changes it
would refuse — like moving from JSON to Avro. Having both is not redundancy; using
only one is the mistake.

**Strings for money and for `changeType`, deliberately.** Avro can express decimals
as bytes with a logical type, which forces every consumer to know the scale; a
decimal string is unambiguous and readable in a dead-lettered message. Avro enums
are stricter than strings and make adding a symbol a compatibility problem,
because a consumer on the old schema has no symbol to map a new one onto.

**Confluent's artefacts are not on Maven Central.** The build needs Confluent's
repository declared. In a bank that means an internal mirror, and "the Confluent
repo has not been proxied yet" is a real first-week ticket.

**Interview relevance.** "How do you evolve an event schema without breaking
consumers?" is close to guaranteed in any event-driven interview. The shallow
answer is "we use Avro". The strong answer names the compatibility mode and what
it implies for deployment ORDER — `BACKWARD` means upgrade consumers first,
`FORWARD` means producers first — explains that a field which may be absent needs
a union with null *and* a default, distinguishes what the registry can enforce
from what needs a new topic, and concedes the operational cost of another service
in the path.
