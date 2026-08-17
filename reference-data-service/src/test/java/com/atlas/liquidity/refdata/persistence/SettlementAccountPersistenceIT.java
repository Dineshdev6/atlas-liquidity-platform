package com.atlas.liquidity.refdata.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.atlas.liquidity.common.money.CurrencyMismatchException;
import com.atlas.liquidity.common.money.Money;
import com.atlas.liquidity.refdata.domain.Jurisdiction;
import com.atlas.liquidity.refdata.domain.SettlementAccount;
import com.atlas.liquidity.refdata.domain.SettlementAccountRepository;
import com.atlas.liquidity.refdata.support.AbstractPostgresIntegrationTest;
import java.util.NoSuchElementException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Integration tests for the JPA adapter against a real Postgres.
 *
 * <p>Named {@code ...IT} rather than {@code ...Test}, so Failsafe runs it in the
 * {@code integration-test} phase and Surefire leaves it alone. {@code mvn test}
 * skips it; {@code mvn verify} runs it.
 *
 * <p>What these tests prove is broader than the assertions suggest. Every one of
 * them silently depends on the Flyway migrations applying cleanly, the Hibernate
 * mappings validating against the real schema, the constraints existing, and the
 * connection pool working. If V1 had a syntax error or the entity named a column
 * that does not exist, none of this would reach its first assertion.
 */
@SpringBootTest
class SettlementAccountPersistenceIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private SettlementAccountRepository repository;

    @Test
    @DisplayName("Flyway applies the migrations and seeds six accounts")
    void migrationsApplyAndSeedData() {
        assertThat(repository.findAll()).hasSize(6);
    }

    @Test
    @DisplayName("the injected repository is the JPA adapter, not a test double")
    void usesTheJpaAdapter() {
        // We assert on the class NAME rather than with isInstanceOf, because
        // @Transactional means Spring hands us a generated proxy rather than the
        // bare object. The proxy's name still contains the target class name,
        // usually with "$$SpringCGLIB$$" appended - which is itself worth seeing
        // once, since "why does my class name have $$ in the stack trace" is a
        // question every Spring developer asks eventually.
        assertThat(repository.getClass().getName()).contains("JpaSettlementAccountRepositoryAdapter");
    }

    @Test
    @DisplayName("reads an account back with its buffer as Money")
    void readsAccountWithMoneyBuffer() {
        SettlementAccount account = repository.findByAccountId("ACC-US-0001").orElseThrow();

        assertThat(account.legalEntity()).isEqualTo("ATLAS-BANK-NA");
        assertThat(account.jurisdiction()).isEqualTo(Jurisdiction.US);
        assertThat(account.residencyRegion()).isEqualTo("us-east");
        assertThat(account.liquidityBuffer()).isEqualTo(Money.of("USD", "25000000.00"));
    }

    @Test
    @DisplayName("normalises a JPY buffer to zero decimal places on the way out of the database")
    void normalisesJpyScaleFromDatabase() {
        // The column is NUMERIC(23,4), so the driver hands back 500000000.0000.
        // Money re-normalises to the currency's minor units - zero for yen.
        // This is the whole reason Money owns scale rather than the schema.
        SettlementAccount account = repository.findByAccountId("ACC-JP-0001").orElseThrow();

        assertThat(account.currencyCode()).isEqualTo("JPY");
        assertThat(account.liquidityBuffer().amount().scale()).isZero();
        assertThat(account.liquidityBuffer()).isEqualTo(Money.of("JPY", "500000000"));
    }

    @Test
    @DisplayName("filters by currency in SQL, not in Java")
    void filtersByCurrency() {
        assertThat(repository.findByCurrency("USD"))
                .hasSize(2)
                .allSatisfy(account -> assertThat(account.currencyCode()).isEqualTo("USD"));

        // Case-insensitive at the boundary, uppercase in the query.
        assertThat(repository.findByCurrency("usd")).hasSize(2);
    }

    @Test
    @DisplayName("filters by jurisdiction using the STRING-mapped enum")
    void filtersByJurisdiction() {
        assertThat(repository.findByJurisdiction(Jurisdiction.EU))
                .singleElement()
                .satisfies(account -> assertThat(account.accountId()).isEqualTo("ACC-EU-0001"));
    }

    @Test
    @DisplayName("returns empty rather than null for an unknown id")
    void unknownAccountReturnsEmpty() {
        assertThat(repository.findByAccountId("ACC-NOPE")).isEmpty();
    }

    @Test
    @DisplayName("persists a new liquidity buffer and reads it back in a later transaction")
    void updatesLiquidityBuffer() {
        Money newBuffer = Money.of("GBP", "22500000.50");

        SettlementAccount updated = repository.updateLiquidityBuffer("ACC-GB-0001", newBuffer);
        assertThat(updated.liquidityBuffer()).isEqualTo(newBuffer);

        // Re-reading in a SEPARATE transaction is what makes this a real test.
        // Asserting on the object the write method returned would pass even if
        // nothing ever reached the database.
        SettlementAccount reloaded = repository.findByAccountId("ACC-GB-0001").orElseThrow();
        assertThat(reloaded.liquidityBuffer()).isEqualTo(newBuffer);

        // Restore, so test ordering cannot affect anything else. This matters
        // now that the database is not thrown away after every run.
        repository.updateLiquidityBuffer("ACC-GB-0001", Money.of("GBP", "15000000.00"));
    }

    @Test
    @DisplayName("rounds an over-precise amount to the currency's minor units")
    void roundsOverPreciseAmounts() {
        // Four decimal places fit the column but not the currency. Money rounds
        // HALF_EVEN at construction, so the database never sees a value the
        // domain considers invalid.
        Money overPrecise = Money.of("SGD", "9000000.0050");
        assertThat(overPrecise).isEqualTo(Money.of("SGD", "9000000.00"));

        SettlementAccount updated = repository.updateLiquidityBuffer("ACC-SG-0001", overPrecise);
        assertThat(updated.liquidityBuffer().amount().scale()).isEqualTo(2);

        repository.updateLiquidityBuffer("ACC-SG-0001", Money.of("SGD", "9000000.00"));
    }

    /**
     * This test found a real bug, and it is worth remembering why.
     *
     * <p>The adapter originally threw {@code IllegalArgumentException} here. It
     * never arrived as one. {@code @Repository} enables Spring's persistence
     * exception translation, which - because the JPA spec uses
     * {@code IllegalArgumentException} for API misuse - rewrites <em>any</em>
     * {@code IllegalArgumentException} leaving a repository into
     * {@code InvalidDataAccessApiUsageException}. The web layer's
     * {@code @ExceptionHandler(IllegalArgumentException.class)} therefore
     * stopped matching, and a validation failure would have been served to
     * clients as a <b>500 instead of a 400</b>.
     *
     * <p>The fix was to throw a domain exception the framework has no claim on.
     * The lesson: inside a framework's territory, generic JDK exceptions are not
     * yours.
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
