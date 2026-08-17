# ADR 0003 — Flyway owns the schema; Hibernate only validates it

**Status:** Accepted
**Date:** Layer 2

## Context

Something has to create and evolve the database schema. There are three
common approaches.

**1. `spring.jpa.hibernate.ddl-auto: update`.** Hibernate compares its entity
mappings to the live schema at startup and issues whatever DDL it thinks will
reconcile them. Zero effort, and it is what most tutorials show.

**2. Hand-run SQL scripts.** A DBA or a developer applies changes manually,
tracked in a wiki, a ticket, or somebody's memory.

**3. Versioned migrations under version control**, applied automatically and
recorded in the database — Flyway or Liquibase.

## Decision

**Flyway owns the schema.** Every change is a numbered, immutable SQL file in
`src/main/resources/db/migration`, applied automatically at startup and recorded
with a checksum in `flyway_schema_history`.

**Hibernate is set to `ddl-auto: validate`** — it checks its mappings against
the real schema on boot and refuses to start if they disagree. It never writes
DDL.

## Consequences

**Why not `ddl-auto: update`.** It is genuinely dangerous, in ways that are not
obvious until they bite:

- It only ever adds. Rename a field and you get a new column beside the old one,
  with all the data still in the old one. Delete a field and the column stays
  forever.
- It cannot express a data migration. "Split `name` into `first_name` and
  `last_name`" is a schema change *and* an UPDATE statement; Hibernate only
  knows about the first half.
- It is not reviewable. Nobody can look at a pull request and see what the
  schema change is, because the schema change does not exist until runtime.
- It is not repeatable. The DDL depends on what the schema happened to look like
  when the app booted, so dev, staging and production can silently diverge.
- No auditor will accept it. "What changed in the database, when, and who
  approved it" has no answer.

**What Flyway gives us.** Schema changes arrive through the same pull request
review as code. Every environment applies the identical sequence in the
identical order. The history table is an audit trail with timestamps. And a
missing migration becomes a startup failure on deploy, thanks to `validate`,
rather than a `column does not exist` error under load two hours later.

**The cost, and it is a real one.** Migrations are immutable. Once V1 has run
anywhere, editing it changes its checksum and every subsequent Flyway run fails.
Fixing a mistake means writing V3, not correcting V1. That discipline is exactly
the point, and it catches people out at least once.

**Why Flyway and not Liquibase.** Flyway migrations are plain SQL, which means
anyone who knows the database can read and review them, and you can use
database-specific features without fighting an abstraction. Liquibase's XML/YAML
changelogs are database-agnostic, which is worth real money if you genuinely
need to support several databases from one codebase — and a layer of indirection
you pay for daily if you do not. We target Oracle in production and Postgres
locally, and the small number of dialect differences (ADR 0004) are better
handled explicitly than hidden.

**Interview relevance.** "How do you manage database schema changes?" is asked
in almost every backend interview above junior level, and `ddl-auto: update` is
the answer that ends the conversation. The strong version of this answer names
the failure modes above, and mentions the harder problem underneath: a migration
must be backwards-compatible with the *currently running* version of the
application, because during a rolling deploy both versions are live at once.
That is why you add a column before you write to it, and drop one an entire
release after you stop reading it — the expand/contract pattern. Layer 12 makes
this concrete.
