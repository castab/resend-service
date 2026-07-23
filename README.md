# resend-service

`resend-service` is a PostgreSQL-backed Kotlin/http4k application targeting
Java 25 and GraalVM Native Image. This branch is migrating the service away
from the previous Node.js/Express runtime while retaining the v1 route paths,
database schema, and immutable Flyway migration history.

The Kotlin work is currently unreleased at `0.3.0-SNAPSHOT`. Published stable
images through `0.2.0` contain the former Express runtime.

## Current status

The Kotlin/http4k runtime is a complete port of the previous Express service.
The HTTP API is backward compatible with `0.2.0`; only the underlying stack
changed (Kotlin/http4k + JDBI + GraalVM in place of Express + Prisma/Node).

| Capability | Current behavior |
| --- | --- |
| `GET /api/health/v1` | Aggregate configuration + database readiness (`ok` / `unhealthy`) |
| `GET /openapi.json` | Serves the full OpenAPI 3.1 contract |
| `GET /docs` | Serves a Swagger UI shell that loads Swagger assets from unpkg |
| Conversation reads and writes | Bearer-authenticated create, list, get, assign, and topic lookup |
| Synchronous and outbox sends | Persisted send intent, Resend delivery, RFC threading, idempotency |
| Outbox enqueue and drain | Scoped-bearer drain with fixed batches, leasing, and retry backoff |
| Resend webhook ingress | Svix-verified ingestion with delivery-state and inbound projection |

Database configuration is initialized before Jetty starts. A malformed or
initially unreachable non-null `DATABASE_URL` can therefore prevent the server
and health route from starting rather than producing a health `503`.

The conversation, webhook, threading, idempotency, and outbox rules in
`AGENTS.md` describe the behavior this runtime enforces.

## Stack

- Kotlin 2.3 with a Java 25 toolchain
- http4k with Jetty
- `kotlinx.serialization`
- Hoplite HOCON configuration
- JDBC, HikariCP, and Flyway
- Kotest
- GraalVM Native Image (`--no-fallback`, Serial GC, `-O3`)

Open this repository as a Gradle project in IntelliJ IDEA and select JDK 25.
No generated sources are required.

## Design notes

### Svix webhook verification is implemented in-tree

Resend signs webhooks with the Svix scheme, and this service verifies them in
about forty lines over JDK crypto primitives
(`src/main/kotlin/com/castab/resend/email/WebhookVerify.kt`) rather than by
depending on the official `com.svix.kotlin:svix-kotlin` library. This is a
deliberate trade-off:

- The library is the full Svix management-API client and brings
  `kotlin-reflect`, `kotlinx-coroutines`, `kotlinx-datetime`, OkHttp, and its
  own `kotlinx-serialization` pin. This service only consumes webhooks —
  Resend owns the Svix application — so none of that client surface would be
  used.
- `kotlin-reflect` and coroutines are the two most common sources of GraalVM
  Native Image friction, and the native executable is a primary build
  artifact of this project.
- The verification contract is small, stable, and published:
  `base64(HMAC_SHA256(secret, "id.timestamp.body"))` compared constant-time
  against each `v1,` entry in the signature header, with a five-minute
  timestamp tolerance. The test suite keeps an independent signer
  (`src/test/kotlin/com/castab/resend/support/Svix.kt`) that must agree with
  the verifier in every webhook integration test.

Revisit this decision if Resend ever migrates to Svix's asymmetric `v1a`
signatures (the in-tree verifier deliberately ignores non-`v1` entries) or if
this service starts calling the Svix API. All verification routes through the
single `WebhookVerifier.verify` seam, so swapping the library in later is a
one-file change.

## Development

Run the unit tests and JVM application:

```bash
./gradlew test
./gradlew run
```

In Windows PowerShell, use `.\gradlew.bat` instead of `./gradlew`.

Configuration is loaded by Hoplite from the classpath resource
`src/main/resources/application.conf`. Runtime values are supplied with these
environment variables:

| Variable | Purpose | Current use |
| --- | --- | --- |
| `PORT` | HTTP listen port; defaults to `3000` | Applied to Jetty |
| `HOST` | Intended listen host; defaults to `0.0.0.0` | Loaded and logged, but not yet applied to Jetty |
| `DATABASE_URL` | PostgreSQL URI such as `postgresql://user:pass@host:5432/db` | Pool creation, migrations, and health |
| `RESEND_API_KEY` | Resend credential | Authenticates send/retrieve calls; required for healthy status |
| `RESEND_API_BASE_URL` | Optional Resend base-URL override (defaults to `https://api.resend.com`) | Used by the Resend client; primarily for tests |
| `RESEND_WEBHOOK_SECRET` | Svix signing secret | Webhook signature verification; required for healthy status |
| `RESEND_FROM` | Server-controlled sender | Applied to every send; required for healthy status |
| `RESEND_REPLY_TO` | Reply routing mailbox | Conversation reply-to routing tokens; required for healthy status |
| `CONVERSATION_API_KEY` | Conversation API bearer credential | Authentication and health |
| `OUTBOX_DRAIN_API_KEY` | Dedicated drain bearer credential | Authentication and health |
| `TEST_DATABASE_URL` | Disposable PostgreSQL for integration tests | Enables the integration Kotest specs |

`application.example.conf` documents the equivalent HOCON shape. It is a
reference file, not an automatically loaded external configuration file.

Run Flyway explicitly before starting the server:

```bash
./gradlew run --args=migrate
```

Normal application startup does not run migrations. Flyway uses the immutable
migrations under `src/main/resources/db/migration`. PostgreSQL 18 or newer is
required because the schema uses native `uuidv7()`.

## Native image and Docker

Use a GraalVM 25 JDK with `native-image` installed for a local native build:

```bash
./gradlew nativeCompile --no-configuration-cache
docker build -t resend-service .
```

The GraalVM Gradle plugin is currently incompatible with this project's enabled
configuration cache, so native builds must include
`--no-configuration-cache`. The Dockerfile already does this.

Run migrations and then start the container with a runtime environment file:

```bash
docker run --rm --env-file path/to/runtime.env resend-service migrate
docker run --rm -p 3000:3000 --env-file path/to/runtime.env resend-service
```

The multi-stage image runs as a non-root user on Oracle Linux. The native image
uses Serial GC with a 16 MiB initial heap and a 128 MiB maximum via embedded
native-image runtime settings, so the container entrypoint remains
`/app/resend-service` for both normal startup and `migrate`. The release
workflow currently publishes `linux/amd64` images only.

For local PostgreSQL, run `docker compose up -d postgresql`. The application is
behind the Compose `apps` profile. Supply all runtime values for health to
return `200`:

```bash
docker compose --profile apps up --build
```

Compose does not run migrations automatically. Its `depends_on` setting does
not wait for PostgreSQL readiness, so migrate the database and confirm it is
accepting connections before starting the application profile.

## Railway

`railway.json` builds the Dockerfile, runs `/app/resend-service migrate` as a
pre-deploy command, checks `/api/health/v1` for up to 120 seconds, and restarts
failed deployments up to three times. The image entrypoint is the bare
`/app/resend-service` binary so Railway can pass `migrate` as the single CLI
argument during predeploy. Configure every health-required variable before
deployment. The health check still does not make the unfinished email
operations production-ready.

## API documentation

- Current OpenAPI contract: `src/main/resources/public/openapi.json`
- Consumer integration status: `docs/api-consumer-guide.md`
- Portable agent handoff: `docs/api-agent-handoff.md`
- Browser UI: `/docs`

The OpenAPI contract describes the implemented Kotlin runtime, whose HTTP API is
backward compatible with the former Express implementation.

Validate documentation changes with:

```bash
npx --yes markdownlint-cli2 "**/*.md" "#.opencode/node_modules/**"
npx --yes @redocly/cli lint src/main/resources/public/openapi.json \
  --skip-rule info-license
```

## Test coverage

The Kotest suite has two layers. Unit specs cover validation, RFC threading,
reply-to routing, Svix verification, idempotency hashing, HOCON loading, and the
health/authentication boundaries. Integration specs — gated on
`TEST_DATABASE_URL` and skipped when it is unset — run against PostgreSQL and an
in-process fake Resend server, exercising conversation create/read/reply, the
outbox enqueue-and-drain engine, and Svix-verified webhook projection end to
end. CI provides a PostgreSQL 18 service container so the integration specs run
on every push.

```bash
TEST_DATABASE_URL=postgresql://user:pass@127.0.0.1:5432/db sh ./gradlew test
```
