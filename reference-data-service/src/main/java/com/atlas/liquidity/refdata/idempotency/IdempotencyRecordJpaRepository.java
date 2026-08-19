package com.atlas.liquidity.refdata.idempotency;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** Spring Data access to the idempotency key register. */
interface IdempotencyRecordJpaRepository extends JpaRepository<IdempotencyRecordEntity, String> {

    /**
     * Deletes expired keys.
     *
     * <p>{@code @Modifying} is required for anything that is not a SELECT -
     * without it Spring Data refuses to execute the statement. A bulk delete like
     * this bypasses the persistence context entirely, which is what makes it fast
     * and also what makes it dangerous: entities already loaded in the current
     * session will not know they were deleted. Here nothing else is loaded, so it
     * is safe.
     *
     * <p>Nothing calls this yet. A scheduled job belongs in Layer 7 alongside the
     * other operational concerns; the method exists now so the migration's index
     * has an obvious purpose.
     */
    @Modifying
    @Query("delete from IdempotencyRecordEntity r where r.expiresAt < :cutoff")
    int deleteExpired(OffsetDateTime cutoff);
}
