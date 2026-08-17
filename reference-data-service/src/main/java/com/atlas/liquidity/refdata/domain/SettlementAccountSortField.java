package com.atlas.liquidity.refdata.domain;

import java.util.Arrays;
import java.util.Locale;

/**
 * The fields a caller is permitted to sort settlement accounts by.
 *
 * <p><b>This enum is a security control, not a convenience.</b> Spring Data will
 * happily sort by any property name you hand it, and it derives that name from
 * the request. Pass {@code ?sort=somethingWrong} and you get a
 * {@code PropertyReferenceException} - a 500, with a message that helpfully
 * enumerates your entity's real property names to whoever sent it. That is both
 * an availability bug and information disclosure, from a query parameter.
 *
 * <p>Worse in other stacks: where sorting is concatenated into SQL rather than
 * going through a criteria API, an unvalidated sort parameter is a genuine SQL
 * injection vector. JPA's Criteria API protects us from the injection, but not
 * from the 500.
 *
 * <p>So: an allow-list, in one place, with the API name and the entity property
 * name held together. Anything not on this list is rejected at the edge as a
 * 400 with a message naming the valid options.
 *
 * <p>Note the split between the two names. {@code liquidityBuffer} is what the
 * API calls it; {@code liquidityBufferAmount} is the column. Keeping both here
 * means the API contract and the schema can drift apart without either one
 * having to know about the other - the same reason the response DTO is separate
 * from the domain record.
 */
public enum SettlementAccountSortField {

    ACCOUNT_ID("accountId", "accountId"),
    ACCOUNT_NUMBER("accountNumber", "accountNumber"),
    LEGAL_ENTITY("legalEntity", "legalEntity"),
    CURRENCY_CODE("currencyCode", "currencyCode"),
    JURISDICTION("jurisdiction", "jurisdiction"),
    LIQUIDITY_BUFFER("liquidityBuffer", "liquidityBufferAmount");

    /** The stable default: a total order, so paging can never repeat or skip a row. */
    public static final SettlementAccountSortField DEFAULT = ACCOUNT_ID;

    private final String apiName;
    private final String entityProperty;

    SettlementAccountSortField(String apiName, String entityProperty) {
        this.apiName = apiName;
        this.entityProperty = entityProperty;
    }

    public String apiName() {
        return apiName;
    }

    public String entityProperty() {
        return entityProperty;
    }

    /**
     * Resolves a caller-supplied sort field name.
     *
     * @throws IllegalArgumentException if the name is not on the allow-list
     */
    public static SettlementAccountSortField parse(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT;
        }
        String normalised = value.trim();
        for (SettlementAccountSortField field : values()) {
            if (field.apiName.equalsIgnoreCase(normalised)) {
                return field;
            }
        }
        throw new IllegalArgumentException(
                "Unknown sort field '" + value + "'. Valid values: "
                        + Arrays.stream(values()).map(SettlementAccountSortField::apiName).toList());
    }

    /** Lower-cased API name, for logging and problem details. */
    @Override
    public String toString() {
        return apiName.toLowerCase(Locale.ROOT);
    }
}
