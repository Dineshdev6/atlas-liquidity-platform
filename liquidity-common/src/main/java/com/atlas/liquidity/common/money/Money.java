package com.atlas.liquidity.common.money;

import java.io.Serializable;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An immutable amount of money in a single currency.
 *
 * <p><b>Why this class exists at all.</b> The single most common defect in
 * financial software is representing money as a {@code double}. Binary floating
 * point cannot represent 0.1 exactly, so {@code 0.1 + 0.2 != 0.3}. On an
 * intraday liquidity platform processing millions of cash movements a day, that
 * error compounds into a position that does not reconcile. We use
 * {@link BigDecimal} and we never expose the raw value.
 *
 * <p><b>Why rounding is HALF_EVEN.</b> "Banker's rounding" rounds a midpoint to
 * the nearest even digit rather than always up. Over a large number of
 * operations, always-up rounding introduces a systematic upward bias; HALF_EVEN
 * does not. It is the default expectation in financial systems and in
 * IEEE 754 decimal arithmetic.
 *
 * <p><b>Why scale is derived from the currency.</b> USD has 2 minor units, JPY
 * has 0, BHD has 3. Hard-coding two decimal places silently corrupts yen.
 *
 * <p>This class is deliberately a plain final class rather than a record: a
 * record would expose a canonical constructor that bypasses our normalisation,
 * and its generated {@code equals} would treat {@code 10.00} and {@code 10.000}
 * as different values.
 */
public final class Money implements Comparable<Money>, Serializable {

    private static final long serialVersionUID = 1L;

    private final BigDecimal amount;
    private final Currency currency;

    private Money(BigDecimal amount, Currency currency) {
        this.currency = Objects.requireNonNull(currency, "currency must not be null");
        this.amount = normalise(amount, currency);
    }

    public static Money of(Currency currency, BigDecimal amount) {
        return new Money(Objects.requireNonNull(amount, "amount must not be null"), currency);
    }

    /**
     * Convenience factory taking an ISO-4217 code and a decimal string.
     * A string is used rather than a double on purpose: {@code new BigDecimal(0.1)}
     * yields 0.1000000000000000055511151231257827021181583404541015625.
     */
    public static Money of(String currencyCode, String amount) {
        return new Money(new BigDecimal(amount), toCurrency(currencyCode));
    }

    public static Money zero(String currencyCode) {
        return new Money(BigDecimal.ZERO, toCurrency(currencyCode));
    }

    public Money plus(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.add(other.amount), this.currency);
    }

    public Money minus(Money other) {
        requireSameCurrency(other);
        return new Money(this.amount.subtract(other.amount), this.currency);
    }

    public Money negate() {
        return new Money(this.amount.negate(), this.currency);
    }

    public Money abs() {
        return new Money(this.amount.abs(), this.currency);
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    /** True when this amount is strictly greater than {@code other}. */
    public boolean isGreaterThan(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount) > 0;
    }

    public boolean isLessThan(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount) < 0;
    }

    public BigDecimal amount() {
        return amount;
    }

    public Currency currency() {
        return currency;
    }

    public String currencyCode() {
        return currency.getCurrencyCode();
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return this.amount.compareTo(other.amount);
    }

    /**
     * Value equality. Because every instance is normalised to the currency's
     * scale at construction, {@code compareTo == 0} and {@code equals} agree -
     * which is what {@link Comparable} asks for and what {@code BigDecimal}
     * itself famously fails to provide.
     */
    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Money other)) {
            return false;
        }
        return currency.equals(other.currency) && amount.equals(other.amount);
    }

    @Override
    public int hashCode() {
        return Objects.hash(amount, currency);
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }

    // --- internals -------------------------------------------------------

    private void requireSameCurrency(Money other) {
        Objects.requireNonNull(other, "other must not be null");
        if (!this.currency.equals(other.currency)) {
            throw new CurrencyMismatchException(this.currencyCode(), other.currencyCode());
        }
    }

    private static BigDecimal normalise(BigDecimal value, Currency currency) {
        int scale = currency.getDefaultFractionDigits();
        if (scale < 0) {
            // Pseudo-currencies such as XAU (gold) report -1. We refuse them
            // rather than guess a scale and silently mis-round.
            throw new IllegalArgumentException(
                    "Currency " + currency.getCurrencyCode() + " has no defined minor unit and is not supported");
        }
        return value.setScale(scale, RoundingMode.HALF_EVEN);
    }

    private static Currency toCurrency(String currencyCode) {
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        try {
            return Currency.getInstance(currencyCode);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown ISO-4217 currency code: " + currencyCode, e);
        }
    }
}
