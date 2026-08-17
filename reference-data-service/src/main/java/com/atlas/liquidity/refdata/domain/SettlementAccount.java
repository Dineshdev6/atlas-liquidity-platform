package com.atlas.liquidity.refdata.domain;

import com.atlas.liquidity.common.money.Money;
import java.util.Objects;

/**
 * A settlement (nostro) account: an account our legal entity holds at a
 * correspondent bank or central bank, through which cash actually moves.
 *
 * <p><b>Layer 2 note - this is still a plain Java record with no JPA
 * annotations, and that is deliberate.</b> The persistence model lives next
 * door in {@code persistence.SettlementAccountEntity}. Most tutorials collapse
 * the two into one annotated class, and for a CRUD app that is a reasonable
 * trade. Here we keep them apart for three concrete reasons:
 *
 * <ul>
 *   <li>This record is <em>immutable and always valid</em> - the compact
 *       constructor below guarantees it. A JPA entity cannot be: the framework
 *       requires a no-arg constructor and mutable fields, so it can always
 *       exist in a half-built, invalid state.</li>
 *   <li>{@link Money} would have to carry JPA annotations, which means
 *       {@code liquidity-common} - a library every future service depends on -
 *       would drag Hibernate onto everyone's classpath.</li>
 *   <li>The schema changes for storage reasons (an index, a partition, a column
 *       split); the domain changes for business reasons. Welding them together
 *       means every storage decision ripples into business code.</li>
 * </ul>
 *
 * <p>The cost is a mapping step in the adapter. That cost is real, and on a
 * simple CRUD service it is not worth paying. Knowing <em>when</em> it is worth
 * paying is the actual senior-engineer judgement here - and "it depends, here
 * is what it depends on" is the answer an interviewer is listening for.
 *
 * @param accountId       stable internal identifier, e.g. {@code ACC-US-0001}
 * @param accountNumber   the account number at the correspondent
 * @param legalEntity     the entity that owns the account
 * @param currencyCode    ISO-4217 code; an account holds exactly one currency
 * @param jurisdiction    regulatory jurisdiction, which drives data residency
 * @param bic             SWIFT BIC of the correspondent institution
 * @param liquidityBuffer minimum intraday cash this account must retain
 */
public record SettlementAccount(
        String accountId,
        String accountNumber,
        String legalEntity,
        String currencyCode,
        Jurisdiction jurisdiction,
        String bic,
        Money liquidityBuffer) {

    public SettlementAccount {
        Objects.requireNonNull(accountId, "accountId must not be null");
        Objects.requireNonNull(accountNumber, "accountNumber must not be null");
        Objects.requireNonNull(legalEntity, "legalEntity must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        Objects.requireNonNull(jurisdiction, "jurisdiction must not be null");
        Objects.requireNonNull(liquidityBuffer, "liquidityBuffer must not be null");

        if (currencyCode.length() != 3) {
            throw new IllegalArgumentException(
                    "currencyCode must be a 3-letter ISO-4217 code, got: " + currencyCode);
        }

        // The invariant that makes this type worth having. An account holds one
        // currency; a buffer denominated in anything else is meaningless. Check
        // it once, here, and no downstream code ever has to wonder.
        if (!liquidityBuffer.currencyCode().equals(currencyCode)) {
            throw new IllegalArgumentException(
                    "liquidityBuffer currency " + liquidityBuffer.currencyCode()
                            + " does not match account currency " + currencyCode);
        }

        if (liquidityBuffer.isNegative()) {
            throw new IllegalArgumentException("liquidityBuffer must not be negative");
        }
    }

    /** Convenience for Layer 11 routing: where this account's data may live. */
    public String residencyRegion() {
        return jurisdiction.residencyRegion();
    }

    /**
     * Returns a copy with a different buffer. Records are immutable, so "change"
     * always means "produce a new one" - which is why there is no setter and no
     * way for a caller to leave this object in a half-updated state.
     */
    public SettlementAccount withLiquidityBuffer(Money newBuffer) {
        return new SettlementAccount(
                accountId, accountNumber, legalEntity, currencyCode, jurisdiction, bic, newBuffer);
    }
}
