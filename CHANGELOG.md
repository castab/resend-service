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

### Changed

- Replaced the Node.js/Express runtime and Prisma tooling with the Kotlin/Gradle
  runtime while retaining the v1 route paths and immutable SQL migrations.
- Updated GitHub Actions to test the JVM build, compile a native executable,
  and build the production image with pinned action revisions.
- Updated the OpenAPI contract and integration guides to describe the current
  partial Kotlin port rather than the removed Express behavior.

### Removed

- Removed the Node.js package, TypeScript sources, Prisma schema/client tooling,
  and Vitest integration-test harness.

### Known limitations

- Conversation persistence and reads, outbound sends, and outbox processing are
  not yet ported and return `501` after successful authentication. Webhook
  verification/projection is not ported and its route always returns `501`.
- Health currently represents configuration and database connectivity, not
  availability of the unfinished email workflows.
- PostgreSQL/fake-Resend integration coverage is deferred until the JDBC service
  ports land.

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
