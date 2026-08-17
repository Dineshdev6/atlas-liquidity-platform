# Atlas Liquidity Platform

A cash and intraday liquidity management platform: event-driven, multi-region,
cloud-native. It tracks in near real time how much cash each legal entity holds
at each settlement account, in each currency and jurisdiction, and raises alerts
before a liquidity buffer is breached.

Built layer by layer as a working study of enterprise Java architecture — see
[`docs/ROADMAP.md`](docs/ROADMAP.md) for the full plan.

**Current layer: 1 — monorepo skeleton.**

---

## Quick start

```bash
# build everything and run the tests
mvn clean verify

# run the reference data service
mvn -pl reference-data-service spring-boot:run
```

Then:

```bash
curl http://localhost:8081/api/v1/accounts | jq
curl http://localhost:8081/api/v1/accounts/ACC-EU-0001 | jq
curl http://localhost:8081/actuator/health
```

Or open [`docs/api-examples.http`](docs/api-examples.http) in VS Code and click
*Send Request* above any block (requires the REST Client extension).

---

## Modules

| Module | What it is | Notes |
|---|---|---|
| `liquidity-common` | Shared library: `Money`, error types, correlation-ID filter | Plain JAR, no Boot plugin |
| `reference-data-service` | Legal entities, settlement accounts, currencies, jurisdictions | Boot app, port 8081 |

More services arrive with later layers: position engine (L5), alerting (L6),
gateway and UI (L9).

---

## Architecture at Layer 1

```
                    ┌──────────────────────────┐
   HTTP :8081 ─────►│ reference-data-service   │
                    │                          │
                    │  api/     controllers,   │
                    │           DTOs, RFC 7807 │
                    │  domain/  records, ports │
                    │  config/  bean assembly  │
                    └───────────┬──────────────┘
                                │ depends on
                    ┌───────────▼──────────────┐
                    │ liquidity-common         │
                    │  Money (BigDecimal)      │
                    │  CorrelationIdFilter     │
                    └──────────────────────────┘
```

The domain package declares a `SettlementAccountRepository` **port**; Layer 1
satisfies it with an in-memory adapter, Layer 2 swaps in JPA over Oracle-style
SQL. Nothing above the port changes when that happens — which is the point.

---

## Conventions

- **Branches:** `feat/L02-domain-model`, `fix/L05-projection-ordering`
- **Commits:** [Conventional Commits](https://www.conventionalcommits.org) —
  `feat(position): add intraday liquidity ladder`
- **Decisions:** recorded as ADRs in [`docs/adr/`](docs/adr/)
- **Java:** 21. **Spring Boot:** 3.5.x, versions imported from the BOM in the
  parent POM.

---

## Documentation

| Document | Purpose |
|---|---|
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | The 14-layer build plan and how each layer maps to the role |
| [`docs/LAYER-00-SETUP.md`](docs/LAYER-00-SETUP.md) | Toolchain, VS Code, Git and GitHub setup |
| [`docs/LAYER-01-SKELETON.md`](docs/LAYER-01-SKELETON.md) | What Layer 1 built and why, plus the exercises |
| [`docs/adr/`](docs/adr/) | Architecture Decision Records |
