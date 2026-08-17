# ADR 0002 — Maven multi-module reactor with an imported Spring Boot BOM

**Status:** Accepted
**Date:** Layer 1

## Context

The platform will grow to roughly six services plus shared libraries. Two
structural choices had to be made up front, and both are expensive to reverse.

**1. One repository or many?** Separate repositories per service give teams
independent release cadence and clean ownership boundaries. They also mean that
changing a shared library requires a release, a version bump and a coordinated
merge across every consumer — and that a cross-cutting change (adding a field to
a domain event, say) becomes a multi-day choreography.

**2. How do we align dependency versions?** Spring Boot ships a curated set of
compatible versions for hundreds of libraries. There are two ways to consume it:
inherit from `spring-boot-starter-parent`, or import `spring-boot-dependencies`
as a BOM in `<dependencyManagement>`.

## Decision

**A single repository containing a Maven multi-module reactor.** One
`mvn clean verify` at the root builds and tests everything, in dependency order.

**Version alignment via BOM import, not parent inheritance.** The root POM
imports `spring-boot-dependencies` with `<scope>import</scope>` and
`<type>pom</type>`, and declares the Spring Boot Maven plugin in
`<pluginManagement>`.

## Consequences

**On the monorepo.** Atomic cross-service changes become a single commit and a
single PR — the shared `Money` type and every consumer of it move together.
Refactoring is genuinely cheap. The cost is that the build gets slower as
modules accumulate, and CI eventually needs selective builds (`-pl ... -am`) or
a build cache. That is a Layer 12 problem, and a solvable one; the coordination
tax of polyrepo at this size is not.

**On BOM import over parent inheritance.** Maven allows exactly one `<parent>`.
Most large organisations want that slot for a corporate parent POM carrying
internal repository configuration, licence scanning, security gates and build
conventions. Consuming Spring Boot as an imported BOM keeps the parent slot free
while still getting identical version alignment.

The cost is that a few conveniences from `spring-boot-starter-parent` are not
inherited — notably resource filtering of `application.yml` with `@...@`
placeholders, and a preconfigured `spring-boot-maven-plugin` version. We declare
the plugin version explicitly in `<pluginManagement>`, and we do not rely on
`@` filtering.

**Interview relevance.** "What's the difference between inheriting from
`spring-boot-starter-parent` and importing `spring-boot-dependencies`?" is asked
often, and answered well rarely. The short version: inheritance gives you
version management *plus* plugin configuration and resource filtering but
consumes your one parent slot; BOM import gives you version management only, and
leaves the slot free. Neither is correct in the abstract — it depends on whether
your organisation has its own parent POM.
