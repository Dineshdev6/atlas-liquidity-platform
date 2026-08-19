# ADR 0006 — Idempotency keys for non-idempotent operations

**Status:** Accepted
**Date:** Layer 3

## Context

A client sends "adjust this account's buffer by +5,000,000". The call times out.

The client cannot distinguish between three outcomes: the request never arrived, the
request arrived and succeeded but the response was lost, or the request arrived and
failed. From its side they look identical.

If it does not retry, the adjustment may never have been applied. If it does retry
and the first attempt landed, the adjustment is applied twice. On a liquidity
platform that is a reconciliation break, a regulatory incident, and a very bad week
for somebody.

There is no client-side solution. The server has to remember.

## Decision

**Non-idempotent operations require an `Idempotency-Key` header.** Currently that is
`POST /api/v1/accounts/{id}/liquidity-buffer-adjustments`.

**Naturally idempotent operations do not.** `PUT .../liquidity-buffer` sets an
absolute value; repeating it changes nothing, so a key would be ceremony.

**The key is the primary key** of an `idempotency_record` table.

**A SHA-256 fingerprint of the request is stored alongside it.** Reuse with a
different payload is rejected with **422**.

**The key record and the business work share one transaction.**

**Keys are honoured for 24 hours**, then eligible for deletion.

## Consequences

**Why the key is the primary key rather than an application-level check.** An
application "check whether the key exists, then insert if not" has a window between
the check and the insert. That window is not theoretical: a retry storm following a
timeout is precisely when concurrent duplicates arrive. Only the database can settle
uniqueness atomically. Two concurrent requests both attempt the insert, exactly one
succeeds, and the loser rolls back — including any work it had already done — and
receives **409** telling it to retry. On retry the winner has committed, so the
loser finds the completed record and receives the original response. End state:
applied once, reported consistently to both callers.

**Why one transaction is the whole correctness argument.** If the business work
committed and recording the key then failed, a retry would execute the work again —
exactly the bug. If the key were recorded and the work then failed, the key would be
burned and the client could never succeed with it, having achieved nothing. Both
halves commit or neither does. A test pins this down: a rejected adjustment leaves
the key reusable.

A useful side effect: because the read and the write inside the operation now share
one transaction, the read-modify-write race that Layers 2 and 3 documented does not
exist on this path.

**Why the fingerprint.** A key alone would let a client reuse a key with a different
payload and receive the earlier response. Concretely: "+5,000,000" under key K, then
"+50,000,000" under the same K by mistake. Honouring the key returns the first
response, the client sees success, and believes fifty million was applied. The money
is right and the client's picture of reality is permanently wrong — which is worse,
because nothing will ever correct it. 422 rather than 400, because the request is
syntactically perfect and semantically impossible.

**Why we hash an explicit string, not the request JSON.** Hashing JSON looks obvious
and is a trap: `{"a":1,"b":2}` and `{"b":2,"a":1}` are the same request with
different bytes, so reuse detection would fire on requests that are genuinely
identical. Canonical JSON is a hard problem — key order, number formatting,
whitespace, Unicode normalisation. Building a stable string from the fields that
matter sidesteps all of it.

**Why 24 hours.** Long enough for any realistic retry — a client backing off over
minutes, an operator re-running a failed batch the same day. Short enough that the
table does not grow without bound, which in an append-only table is a slow-motion
outage. The `expires_at` index makes the cleanup delete an index range scan.

**What we have not built yet.** The cleanup job itself (Layer 7, with the other
scheduled operational concerns). Persisting the response *headers* as well as the
body — we replay the body and status, which is enough here. And a per-client
namespace on the key: today two different clients choosing the same UUID would
collide, which is vanishingly unlikely with UUIDs and unacceptable if keys were
client-chosen strings. Layer 8 adds authenticated principals and the key becomes
`(client, key)`.

**Interview relevance.** This is the single most valuable thing in Layer 3 for a
payments role, and the depth of answer varies enormously between candidates. The
shallow answer is "we store the key and return the cached response". The strong
answer covers: why the database's unique index rather than an application check; why
the key record and the work must share a transaction, in both failure directions;
why a payload fingerprint is needed and why hashing raw JSON is wrong; the status
codes and *why* 409 and 422 rather than 400; and which operations do not need a key
at all because they are already idempotent. The natural follow-up is Layer 4's
version of the same problem: a message broker's at-least-once delivery means a Kafka
consumer faces exactly this, which is why `IdempotencyService` was written generic.
