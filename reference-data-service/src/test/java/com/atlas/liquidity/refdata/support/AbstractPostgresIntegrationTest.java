package com.atlas.liquidity.refdata.support;

import org.springframework.test.context.ActiveProfiles;

/**
 * Base class for tests that need a real Postgres.
 *
 * <p><b>What changed, and why.</b> This class originally used Testcontainers to
 * start a throwaway {@code postgres:16-alpine} per test run. That is the better
 * design, and it did not work here: Docker Engine 29 (shipped with Docker
 * Desktop 4.86) dropped support for the Engine API version that Testcontainers
 * 1.21.3's bundled docker-java client requests, so every attempt to reach the
 * daemon came back {@code 400 Bad Request} - over the legacy named pipe, over
 * the WSL2 pipe, and over TCP alike. The {@code docker} CLI was unaffected
 * because it negotiates an API version first; docker-java did not.
 *
 * <p>So this base class now points the tests at the Postgres that
 * {@code docker compose up} already runs, using a separate database
 * ({@code atlas_liquidity_test}) so the suite and your {@code psql} session
 * cannot corrupt each other.
 *
 * <p><b>What we keep.</b> Everything Layer 2 is actually about: real Postgres at
 * the real version, real Flyway migrations applied by Flyway, Hibernate schema
 * validation against a real schema, real SQL, real transactions, real optimistic
 * locking. None of that is simulated.
 *
 * <p><b>What we give up.</b> The database is no longer created and destroyed per
 * run, which means (a) the tests must leave the data as they found it, and they
 * are written to do so, and (b) the suite needs {@code docker compose up -d}
 * beforehand rather than being self-contained. On a CI agent that is a real
 * cost - it becomes a shared resource that parallel builds can fight over,
 * which is precisely the problem Testcontainers was invented to solve.
 *
 * <p><b>The honest interview version of this story:</b> "We used Testcontainers
 * for integration tests. On Docker Engine 29 the bundled Docker client couldn't
 * negotiate an API version, so as a stopgap we pointed the suite at a dedicated
 * database in our Compose stack - same engine, same migrations - and tracked
 * restoring container-per-run as a follow-up." That is a better answer than
 * either "we used H2" or a green build that silently skipped every test.
 *
 * <p><b>To restore Testcontainers later</b>, try bumping the Testcontainers
 * version in the root POM ahead of what Spring Boot's BOM manages:
 * {@code <testcontainers.version>1.22.0</testcontainers.version>}. A newer
 * docker-java negotiates the API version properly.
 */
@ActiveProfiles("it")
public abstract class AbstractPostgresIntegrationTest {
    // No members. @ActiveProfiles is inherited by subclasses, so annotating
    // this one class points every *IT that extends it at application-it.yml.
}
