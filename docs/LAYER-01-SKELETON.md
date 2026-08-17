# Layer 1 — Monorepo skeleton

**Goal:** a Maven multi-module reactor that builds, a shared library, one real
Spring Boot service with a real API and real tests, and all of it on GitHub.

**Not in this layer:** no database, no Kafka, no Docker, no React. Those are
Layers 2, 4, 10 and 9. Resist the urge to jump ahead — the value is the
sequence.

---

## 1. What is in the box

```
atlas-liquidity-platform/
├── pom.xml                        aggregator + parent, BOM import
├── .gitignore  .editorconfig      hygiene
├── .vscode/                       recommended extensions + Java settings
├── docs/
│   ├── ROADMAP.md                 the 14-layer plan
│   ├── LAYER-00-SETUP.md          toolchain and GitHub setup
│   ├── LAYER-01-SKELETON.md       this file
│   ├── api-examples.http          clickable requests in VS Code
│   └── adr/                       architecture decision records
├── liquidity-common/              shared library (plain JAR)
│   └── money/Money.java           BigDecimal money type
│   └── web/CorrelationIdFilter    request tracing
└── reference-data-service/        Spring Boot app, port 8081
    ├── domain/                    records, enum, repository port
    ├── api/                       controller, DTO, RFC 7807 errors
    └── config/                    explicit bean assembly
```

Every non-obvious class carries a comment explaining *why* it is the way it is.
Read those comments — they are the interview preparation, not decoration.

---

## 2. Run it

From the repository root:

```bash
mvn clean verify
```

You should see two modules build and **26 tests pass** (14 in `liquidity-common`,
12 in `reference-data-service`). Then:

```bash
mvn -pl reference-data-service spring-boot:run
```

`-pl` means "project list" — build/run only that module. Add `-am` ("also make")
when you want its dependencies rebuilt first:

```bash
mvn -pl reference-data-service -am clean install
```

Learn these two flags now. On a monorepo they are the difference between a
12-second build and a 4-minute one, and knowing them is a small but real signal
of monorepo experience.

Then exercise the API — open `docs/api-examples.http` in VS Code and click
*Send Request*, or:

```bash
curl -s http://localhost:8081/api/v1/accounts | jq
curl -s http://localhost:8081/api/v1/accounts/ACC-NOPE | jq
curl -si http://localhost:8081/api/v1/accounts -H 'X-Correlation-Id: test-123' | grep -i correlation
```

Watch the service console while you do the last one. The correlation ID appears
in every log line for that request. That is the mechanism that makes a
distributed system debuggable, and you now have it from day one.

---

## 3. The five things to actually understand here

**1. Aggregator vs parent, and BOM import vs parent inheritance.**
Read `pom.xml` and `docs/adr/0002`. Be able to say: Maven gives you one parent
slot; enterprises usually want it for their corporate POM; importing
`spring-boot-dependencies` gets identical version alignment without consuming
it. This is asked more often than you would expect.

**2. Why `Money` is not a `double`, and not a `record` either.**
`0.1 + 0.2 != 0.3` in binary floating point. On a platform aggregating millions
of cash movements, that error does not stay small. And it is not a record
because a record's canonical constructor would let callers bypass scale
normalisation, and the generated `equals` would say `10.00 != 10.000`.

**3. Why the repository interface is in `domain/` and the implementation is not.**
The domain declares what it needs; the outside world supplies it. In Layer 2 we
replace the in-memory implementation with JPA over a real database and nothing
above that interface changes. That is dependency inversion, and it is also the
honest answer to "how do you keep services testable".

**4. Why the DTO exists even though it looks like the domain record.**
The domain changes for business reasons; the API contract changes for consumer
reasons. Serialising the domain object directly welds the two together, and a
domain field rename silently breaks every client.

**5. Why there are three kinds of test.**
`MoneyTest` is a pure unit test — no Spring, milliseconds.
`SettlementAccountControllerTest` is a **slice** test — `@WebMvcTest` boots only
the MVC layer with a mocked repository. `ReferenceDataApplicationTest` is a full
integration test on a random port. Many fast, few slow. Being able to justify
that pyramid matters more than reciting it.

---

## 4. Deliberate imperfections (we fix these later)

I left three things wrong on purpose. Finding them yourself is worth more than
me listing them, but here they are so you are not misled:

**a. The catch-all `@ExceptionHandler(Exception.class)` is too greedy.** It will
swallow Spring's own MVC exceptions — a request to an unmapped path, or a
malformed request body — and turn a correct 404 or 400 into a 500. **Layer 3**
fixes this by extending `ResponseEntityExceptionHandler` so framework exceptions
keep their proper status codes.

**b. Filtering by both `currency` and `jurisdiction` does two passes.** The
controller calls the repository, then filters the result in Java. Harmless over
six seed accounts, indefensible over a real table. **Layer 2** pushes filtering
into the query.

**c. `liquidity-common` is registered by hand rather than auto-configured.** The
polished version publishes a Spring Boot auto-configuration so consumers get the
filter by adding the dependency. Explicit is easier to follow while the platform
is small; the trade-off is worth being able to discuss.

Being able to say "yes, I know, and here is when I'd fix it and why I didn't
yet" is a much stronger position in a code review than pretending the code is
perfect.

---

## 5. Exercises — do these before Layer 2

These are the layer. Reading my code teaches you much less than breaking it.

1. **Add a currency to `Money` you cannot construct.** Try `Money.of("XAU",
   "1.00")` in a test. Understand why gold is rejected rather than guessed at.

2. **Add `INR` to `Jurisdiction`** with residency region `in-west` — already
   there as `IN`. Instead: add `AU` (Australia, `apac-south`), add a seed
   account for it, and watch `ReferenceDataApplicationTest` still pass. Then
   make it fail by asserting on the new region, and fix it.

3. **Break the correlation filter deliberately.** Remove the `finally` block in
   `CorrelationIdFilter` and run the integration test twice in a loop. Reason
   about why thread pooling makes the leak intermittent rather than obvious.
   Then put it back.

4. **Add `GET /api/v1/accounts/{id}/summary`** returning account ID, currency
   and residency region only. Write the slice test first, watch it fail, then
   make it pass. That is TDD, and the JD asks for it explicitly.

5. **Add a `Money.multiply(BigDecimal factor)`** method with a test that proves
   the rounding is HALF_EVEN. Think about whether the result should round at
   each step or only at the end — this is a real question in interest accrual.

6. **Run `mvn clean verify` and open** `target/site/jacoco/index.html` in each
   module. Find the lowest-covered class. Decide whether that coverage gap
   actually matters — "we cover behaviour, not lines" is the right instinct, and
   Layer 12 makes it a build gate.

---

## 6. Interview questions this layer prepares you for

Answer these out loud, without notes, before moving on.

1. What is the difference between `<dependencies>` and `<dependencyManagement>`?
2. What does `<scope>import</scope>` do, and why can it only be used with
   `<type>pom</type>`?
3. Why does `spring-boot-maven-plugin` on a shared library break its consumers?
4. Why is `BigDecimal.equals` different from `BigDecimal.compareTo`, and why
   does that matter for a money type?
5. What is banker's rounding and why do financial systems prefer it?
6. `@SpringBootApplication` — what three annotations is it composed of, and what
   determines what gets component-scanned?
7. Constructor injection vs field injection — give two concrete advantages of
   constructor injection.
8. What does `@WebMvcTest` start, and what does it deliberately not start?
9. What is RFC 7807, and why standardise error responses across an estate?
10. Why must the MDC be cleared in a `finally` block?
11. What is the difference between liveness and readiness, and what happens if
    you point both at the same endpoint?
12. Why does `server.shutdown: graceful` matter during a rolling deployment?

If you stumble on any of these, that is exactly the useful signal — send me the
number and we will go deeper before Layer 2.

---

## 7. Commit and push

```bash
git add .
git commit -m "feat(platform): scaffold maven reactor, liquidity-common and reference-data-service

- multi-module reactor with imported spring boot BOM (ADR 0002)
- Money value type with BigDecimal and banker's rounding
- correlation id filter for distributed tracing
- settlement account read API with RFC 7807 error handling
- unit, slice and integration test layers"

git push -u origin main
```

From Layer 2 onward, work on a branch and merge via pull request:

```bash
git checkout -b feat/L02-domain-model
```

---

## 8. Troubleshooting

**`Non-resolvable import POM: spring-boot-dependencies:3.5.6`**
Your Maven cannot reach Maven Central, or that patch version does not exist on
your mirror. Check network/proxy first. To use a different version, change
`<spring-boot.version>` in the root `pom.xml` — any 3.5.x works, and 3.4.x works
too if you also change `@MockitoBean` back to `@MockBean` in
`SettlementAccountControllerTest`.

**VS Code says it cannot find a class that Maven compiles fine**
The Java language server's model is stale. Command palette →
*Java: Clean Java Language Server Workspace* → Restart. The
`java.configuration.updateBuildConfiguration: automatic` setting in
`.vscode/settings.json` prevents most recurrences.

**`Port 8081 is already in use`**
Something is still running from a previous attempt.
`lsof -ti:8081 | xargs kill` on macOS/Linux, or
`netstat -ano | findstr :8081` then `taskkill /PID <pid> /F` on Windows.

**Tests pass individually but fail together**
Almost always shared mutable state. In this layer there should be none — if you
see it, tell me, because that is a genuinely interesting bug to walk through.

**`release version 21 not supported`**
Your JDK is older than 21. Either install JDK 21 or change `<java.version>` to
`17` in the root POM — everything here works on 17.

---

## Definition of done

- [ ] `mvn clean verify` is green
- [ ] the service starts and `/api/v1/accounts` returns six accounts
- [ ] a 404 returns JSON problem detail, not an HTML page
- [ ] your own `X-Correlation-Id` comes back in the response header
- [ ] at least three of the six exercises are done and committed
- [ ] you can answer 10 of the 12 questions out loud
- [ ] it is pushed to GitHub

Then tell me you are ready for Layer 2 — domain model and persistence.
