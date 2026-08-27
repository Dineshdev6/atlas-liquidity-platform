package com.atlas.liquidity.position;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the Position Service.
 *
 * <p>The second service in the platform, and the first one that exists purely to
 * consume. It has no dependency on reference-data-service, no access to its
 * database, and never calls it. Everything it knows arrived on a Kafka topic.
 *
 * <p>That independence is the property to demonstrate rather than claim: stop
 * reference-data-service entirely and this service keeps serving reads from its
 * own projection, because it is not asking anyone anything.
 */
@SpringBootApplication
public class PositionServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(PositionServiceApplication.class, args);
    }
}
