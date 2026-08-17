package com.atlas.liquidity.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Tests for {@link Money}.
 *
 * <p>Read these as much as the production code. The structure here is the one
 * we use for the rest of the build:
 * <ul>
 *   <li>{@code @Nested} classes group behaviour, so the test report reads like
 *       a specification rather than a flat list of method names.</li>
 *   <li>{@code @DisplayName} states the expected behaviour in English.</li>
 *   <li>{@code @ParameterizedTest} covers a table of cases without duplication.</li>
 *   <li>AssertJ ({@code assertThat}) is used rather than JUnit's assertions -
 *       fluent, and the failure messages are far more informative.</li>
 * </ul>
 *
 * <p>Interview note: if asked "how do you decide what to test", the answer this
 * class demonstrates is - the boundaries and the invariants, not the getters.
 */
class MoneyTest {

    @Nested
    @DisplayName("construction")
    class Construction {

        @Test
        @DisplayName("normalises scale to the currency's minor units")
        void normalisesScaleToCurrencyMinorUnits() {
            assertThat(Money.of("USD", "10").amount()).isEqualTo(new BigDecimal("10.00"));
            assertThat(Money.of("JPY", "10").amount()).isEqualTo(new BigDecimal("10"));
            assertThat(Money.of("BHD", "10").amount()).isEqualTo(new BigDecimal("10.000"));
        }

        @ParameterizedTest(name = "{0} rounds to {1}")
        @DisplayName("uses banker's rounding on the midpoint")
        @CsvSource({
            "2.345, 2.34",   // midpoint down to even
            "2.355, 2.36",   // midpoint up to even
            "2.341, 2.34",
            "2.346, 2.35"
        })
        void usesBankersRounding(String input, String expected) {
            assertThat(Money.of("USD", input)).isEqualTo(Money.of("USD", expected));
        }

        @Test
        @DisplayName("rejects an unknown currency code")
        void rejectsUnknownCurrency() {
            assertThatThrownBy(() -> Money.of("XYZ", "1.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Unknown ISO-4217 currency code");
        }

        @Test
        @DisplayName("rejects a currency with no defined minor unit")
        void rejectsCurrencyWithoutMinorUnit() {
            // XAU (gold) reports -1 fraction digits. Guessing a scale here would
            // silently mis-round, so we refuse it instead.
            assertThatThrownBy(() -> Money.of("XAU", "1.00"))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no defined minor unit");
        }
    }

    @Nested
    @DisplayName("arithmetic")
    class Arithmetic {

        @Test
        @DisplayName("adds and subtracts within the same currency")
        void addsAndSubtracts() {
            Money opening = Money.of("USD", "1000.00");
            Money credit = Money.of("USD", "250.50");

            assertThat(opening.plus(credit)).isEqualTo(Money.of("USD", "1250.50"));
            assertThat(opening.minus(credit)).isEqualTo(Money.of("USD", "749.50"));
        }

        @Test
        @DisplayName("does not lose precision the way a double would")
        void avoidsFloatingPointError() {
            // 0.1 + 0.2 == 0.30000000000000004 in double arithmetic.
            Money result = Money.of("USD", "0.10").plus(Money.of("USD", "0.20"));
            assertThat(result).isEqualTo(Money.of("USD", "0.30"));
        }

        @Test
        @DisplayName("refuses to combine different currencies")
        void refusesCurrencyMismatch() {
            assertThatThrownBy(() -> Money.of("USD", "1.00").plus(Money.of("EUR", "1.00")))
                    .isInstanceOf(CurrencyMismatchException.class)
                    .hasMessageContaining("USD")
                    .hasMessageContaining("EUR");
        }

        @Test
        @DisplayName("is immutable - operations return new instances")
        void isImmutable() {
            Money original = Money.of("USD", "100.00");
            original.plus(Money.of("USD", "50.00"));

            assertThat(original).isEqualTo(Money.of("USD", "100.00"));
        }
    }

    @Nested
    @DisplayName("sign and comparison")
    class SignAndComparison {

        @Test
        @DisplayName("detects an intraday overdraft as a negative position")
        void detectsNegativePosition() {
            Money position = Money.of("USD", "500.00").minus(Money.of("USD", "800.00"));

            assertThat(position.isNegative()).isTrue();
            assertThat(position).isEqualTo(Money.of("USD", "-300.00"));
            assertThat(position.abs()).isEqualTo(Money.of("USD", "300.00"));
        }

        @Test
        @DisplayName("compares amounts in the same currency")
        void comparesAmounts() {
            Money buffer = Money.of("USD", "1000000.00");
            Money position = Money.of("USD", "750000.00");

            assertThat(position.isLessThan(buffer)).isTrue();
            assertThat(buffer.isGreaterThan(position)).isTrue();
            assertThat(position.compareTo(buffer)).isNegative();
        }

        @Test
        @DisplayName("treats equal value as equal regardless of input scale")
        void equalsIgnoresInputScale() {
            // BigDecimal itself says 10.0 != 10.00 under equals(). Money must not.
            assertThat(Money.of("USD", "10")).isEqualTo(Money.of("USD", "10.00"));
            assertThat(Money.of("USD", "10")).hasSameHashCodeAs(Money.of("USD", "10.0000"));
        }
    }
}
