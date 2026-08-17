# Atlas Liquidity Platform — Build Roadmap

**Purpose:** a single, coherent codebase that lets you speak with first-hand
authority about every line of the Citi "Full Stack Engineer — Cash & Intraday
Liquidity Management" job description.

**Domain:** intraday cash and liquidity management. We track, in near real time,
how much cash a legal entity holds at each nostro/settlement account, in each
currency, in each jurisdiction — and we alert when a buffer is about to be
breached before a payment goes out.

Why this domain and not a to-do app: it is genuinely high-throughput, genuinely
event-driven, genuinely multi-region, and genuinely regulated. Every hard
engineering pattern in the JD falls out of the domain naturally instead of being
bolted on.

---

## The JD → layer map

| JD requirement | Layer(s) that prove it |
|---|---|
| Java, Spring Boot, microservices, APIs | L1, L2, L3 |
| Event-driven with Kafka / IBM MQ | L4, L5 |
| Oracle + MongoDB | L2, L6 |
| High throughput, business-critical | L5, L7 |
| ReactJS front end | L9 |
| Kubernetes, Docker, OpenShift, public cloud | L10 |
| Multi-region HA, active-active/passive, DR, data residency | L11 |
| Security, audit-ready, risk/compliance partnership | L8, L11 |
| CI/CD, TDD, JUnit, Mockito, Cucumber, JMeter, Jenkins, GH Actions | L2 onward, consolidated in L12 |
| AI-first engineering, Copilot/Claude Code | L13 |
| Payments / liquidity domain knowledge | whole build, narrated in L14 |
| Agile at enterprise scale, exec communication | L14 |

---

## The layers

Each layer is: **build → test → commit → push → explain**. You do not move to
layer N+1 until layer N is green on GitHub Actions and you can explain the
"why" out loud.

### L0 — Foundations
Local toolchain, VS Code, Git identity, GitHub repo, branch protection,
conventional commits. No application code.

### L1 — Monorepo skeleton *(you are here)*
Maven multi-module reactor, parent POM with dependency management, a shared
`liquidity-common` library, the first Spring Boot service, Actuator health,
first unit test, first push.
*Interview themes:* dependency management vs dependencies, why a monorepo,
BOM discipline, semantic versioning.

### L2 — Domain model + persistence
Entities, value objects, aggregates. Postgres locally standing in for Oracle,
with Oracle-specific concerns called out (sequences, `MERGE`, partitioning,
optimistic locking). Flyway migrations. Repository layer. Testcontainers.
*Interview themes:* JPA N+1, lazy loading, transaction boundaries, isolation
levels, why not `@Transactional` on everything.

### L3 — REST APIs
Controllers, DTO mapping, Bean Validation, RFC 7807 problem details, global
exception handling, OpenAPI, API versioning, idempotency keys, pagination.
*Interview themes:* idempotency in payments, PUT vs POST, versioning strategy,
backwards compatibility.

### L4 — Event-driven backbone
Kafka via Docker Compose. Producers, consumers, consumer groups, partitioning
by account key, transactional outbox, schema registry / Avro, dead letter
topics, retry topics, IBM MQ bridge for legacy payment feeds.
*Interview themes:* at-least-once vs exactly-once, ordering guarantees,
rebalancing, poison messages, outbox vs dual write.

### L5 — The position engine
The heart of the platform: event-sourced intraday cash positions, projections,
snapshotting, replay, high-throughput concurrent updates, time-bucketed
liquidity ladders.
*Interview themes:* event sourcing vs CRUD, CQRS, idempotent projections,
handling out-of-order events, hot-partition mitigation.

### L6 — MongoDB rules & alerting
Alert rules, thresholds, buffer policies in MongoDB. Aggregation pipelines,
change streams, TTL indexes. When document stores beat relational and when
they do not.
*Interview themes:* schema design in Mongo, indexes, read/write concerns,
polyglot persistence justification.

### L7 — Resilience & performance
Resilience4j (circuit breaker, bulkhead, retry, rate limiter), Redis caching,
backpressure, connection pool tuning, virtual threads, JMeter load profile,
p99 latency budget.
*Interview themes:* cascading failure, thundering herd, cache invalidation,
what actually breaks at 10k TPS.

### L8 — Security & audit
Spring Security, OAuth2 resource server, JWT, method-level authorization,
mTLS between services, secrets handling, immutable audit trail, PII handling,
OWASP top 10 for this platform.
*Interview themes:* authn vs authz, token validation, why audit logs are
append-only, four-eyes approval.

### L9 — ReactJS front end
Vite + React + TypeScript dashboard: live position grid, liquidity ladder
chart, alert console, SSE/WebSocket streaming, React Query, error boundaries,
component tests.
*Interview themes:* state management, re-render cost on streaming data,
virtualized grids, CSR vs SSR.

### L10 — Containers & Kubernetes
Multi-stage Dockerfiles, distroless images, Kubernetes manifests, Helm chart,
probes, HPA, resource requests/limits, ConfigMaps/Secrets, OpenShift
specifics (SCCs, Routes, ImageStreams).
*Interview themes:* liveness vs readiness, graceful shutdown, rolling vs
blue-green, why your pod got OOMKilled.

### L11 — Multi-region, DR, data residency
Active-active and active-passive topologies, Kafka MirrorMaker 2, regional
data partitioning for residency, RPO/RTO targets, failover runbook, chaos
drill, jurisdictional config.
*Interview themes:* CAP in practice, split brain, conflict resolution,
"how do you keep EU data in the EU", DR test evidence for auditors.

### L12 — CI/CD & quality gates
GitHub Actions pipeline (build, test, coverage, SAST, container scan, publish),
Jenkinsfile equivalent, Cucumber BDD suite, JMeter in CI, JaCoCo thresholds,
SonarQube-style gates, trunk-based delivery.
*Interview themes:* what blocks a release, flaky test policy, deployment
frequency vs change failure rate.

### L13 — AI-first engineering
LLM inside the platform (natural-language position queries, anomaly
explanation, alert triage) plus LLM in the workflow (Copilot / Claude Code for
tests, reviews, migrations). Prompt safety, evaluation, guardrails, cost.
*Interview themes:* where AI genuinely helps, hallucination controls in a
regulated system, how you raised the team's quality bar with it.

### L14 — Interview assembly
The 3-minute architecture narrative, a whiteboard-ready diagram you can draw
from memory, STAR stories per layer, trade-off flashcards, and a rehearsed
answer bank of ~80 likely questions.

---

## Working agreement

- One layer at a time. Do not skip ahead — the interview value is in the
  sequence, not the finished repo.
- Every layer ends with a green build and a push to `main` via a PR.
- When something breaks, paste the exact error. Debugging your own stack traces
  is the single highest-value interview preparation in this whole plan.
- At the end of each layer, close your laptop and explain the layer out loud in
  under 90 seconds. If you cannot, you are not done with it.
