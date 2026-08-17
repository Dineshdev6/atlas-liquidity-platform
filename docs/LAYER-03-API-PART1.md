# Layer 3, part 1 — The read API done properly

**Goal:** pagination, dynamic filtering in one query, an allow-listed sort, and an
exception handler that stops reporting client mistakes as server failures.

**Prerequisite:** Docker running and `docker compose up -d`. No new
infrastructure, no new dependencies — this slice is pure Java and API design.

**Scope note:** this is the first of two or three PRs for Layer 3. The Layer 2 PR
was 2,050 lines and broke GitHub's own diff renderer, which is a fairly emphatic
sign no human was reviewing it either.

---

## 1. What changed

```
NEW  liquidity-common .../query/PageRequest.java      page, size, sort — with a size cap
NEW  liquidity-common .../query/Page.java             content + metadata, framework-free
NEW  liquidity-common .../query/SortDirection.java
NEW  liquidity-common test .../query/PagingTest.java

NEW  refdata/domain/SettlementAccountQuery.java       filter criteria as one object
NEW  refdata/domain/SettlementAccountSortField.java   sort allow-list
NEW  refdata/persistence/SettlementAccountSpecifications.java
NEW  refdata/api/PageResponse.java                    our own wire envelope
NEW  docs/adr/0005-own-the-pagination-contract.md

CHG  refdata/domain/SettlementAccountRepository.java  4 read methods → 1 search
CHG  refdata/persistence/SettlementAccountJpaRepository.java  + JpaSpecificationExecutor
CHG  refdata/persistence/JpaSettlementAccountRepositoryAdapter.java
CHG  refdata/api/SettlementAccountController.java     paging + filters at the edge
CHG  refdata/api/GlobalExceptionHandler.java          extends ResponseEntityExceptionHandler
CHG  all three test classes
```

**What did not change: `SettlementAccountResponse`, the entity, the migrations, the
database.** This slice is entirely about the boundary.

---

## 2. Run it

```powershell
docker compose up -d
mvn clean verify
```

Then start the service and try the new parameters:

```powershell
mvn clean install -DskipTests
mvn -pl reference-data-service spring-boot:run
```

```powershell
curl.exe -s "http://localhost:8081/api/v1/accounts?size=2&page=0"
curl.exe -s "http://localhost:8081/api/v1/accounts?currency=usd&jurisdiction=us"
curl.exe -s "http://localhost:8081/api/v1/accounts?sort=liquidityBuffer&direction=desc"
curl.exe -s "http://localhost:8081/api/v1/accounts?size=999999"
curl.exe -s "http://localhost:8081/api/v1/accounts?sort=password"
curl.exe -s -i "http://localhost:8081/api/v1/nonexistent"
```

The last two are the interesting ones. `sort=password` returns a **400** naming the
valid fields rather than a 500 naming your columns. `/api/v1/nonexistent` returns a
**404** rather than the 500 it would have returned yesterday.

`docs/api-examples.http` has all of these clickable in VS Code.

---

## 3. The six things to actually understand

**1. Your paginated response is a contract you must own.** Serialising Spring
Data's `Page` looks free and is not: its JSON is whatever Jackson makes of
`PageImpl`'s getters, so a Spring Data upgrade can add, move or remove fields and
break every client in a patch release. It also emits a `pageable` object full of
internals. Spring Boot 3.3 added a warning about this. Define your own envelope.
ADR 0005 has the full argument.

**2. An uncapped page size is a denial-of-service vector.** `?size=10000000` is one
anonymous request that asks the database for every row, holds it in heap and
serialises it. The cap lives in `PageRequest`'s constructor, so it cannot be
forgotten by a caller.

**3. An unvalidated sort parameter is worse than it looks.** Spring Data sorts by
any property name you hand it. `?sort=nonsense` throws
`PropertyReferenceException` — a 500 whose message enumerates your entity's real
property names to whoever sent the request. Availability bug plus information
disclosure, from a query string. Hence the allow-list enum, resolved at the edge.

**4. A total order is part of correctness.** Sort by a non-unique column and ties
come back in whatever order the database feels like — which can differ between the
page-1 query and the page-2 query, so a row appears on both pages or on neither.
The adapter appends `accountId` as a tie-break. There is a test that pages through
by `currencyCode` and asserts no duplicates; that test would fail without the
tie-break.

**5. One criteria object beats a method per combination.** Three optional filters
is eight combinations; four is sixteen. `findByCurrencyCodeAndJurisdictionAnd...`
does not scale, and that is precisely where Spring Data's derived query methods
stop being the right tool. One `Specification` handles every combination in one
SQL statement, and adding a filter is one more `if`. Values are bound as JDBC
parameters, so injection is structurally impossible rather than merely guarded
against.

**6. A catch-all exception handler with nothing above it is a bug.** Layers 1 and 2
had `@ExceptionHandler(Exception.class)` and no base class, so Spring's own
correct exceptions — 404 for an unmapped path, 405 for a wrong method, 400 for an
unbindable parameter — were all caught and served as **500**. Client mistakes
reported as server failures means your 5xx alert fires constantly and a real
outage is lost in the noise. Extending `ResponseEntityExceptionHandler` fixes it,
because Spring dispatches to the most specific matching handler and the base class
declares handlers for all of them.

---

## 4. What this slice closes out

Two of the three deliberate imperfections from earlier layers are now dead:

- **Layer 1's greedy exception handler** — fixed, with tests asserting 404 and 405.
- **Layer 2's two-pass filtering** — fixed, one query, with a test proving that a
  contradictory filter combination returns nothing rather than the union.

Still open by design: the **read-modify-write race** on the PUT endpoint, which
needs a transaction spanning both operations and therefore an application service.
Layer 5, when there is something for that service to orchestrate.

---

## 5. Exercises

1. **Prove the tie-break matters.** In `JpaSettlementAccountRepositoryAdapter`,
   delete the `if (!"accountId".equals(...))` block that appends the secondary
   sort. Run `mvn verify`. Watch `pagingByNonUniqueColumnIsStable` fail — or
   worse, watch it pass, and understand why an intermittent test is scarier than
   a failing one. Then put it back.

2. **Count the queries.** Uncomment the two `org.hibernate` logging lines in
   `application.yml`, restart, and hit `/api/v1/accounts?size=2`. You will see
   **two** statements: a `SELECT ... limit ?` and a `SELECT count(*)`. That second
   one is what `totalElements` costs you.

3. **Remove the count.** Change `search` to fetch `size + 1` rows and report only
   whether a next page exists, with no `count(*)`. Compare the SQL. This is what
   an infinite-scroll API does, and why.

4. **Add a fourth filter.** Add `bic` to `SettlementAccountQuery`, the
   Specification, and the controller. Notice it is one `if` and one parameter — no
   combinatorial explosion. Then imagine having done it with derived query
   methods.

5. **Try to break the sort.** Send `?sort=liquidityBufferAmount` (the *column*
   name, not the API name). It should be rejected — the allow-list holds API names
   only, which is what keeps the two vocabularies independent.

6. **Attempt an injection.** `?legalEntity=x' OR '1'='1` and
   `?legalEntity=x'; DROP TABLE settlement_account; --`. Both return an empty page.
   Turn on SQL logging and look at why: the value is a bound parameter, not text
   spliced into a statement.

7. **Make the 500 come back.** Temporarily remove `extends
   ResponseEntityExceptionHandler` from `GlobalExceptionHandler` and run
   `mvn verify`. Two tests fail with 500 where they expect 404 and 405. Seeing the
   defect reappear on demand is how you remember it.

---

## 6. Interview questions this slice prepares you for

1. How would you design a paginated API response, and why not return the
   framework's page type?
2. Offset vs keyset pagination — what breaks first, and what can keyset not do?
3. What does `SELECT count(*)` cost on a paginated endpoint, and when would you
   drop it?
4. Why must a paginated query have a total order?
5. How do you stop a caller requesting a million rows?
6. What happens if you pass a user-supplied string as a sort field to Spring Data?
7. When do Spring Data derived query methods stop being the right tool?
8. Why is a Criteria API query immune to SQL injection?
9. Should a domain port depend on Spring Data types? Argue both sides.
10. URI vs header vs media-type API versioning — which and why?
11. What does `ResponseEntityExceptionHandler` give you, and what goes wrong
    without it?
12. Why is a 404 served as a 500 an operational problem, not just an aesthetic one?

---

## 7. Commit

```powershell
git checkout -b feat/L03-paging-and-error-contract
git add .
git commit -m "feat(api): paginate, filter and sort accounts; fix the error contract

- own Page/PageRequest in liquidity-common, no framework types in the port
- server-side page size cap and an allow-listed sort field
- total order via an accountId tie-break, so paging cannot repeat or skip rows
- one Specification replaces four derived query methods
- GlobalExceptionHandler extends ResponseEntityExceptionHandler, so 404 and 405
  are no longer served as 500
- ADR 0005"
git push -u origin feat/L03-paging-and-error-contract
```

---

## Definition of done

- [ ] `mvn clean verify` green, `Skipped: 0`
- [ ] `?size=999999` returns 400, `?sort=password` returns 400
- [ ] `/api/v1/nonexistent` returns 404 and `DELETE /api/v1/accounts` returns 405
- [ ] you have seen the two SQL statements a paged query issues
- [ ] at least three exercises done, including #1 and #7
- [ ] merged to `main` via a PR you actually read
- [ ] you can answer 9 of the 12 questions out loud

Then part 2 — OpenAPI and idempotency keys.
