package com.atlas.liquidity.position.api;

import com.atlas.liquidity.position.projection.AccountPositionEntity;
import java.time.OffsetDateTime;

/**
 * One account's position, as this service sees it.
 *
 * <p>{@code currentBuffer} is a String for the same reason it is in
 * reference-data-service: JSON has one numeric type and most clients parse it as
 * an IEEE-754 double, which loses precision on large values.
 *
 * <p>{@code appliedCount} is exposed deliberately. It is not business data - it
 * is how you can see, from outside, that a duplicate delivery changed nothing.
 * Send the same event twice and this number does not move.
 */
public record PositionResponse(
        String accountId,
        String currencyCode,
        String currentBuffer,
        String lastEventId,
        OffsetDateTime lastEventAt,
        long appliedCount,
        OffsetDateTime updatedAt) {

    public static PositionResponse from(AccountPositionEntity entity) {
        return new PositionResponse(
                entity.getAccountId(),
                entity.getCurrencyCode(),
                entity.getCurrentBuffer().toPlainString(),
                entity.getLastEventId(),
                entity.getLastEventAt(),
                entity.getAppliedCount(),
                entity.getUpdatedAt());
    }
}
