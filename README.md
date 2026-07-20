# resend-service

`resend-service` is an npm-workspaces monorepo for receiving Resend webhooks
and exposing a private API for topic-centered email conversations. Two
independently deployable Next.js applications share one PostgreSQL schema and
the same email-threading implementation.

## Architecture

```text
Internet
  -> webhook public Railway domain
  -> POST /api/webhooks/resend/v1
  -> Svix verification
  -> Resend Receiving API enrichment
  -> PostgreSQL webhook ledger + conversation projection

Adjacent Railway services
  -> conversation-api.railway.internal:<conversation-service-port>
  -> bearer-authenticated conversation API
  -> synchronous Resend Sending API or transactional outbox
  -> bounded Resend Batch API drain
  -> PostgreSQL conversation projection
```

The applications have deliberately different network boundaries:

| Application | Exposure | Responsibility |
| --- | --- | --- |
| `apps/webhook` | Public HTTPS | Verify and ingest Resend webhooks |
| `apps/conversation-api` | Railway private network only | Start, continue, assign, and read conversations |

Railway private networking is environment-isolated and encrypted. The private
API also requires a bearer token so network reachability does not automatically
grant permission to send or read email.

## Repository Layout

```text
apps/
  webhook/                    # Public Resend webhook application
  conversation-api/           # Private conversation application
packages/
  database/                   # Prisma schema, migrations, and generated client
  email/                      # Resend client, types, projection, and threading
tests/                        # Shared integration-test support
Dockerfile.webhook
Dockerfile.conversation-api
docker-compose.yml
package.json                  # Workspace orchestration
```

Each application and package has its own README with local details. Repository
rules are in the root and nested `AGENTS.md` files.

## API Routes

Routes follow `/api/{capability}/{integration?}/v{major}`.

Public webhook application:

```text
POST /api/webhooks/resend/v1
GET  /docs
GET  /openapi.json
GET  /api/health/v1
```

Private conversation application:

```text
POST  /api/conversations/v1
POST  /api/conversations/v1/outbox
POST  /api/conversations/v1/outbox/drain
GET   /api/conversations/v1?assignment=unassigned
GET   /api/conversations/v1/{conversationId}
PATCH /api/conversations/v1/{conversationId}
POST  /api/conversations/v1/{conversationId}/messages
POST  /api/conversations/v1/{conversationId}/messages/outbox
GET   /api/conversations/v1/topics/{topicType}/{externalTopicId}
GET   /docs
GET   /openapi.json
GET   /api/health/v1
```

The former `/api/webhooks/v1/resend` route is intentionally unavailable.

## Conversation Model

A topic is identified by a caller-owned `(topicType, externalTopicId)` pair.
Each topic can own one conversation. A conversation currently has one external
participant and one configured sender mailbox.

Messages store:

- Their conversation and optional parent message
- Resend and RFC Message-ID identifiers
- In-Reply-To and ordered References ancestry
- Direction and send state
- Sender, recipient, subject, text, and HTML
- Provider and persistence timestamps

Asynchronous sends use the same pending message rows as synchronous sends. A
separate outbox stores only queue entries and persistent batch coordination; it
does not duplicate addresses, subjects, or bodies. Batch membership remains
fixed across retries because Resend idempotency applies to the whole ordered
batch.

Inbound messages are attached using RFC headers. An unmatched inbound message
creates an unassigned conversation that can later be associated with a topic.
Email delivery is unordered, so the projector also resolves children that
arrived before their parent.

Attachments are not retrieved or persisted. Full inbound text and HTML bodies
are stored. HTML returned by the private API is untrusted and must be sanitized
before browser rendering.

## Requirements

- Node.js 22 or newer
- npm 10 or newer
- PostgreSQL 18 or newer for native `uuidv7()`
- A Resend API key
- A Resend webhook signing secret
- A verified Resend sending domain and a receiving path for `RESEND_FROM`

## Configuration

Create an ignored `.env` for local development:

```env
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/resend_test
RESEND_API_KEY=re_xxxxxxxxx
RESEND_WEBHOOK_SECRET=whsec_xxxxxxxxx
RESEND_FROM=Mailbox <mailbox@example.com>
CONVERSATION_API_KEY=replace-with-a-long-random-secret
OUTBOX_DRAIN_API_KEY=replace-with-another-long-random-secret
```

Environment ownership:

| Variable | Webhook | Conversation API | Purpose |
| --- | --- | --- | --- |
| `DATABASE_URL` | Required | Required | Shared PostgreSQL connection |
| `RESEND_API_KEY` | Required | Required | Retrieve inbound and send outbound email |
| `RESEND_WEBHOOK_SECRET` | Required | Not used | Verify Svix signatures |
| `RESEND_FROM` | Not used | Required | Configured sender identity |
| `CONVERSATION_API_KEY` | Not used | Required | Private API bearer credential |
| `OUTBOX_DRAIN_API_KEY` | Not used | Required | Dedicated scheduled-drain bearer credential |
| `RESEND_API_BASE_URL` | Optional | Optional | Test-only Resend-compatible base URL |

Normal mail-client replies go to `RESEND_FROM`. Its domain must be configured
for Resend Receiving, or the mailbox must forward incoming mail to a Resend
receiving address. If the root domain already uses another mail provider, use a
receiving subdomain or forwarding rather than replacing its production MX
records. In Resend, subscribe the public webhook URL to the `email.received`
event.

Prisma CLI commands load `.env` through
`packages/database/prisma.config.ts`. Values stored only in `.env.local` are
not available to Prisma.

## Local Development

Install dependencies and start PostgreSQL:

```bash
npm ci
docker compose up -d
npm run db:setup
```

Start the applications in separate terminals:

```bash
npm run dev:webhook
npm run dev:conversation
```

The webhook app listens on port 3000. The conversation app uses port 3001 in
development. Their Swagger pages are available at:

```text
http://localhost:3000/docs
http://localhost:3001/docs
```

The Compose `apps` profile can build both production images locally:

```bash
docker compose --profile apps up --build
```

## Database Changes

`packages/database/prisma/schema.prisma` and its checked-in migrations are the
database sources of truth. Never edit an already deployed migration.

```bash
npm run db:validate
npm run db:generate
npm run db:migrate:deploy
```

Both Railway services run `prisma migrate deploy` before starting. Prisma
coordinates concurrent attempts. Because Railway deploys services
independently, migrations must use expand/contract sequencing so old and new
application versions can temporarily share the schema.

Generated Prisma files live under
`packages/database/src/generated/prisma`, are ignored by Git, and must not be
edited manually.

## Testing

Create an ignored `.env.test`:

```env
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/resend_test
TEST_DATABASE_URL=postgresql://postgres:postgres@localhost:5432/resend_test
RESEND_WEBHOOK_SECRET=whsec_dGVzdF9zZWNyZXRfa2V5X2Zvcl90ZXN0aW5nXzEyMzQ=
RESEND_API_KEY=test-resend-api-key
RESEND_API_BASE_URL=http://localhost:4010
RESEND_FROM=Test Mailbox <mailbox@example.com>
CONVERSATION_API_KEY=test-conversation-api-key
OUTBOX_DRAIN_API_KEY=test-outbox-drain-api-key
APP_BASE_URL=http://localhost:3000
CONVERSATION_BASE_URL=http://localhost:3001
```

`DATABASE_URL` configures the applications and Prisma CLI. The integration
tests require `TEST_DATABASE_URL` for direct database access and cleanup. Set
both to the same disposable test database; the suite truncates its tables
before each test and never falls back to `DATABASE_URL`.

Prepare the database, then start each test application in its own terminal. Run
the PostgreSQL suite from a third terminal. The tests start a bounded local fake
Resend server and never call the real Resend API.

```bash
npm run db:setup
```

```bash
npm run dev:webhook:test
```

```bash
npm run dev:conversation:test
```

```bash
npm run test:postgresql
```

Repository checks:

```bash
npm run db:validate
npm run api:validate
npm run lint
npm run build
npm run test:postgresql
```

## Docker

Build each independently deployable image from the repository root:

```bash
docker build -f Dockerfile.webhook -t resend-webhook .
docker build -f Dockerfile.conversation-api -t resend-conversation-api .
```

Both images contain migration tooling and the shared schema. On platforms
without Railway pre-deploy commands, run `npm run db:migrate:deploy` from one
image before starting application containers.

## Railway

Create two services from this repository and keep the repository root as the
build context. Shared workspace packages are outside each app directory.

Webhook service:

- Select `/apps/webhook/railway.json` as its config file
- Assign a public Railway or custom domain
- Configure `DATABASE_URL`, `RESEND_API_KEY`, and `RESEND_WEBHOOK_SECRET`
- Configure Resend to deliver to
  `https://<public-host>/api/webhooks/resend/v1`
- Subscribe that webhook to `email.received`; normal replies cannot be
  projected without that event

Conversation service:

- Select `/apps/conversation-api/railway.json` as its config file
- Do not generate or attach a public domain
- Configure `DATABASE_URL`, `RESEND_API_KEY`, `RESEND_FROM`, and
  `CONVERSATION_API_KEY`
- Configure a distinct `OUTBOX_DRAIN_API_KEY` for the private scheduled caller
- Define `PORT` explicitly as a service variable on the conversation service.
  Railway reference variables resolve against service variables, not image
  defaults, so `${{conversation-api.PORT}}` is empty without it.
- In each caller, define `CONVERSATION_API_URL` with Railway references:
  `http://${{conversation-api.RAILWAY_PRIVATE_DOMAIN}}:${{conversation-api.PORT}}`
- Invoke `POST /api/conversations/v1/outbox/drain` at least once per minute to
  keep healthy-path queue delay below five minutes. Each call handles one batch
  of at most 100 messages and does not poll internally.

Both configs use `/api/health/v1` as the Railway readiness check. It validates
required configuration and database access without returning sensitive details.
Both services watch their app plus shared build inputs and packages.

## Retention and Security

Webhook ledger rows and projected conversations are retained indefinitely.
There is currently no deletion, archival, or expiration workflow. Stored data
can contain personal or sensitive information, including addresses, names,
subjects, message bodies, IP addresses, and user agents.

Protect PostgreSQL, the private bearer token, Resend API key, and webhook secret.
Do not expose the conversation application through a Railway public domain.
