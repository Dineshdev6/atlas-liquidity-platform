# ADR 0001 — Record architecture decisions

**Status:** Accepted
**Date:** Layer 1

## Context

This platform will accumulate dozens of architectural choices: event sourcing
over CRUD, Kafka over IBM MQ, active-active over active-passive. Six months
later, nobody — including the person who made the choice — remembers what the
alternatives were or what constraint ruled them out. The code shows *what* was
decided and never *why*.

In a regulated environment this is worse than an inconvenience. When an auditor
asks why customer data for the EU jurisdiction lives where it lives, "that's how
we built it" is not an answer.

## Decision

We record every significant architectural decision as a short Markdown file in
`docs/adr/`, numbered sequentially, following Michael Nygard's ADR format:
**Context → Decision → Consequences**.

A decision is significant if it is expensive to reverse, or if a reasonable
engineer would ask "why did you do it that way?".

ADRs are immutable once accepted. A decision that is later reversed gets a new
ADR that supersedes the old one; the old one is marked `Superseded by NNNN` and
kept. The history is the value.

## Consequences

**Good.** Onboarding gets much cheaper — a new engineer reads the ADRs and
understands the shape of the system. Design reviews have something concrete to
argue with. Audit and compliance conversations have written evidence of
deliberate design rather than accident.

**Costly.** It is discipline. An ADR written a week after the decision is
already partly fiction, so they have to be written at the time.

**Interview relevance.** "How do you document architecture?" is a standard
senior-engineer question, and "we keep ADRs in the repo next to the code, so the
documentation ages with the system rather than rotting in a wiki" is a strong,
specific answer. Being able to point at an actual example is stronger still.
