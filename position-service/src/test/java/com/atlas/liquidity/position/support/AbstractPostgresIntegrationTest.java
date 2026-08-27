package com.atlas.liquidity.position.support;

import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for this module's integration tests.
 *
 * <p>Same arrangement as reference-data-service: the {@code it} profile points
 * the suite at a separate database in the Compose Postgres. Testcontainers is not
 * used because Docker Desktop 4.86 ships Engine 29, whose API version the bundled
 * docker-java client cannot negotiate - see the reference-data-service equivalent
 * for the full story and how to restore it.
 *
 * <p>Note this module's test database is {@code atlas_positions_test}, separate
 * again from reference-data-service's. Sharing one would let a failure in one
 * suite corrupt the other, and would quietly reintroduce exactly the coupling the
 * separate service exists to avoid.
 */
@ActiveProfiles("it")
public abstract class AbstractPostgresIntegrationTest {
}
