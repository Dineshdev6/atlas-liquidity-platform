package com.atlas.liquidity.common.query;

import java.util.Objects;

/**
 * A request for one page of results.
 *
 * <p><b>Why we define this instead of using Spring Data's {@code Pageable}.</b>
 * {@code Pageable} is a perfectly good type - it is also a Spring Data type, and
 * this class lives in {@code liquidity-common}, which every future service
 * depends on. Putting {@code Pageable} in the domain port would mean the domain
 * cannot be understood, tested or reused without Spring Data on the classpath.
 * The adapter translates to {@code Pageable} at the boundary, which is a
 * three-line method and exactly where that knowledge belongs.
 *
 * <p><b>The size cap is a denial-of-service control, not a nicety.</b> Without
 * it, {@code ?size=10000000} is an unauthenticated request that asks your
 * database for every row, holds it all in heap, serialises it to JSON, and takes
 * the service down. One query string, one outage. Every paginated API needs a
 * maximum, and being asked "how do you stop a caller requesting a million rows"
 * and answering "we cap page size server-side and document it" is a small but
 * real signal.
 *
 * @param page   zero-based page index
 * @param size   rows per page, between 1 and {@link #MAX_SIZE}
 * @param sortBy property to sort by; callers must have validated it against a
 *               whitelist before constructing this - see
 *               {@code SettlementAccountSortField}
 * @param direction sort direction
 */
public record PageRequest(int page, int size, String sortBy, SortDirection direction) {

    /** Hard server-side ceiling. A caller asking for more is a bug or an attack. */
    public static final int MAX_SIZE = 200;

    public static final int DEFAULT_SIZE = 20;

    public PageRequest {
        if (page < 0) {
            throw new IllegalArgumentException("page must be zero or greater, got: " + page);
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1, got: " + size);
        }
        if (size > MAX_SIZE) {
            throw new IllegalArgumentException(
                    "size must not exceed " + MAX_SIZE + ", got: " + size);
        }
        Objects.requireNonNull(sortBy, "sortBy must not be null");
        if (sortBy.isBlank()) {
            throw new IllegalArgumentException("sortBy must not be blank");
        }
        Objects.requireNonNull(direction, "direction must not be null");
    }

    public static PageRequest of(int page, int size, String sortBy, SortDirection direction) {
        return new PageRequest(page, size, sortBy, direction);
    }

    /** First page, default size, ascending by the given property. */
    public static PageRequest firstPage(String sortBy) {
        return new PageRequest(0, DEFAULT_SIZE, sortBy, SortDirection.ASC);
    }

    /** Zero-based row offset, for adapters that need it rather than a page index. */
    public long offset() {
        return (long) page * size;
    }
}
