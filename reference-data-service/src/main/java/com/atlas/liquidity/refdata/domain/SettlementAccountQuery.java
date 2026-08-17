package com.atlas.liquidity.refdata.domain;

import java.util.Locale;

/**
 * The filter criteria for a settlement account search. Every field is optional;
 * {@code null} means "do not filter on this".
 *
 * <p><b>Why one object rather than a method per combination.</b> Layer 2 had
 * {@code findByCurrency} and {@code findByJurisdiction}, and the controller
 * combined them by fetching one set and filtering the other in Java - a defect
 * I left in deliberately. The obvious fix is
 * {@code findByCurrencyCodeAndJurisdiction}, and then the next request is "also
 * by legal entity", and you need
 * {@code findByCurrencyCodeAndJurisdictionAndLegalEntity} plus every other
 * subset. Three optional filters is already <b>eight</b> combinations; four is
 * sixteen. That is a combinatorial explosion, and it is exactly where Spring
 * Data's derived query methods stop being the right tool.
 *
 * <p>One criteria object plus one Specification handles every combination with
 * a single query, and adding a fifth filter is one more {@code if}. Knowing
 * where that line sits - and saying so unprompted - reads as experience rather
 * than recall.
 *
 * <p>Normalisation happens in the constructor, so no caller and no downstream
 * query has to remember to trim or upper-case anything. Blank strings become
 * {@code null}, because {@code ?currency=} in a query string means "the caller
 * did not supply a currency", not "find accounts whose currency is empty".
 *
 * @param currencyCode ISO-4217 code, upper-cased; null for any
 * @param jurisdiction regulatory jurisdiction; null for any
 * @param legalEntity  owning legal entity, exact match; null for any
 */
public record SettlementAccountQuery(
        String currencyCode,
        Jurisdiction jurisdiction,
        String legalEntity) {

    public SettlementAccountQuery {
        String normalisedCurrency = normalise(currencyCode);
        currencyCode = normalisedCurrency == null ? null : normalisedCurrency.toUpperCase(Locale.ROOT);
        legalEntity = normalise(legalEntity);

        if (currencyCode != null && currencyCode.length() != 3) {
            throw new IllegalArgumentException(
                    "currency must be a 3-letter ISO-4217 code, got: " + currencyCode);
        }
    }

    /** Matches everything. */
    public static SettlementAccountQuery all() {
        return new SettlementAccountQuery(null, null, null);
    }

    public static SettlementAccountQuery byCurrency(String currencyCode) {
        return new SettlementAccountQuery(currencyCode, null, null);
    }

    public static SettlementAccountQuery byJurisdiction(Jurisdiction jurisdiction) {
        return new SettlementAccountQuery(null, jurisdiction, null);
    }

    /** True when no filter is set - useful for skipping work or for logging. */
    public boolean isUnfiltered() {
        return currencyCode == null && jurisdiction == null && legalEntity == null;
    }

    private static String normalise(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
