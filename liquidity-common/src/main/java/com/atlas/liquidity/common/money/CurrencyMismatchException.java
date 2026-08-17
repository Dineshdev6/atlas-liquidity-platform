package com.atlas.liquidity.common.money;

/**
 * Thrown when an arithmetic operation is attempted across two different
 * currencies.
 *
 * <p>This is an unchecked exception on purpose. Adding USD to EUR is a
 * programming error, not a recoverable runtime condition - there is no sensible
 * {@code catch} block for it, and forcing callers to declare it would pollute
 * every signature in the domain model.
 */
public class CurrencyMismatchException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String left;
    private final String right;

    public CurrencyMismatchException(String left, String right) {
        super("Cannot combine amounts in different currencies: " + left + " and " + right);
        this.left = left;
        this.right = right;
    }

    public String left() {
        return left;
    }

    public String right() {
        return right;
    }
}
