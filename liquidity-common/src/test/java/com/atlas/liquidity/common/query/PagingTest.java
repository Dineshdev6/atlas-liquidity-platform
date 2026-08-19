package com.atlas.liquidity.common.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Tests for the paging primitives.
 *
 * <p>These are pure unit tests - no Spring, no database, milliseconds. That is
 * possible precisely because {@code Page} and {@code PageRequest} are plain Java
 * with no framework dependency, which is the main argument for defining them
 * ourselves rather than reusing Spring Data's.
 */
class PagingTest {

    @Nested
    @DisplayName("PageRequest")
    class PageRequestTests {

        @Test
        @DisplayName("computes a row offset from page and size")
        void computesOffset() {
            assertThat(PageRequest.of(0, 20, "accountId", SortDirection.ASC).offset()).isZero();
            assertThat(PageRequest.of(3, 20, "accountId", SortDirection.ASC).offset()).isEqualTo(60);
        }

        @Test
        @DisplayName("offset does not overflow at a large page index")
        void offsetDoesNotOverflow() {
            // (long) page * size, not page * size. With int arithmetic,
            // 200_000_000 * 200 overflows to a negative number and the database
            // is asked for OFFSET -1863462912. A one-word cast prevents it, and
            // this test is why anyone would ever notice.
            long offset = PageRequest.of(200_000_000, 200, "accountId", SortDirection.ASC).offset();
            assertThat(offset).isEqualTo(40_000_000_000L).isPositive();
        }

        @ParameterizedTest
        @ValueSource(ints = {-1, -100, Integer.MIN_VALUE})
        @DisplayName("rejects a negative page index")
        void rejectsNegativePage(int page) {
            assertThatThrownBy(() -> PageRequest.of(page, 20, "accountId", SortDirection.ASC))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("page must be zero or greater");
        }

        @ParameterizedTest
        @ValueSource(ints = {0, -1})
        @DisplayName("rejects a page size below one")
        void rejectsTooSmallSize(int size) {
            assertThatThrownBy(() -> PageRequest.of(0, size, "accountId", SortDirection.ASC))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("size must be at least 1");
        }

        @Test
        @DisplayName("caps page size, because an uncapped size is a denial-of-service vector")
        void capsPageSize() {
            // ?size=10000000 with no cap is one anonymous request that asks the
            // database for every row, holds it in heap and serialises it.
            assertThatThrownBy(() -> PageRequest.of(0, 10_000_000, "accountId", SortDirection.ASC))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("must not exceed " + PageRequest.MAX_SIZE);

            // The boundary itself is allowed - off-by-one errors in a limit check
            // are exactly the kind of thing a test should pin down.
            assertThat(PageRequest.of(0, PageRequest.MAX_SIZE, "accountId", SortDirection.ASC).size())
                    .isEqualTo(PageRequest.MAX_SIZE);
        }

        @Test
        @DisplayName("rejects a blank sort property")
        void rejectsBlankSortBy() {
            assertThatThrownBy(() -> PageRequest.of(0, 20, "   ", SortDirection.ASC))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sortBy must not be blank");
        }
    }

    @Nested
    @DisplayName("SortDirection")
    class SortDirectionTests {

        @ParameterizedTest(name = "\"{0}\" parses to {1}")
        @CsvSource({"asc, ASC", "ASC, ASC", "Desc, DESC", "DESC, DESC", "  desc  , DESC"})
        @DisplayName("parses case-insensitively and ignores surrounding whitespace")
        void parsesLeniently(String input, SortDirection expected) {
            assertThat(SortDirection.parse(input)).isEqualTo(expected);
        }

        @Test
        @DisplayName("defaults to ascending when absent")
        void defaultsToAscending() {
            assertThat(SortDirection.parse(null)).isEqualTo(SortDirection.ASC);
            assertThat(SortDirection.parse("")).isEqualTo(SortDirection.ASC);
        }

        @Test
        @DisplayName("rejects an unknown direction with a message naming the valid ones")
        void rejectsUnknownDirection() {
            assertThatThrownBy(() -> SortDirection.parse("sideways"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("sideways")
                    .hasMessageContaining("asc, desc");
        }
    }

    @Nested
    @DisplayName("Page")
    class PageTests {

        private static final PageRequest FIRST_OF_THREE =
                PageRequest.of(0, 3, "accountId", SortDirection.ASC);

        @ParameterizedTest(name = "{0} rows at size {1} is {2} pages")
        @CsvSource({"10, 3, 4", "9, 3, 3", "1, 3, 1", "0, 3, 0", "4, 2, 2", "201, 200, 2"})
        @DisplayName("rounds total pages up, not down")
        void roundsTotalPagesUp(long total, int size, int expectedPages) {
            Page<String> page = Page.of(
                    List.of(), PageRequest.of(0, size, "accountId", SortDirection.ASC), total);
            assertThat(page.totalPages()).isEqualTo(expectedPages);
        }

        @Test
        @DisplayName("knows whether it is the first or last page")
        void knowsItsPosition() {
            Page<String> firstOfFour = Page.of(List.of("a", "b", "c"), FIRST_OF_THREE, 10);
            assertThat(firstOfFour.first()).isTrue();
            assertThat(firstOfFour.last()).isFalse();

            Page<String> lastOfFour = Page.of(
                    List.of("j"), PageRequest.of(3, 3, "accountId", SortDirection.ASC), 10);
            assertThat(lastOfFour.first()).isFalse();
            assertThat(lastOfFour.last()).isTrue();
        }

        @Test
        @DisplayName("an empty result is both first and last")
        void emptyResultIsFirstAndLast() {
            // The degenerate case, and the one that produces "page 1 of 0" in a UI
            // if you get it wrong.
            Page<String> empty = Page.empty(FIRST_OF_THREE);

            assertThat(empty.empty()).isTrue();
            assertThat(empty.totalPages()).isZero();
            assertThat(empty.first()).isTrue();
            assertThat(empty.last()).isTrue();
        }

        @Test
        @DisplayName("map converts the content and leaves the metadata untouched")
        void mapPreservesMetadata() {
            // This is what lets the API layer convert domain objects to DTOs
            // without recalculating any count - and therefore without any chance
            // of the counts disagreeing with the rows.
            Page<String> source = Page.of(List.of("aa", "bbb"), FIRST_OF_THREE, 10);

            Page<Integer> mapped = source.map(String::length);

            assertThat(mapped.content()).containsExactly(2, 3);
            assertThat(mapped.page()).isEqualTo(source.page());
            assertThat(mapped.size()).isEqualTo(source.size());
            assertThat(mapped.totalElements()).isEqualTo(source.totalElements());
            assertThat(mapped.totalPages()).isEqualTo(source.totalPages());
        }

        @Test
        @DisplayName("content is an immutable copy, so callers cannot mutate a result")
        void contentIsImmutable() {
            Page<String> page = Page.of(List.of("a"), FIRST_OF_THREE, 1);

            assertThatThrownBy(() -> page.content().add("b"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        @DisplayName("rejects a negative total")
        void rejectsNegativeTotal() {
            assertThatThrownBy(() -> Page.of(List.of(), FIRST_OF_THREE, -1))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("totalElements");
        }
    }
}
