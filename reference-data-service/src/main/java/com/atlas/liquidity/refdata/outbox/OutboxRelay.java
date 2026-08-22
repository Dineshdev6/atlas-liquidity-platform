package com.atlas.liquidity.refdata.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs {@link OutboxPublisher} on a timer.
 *
 * <p>Nothing but scheduling lives here, and the separation is load-bearing rather
 * than tidy: calling a {@code @Transactional} method on the same instance
 * bypasses Spring's proxy and the annotation is silently ignored. See the class
 * comment on {@link OutboxPublisher} for what that would have cost.
 *
 * <p><b>This is polling, and the alternative deserves naming.</b> Change data
 * capture - Debezium tailing the database's write-ahead log and publishing outbox
 * inserts with no application code involved - has lower latency, adds no query
 * load, and is what a large bank is more likely to run. It also adds a
 * distributed system to operate, connector configuration, and a replication slot
 * that will fill the database's disk if it ever stops being consumed. Polling a
 * small, partially-indexed table once a second is the cheaper correct answer at
 * this scale. Being able to say why you did not choose the fancier option is
 * worth more than having chosen it.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxPublisher publisher;
    private final boolean enabled;

    OutboxRelay(OutboxPublisher publisher, @Value("${atlas.outbox.relay-enabled:true}") boolean enabled) {
        this.publisher = publisher;
        this.enabled = enabled;
    }

    /**
     * The scheduled trigger.
     *
     * <p><b>{@code fixedDelay}, not {@code fixedRate}.</b> Fixed delay waits the
     * interval after the previous run <em>finishes</em>; fixed rate tries to start
     * every interval regardless. If a run ever takes longer than the interval,
     * fixed rate queues overlapping executions and a slow database becomes a
     * pile-up. Fixed delay simply runs less often, which is what you want.
     *
     * <p><b>Every exception is caught here on purpose.</b> A {@code @Scheduled}
     * method that throws is not retried, and the failure is logged by the
     * framework rather than by you. Catching means a transient broker outage
     * costs one tick instead of quietly stalling event publication until somebody
     * notices.
     */
    @Scheduled(
            fixedDelayString = "${atlas.outbox.poll-interval-ms:1000}",
            initialDelayString = "${atlas.outbox.initial-delay-ms:5000}")
    public void poll() {
        if (!enabled) {
            return;
        }
        try {
            // Through the proxy, because OutboxPublisher is a different bean.
            // This is the line that makes @Transactional actually apply.
            publisher.publishPending();
        } catch (Exception e) {
            log.error("Outbox relay run failed; will retry on the next tick", e);
        }
    }
}
