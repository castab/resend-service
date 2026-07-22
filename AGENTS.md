# Repository Rules

## Current Port Status

- The Kotlin/http4k runtime implements the full conversation, outbox, and
  webhook surface that the previous Express runtime exposed: configuration,
  database pool creation, explicit Flyway migration, health, static API
  documentation, bearer/drain authentication, synchronous and outbox sending
  via Resend, RFC threading, the transactional outbox drain engine, and
  Svix-verified webhook ingestion with delivery-state and inbound projection.
- The data layer uses JDBI (Fluent API with explicit row mappers) over the
  Hikari pool; the Resend client uses http4k's OkHttp client; Svix signature
  verification is implemented with the JDK crypto primitives.
- The rules below are enforced behavior. Keep them true when changing code, and
  verify every capability against the Kotlin routes and the Kotest suite.

## Application Boundaries

- This repository contains one deployable Kotlin/http4k application.
- Keep HTTP route registration in `src/main/kotlin/com/castab/resend/App.kt` and
  provider-neutral email behavior under `src/main/kotlin/com/castab/resend`.
- Keep JDBC/Flyway database access under `src/main/kotlin/com/castab/resend` and
  checked-in migrations under `src/main/resources/db/migration`.
- Use the API gateway to control external route exposure; do not weaken
  application-layer authentication based on network placement.
- Keep `GET /api/health/v1` unauthenticated for readiness checks and return only
  aggregate status.

## Webhook Ingress

- Keep the route exactly `POST /api/webhooks/resend/v1`.
- Verify the exact raw request body before JSON transformation.
- Require `svix-id`, `svix-timestamp`, and `svix-signature`.
- Keep duplicate deliveries idempotent through the database `svix_id`
  constraints and acknowledge completed duplicates with `200`.
- Return `500` when required inbound retrieval or projection fails so Resend can
  retry. Do not acknowledge incomplete projection work.
- Do not fetch attachments.

## Conversation API

- Require bearer authentication on every conversation operation.
- Require the dedicated drain credential on the outbox drain operation.
- Require `Idempotency-Key` on every operation that can send or enqueue email.
- Persist send intent before calling Resend and never add unbounded retries.
- Use only the server-configured `RESEND_FROM`; callers cannot choose senders.
- Preserve one conversation per `(topicType, externalTopicId)` and one external
  participant per conversation.
- An explicit reply parent must belong to the conversation. Otherwise use the
  latest accepted or received message.
- Never send a reply without a parent RFC Message-ID.
- Build `References` from the selected parent's ancestry.
- Treat stored and returned HTML as untrusted.
- Preserve fixed ordered outbox batch membership, bounded batch size, retry
  backoff, and the provider idempotency safety window.

## Database

- Treat checked-in Flyway migrations under
  `src/main/resources/db/migration` as authoritative.
- Add migrations; never edit deployed migration history.
- Preserve UUIDv7 primary keys and all webhook, message, and outbox idempotency
  constraints.
- Run the relevant Gradle tests after schema changes.
- Treat all email-related columns as sensitive and avoid logging values.

## Email Behavior

- Keep RFC Message-ID parsing provider-neutral.
- Preserve ordered References ancestry and selected-parent semantics.
- Expect missing, malformed, duplicated, and out-of-order webhook metadata.
- Keep projection idempotent by Resend email ID and RFC Message-ID.
- Never infer thread membership from subject alone when RFC ancestry exists.
- Keep Resend calls bounded with an abort timeout.
- Do not log message bodies, addresses, subjects, headers, or credentials.

## Contracts And Tests

- Keep `src/main/resources/public/openapi.json` aligned with every business API
  route or behavior change. `/docs` and `/openapi.json` are supporting resources
  described in the guides rather than OpenAPI operations.
- Preserve explicit webhook and outbox-drain security overrides in OpenAPI.
- Database integration tests require a dedicated disposable `TEST_DATABASE_URL`,
  truncate application tables between tests, and stay serial while they share
  PostgreSQL and the in-process fake Resend server. They are skipped when
  `TEST_DATABASE_URL` is unset.
- Tests are Kotest specs: unit-level specs (validation, threading, routing,
  Svix, idempotency hashing) plus `TEST_DATABASE_URL`-gated integration specs
  that exercise PostgreSQL and the fake Resend server end to end.
