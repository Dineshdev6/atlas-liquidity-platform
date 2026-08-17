# Layer 2 — Domain model and persistence

**Goal:** the in-memory list becomes a real database. Flyway-managed schema,
JPA/Hibernate over Postgres, connection pooling, optimistic locking, a write
endpoint, and integration tests against a genuine database in Docker.

**Prerequisite:** Docker Desktop installed and running. This layer cannot be
done without it, and neither can Layers 4, 10, 11 or 12.

---

## 1. What changed

```
NEW   docker-compose.yml                      Postgres 16 for local development
NEW   db/migration/V1__create_settlement_account.sql
NEW   db/migration/V2__seed_settlement_accounts.sql
NEW   persistence/SettlementAccountEntity.java        JPA mapping
NEW   persistence/SettlementAccountJpaRepository.java Spring Data interface
NEW   persistence/JpaSettlementAccountRepositoryAdapter.java  the adapter
NEW   api/UpdateLiquidityBufferRequest.java           validated write DTO
NEW   support/AbstractPostgresIntegrationTest.java    Testcontainers base
NEW   persistence/SettlementAccountPersistenceIT.java
NEW   docs/adr/0003, docs/adr/0004

CHG   reference-data-service/pom.xml          JPA, Postgres, Flyway, Testcontainers, Failsafe
CHG   application.yml                         datasource, Hikari, JPA, Flyway
CHG   domain/SettlementAccount.java           gains a Money liquidityBuffer
CHG   domain/SettlementAccountRepository.java gains updateLiquidityBuffer
CHG   api/SettlementAccountResponse.java      exposes the buffer as a String
CHG   api/SettlementAccountController.java    gains PUT .../liquidity-buffer
CHG   api/GlobalExceptionHandler.java         409, validation errors, 400 on bad JSON

GONE  domain/InMemorySettlementAccountRepository.java
GONE  ReferenceDataApplicationTest.java  →  renamed ReferenceDataApplicationIT.java
```

**What did not change: the controller's read methods.** The database arrived and
they are byte-for-byte what they were in Layer 1. That is the port and adapter
arrangement earning its keep, and it is the concrete example to reach for when
someone asks why you would not just call Spring Data from the controller.

---

## 2. Run it

```powershell
docker compose up -d
docker compose ps                    # wait until postgres is "healthy"
```

```powershell
mvn clean install -DskipTests        # remember Layer 1: verify does not install
mvn -pl reference-data-service spring-boot:run
```

Watch the startup log. You should see Flyway announce itself:

```
Migrating schema "public" to version "1 - create settlement account"
Migrating schema "public" to version "2 - seed settlement accounts"
Successfully applied 2 migrations
```

Then exercise it — `docs/api-examples.http` has all of these clickable:

```powershell
curl.exe -s http://localhost:8081/api/v1/accounts
curl.exe -s -X PUT http://localhost:8081/api/v1/accounts/ACC-US-0001/liquidity-buffer -H "Content-Type: application/json" -d "{\"amount\":\"31000000.00\"}"
curl.exe -s http://localhost:8081/actuator/health
```

**Look inside the database yourself.** This is worth doing rather than trusting
the API:

```powershell
docker exec -it atlas-postgres psql -U atlas -d atlas_liquidity
```

```sql
\dt
\d settlement_account
SELECT account_id, currency_code, liquidity_buffer_amount, version FROM settlement_account;
SELECT * FROM flyway_schema_history;
\q
```

That `version` column is the optimistic lock. Run the PUT again and watch it
increment. `flyway_schema_history` is the audit trail — the applied migrations,
their checksums, who ran them and when.

**Run the full suite:**

```powershell
mvn clean verify
```

Surefire runs the fast unit and slice tests. Failsafe then starts a real
Postgres container and runs the `*IT` tests against it. The first run pulls the
`postgres:16-alpine` image, so allow a couple of minutes.

---

## 3. The eight things to actually understand

**1. Flyway owns the schema; Hibernate only validates it.** `ddl-auto: validate`
makes Hibernate check its mappings against the real schema at boot and refuse to
start on a mismatch — so a forgotten migration fails on deploy, loudly, instead
of at 3am under load. `ddl-auto: update` is the answer that ends an interview.
Read ADR 0003 for the full list of why.

**2. Migrations are immutable.** Flyway checksums each file. Edit V1 after it has
run and every future migration fails. Fixing a mistake means writing V3. The
harder version of this: during a rolling deploy, the old and new versions of the
application are both live, so a migration must be compatible with both. Add a
column before you write to it; drop it a release after you stop reading it.

**3. Entity and domain record are separate on purpose.** The record is immutable
and always valid; a JPA entity cannot be either, because the framework demands a
no-arg constructor and mutable fields. Keeping them apart also stops Hibernate
leaking into `liquidity-common`. The cost is a mapping step — real, and not
always worth paying. Knowing when it is worth paying is the actual judgement.

**4. Automatic dirty checking.** `updateLiquidityBuffer` never calls `save()`.
Inside a transaction, entities are *managed*: Hibernate snapshots them on load
and issues an UPDATE at commit for whatever changed. The flip side is the trap —
accidentally mutate a managed entity and you have silently written to the
database. "Why is my read endpoint issuing an UPDATE" has this as its answer.

**5. `@Version` and the lost update problem.** Two transactions read the same
row, both write, and one silently destroys the other's work. `@Version` appends
`AND version = ?` to the UPDATE, so the loser updates zero rows and gets
`OptimisticLockingFailureException` → **409 Conflict**, not 500. Nothing is
broken; the system refused to lose data. Pessimistic locking (`SELECT ... FOR
UPDATE`) is the alternative — correct under high contention, expensive
otherwise.

**6. `open-in-view: false`.** On by default, and a trap. It holds a Hibernate
session — and a pooled connection — for the entire HTTP request, including the
time spent serialising JSON. It hides N+1 problems by making lazy loads work
from the view layer. Turn it off deliberately, every time, and be able to say
why.

**7. Small connection pools are usually faster.** A pool is a queue, not a cache;
every pooled connection is a real backend process holding memory. If the
database can genuinely do 10 things at once, a pool of 100 does not make it do
100 — it makes 100 queries contend and every one gets slower. Twenty services
with 50 connections each will take the database down long before it takes them
down.

**8. Testcontainers over H2.** H2 has a different dialect, different type
coercion, different locking, and no real `TIMESTAMPTZ`. A suite green on H2 and
red on Postgres is worse than no suite, because the confidence was not earned.
Testcontainers runs your real migrations against the real engine at the real
version, on every machine.

---

## 4. Deliberate imperfections

**a. The PUT endpoint has a read-modify-write race.** The controller calls
`findByAccountId` and then `updateLiquidityBuffer` in two separate transactions.
Between them, another request could change or delete the account. The fix is a
single transaction spanning both, which means an application service owning the
boundary — deferred until Layer 5, when there is something for it to
orchestrate. It is documented in the controller's Javadoc.

**b. Filtering by currency *and* jurisdiction still does two passes.** The
currency filter is now a real SQL query, but the jurisdiction filter is still a
Java stream over the result. Layer 3 pushes both into one query with
Specifications.

**c. `disabledWithoutDocker = true` lets the build pass while testing nothing.**
Convenient for a developer without Docker; wrong for CI, where a skipped test
must never look like a passing one. In a real pipeline you remove it.

**d. No pagination.** `findAll` returns every row. Fine over six seed accounts;
an outage over six million. Layer 3.

---

## 5. Exercises

1. **Break a migration checksum.** Add a space to the end of `V1`, restart the
   app, and read the error. Then undo it. Now you will recognise
   `FlywayValidateException` instantly when it happens for real.

2. **Watch the SQL.** Uncomment the two `org.hibernate` logging lines at the
   bottom of `application.yml`, restart, and hit `/api/v1/accounts`. Count the
   queries. Then hit the PUT endpoint and find the `UPDATE ... WHERE account_id
   = ? AND version = ?`.

3. **Cause a lost update, then prevent one.** Open two `psql` sessions.
   `BEGIN` in both, `UPDATE ... SET liquidity_buffer_amount = ...` on the same
   row in both, then `COMMIT` both. Observe that the second wins silently. Now
   do the same thing through the API with a deliberately stale `version` and
   watch the 409.

4. **Write V3.** Add a `nullable` column `last_reviewed_at TIMESTAMPTZ` to
   `settlement_account`, map it on the entity, expose it on the response, and
   watch Flyway apply it on the next start. Then deliberately *forget* to write
   the migration and add only the entity field — see `ddl-auto: validate` refuse
   to start. That failure is the whole reason for `validate`.

5. **Add `findByLegalEntity`.** Add it to the Spring Data interface, the port,
   and the adapter, driven by a test in `SettlementAccountPersistenceIT` you
   write first. Then deliberately misspell the property name in the method and
   watch the context fail to start.

6. **Shrink the pool to 1.** Set `maximum-pool-size: 1`, restart, and hit the
   API from two terminals at once. Then set `connection-timeout: 250` and watch
   requests start failing rather than queueing. This is what saturation looks
   like, and Layer 7 measures it properly.

7. **Kill the database while the app is running.** `docker compose stop
   postgres`, then hit `/actuator/health`. It should go `DOWN`. Restart it and
   watch it recover. That behaviour is what makes a Kubernetes readiness probe
   worth having.

---

## 6. Interview questions this layer prepares you for

1. How do you manage database schema changes across environments?
2. Why is `ddl-auto: update` dangerous in production?
3. What makes a migration safe during a rolling deploy?
4. What is the N+1 problem, how do you detect it, and how do you fix it?
5. Optimistic vs pessimistic locking — when would you choose each?
6. What HTTP status do you return when an optimistic lock fails, and why?
7. What does `@Transactional(readOnly = true)` actually do?
8. Where should transaction boundaries live, and why not on the repository?
9. What is `open-in-view` and why would you disable it?
10. Why is a smaller connection pool often faster than a larger one?
11. Why `@Enumerated(STRING)` and never `ORDINAL`?
12. Why is money a `String` in the JSON and a `NUMERIC` in the database?
13. Why Testcontainers rather than H2 for repository tests?
14. What is the difference between Surefire and Failsafe?
15. If you have not used Oracle, how would you approach a codebase that targets
    it? (ADR 0004 is your answer.)

---

## 7. Commit

```powershell
git checkout -b feat/L02-persistence
git add .
git commit -m "feat(persistence): replace in-memory store with JPA over Postgres

- Flyway-managed schema with seed reference data
- JPA entity separated from the immutable domain record
- optimistic locking via @Version, surfaced as 409
- PUT endpoint for the liquidity buffer, validated at the edge
- Testcontainers integration tests against real Postgres
- ADR 0003 (Flyway owns the schema), ADR 0004 (Postgres vs Oracle)"
git push -u origin feat/L02-persistence
```

Then open a pull request on GitHub and merge it. Read your own diff before you
merge — that habit is most of what code review is.

---

## 8. Troubleshooting

**`Connection to localhost:5432 refused`**
Postgres is not running. `docker compose up -d`, then `docker compose ps` and
wait for `healthy`.

**`Unsupported Database: PostgreSQL 16.x`**
The `flyway-database-postgresql` dependency is missing. Since Flyway 10 each
database family ships separately.

**`Schema-validation: missing column [x] in table [settlement_account]`**
Exactly what `validate` is for — the entity and the schema disagree. Either the
migration was not written, or it did not run. Check `flyway_schema_history`.

**`Migration checksum mismatch for migration version 1`**
You edited a migration that had already run. Restore the original file, or wipe
the database with `docker compose down -v` and start again. Never "fix" this by
editing the history table.

**`Could not find a valid Docker environment`**
Docker Desktop is not running. The `*IT` tests will skip rather than fail —
which means a green build that tested nothing. Check for `Tests run: 0` before
you trust it.

**Tests pass with `mvn test` but fail with `mvn verify`**
Working as designed: `test` runs Surefire only, `verify` adds the Failsafe
integration tests.

---

## Definition of done

- [ ] `docker compose up -d` and Postgres reports healthy
- [ ] the app starts and the log shows Flyway applying 2 migrations
- [ ] `mvn clean verify` is green, and the `*IT` tests actually **ran** (not skipped)
- [ ] you have looked at the table and `flyway_schema_history` in `psql`
- [ ] the PUT endpoint changes the buffer and the `version` column increments
- [ ] at least four of the seven exercises are done and committed
- [ ] merged to `main` via a pull request
- [ ] you can answer 12 of the 15 questions out loud

Then we go to Layer 3 — REST APIs properly: pagination, OpenAPI, idempotency
keys, Specifications, and API versioning.
