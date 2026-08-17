package com.atlas.liquidity.refdata.api;

import com.atlas.liquidity.refdata.domain.SettlementAccount;

/**
 * The wire representation of a settlement account.
 *
 * <p><b>Why a separate DTO instead of returning the domain record directly.</b>
 * They currently look almost identical, and it is tempting to skip this class.
 * Don't. The domain model changes for business reasons; the API contract changes
 * for consumer reasons, and consumers you do not control are depending on it.
 * Serialising the domain object directly welds those two rates of change
 * together - rename a domain field and you have silently broken every client.
 *
 * <p>It also controls exposure: when Layer 8 adds internal fields to
 * {@code SettlementAccount}, they do not leak to the API just because someone
 * forgot a {@code @JsonIgnore}.
 *
 * <p>Note {@code residencyRegion} is derived rather than stored - the API can
 * expose a computed view without the domain having to carry it.
 */
public record SettlementAccountResponse(
        String accountId,
        String accountNumber,
        String legalEntity,
        String currencyCode,
        String jurisdiction,
        String residencyRegion,
        String bic) {

    public static SettlementAccountResponse from(SettlementAccount account) {
        return new SettlementAccountResponse(
                account.accountId(),
                account.accountNumber(),
                account.legalEntity(),
                account.currencyCode(),
                account.jurisdiction().name(),
                account.residencyRegion(),
                account.bic());
    }
}
