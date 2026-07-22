# resend-service

`resend-service` is a PostgreSQL-backed Kotlin/http4k application targeting Java
25 and GraalVM Native Image. It retains the existing HTTP paths, OpenAPI document,
database schema, and immutable SQL migration history while moving away from the
Node.js/Express/Prisma runtime.

## Stack

- Kotlin with a Java 25 toolchain
- http4k with the Jetty server backend
- Reflection-free `kotlinx.serialization` JSON codecs
- Hoplite with type-safe HOCON configuration
- JDBC/HikariCP and Flyway
- Kotest
- GraalVM Native Image (`--no-fallback`, `--gc=serial`, `-O3`)

Open this repository as a Gradle project in IntelliJ IDEA and select JDK 25. No
generated sources are required.

## API compatibility and cutover status

The health, webhook, conversation, outbox, OpenAPI, and documentation paths are
registered exactly as in v0.2. Conversation and drain authentication boundaries
are retained. `GET /api/health/v1`, `GET /openapi.json`, and `GET /docs` are
implemented. In this first native-runtime cutover, conversation persistence,
Resend sends, outbox draining, and webhook verification/projection deliberately
return `501` after authentication. They must not be deployed for production email
traffic until their JDBC ports land; an error preserves retry behavior and is
safer than acknowledging incomplete work.

The Prisma schema remains as domain documentation. Flyway executes immutable
copies of all checked-in migrations from `src/main/resources/db/migration`,
numbered sequentially from `V001` so their order remains obvious.

## Development

```bash
./gradlew test
./gradlew run
```

Configuration is loaded and type-checked by Hoplite from the classpath
`application.conf`. The checked-in file uses optional HOCON substitutions, so
the container environment remains compatible with the previous image:

```env
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/resend_test
RESEND_API_KEY=re_xxxxxxxxx
RESEND_WEBHOOK_SECRET=whsec_xxxxxxxxx
RESEND_FROM=Mailbox <mailbox@example.com>
RESEND_REPLY_TO=mailbox@replies.example.com
CONVERSATION_API_KEY=replace-with-a-long-random-secret
OUTBOX_DRAIN_API_KEY=replace-with-another-long-random-secret
PORT=3000
HOST=0.0.0.0
```

Run migrations with `./gradlew run --args=migrate`. Swagger UI is exposed at
`/docs`, with the unchanged OpenAPI 3 contract at `/openapi.json`.

## Native image and Docker

```bash
./gradlew nativeCompile
docker build -t resend-service .
docker run --rm -e DATABASE_URL="$DATABASE_URL" resend-service migrate
docker run --rm -p 3000:3000 --env-file .env resend-service
```

The multi-stage build compiles with GraalVM 25 and runs as a non-root user in a
small Oracle Linux image. The container starts with a 16 MiB initial heap and a
128 MiB hard maximum: this leaves practical headroom for Flyway, HikariCP, TLS,
and PostgreSQL metadata while preventing unbounded heap growth. Native-image
uses the low-overhead Serial GC, and http4k uses streaming bodies rather than
buffering payloads in memory. Native-image metadata explicitly retains the HOCON,
OpenAPI, Swagger, and Flyway resources plus the reflected configuration model.
PostgreSQL 18+ is required for native `uuidv7()`.

## Test-port status

Provider-neutral RFC Message-ID, subject, ancestry, address, route exposure,
health aggregation, and authentication objectives are ported to Kotest. The old
PostgreSQL/fake-Resend integration scenarios are deferred with the JDBC
conversation, outbox, and webhook implementations described above.
