# Layer 3, part 2 — OpenAPI and idempotency keys

**Goal:** a published, machine-readable API contract, and an operation that is safe
to retry.

**Prerequisite:** part 1 merged, Docker running, `docker compose up -d`.

**One new dependency:** springdoc. Everything else is Java.

---

## 1. What changed

```
NEW  db/migration/V3__create_idempotency_record.sql
NEW  idempotency/IdempotencyRecordEntity.java
NEW  idempotency/IdempotencyRecordJpaRepository.java
NEW  idempotency/IdempotencyExceptions.java          KeyReuse (422), KeyInFlight (409)
NEW  idempotency/IdempotencyService.java             the mechanism, generic
NEW  application/LiquidityBufferAdjustmentService.java   first application service
NEW  api/AdjustLiquidityBufferRequest.java
NEW  api/OpenApiConfig.java
NEW  test idempotency/LiquidityBufferAdjustmentIT.java
NEW  docs/adr/0006-idempotency-keys-for-non-idempotent-operations.md

CHG  reference-data-service/pom.xml                  + springdoc
CHG  api/SettlementAccountController.java            + POST adjustment, + OpenAPI annotations
CHG  api/GlobalExceptionHandler.java                 + 422 and 409 handlers
CHG  test SettlementAccountControllerTest.java       + 2 mocks, + 10 adjustment tests
```

### A defect worth keeping, because you found it by building

The first cut of this slice shipped without the updated
`SettlementAccountControllerTest`, and the build failed like this:

```
[ERROR] Tests run: 22, Failures: 0, Errors: 22, Skipped: 0
java.lang.IllegalStateException: ApplicationContext failure threshold (1) exceeded:
    skipping repeated attempt to load context for [WebMergedContextConfiguration@... ]
```

Twenty-two errors, one cause. The controller's constructor went from one dependency
to three; `@WebMvcTest` builds a web-layer-only context that does **not**
component-scan `@Service` beans, so the two new ones had to be supplied as
`@MockitoBean` by hand. They were not, so the context failed to build - once. Spring
then caches that failure rather than retrying a context it already knows is broken,
which is why every subsequent test in the class reports the *threshold* message
instead of the real one.

Two things to take from that:

1. **Only the first stack trace is real.** When a whole test class errors identically,
   `Get-Content reference-data-service\target\surefire-reports\*.txt` and read the
   first one - it names the actual `NoSuchBeanDefinitionException`. The other
   twenty-one are noise.
2. **A slice test's mock list is a second declaration of the controller's
   dependencies**, and nothing keeps the two in step. That is the cost of slicing;
   the benefit is a test that runs in a second without Docker. Worth being able to
   argue both sides.

### A second defect, and this one is a genuine interview question

With the slice green, two integration tests still failed:

```
[ERROR] LiquidityBufferAdjustmentIT.sameKeyIsAppliedOnce:120
expected: 200 OK but was: 409 CONFLICT
[ERROR] LiquidityBufferAdjustmentIT.manyRetriesStillApplyOnce:139
expected: 200 OK but was: 409 CONFLICT
```

The *first* call worked and the buffer moved correctly. The **retry** came back 409,
"a request with this key is already being processed" - for a request that had
finished a moment earlier.

**The cause.** Spring Data's `save()` has to choose between
`EntityManager.persist` (a plain INSERT) and `EntityManager.merge` (a SELECT, then
an INSERT or an UPDATE). By default it chooses by looking at the identifier:

```java
// SimpleJpaRepository, roughly
if (entityInformation.isNew(entity)) { em.persist(entity); return entity; }
else { return em.merge(entity); }
```

and `isNew()` means, by default, *the identifier is null*. That is right for
generated identifiers. Ours is **assigned** - the client chooses the key - so it is
never null, so `save()` took the `merge` branch.

And `merge` does not manage the object you hand it. It returns a **managed copy**
and leaves your instance detached. So this line:

```java
records.saveAndFlush(claim);   // returns a managed copy, which we threw away
...
claim.recordResponse(200, serialise(result));   // mutates a DETACHED object
```

wrote the response onto an object Hibernate was no longer watching. No exception, no
warning, no log line - just an UPDATE that never happened. The row was inserted with
`response_status` and `response_body` NULL and stayed that way. On the retry,
`findById` found the record, `isComplete()` said false, and the service correctly
concluded "someone claimed this key and never finished" - 409.

**Two changes fix it**, and both are worth having:

1. `IdempotencyRecordEntity implements Persistable<String>`, so the entity answers
   "am I new?" itself and `save()` calls `persist`. That also removes the pointless
   SELECT `merge` performs - which matters here for more than speed, because a claim
   that SELECTs before it INSERTs is quietly reintroducing the check-then-insert
   this whole design exists to avoid.
2. The service now uses the instance `saveAndFlush` **returns** rather than the one
   it passed in. With `persist` they are the same object; assigning the result
   anyway means the code survives anyone changing that.

**Why this is worth remembering.** "What does `save()` do differently for an entity
with an assigned identifier?" is a real senior-level JPA question, and the honest
answer includes the failure mode: silent data loss on any field you set after the
save. Notice also which test caught it. The slice test could not - `IdempotencyService`
is mocked there, so there is no persistence context to get wrong. Only an
integration test against a real database could find this, which is the clearest
argument for keeping them that this project has produced.

You can see the wreckage for yourself. Before the fix, the register looked like this:

```sql
SELECT idempotency_key, response_status, response_body FROM idempotency_record;
-- response_status and response_body NULL on every row
```

A sharper regression test than the two above is exercise 8.

---

## 2. Run it

```powershell
docker compose up -d
mvn clean verify
```

**If the build fails with `Could not find artifact org.springdoc:springdoc-openapi-starter-webmvc-ui:jar:2.8.5`**,
that version does not exist on your mirror. Pick a real one from
https://central.sonatype.com/artifact/org.springdoc/springdoc-openapi-starter-webmvc-ui/versions
and change `<springdoc.version>` in `reference-data-service/pom.xml`. Nothing else
depends on the number.

Then start the service:

```powershell
mvn clean install -DskipTests
mvn -pl reference-data-service spring-boot:run
```

**Open Swagger UI in your browser: http://localhost:8081/swagger-ui.html**

Every endpoint, every schema, every validation constraint, every status code — read
from the real mappings at startup. Click *Try it out* on the adjustment endpoint and
fire a real request. This is what you hand a consumer team instead of a conversation.

The machine-readable version is at http://localhost:8081/v3/api-docs — that is the
part that actually matters, because client SDKs, contract tests and gateway config
can be generated from it.

---

## 3. See the idempotency mechanism work

In a second terminal. Note the buffer starts at 15,000,000 for `ACC-GB-0001`.

```powershell
curl.exe -s http://localhost:8081/api/v1/accounts/ACC-GB-0001
```

**Apply an adjustment.** Generate a key once and hold onto it:

```powershell
$key = [guid]::NewGuid().ToString(); $key
```

```powershell
curl.exe -s -i -X POST "http://localhost:8081/api/v1/accounts/ACC-GB-0001/liquidity-buffer-adjustments" -H "Content-Type: application/json" -H "Idempotency-Key: $key" -d "{\"amount\":\"5000000.00\"}"
```

Buffer is now 20,000,000 and the response header says `Idempotency-Replayed: false`.

**Now send the exact same request again** — the retry a real client makes after a
timeout:

```powershell
curl.exe -s -i -X POST "http://localhost:8081/api/v1/accounts/ACC-GB-0001/liquidity-buffer-adjustments" -H "Content-Type: application/json" -H "Idempotency-Key: $key" -d "{\"amount\":\"5000000.00\"}"
```

`Idempotency-Replayed: true`, an identical body, and:

```powershell
curl.exe -s http://localhost:8081/api/v1/accounts/ACC-GB-0001
```

**Still 20,000,000.** Run the POST ten more times if you like. That is the whole
point of the layer. Without the key, each call would add another five million and
nothing would ever tell you.

**Now reuse the key with a different amount:**

```powershell
curl.exe -s -X POST "http://localhost:8081/api/v1/accounts/ACC-GB-0001/liquidity-buffer-adjustments" -H "Content-Type: application/json" -H "Idempotency-Key: $key" -d "{\"amount\":\"50000000.00\"}"
```

**422**, refusing outright. Consider the alternative: if we honoured the key, you
would receive the +5,000,000 response, see success, and believe fifty million had
been applied.

**Look at the register:**

```powershell
docker exec atlas-postgres psql -U atlas -d atlas_liquidity -c "SELECT idempotency_key, operation, left(request_fingerprint,12) AS fingerprint, response_status, expires_at FROM idempotency_record;"
```

Then put the account back:

```powershell
curl.exe -s -X PUT "http://localhost:8081/api/v1/accounts/ACC-GB-0001/liquidity-buffer" -H "Content-Type: application/json" -d "{\"amount\":\"15000000.00\"}"
```

Note that PUT needed no key at all — it is naturally idempotent, which is exactly why.

---

## 4. The seven things to actually understand

**1. Which operations need a key, and why.** `PUT` says "make it exactly this" —
repeating it changes nothing. `POST .../adjustments` says "add this much" — repeating
it is wrong. Only the second needs a key. Adding idempotency keys to everything is a
sign someone learned the pattern without learning the reason.

**2. The database's unique index is the mechanism, not application code.** "Check
whether the key exists, then insert" has a window between the check and the insert,
and a retry storm after a timeout is exactly when concurrent duplicates arrive. The
key *is* the primary key, so the database settles it atomically. This is the single
best discriminator between having read about idempotency and having implemented it.

**3. One transaction, in both failure directions.** If the work committed and the key
record failed, a retry re-executes — the original bug. If the key recorded and the
work failed, the key is burned and the client can never succeed. Both commit or
neither. There is a test named `failedAdjustmentDoesNotBurnTheKey` for precisely this.

**4. A key without a payload fingerprint is a trap.** Reuse with a different payload
must be an error, not a replay, or the client ends up permanently believing something
false. And hash an explicitly built string, not the request JSON — `{"a":1,"b":2}`
and `{"b":2,"a":1}` are the same request with different bytes, and canonical JSON is
a genuinely hard problem.

**5. 409 and 422 are not decoration.** 409 means "in flight, retry and you will get
the original answer". 422 means "syntactically fine, semantically impossible — use a
new key". Returning 400 for both throws away information the client needs to behave
correctly.

**6. `@Transactional(readOnly = true)` on the repository is ignored here.** With the
default `REQUIRED` propagation, the adapter's methods *join* the service's read-write
transaction rather than starting their own, and read-only is a property of a
transaction, not of a method. "I marked it read-only and it still wrote" has this as
its answer. It is also what makes the read-modify-write atomic on this path.

**7. Generate the API document, do not write it.** A hand-written spec is correct the
day it is written and wrong within a fortnight, because nothing makes it wrong loudly.
springdoc reads the real mappings, DTOs and validation constraints. The trade-off,
worth naming: code-first means the spec follows the implementation, so you cannot
agree a contract before building it — design-first is better when several teams must
integrate against something that does not exist yet, which is common in a bank.

---

## 5. Exercises

1. **Delete the key and watch the money double.** Comment out the
   `idempotencyService.execute(...)` wrapper in the controller and call the
   adjustment directly. Run `LiquidityBufferAdjustmentIT`. Watch
   `sameKeyIsAppliedOnce` fail with 25,000,000 instead of 20,000,000. That failure
   is the bug this layer prevents, seen once.

2. **Split the transaction.** Add `@Transactional(propagation = Propagation.REQUIRES_NEW)`
   to `LiquidityBufferAdjustmentService.adjustBy`. Now the work commits in its own
   transaction. Run `failedAdjustmentDoesNotBurnTheKey` and reason carefully about
   what changed and why it is dangerous. Then revert.

3. **Fix the PUT endpoint's race.** Move `setLiquidityBuffer` onto
   `LiquidityBufferAdjustmentService` as a `setTo(accountId, amount)` method with
   `@Transactional`, and have the controller call it. That closes the last
   deliberate defect from Layer 2 — and it is genuinely a five-line change.

4. **Race two requests.** Send the same key twice concurrently:
   `1..2 | ForEach-Object -Parallel { ... }` in PowerShell 7, or two terminals as
   fast as you can. One should get 200 and one 409. Then retry the 409 and watch it
   replay.

5. **Add a cleanup job.** `@Scheduled(cron = "0 0 * * * *")` calling
   `deleteExpired(OffsetDateTime.now())`, with `@EnableScheduling`. Then think about
   what happens when three instances of this service all run that job at the same
   time — which is a Layer 7 conversation about distributed locks.

6. **Break the fingerprint deliberately.** Change `IdempotencyService` to hash only
   the key rather than the request. Watch `keyReuseWithDifferentPayloadIsRejected`
   fail, and note that it fails by *succeeding* — the worst kind.

7. **Pin the merge-versus-persist bug directly.** The two integration tests that
   caught it report `expected 200 but was 409`, which is a symptom three steps
   removed from the cause. Write an IT in `com.atlas.liquidity.refdata.idempotency`
   that autowires `IdempotencyRecordJpaRepository` and `IdempotencyService`, calls
   `execute` once with a trivial action, then reads the record back with `findById`
   and asserts `isComplete()`. Now delete `implements Persistable<String>` and watch
   it fail saying exactly what is wrong. Naming the cause rather than the symptom is
   most of what makes a test worth keeping.

8. **Read your own OpenAPI document.** Fetch `/v3/api-docs` and find the
   `Idempotency-Key` parameter, the 422 response, and the `AdjustLiquidityBufferRequest`
   schema with its regex. Everything you annotated is there; everything you did not is
   not. That is the honest feedback loop on API documentation.

---

## 6. Interview questions this slice prepares you for

1. A client's payment request times out. What should it do, and what must your server
   have done to make that safe?
2. How do idempotency keys work, and where is the key stored?
3. Why is an application-level "check then insert" insufficient?
4. Why must the key record and the business work share a transaction? What breaks in
   each direction if they do not?
5. Why store a request fingerprint as well as the key?
6. Why is hashing the raw request JSON the wrong way to build that fingerprint?
7. Which HTTP status for key reuse with a different payload, and why not 400?
8. Which status for a concurrent request with the same key, and what should the
   client do?
9. Which HTTP methods are naturally idempotent, and which operations therefore need
   no key?
10. How long should keys be retained, and what goes wrong at either extreme?
11. What does `@Transactional(readOnly = true)` do when the method is called inside
    an existing read-write transaction?
12. Where do transaction boundaries belong, and why not on the repository?
13. Code-first vs design-first OpenAPI — when would you choose each?
14. Should Swagger UI be exposed in production?
15. Kafka delivers at-least-once. How does that relate to everything above?

---

## 7. Commit

```powershell
git checkout -b feat/L03-openapi-and-idempotency
git add .
git commit -m "feat(api): publish OpenAPI and add idempotency keys for adjustments

- POST .../liquidity-buffer-adjustments requires an Idempotency-Key
- idempotency_record keyed on the client's key, so the database enforces
  uniqueness atomically rather than an application check-then-insert
- key record and business work share one transaction, in both directions
- SHA-256 fingerprint of a stable request string; reuse with a different
  payload is 422, concurrent use is 409
- first application service, which also makes read-modify-write atomic
- springdoc: /swagger-ui.html and /v3/api-docs generated from real mappings
- ADR 0006"
git push -u origin feat/L03-openapi-and-idempotency
```

---

## Definition of done

- [ ] `mvn clean verify` green, `Skipped: 0`
- [ ] Swagger UI loads at http://localhost:8081/swagger-ui.html
- [ ] you have sent the same adjustment twice with one key and confirmed the buffer
      moved once
- [ ] you have seen the 422 on key reuse with a different amount
- [ ] you have looked at `idempotency_record` in `psql`
- [ ] exercises #1 and #3 done — #1 shows you the bug, #3 closes the last Layer 2 defect
- [ ] merged to `main` via a PR you read
- [ ] you can answer 11 of the 15 questions out loud, especially 1 through 6

Then Layer 4 — the event-driven backbone. Kafka, the transactional outbox, consumer
idempotency (the same mechanism, different transport), and an IBM MQ bridge.
