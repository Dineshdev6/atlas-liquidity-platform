package com.atlas.liquidity.common.query;

import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * One page of results plus the metadata a caller needs to walk the rest.
 *
 * @param content       the rows on this page
 * @param page          zero-based index of this page
 * @param size          requested page size
 * @param totalElements total matching rows across all pages
 */
public record Page<T>(List<T> content, int page, int size, long totalElements) {

    public Page {
        Objects.requireNonNull(content, "content must not be null");
        content = List.copyOf(content);   // defensive copy; a record's field would otherwise be shared
        if (page < 0) {
            throw new IllegalArgumentException("page must be zero or greater");
        }
        if (size < 1) {
            throw new IllegalArgumentException("size must be at least 1");
        }
        if (totalElements < 0) {
            throw new IllegalArgumentException("totalElements must not be negative");
        }
    }

    public static <T> Page<T> of(List<T> content, PageRequest request, long totalElements) {
        return new Page<>(content, request.page(), request.size(), totalElements);
    }

    public static <T> Page<T> empty(PageRequest request) {
        return new Page<>(List.of(), request.page(), request.size(), 0);
    }

    public int totalPages() {
        return (int) Math.ceil((double) totalElements / size);
    }

    public boolean first() {
        return page == 0;
    }

    public boolean last() {
        return page >= totalPages() - 1;
    }

    public boolean empty() {
        return content.isEmpty();
    }

    /**
     * Converts the content while keeping the paging metadata intact.
     *
     * <p>This is what lets the API layer turn a {@code Page<SettlementAccount>}
     * into a {@code Page<SettlementAccountResponse>} without recalculating any
     * of the counts - and therefore without any chance of getting them wrong.
     */
    public <R> Page<R> map(Function<? super T, ? extends R> mapper) {
        Objects.requireNonNull(mapper, "mapper must not be null");
        List<R> mapped = content.stream().<R>map(mapper).toList();
        return new Page<>(mapped, page, size, totalElements);
    }
}
