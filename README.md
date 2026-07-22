# resend-service

`resend-service` is a PostgreSQL-backed Kotlin/http4k application targeting
Java 25 and GraalVM Native Image. This branch is migrating the service away
from the previous Node.js/Express runtime while retaining the v1 route paths,
database schema, and immutable Flyway migration history.

The Kotlin work is currently unreleased at `0.3.0-SNAPSHOT`. Published stable
images through `0.2.0` contain the former Express runtime.

## Current status

This is a partial runtime port and is not ready for production email traffic.

| Capability | Current behavior |
| --- | --- |
| `GET /api/health/v1` | Implemented; reports aggregate configuration and database connectivity only |
| `GET /openapi.json` | Serves the current implementation contract |
| `GET /docs` | Serves a Swagger UI shell that loads Swagger assets from unpkg |
| Conversation reads and writes | Bearer authentication is enforced, then the operation returns `501` |
| Outbox enqueue and drain | Scoped bearer authentication is enforced, then the operation returns `501` |
| Resend webhook ingress | Returns `501` without reading or verifying the request |

Health can return `200` while the email operations above remain unavailable. It
is currently a configuration/database readiness signal, not proof that the
email workflow port is complete.

Database configuration is initialized before Jetty starts. A malformed or
initially unreachable non-null `DATABASE_URL` can therefore prevent the server
and health route from starting rather than producing a health `503`.

The intended conversation, webhook, threading, idempotency, and outbox rules
remain acceptance criteria in `AGENTS.md`. They must not be presented as
implemented until their JDBC and Resend ports and integration tests land.

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
| `RESEND_API_KEY` | Resend credential | Required for healthy status; provider calls are not ported |
| `RESEND_WEBHOOK_SECRET` | Svix signing secret | Required for healthy status; verification is not ported |
| `RESEND_FROM` | Server-controlled sender | Required for healthy status; sends are not ported |
| `RESEND_REPLY_TO` | Reply routing mailbox | Required for healthy status; sends are not ported |
| `CONVERSATION_API_KEY` | Conversation API bearer credential | Authentication and health |
| `OUTBOX_DRAIN_API_KEY` | Dedicated drain bearer credential | Authentication and health |

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
uses Serial GC with a 16 MiB initial heap and a 128 MiB maximum. The release
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
failed deployments up to three times. Configure every health-required variable
before deployment. The health check still does not make the unfinished email
operations production-ready.

## API documentation

- Current OpenAPI contract: `src/main/resources/public/openapi.json`
- Consumer integration status: `docs/api-consumer-guide.md`
- Portable agent handoff: `docs/api-agent-handoff.md`
- Browser UI: `/docs`

The OpenAPI contract describes the partial Kotlin runtime, not the former
Express implementation or the target post-port API.

Validate documentation changes with:

```bash
npx --yes markdownlint-cli2 "**/*.md" "#.opencode/node_modules/**"
npx --yes @redocly/cli lint src/main/resources/public/openapi.json \
  --skip-rule info-license \
  --skip-rule operation-2xx-response \
  --skip-rule operation-4xx-response
```

The skipped response rules assume every operation has both 2xx and 4xx
responses; that would misrepresent the current `501`-only stubs.

## Test coverage

The current Kotest suite covers aggregate unavailable health, representative
conversation/drain authentication checks, HOCON loading, and provider-neutral
threading helpers. It does not yet include PostgreSQL, fake-Resend, webhook,
conversation, or outbox integration tests. The threading helpers are not
connected to HTTP operations yet.
