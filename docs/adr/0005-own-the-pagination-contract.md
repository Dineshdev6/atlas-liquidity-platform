# ADR 0005 — Own the pagination contract; cap page size server-side

**Status:** Accepted
**Date:** Layer 3

## Context

Every collection endpoint needs pagination. There are three decisions to make and
each has a default that is wrong.

**1. What type crosses the wire?** The path of least resistance is to return
Spring Data's `Page` and let Jackson serialise it. It works immediately.

**2. What type crosses the domain port?** Spring Data's `Pageable` and `Page` are
right there, and using them means no new code.

**3. Is page size bounded?** By default, no.

## Decision

**Define our own `Page<T>` and `PageRequest` in `liquidity-common`**, with no
framework dependency, and use them in the domain port.

**Define our own `PageResponse<T>` envelope** in the API layer, with paging
metadata in a nested `page` object.

**Cap page size at 200**, enforced in `PageRequest`'s constructor, and reject
anything larger with a 400.

**Validate the sort field against an allow-list** (`SettlementAccountSortField`)
at the web boundary, before it can reach Spring Data.

## Consequences

**Why not serialise Spring Data's `Page`.** This is the most common paginated-API
mistake in the Spring ecosystem, and Spring Boot 3.3 began emitting a warning
about it:

- **It is not a stable contract.** `PageImpl`'s JSON is whatever Jackson makes of
  its getters. Upgrade Spring Data and fields can appear, disappear or move — you
  have broken every client in a patch release without changing a line of your own
  code.
- **It leaks internals.** The default output carries a `pageable` object with
  `offset`, `paged`, `unpaged` and a nested `sort` — implementation detail that
  becomes part of your public API by accident.
- **You cannot own its schema.** An OpenAPI definition for a type you do not
  control is a definition you cannot version.

**Why not `Pageable` in the domain port.** `liquidity-common` is depended on by
every service we will build. Putting a Spring Data type in the port means the
domain cannot be compiled, tested or reasoned about without Spring Data present.
The adapter converts at the boundary in three lines — which is exactly where that
knowledge belongs. The cost is two small classes we maintain ourselves; the
benefit is a domain that owes nothing to a framework.

**Why the cap is a security control.** Without it, `?size=10000000` is a single
anonymous request that asks the database for every row, holds the result in heap,
and serialises it to JSON. One query string, one outage — no authentication
required. 200 is chosen to be comfortably above any legitimate UI page and far
below anything dangerous. The number matters less than having one.

**Why the sort allow-list is not optional.** Spring Data will sort by any property
name you hand it, and that name comes from the request. `?sort=nonsense` produces
`PropertyReferenceException` — a 500 whose message helpfully enumerates the
entity's real property names to whoever sent it. That is an availability bug and
information disclosure from a query parameter. In stacks where sorting is
concatenated into SQL rather than going through a criteria API, it is also a
genuine injection vector.

**A total order is part of correctness, not a nicety.** Sorting by a non-unique
column leaves ties in an order the database may return differently between the
page-1 and page-2 queries, so a row can appear on both pages or on neither. The
adapter appends `accountId` as a tie-break. This is a real, intermittent,
maddening production bug and one line prevents it.

**What we accept.** `totalElements` costs a second `SELECT count(*)` on every
request, and on a large table that count can scan far more than the page it
describes. We keep it because an operations user genuinely wants a total. The
alternatives — return only "is there a next page" by fetching `size + 1` rows, or
switch to keyset pagination — are documented in the adapter and are the right
answer once the table is large or the endpoint is hot.

**Interview relevance.** "How would you design a paginated API?" is a common
question with a shallow expected answer. The strong version covers all of this:
own the envelope, cap the size, allow-list the sort, make the order total, and
know what the count costs. Offset versus keyset pagination is the natural
follow-up — offset degrades because the database must count past every skipped
row, keyset does not, but keyset cannot jump to an arbitrary page number.
