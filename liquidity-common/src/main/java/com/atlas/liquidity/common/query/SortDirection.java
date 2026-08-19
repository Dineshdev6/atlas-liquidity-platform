package com.atlas.liquidity.common.query;

import java.util.Locale;

/** Sort direction for a paged query. */
public enum SortDirection {

    ASC,
    DESC;

    /**
     * Parses a caller-supplied direction, case-insensitively.
     *
     * <p>Throws {@link IllegalArgumentException} with a message that names the
     * valid values, rather than letting {@code Enum.valueOf} produce
     * "No enum constant com.atlas...DESCC". Whoever is integrating against you
     * at 2am will notice the difference.
     */
    public static SortDirection parse(String value) {
        if (value == null || value.isBlank()) {
            return ASC;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown sort direction '" + value + "'. Valid values: asc, desc", e);
        }
    }
}
