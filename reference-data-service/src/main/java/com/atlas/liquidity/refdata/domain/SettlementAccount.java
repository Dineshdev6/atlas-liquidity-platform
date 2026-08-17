package com.atlas.liquidity.refdata.domain;

import java.util.Objects;

/**
 * A settlement (nostro) account: an account our legal entity holds at a
 * correspondent bank or central bank, through which cash actually moves.
 *
 * <p>Intraday liquidity is measured per account, per currency, per legal entity.
 * This record is the identity half of that; the balance half arrives in Layer 5.
 *
 * <p>Modelled as a {@code record} because it is an immutable data carrier with
 * value semantics. Contrast with {@code Money} in {@code liquidity-common},
 * which is a class precisely because it needs a normalising constructor. Knowing
 * when a record is and is not the right tool is a good thing to be able to
 * articulate.
 *
 * @param accountId      stable internal identifier, e.g. {@code ACC-US-0001}
 * @param accountNumber  the account number at the correspondent
 * @param legalEntity    the entity that owns the account, e.g. {@code ATLAS-BANK-NA}
 * @param currencyCode   ISO-4217 code; an account holds exactly one currency
 * @param jurisdiction   regulatory jurisdiction, which drives data residency
 * @param bic            SWIFT BIC of the correspondent institution
 */
public record SettlementAccount(
        String accountId,
        String accountNumber,
        String legalEntity,
        String currencyCode,
        Jurisdiction jurisdiction,
        String bic) {

    /**
     * Compact constructor. Records let you validate invariants here, so an
     * invalid {@code SettlementAccount} can never exist - the object is either
     * fully valid or it was never constructed. This is "make illegal states
     * unrepresentable", and it is far stronger than validating at the API edge.
     */
    public SettlementAccount {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(accountNumber, "accountNumber must not be null");
        Objects.requireNonNull(legalEntity, "legalEntity must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        Objects.requireNonNull(jurisdiction, "jurisdiction must not be null");

        if (currencyCode.length() != 3) {
            throw new IllegalArgumentException("currencyCode must be a 3-letter ISO-4217 code, got: " + currencyCode);
        }
    }

    /** Convenience for Layer 11 routing: where this account's data may live. */
    public String residencyRegion() {
        return jurisdiction.residencyRegion();
    }
}
