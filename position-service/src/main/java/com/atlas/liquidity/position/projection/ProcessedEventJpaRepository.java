package com.atlas.liquidity.position.projection;

import java.time.OffsetDateTime;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

/** The consumer's record of what it has already handled. */
public interface ProcessedEventJpaRepository extends JpaRepository<ProcessedEventEntity, String> {

    /**
     * Deletes records older than {@code cutoff}.
     *
     * <p>Nothing calls this yet; the scheduled job belongs in Layer 7. It exists
     * so that "what stops this table growing forever?" has an answer in the code
     * rather than only in a comment - and the answer has a real constraint on it:
     * the retention must comfortably exceed the topic's retention, or an event
     * replayed from the far end of the log would be applied a second time.
     */
    @Modifying
    @Query("delete from ProcessedEventEntity p where p.processedAt < :cutoff")
    int deleteProcessedBefore(OffsetDateTime cutoff);
}
