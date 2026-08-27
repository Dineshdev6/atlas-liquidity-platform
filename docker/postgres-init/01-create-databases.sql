-- Databases for the platform, created on FIRST START of the Postgres volume.
--
-- Postgres runs everything in /docker-entrypoint-initdb.d exactly once, when the
-- data directory is empty. On an existing volume this file is ignored - which is
-- why the Layer 4 part 2 instructions also give you the createdb commands to run
-- by hand. Fresh clones of the repo get them automatically.
--
-- WHY FOUR DATABASES AND NOT ONE SCHEMA
--
-- Each service owns its own database and its own Flyway history. position-service
-- is at V1 while reference-data-service is at V4, and neither can read the
-- other's tables even if someone wanted to. Sharing one database is how you get a
-- distributed monolith: two services that must be deployed together because a
-- column change breaks both, with every operational cost of a distributed system
-- and none of the independence.
--
-- On a laptop they share a Postgres INSTANCE, which is a resource decision rather
-- than an architectural one - in production they would be separate instances, and
-- in this platform's case, per ADR 0004, separate Oracle databases.

-- reference-data-service (atlas_liquidity is created by POSTGRES_DB)
CREATE DATABASE atlas_liquidity_test OWNER atlas;

-- position-service
CREATE DATABASE atlas_positions      OWNER atlas;
CREATE DATABASE atlas_positions_test OWNER atlas;
