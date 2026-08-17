# ADR 0004 — Develop against Postgres, target Oracle

**Status:** Accepted
**Date:** Layer 2

## Context

The platform's production database is Oracle — it is what the job description
names and what most of Citi's transaction estate runs on. But Oracle is
impractical for local development and CI: the images are licensed, large, slow
to start, and cannot be freely pulled on a build agent.

## Decision

Develop and test against **Postgres 16**, in Docker locally and via
Testcontainers in the build. Keep the SQL in migrations close to the ANSI
standard, and record the places where Oracle genuinely differs so they are
handled deliberately rather than discovered late.

## Consequences

**What transfers unchanged**, which is most of it: transactions and isolation
levels, ACID semantics, indexing strategy and query planning, optimistic and
pessimistic locking, connection pooling, the N+1 problem, JPA mapping,
constraint design, and every piece of Flyway discipline.

**What does not transfer** — the list to actually know:

| Concern | Postgres | Oracle |
|---|---|---|
| Variable-length text | `VARCHAR(n)` | `VARCHAR2(n CHAR)` |
| Empty string | distinct from `NULL` | **treated as `NULL`** |
| Generated keys | `GENERATED ... AS IDENTITY` or `SERIAL` | sequences, or identity from 12c |
| Current timestamp | `now()` | `SYSTIMESTAMP` |
| Upsert | `INSERT ... ON CONFLICT` | `MERGE` |
| Row limiting | `LIMIT n OFFSET m` | `OFFSET m ROWS FETCH NEXT n ROWS ONLY` |
| Case sensitivity | folds unquoted identifiers to **lower** | folds to **UPPER** |
| Default isolation | Read Committed | Read Committed, but with different reader/writer behaviour |
| Hibernate dialect | `PostgreSQLDialect` | `OracleDialect` |

The empty-string one is the nastiest: code that relies on `'' <> NULL` behaves
correctly on Postgres and incorrectly on Oracle, silently, with no error.

**Managing the risk.** Migrations stay close to standard SQL. Anything genuinely
dialect-specific gets a comment naming the Oracle equivalent — see `V1`. In a
real deployment the pipeline would run the suite a second time against an Oracle
instance before release; the schema-validation step at startup (`ddl-auto:
validate`, ADR 0003) is the safety net that catches a mapping which is fine on
one and wrong on the other.

**Interview relevance.** This is a genuinely good answer to "you have not used
Oracle in production, is that a problem?". *"I developed against Postgres and
targeted Oracle. Ninety percent is identical — transactions, locking, indexing,
JPA. The differences I had to be careful about were empty string versus NULL,
identity versus sequences, MERGE versus ON CONFLICT, and identifier case
folding. We caught mapping drift with Hibernate schema validation at startup."*
That is specific, honest, and demonstrates you have thought about it — which is
much stronger than either bluffing familiarity or apologising for the gap.
