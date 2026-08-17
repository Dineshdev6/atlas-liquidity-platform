package com.atlas.liquidity.refdata.api;

import com.atlas.liquidity.refdata.domain.SettlementAccount;

/**
 * The wire representation of a settlement account.
 *
 * <p><b>Why {@code liquidityBuffer} is a String and not a number.</b> This
 * looks wrong the first time you see it, and it is one of the more valuable
 * things in this layer.
 *
 * <p>JSON has exactly one numeric type, and every JavaScript client parses it
 * as an IEEE-754 double. Doubles hold 15-17 significant decimal digits.
 * {@code 500000000} is safe; a JPY position of {@code 12345678901234567} is
 * not - it silently becomes {@code 12345678901234568} in the browser, and no
 * error is raised anywhere. Serialise money as a string and the client decides
 * how to parse it, with full precision intact.
 *
 * <p>This is not theoretical. It is the reason financial APIs from Stripe to
 * the major card networks either use strings or integer minor units. Being able
 * to explain it is a strong signal that you have thought about money as data
 * rather than as a number.
 *
 * <p>The currency is not repeated on the buffer because an account holds
 * exactly one currency - {@code currencyCode} already applies to it. That
 * invariant is enforced in the domain record's constructor, so the API can rely
 * on it rather than restate it.
 */
public record SettlementAccountResponse(
        String accountId,
        String accountNumber,
        String legalEntity,
        String currencyCode,
        String jurisdiction,
        String residencyRegion,
        String bic,
        String liquidityBuffer) {

    public static SettlementAccountResponse from(SettlementAccount account) {
        return new SettlementAccountResponse(
                account.accountId(),
                account.accountNumber(),
                account.legalEntity(),
                account.currencyCode(),
                account.jurisdiction().name(),
                account.residencyRegion(),
                account.bic(),
                account.liquidityBuffer().amount().toPlainString());
    }
}
