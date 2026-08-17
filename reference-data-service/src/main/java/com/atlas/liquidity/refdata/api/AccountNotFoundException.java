package com.atlas.liquidity.refdata.api;

/**
 * Raised when a requested settlement account does not exist.
 *
 * <p>Note what this class does <em>not</em> do: it carries no
 * {@code @ResponseStatus} annotation. Mapping a domain condition to an HTTP
 * status code is the web layer's job, and it happens in one place -
 * {@link GlobalExceptionHandler}. Scattering {@code @ResponseStatus} across
 * exception classes works, but it distributes your API's error contract over
 * dozens of files where nobody can review it as a whole.
 */
public class AccountNotFoundException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final String accountId;

    public AccountNotFoundException(String accountId) {
        super("No settlement account found with id: " + accountId);
        this.accountId = accountId;
    }

    public String accountId() {
        return accountId;
    }
}
