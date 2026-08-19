package com.atlas.liquidity.refdata.api;

import com.atlas.liquidity.common.query.Page;
import java.util.List;

/**
 * The wire representation of a page of results.
 *
 * <p><b>Why we define our own shape instead of returning Spring Data's
 * {@code Page}.</b> Serialising {@code PageImpl} directly is the single most
 * common paginated-API mistake in the Spring world, and Spring Boot 3.3 started
 * emitting a warning about it. Three reasons:
 *
 * <ul>
 *   <li><b>It is not a stable contract.</b> {@code PageImpl}'s JSON is whatever
 *       Jackson makes of its getters. Upgrade Spring Data and fields can appear,
 *       disappear or move - and you have just broken every client, in a patch
 *       release, without changing a line of your own code.</li>
 *   <li><b>It leaks internals.</b> The default output includes a {@code pageable}
 *       object with {@code offset}, {@code paged}, {@code unpaged} and a nested
 *       {@code sort} - implementation detail nobody outside your JVM should care
 *       about, and noise in every response.</li>
 *   <li><b>You cannot document or version it.</b> An OpenAPI schema for a type
 *       you do not own is a schema you cannot control.</li>
 * </ul>
 *
 * <p>The metadata sits in a nested {@code page} object rather than flat
 * alongside {@code content}, so a field called {@code size} is unambiguous - it
 * is the page size, not the number of items returned, and no client has to guess.
 *
 * <p>"How do you design a paginated API response?" is a fair interview question,
 * and "define your own envelope, because the framework's is an implementation
 * detail that will change underneath you" is the answer that shows you have been
 * burned once.
 */
public record PageResponse<T>(List<T> content, PageMetadata page) {

    /**
     * @param number        zero-based index of this page
     * @param size          requested page size
     * @param totalElements total matching rows across all pages
     * @param totalPages    number of pages at this size
     * @param first         true when this is the first page
     * @param last          true when this is the last page
     */
    public record PageMetadata(
            int number,
            int size,
            long totalElements,
            int totalPages,
            boolean first,
            boolean last) {
    }

    public static <T> PageResponse<T> from(Page<T> page) {
        return new PageResponse<>(
                page.content(),
                new PageMetadata(
                        page.page(),
                        page.size(),
                        page.totalElements(),
                        page.totalPages(),
                        page.first(),
                        page.last()));
    }
}
