# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog, and this project uses Semantic
Versioning.

## [Unreleased]

### Added

- Added a Kotlin/http4k application targeting Java 25 and GraalVM Native Image.
- Added Gradle builds, Kotest coverage, Hoplite HOCON configuration, HikariCP,
  and Flyway migration execution.
- Added GraalVM resource and reflection metadata plus a native multi-stage
  Docker image.
- Ported the full conversation, message, and outbox API: create/list/get/assign,
  topic lookup, synchronous reply sending, outbox enqueue, and the transactional
  outbox drain engine (fixed batches, leasing, retry backoff, and the provider
  idempotency safety window).
- Ported Svix-verified Resend webhook ingestion with idempotent delivery-state
  projection and RFC-threaded inbound email projection.
- Added a JDBI data layer (Fluent API with explicit row mappers), an http4k
  OkHttp-based Resend client, JDK-crypto Svix verification, and canonical
  request-hash idempotency.
- Added `TEST_DATABASE_URL`-gated Kotest integration specs (PostgreSQL plus an
  in-process fake Resend server) alongside unit specs, and a PostgreSQL 18
  service container in CI.

### Changed

- Replaced the Node.js/Express runtime and Prisma tooling with the Kotlin/Gradle
  runtime while preserving the v1 route paths, request/response shapes, status
  codes, and immutable SQL migrations.
- Updated GitHub Actions to run the integration suite against PostgreSQL, test
  the JVM build, compile a native executable, and build the production image.
- Restored the full OpenAPI contract and integration guides to describe the
  ported behavior.
- Added `RESEND_API_BASE_URL` as an optional Resend base-URL override.

### Removed

- Removed the Node.js package, TypeScript sources, Prisma schema/client tooling,
  and Vitest integration-test harness.

## [0.2.0] - 2026-07-21

### Changed

- Migrated the application runtime from Next.js to Express 5 while preserving
  the public API contract and local Swagger UI.

## [0.1.0] - 2026-07-21

### Added

- Added outbound delivery-state projection from Resend lifecycle webhooks while
  preserving provider send acceptance as the existing message `state`.
- Added optional per-message Reply-To display names for conversation sends and
  outbox sends.

## [0.0.1] - 2026-07-21

### Added

- Initial public release process with SemVer metadata, changelog tracking, and
  tag-triggered Docker Hub publication guidance.
