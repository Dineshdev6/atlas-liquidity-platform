package com.atlas.liquidity.refdata.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.liquidity.common.money.CurrencyMismatchException;
import com.atlas.liquidity.common.money.Money;
import com.atlas.liquidity.common.query.Page;
import com.atlas.liquidity.common.query.PageRequest;
import com.atlas.liquidity.common.query.SortDirection;
import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountQuery;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import com.atlas.liquidity.refdata.support.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for the JPA adapter against a real Postgres.
 *
 * <p>Named {@code ...IT} so Failsafe runs it in the {@code integration-test}
 * phase and Surefire leaves it alone. {@code mvn test} skips it;
 * {@code mvn verify} runs it.
 *
 * <p>Every test here silently depends on the Flyway migrations applying cleanly,
 * the Hibernate mappings validating against the real schema, the constraints
 * existing, and the connection pool working. If a migration had a syntax error or
 * the entity named a column that does not exist, none of this would reach its
 * first assertion.
 */
@SpringBootTest
class SettlementAccountPersistenceIT extends AbstractPostgresIntegrationTest {

    private static final PageRequest ALL_ROWS =
            PageRequest.of(0, 50, "accountId", SortDirection.ASC);

    @Autowired
    private SettlementAccountRepository repository;

    // --- wiring ----------------------------------------------------------

    @Test
    @DisplayName("Flyway applies the migrations and seeds six accounts")
    void migrationsApplyAndSeedData() {
        assertThat(repository.search(SettlementAccountQuery.all(), ALL_ROWS).totalElements())
                .isEqualTo(6);
    }

    @Test
    @DisplayName("the injected repository is the JPA adapter, not a test double")
    void usesTheJpaAdapter() {
        // Asserted on the class NAME rather than with isInstanceOf, because
        // @Transactional means Spring hands us a generated proxy. The proxy's name
        // still contains the target class name, usually with "$$SpringCGLIB$$"
        // appended - worth seeing once, since "why does my class have $$ in the
        // stack trace" is a question every Spring developer eventually asks.
        assertThat(repository.getClass().getName()).contains("JpaSettlementAccountRepositoryAdapter");
    }

    // --- paging ----------------------------------------------------------

    @Nested
    @DisplayName("paging")
    class Paging {

        @Test
        @DisplayName("returns one page of rows plus a total across all pages")
        void returnsOnePageWithTotal() {
            Page<SettlementAccount> first = repository.search(
                    SettlementAccountQuery.all(),
                    PageRequest.of(0, 2, "accountId", SortDirection.ASC));

            assertThat(first.content()).hasSize(2);
            assertThat(first.totalElements()).isEqualTo(6);
            assertThat(first.totalPages()).isEqualTo(3);
            assertThat(first.first()).isTrue();
            assertThat(first.last()).isFalse();
        }

        @Test
        @DisplayName("the last page reports itself as last")
        void lastPageKnowsIt() {
            Page<SettlementAccount> last = repository.search(
                    SettlementAccountQuery.all(),
                    PageRequest.of(2, 2, "accountId", SortDirection.ASC));

            assertThat(last.content()).hasSize(2);
            assertThat(last.last()).isTrue();
            assertThat(last.first()).isFalse();
        }

        @Test
        @DisplayName("a page beyond the end is empty rather than an error")
        void pageBeyondEndIsEmpty() {
            // A caller who keeps incrementing gets an empty page, not a 500. That
            // makes "loop until empty" a safe client pattern.
            Page<SettlementAccount> beyond = repository.search(
                    SettlementAccountQuery.all(),
                    PageRequest.of(99, 20, "accountId", SortDirection.ASC));

            assertThat(beyond.content()).isEmpty();
            assertThat(beyond.totalElements()).isEqualTo(6);
        }

        @Test
        @DisplayName("sorts descending when asked")
        void sortsDescending() {
            Page<SettlementAccount> page = repository.search(
                    SettlementAccountQuery.all(),
                    PageRequest.of(0, 6, "liquidityBufferAmount", SortDirection.DESC));

            // JPY 500,000,000 is the largest raw number in the seed data. Note it
            // is NOT the largest economic value - sorting money across currencies
            // is meaningless without conversion, which is a genuine domain trap
            // and something Layer 5 has to deal with properly.
            assertThat(page.content().get(0).accountId()).isEqualTo("ACC-JP-0001");
        }

        @Test
        @DisplayName("paging by a non-unique column never repeats or skips a row")
        void pagingByNonUniqueColumnIsStable() {
            // The subtle bug this pins down: two USD accounts have equal
            // currency_code. Sorting only by that column leaves their relative
            // order undefined, and Postgres is free to return it differently
            // between the page-1 query and the page-2 query - so a row can appear
            // on both pages, or on neither. The adapter appends accountId as a
            // tie-break to make the ordering TOTAL.
            List<String> seen = new ArrayList<>();
            for (int page = 0; page < 3; page++) {
                repository.search(
                                SettlementAccountQuery.all(),
                                PageRequest.of(page, 2, "currencyCode", SortDirection.ASC))
                        .content()
                        .forEach(account -> seen.add(account.accountId()));
            }

            assertThat(seen).hasSize(6).doesNotHaveDuplicates();
        }
    }

    // --- filtering -------------------------------------------------------

    @Nested
    @DisplayName("filtering")
    class Filtering {

        @Test
        @DisplayName("filters by currency in SQL, not in Java")
        void filtersByCurrency() {
            Page<SettlementAccount> page =
                    repository.search(SettlementAccountQuery.byCurrency("USD"), ALL_ROWS);

            assertThat(page.totalElements()).isEqualTo(2);
            assertThat(page.content())
                    .allSatisfy(account -> assertThat(account.currencyCode()).isEqualTo("USD"));
        }

        @Test
        @DisplayName("normalises a lower-case currency filter")
        void normalisesCurrencyCase() {
            assertThat(repository.search(SettlementAccountQuery.byCurrency("usd"), ALL_ROWS)
                    .totalElements()).isEqualTo(2);
        }

        @Test
        @DisplayName("filters by jurisdiction using the STRING-mapped enum")
        void filtersByJurisdiction() {
            Page<SettlementAccount> page =
                    repository.search(SettlementAccountQuery.byJurisdiction(Jurisdiction.EU), ALL_ROWS);

            assertThat(page.content()).singleElement()
                    .satisfies(account -> assertThat(account.accountId()).isEqualTo("ACC-EU-0001"));
        }

        @Test
        @DisplayName("combines every filter into a single query")
        void combinesFilters() {
            // This is the Layer 2 defect, dead. It used to fetch by currency and
            // then filter by jurisdiction in Java - correct answer, wrong reason:
            // it only worked because the result set was tiny.
            Page<SettlementAccount> matching = repository.search(
                    new SettlementAccountQuery("USD", Jurisdiction.US, "ATLAS-BANK-NA"), ALL_ROWS);
            assertThat(matching.totalElements()).isEqualTo(2);

            // A combination that matches nothing must return nothing, not the
            // union of the individual filters.
            Page<SettlementAccount> contradictory = repository.search(
                    new SettlementAccountQuery("USD", Jurisdiction.EU, null), ALL_ROWS);
            assertThat(contradictory.content()).isEmpty();
            assertThat(contradictory.totalElements()).isZero();
        }

        @Test
        @DisplayName("filters by legal entity")
        void filtersByLegalEntity() {
            Page<SettlementAccount> page = repository.search(
                    new SettlementAccountQuery(null, null, "ATLAS-BANK-APAC"), ALL_ROWS);

            assertThat(page.totalElements()).isEqualTo(2);
            assertThat(page.content())
                    .allSatisfy(a -> assertThat(a.legalEntity()).isEqualTo("ATLAS-BANK-APAC"));
        }

        @Test
        @DisplayName("a value that looks like SQL injection is bound as a parameter and matches nothing")
        void injectionAttemptIsHarmless() {
            // The Criteria API binds values as JDBC parameters; nothing is
            // concatenated into SQL text. So this is not "escaped" - it is
            // structurally incapable of changing the query.
            Page<SettlementAccount> page = repository.search(
                    new SettlementAccountQuery(null, null, "x' OR '1'='1"), ALL_ROWS);

            assertThat(page.content()).isEmpty();
        }
    }

    // --- single reads and writes -----------------------------------------

    @Test
    @DisplayName("reads an account back with its buffer as Money")
    void readsAccountWithMoneyBuffer() {
        SettlementAccount account = repository.findByAccountId("ACC-US-0001").orElseThrow();

        assertThat(account.legalEntity()).isEqualTo("ATLAS-BANK-NA");
        assertThat(account.jurisdiction()).isEqualTo(Jurisdiction.US);
        assertThat(account.residencyRegion()).isEqualTo("us-east");
    }

    @Test
    @DisplayName("normalises a JPY buffer to zero decimal places coming out of the database")
    void normalisesJpyScale() {
        // The column is NUMERIC(23,4), so the driver hands back 500000000.0000.
        // Money re-normalises to the currency's minor units - zero for yen. This is
        // why Money owns scale rather than the schema.
        SettlementAccount account = repository.findByAccountId("ACC-JP-0001").orElseThrow();

        assertThat(account.currencyCode()).isEqualTo("JPY");
        assertThat(account.liquidityBuffer().amount().scale()).isZero();
        assertThat(account.liquidityBuffer()).isEqualTo(Money.of("JPY", "500000000"));
    }

    @Test
    @DisplayName("returns empty rather than null for an unknown id")
    void unknownAccountReturnsEmpty() {
        assertThat(repository.findByAccountId("ACC-NOPE")).isEmpty();
    }

    @Test
    @DisplayName("persists a new buffer and reads it back in a later transaction")
    void persistsNewBuffer() {
        Money newBuffer = Money.of("GBP", "22500000.50");

        SettlementAccount updated = repository.updateLiquidityBuffer("ACC-GB-0001", newBuffer);
        assertThat(updated.liquidityBuffer()).isEqualTo(newBuffer);

        // Re-reading in a SEPARATE transaction is what makes this a real test.
        // Asserting on the returned object would pass even if nothing reached the
        // database.
        assertThat(repository.findByAccountId("ACC-GB-0001").orElseThrow().liquidityBuffer())
                .isEqualTo(newBuffer);

        // Restore. The database is not thrown away between runs, so tests must
        // leave the data as they found it.
        repository.updateLiquidityBuffer("ACC-GB-0001", Money.of("GBP", "15000000.00"));
    }

    @Test
    @DisplayName("rounds an over-precise amount to the currency's minor units")
    void roundsOverPreciseAmounts() {
        Money overPrecise = Money.of("SGD", "9000000.0050");
        assertThat(overPrecise).isEqualTo(Money.of("SGD", "9000000.00"));

        assertThat(repository.updateLiquidityBuffer("ACC-SG-0001", overPrecise)
                .liquidityBuffer().amount().scale()).isEqualTo(2);

        repository.updateLiquidityBuffer("ACC-SG-0001", Money.of("SGD", "9000000.00"));
    }

    /**
     * This test found a real bug in Layer 2 and is kept for that reason.
     *
     * <p>The adapter originally threw {@code IllegalArgumentException}. It never
     * arrived as one: {@code @Repository} enables Spring's persistence exception
     * translation, which rewrites any {@code IllegalArgumentException} leaving a
     * repository into {@code InvalidDataAccessApiUsageException} - because the JPA
     * spec uses that exception for API misuse. The web layer's handler therefore
     * stopped matching and a validation failure would have been served as a
     * <b>500 instead of a 400</b>.
     */
    @Test
    @DisplayName("refuses a buffer in the wrong currency, with a domain exception the framework will not rewrite")
    void refusesCurrencyMismatch() {
        assertThatThrownBy(() ->
                repository.updateLiquidityBuffer("ACC-US-0001", Money.of("EUR", "1000.00")))
                .isInstanceOf(CurrencyMismatchException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("EUR");
    }

    @Test
    @DisplayName("updating an unknown account raises NoSuchElementException")
    void updatingUnknownAccountThrows() {
        assertThatThrownBy(() ->
                repository.updateLiquidityBuffer("ACC-NOPE", Money.of("USD", "1000.00")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("ACC-NOPE");
    }
}
