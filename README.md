# resend-service

[![Docker Hub](https://img.shields.io/docker/pulls/castab/resend-service?label=Docker%20Hub)](https://hub.docker.com/r/castab/resend-service) [![Docker Image Version](https://img.shields.io/docker/v/castab/resend-service?sort=semver&label=version)](https://hub.docker.com/r/castab/resend-service/tags)

`resend-service` is one PostgreSQL-backed Next.js application for receiving
Resend webhooks and managing topic-centered email conversations. An external
API gateway controls which paths are publicly reachable; authentication and
signature verification remain enforced by the application.

## Architecture

```text
Resend
  -> API gateway
  -> POST /api/webhooks/resend/v1
  -> Svix verification
  -> webhook ledger and inbound conversation projection

Authorized callers
  -> API gateway or private service address
  -> /api/conversations/v1
  -> bearer authentication
  -> synchronous Resend send or transactional outbox
  -> PostgreSQL conversation projection
```

The single process shares one Prisma client, deployment lifecycle, health
check, OpenAPI contract, Docker image, and Railway service configuration.

## Repository Layout

```text
prisma/                 # Prisma schema and immutable migration history
public/openapi.json     # Unified OpenAPI contract
src/app/                # Next.js routes and Swagger UI
src/lib/database/       # Prisma client construction and exports
src/lib/email/          # Resend client, webhook types, projection, threading
src/lib/                # HTTP authentication, validation, and services
tests/                  # PostgreSQL integration tests and fake Resend server
Dockerfile              # Standalone production image
railway.json            # Railway build and deployment configuration
```

## API Routes

```text
GET   /api/health/v1
POST  /api/webhooks/resend/v1
POST  /api/conversations/v1
GET   /api/conversations/v1?assignment=unassigned
POST  /api/conversations/v1/outbox
POST  /api/conversations/v1/outbox/drain
GET   /api/conversations/v1/{conversationId}
PATCH /api/conversations/v1/{conversationId}
POST  /api/conversations/v1/{conversationId}/messages
POST  /api/conversations/v1/{conversationId}/messages/outbox
GET   /api/conversations/v1/topics/{topicType}/{externalTopicId}
GET   /docs
GET   /openapi.json
```

The webhook requires a valid signature over the exact raw body and all three
Svix headers. Conversation operations require `CONVERSATION_API_KEY`. The
outbox drain uses the separate `OUTBOX_DRAIN_API_KEY`. Sending and enqueueing
operations also require `Idempotency-Key`.

The former `/api/webhooks/v1/resend` path remains intentionally unavailable.

## Conversation Model

A caller-owned `(topicType, externalTopicId)` pair identifies a conversation.
Each conversation currently has one external participant and one configured
sender mailbox. Each conversation has an opaque routing token and a stable
Reply-To address under the configured receiving mailbox. Each outbound message
may add its own Reply-To display name while preserving that generated address.
Messages retain provider and RFC identifiers, ordered reply ancestry, send
state, projected outbound delivery state, content, and timestamps.

Asynchronous sends persist the same pending message rows used by synchronous
sends. Outbox rows coordinate fixed, ordered Resend batches and bounded retries
without duplicating message content. Inbound messages are attached through RFC
headers, including repair when children arrive before their parent. If eligible
RFC ancestry does not resolve a conversation, the service falls back to the
conversation token in a `to` or `received_for` address. Token routing still
requires the inbound sender to match the conversation participant.

Outbound `accepted` state means Resend accepted the send API request. A message
is only marked delivered after a matching `email.delivered` webhook is projected.
Opened and clicked events are ingested into the webhook ledger when enabled in
Resend, but they do not change delivery status.

Attachments are not retrieved or persisted. Returned HTML is untrusted and
must be sanitized before browser rendering.

## Requirements

- Node.js 22 or newer
- npm 10 or newer
- PostgreSQL 18 or newer for native `uuidv7()`
- A Resend API key and webhook signing secret
- A verified Resend sender and receiving configuration

## Configuration

Create an ignored `.env` for local development:

```env
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/resend_test
RESEND_API_KEY=re_xxxxxxxxx
RESEND_WEBHOOK_SECRET=whsec_xxxxxxxxx
RESEND_FROM=Mailbox <mailbox@example.com>
RESEND_REPLY_TO=mailbox@replies.example.com
CONVERSATION_API_KEY=replace-with-a-long-random-secret
OUTBOX_DRAIN_API_KEY=replace-with-another-long-random-secret
```

`RESEND_REPLY_TO` must be a plain mailbox on a Resend Receiving domain, without
a display name or existing `+` tag. Resend must accept every generated
`mailbox+c_<token>@domain` address. Keep this value stable while previously sent
messages can still receive replies. API callers can provide per-message
Reply-To display names, which are formatted only in outbound provider requests.
`RESEND_API_BASE_URL` is optional and
intended for a Resend-compatible test endpoint. The health route requires every
variable above and database access; it returns `503` if any application
capability is not configured.

Prisma CLI commands load `.env` through `prisma.config.ts`. Values stored only
in `.env.local` are not available to Prisma.

## Local Development

```bash
npm ci
docker compose up -d postgresql
npm run db:setup
npm run dev
```

The application listens on port 3000. Swagger UI is available at
`http://localhost:3000/docs`.

The optional application Compose profile builds the production image:

```bash
docker compose --profile apps up --build
```

## Database Changes

`prisma/schema.prisma` and `prisma/migrations` are the database sources of
truth. Never edit an already deployed migration.

```bash
npm run db:validate
npm run db:generate
npm run db:migrate:deploy
```

Generated Prisma files live under `src/generated/prisma`, are ignored by Git,
and must not be edited manually.

## Testing

Integration tests use one application process, a local fake Resend server, and
a disposable PostgreSQL database. They truncate application tables.

Create an ignored `.env.test`:

```env
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/resend_test
TEST_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/resend_test
RESEND_WEBHOOK_SECRET=whsec_dGVzdF9zZWNyZXRfa2V5X2Zvcl90ZXN0aW5nXzEyMzQ=
RESEND_API_KEY=test-resend-api-key
RESEND_API_BASE_URL=http://localhost:4010
RESEND_FROM=Test Mailbox <mailbox@example.com>
RESEND_REPLY_TO=mailbox@replies.example.com
CONVERSATION_API_KEY=test-conversation-api-key
OUTBOX_DRAIN_API_KEY=test-outbox-drain-api-key
APP_BASE_URL=http://localhost:3000
```

Prepare the database and start the test application:

```bash
npm run db:setup
npm run dev:test
```

Run the suite from another terminal:

```bash
npm run test:postgresql
```

`TEST_DATABASE_URL` is deliberately separate from `DATABASE_URL` so tests never
fall back to an application or production database for destructive cleanup.

## Docker

```bash
docker build -t resend-service .
docker run --rm -e DATABASE_URL="$DATABASE_URL" resend-service npm run db:migrate:deploy
docker run --rm -p 3000:3000 --env-file .env resend-service
```

The image contains Prisma migration tooling, schema, migrations, public assets,
and the standalone Next.js server.

Published releases are also available on Docker Hub as
`castab/resend-service`.

```bash
docker pull castab/resend-service:0.1.0
docker run --rm -e DATABASE_URL="$DATABASE_URL" castab/resend-service:0.1.0 npm run db:migrate:deploy
docker run --rm -p 3000:3000 --env-file .env castab/resend-service:0.1.0
```

Stable releases publish one immutable exact tag, plus moving convenience tags:

- `x.y.z`
- `x.y`
- `x`
- `latest`

Run migrations before starting a newly pulled image. Docker Hub publication is
currently limited to `linux/amd64` images.

## Releases

- Repository metadata, OpenAPI metadata, and consumer documentation use the
  same SemVer value.
- `CHANGELOG.md` is the source of release notes.
- Release pull requests prepare the version bump and changelog entry.
- After the release PR merges to `main`, push the matching annotated `vX.Y.Z`
  tag to publish Docker images.

Detailed release steps live in `docs/releasing.md`.

## Railway

Create one service from this repository and use `/railway.json`. Configure all
runtime variables before deployment because `/api/health/v1` checks the complete
configuration. The pre-deploy command applies pending Prisma migrations.

Route public and private traffic through the API gateway as needed. Keep bearer
authentication enabled even when conversation routes are gateway-restricted.
Configure Resend to deliver signed events to
`https://<webhook-host>/api/webhooks/resend/v1`.

Invoke `POST /api/conversations/v1/outbox/drain` at least once per minute when
using asynchronous sends. Each request handles one bounded batch and does not
poll internally.

## Verification

```bash
npm run release:validate
npm run db:validate
npm run api:validate
npm run lint
npm run build
npm run test:postgresql
```

Webhook ledgers and projected conversations are retained indefinitely. Protect
PostgreSQL and every API credential because stored data can include addresses,
names, subjects, bodies, IP addresses, and user agents.
