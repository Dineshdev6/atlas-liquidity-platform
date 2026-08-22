package com.atlas.liquidity.refdata.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on {@code @Scheduled}, which is off until something asks for it.
 *
 * <p>Kept as its own class rather than annotating the application class, so that
 * a test can exclude scheduling without excluding the whole application, and so
 * that there is one obvious place to look when someone asks "what runs on a
 * timer in this service?".
 *
 * <p><b>The thing to know before Layer 7.</b> Spring's default scheduler is a
 * single-threaded pool. Every {@code @Scheduled} method in the application takes
 * turns on one thread, so one slow job delays every other one. With a single job
 * that is fine and simple; the moment there are three, it stops being fine and
 * nothing warns you.
 *
 * <p><b>The bigger thing.</b> Run three instances of this service and all three
 * run every scheduled job. For the outbox relay that is survivable - two
 * instances publishing the same event produces a duplicate, and consumers are
 * required to be idempotent anyway - but for a job that sends an email or moves
 * money it is not. The general answers are a distributed lock (ShedLock, or a
 * row in the database) or leader election. Layer 7 does this properly; know now
 * that "@Scheduled just works" stops being true at two instances.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
