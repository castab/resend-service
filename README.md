# resend-service

`resend-service` receives [Resend](https://resend.com) webhooks, verifies
their Svix signatures, and stores selected event data in PostgreSQL.

[Railway](https://railway.com) is the recommended deployment target and is
configured in this repository. The included Dockerfile builds a portable
container image that can run on any compatible container platform.

This project was originally based on
[`resend/resend-webhooks-ingester`](https://github.com/resend/resend-webhooks-ingester).
It has been narrowed to a single PostgreSQL-backed service that can be extended
with additional application capabilities over time.

## Current Scope

- Accepts Resend webhooks at `POST /api/webhooks/resend`
- Verifies the raw request body with the Resend webhook signing secret
- Stores email, contact, and domain events in PostgreSQL
- Ignores duplicate deliveries using the unique `svix-id` header
- Manages the database schema with Prisma migrations
- Builds as a standalone Next.js application and Docker image

The service stores normalized fields used by its current schema. It does not
store the complete original webhook payload.

## Architecture

```text
Resend
  -> HTTPS endpoint
  -> Next.js webhook route
  -> Svix signature verification
  -> Prisma
  -> PostgreSQL
```

The application uses:

- Node.js 22
- Next.js 16 and React 19
- Prisma 7 with the PostgreSQL driver adapter
- PostgreSQL 18 for local development and CI
- Svix for webhook signature verification

## Supported Events

### Email Events

| Event | Description |
| --- | --- |
| `email.sent` | Resend accepted the email for delivery |
| `email.delivered` | The email was delivered |
| `email.delivery_delayed` | Delivery was temporarily delayed |
| `email.bounced` | Delivery permanently failed |
| `email.complained` | The recipient reported the email as spam |
| `email.opened` | The recipient opened the email |
| `email.clicked` | The recipient clicked a link |
| `email.failed` | Resend could not send the email |
| `email.scheduled` | The email was scheduled for later delivery |
| `email.suppressed` | Resend suppressed the email |
| `email.received` | Resend received an inbound email |

### Contact Events

| Event | Description |
| --- | --- |
| `contact.created` | A contact was created |
| `contact.updated` | A contact was updated |
| `contact.deleted` | A contact was deleted |

### Domain Events

| Event | Description |
| --- | --- |
| `domain.created` | A domain was created |
| `domain.updated` | A domain was updated |
| `domain.deleted` | A domain was deleted |

## Data Storage

Events are appended to three PostgreSQL tables:

| Table | Contents |
| --- | --- |
| `resend_wh_emails` | Delivery lifecycle, sender, recipients, subject, tags, bounce data, and click data |
| `resend_wh_contacts` | Contact identity, audience, segments, names, and subscription state |
| `resend_wh_domains` | Domain identity, status, region, and DNS records |

Each table has its own unique constraint on `svix_id`. Re-delivering the same
event to the same event family is acknowledged with `200` without inserting a
second row.

### Retention

Stored events are retained indefinitely. The application does not currently
delete, truncate, archive, or expire webhook records. The stored fields can
include personal or sensitive data such as email addresses, names, subjects,
IP addresses, and user-agent strings. Operators are responsible for access
control, backups, and any retention policy required for their environment.

## Requirements

- Node.js 22 or newer
- npm 10 or newer
- Docker and Docker Compose for the included local PostgreSQL service

## Configuration

Create a `.env` file in the repository root:

```env
RESEND_WEBHOOK_SECRET=whsec_xxxxxxxxxxxxxxxxxxxxx
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/resend_test
```

| Variable | Required | Purpose |
| --- | --- | --- |
| `RESEND_WEBHOOK_SECRET` | Yes | Signing secret for the webhook endpoint configured in Resend |
| `DATABASE_URL` | Yes | PostgreSQL connection URL used by the application and Prisma migrations |

A Resend API key is not required because this service receives webhooks and
does not call the Resend API.

Use `.env` for local Prisma commands. `prisma.config.ts` loads this file with
`dotenv`; a value stored only in `.env.local` will not be available to the
Prisma CLI.

## Local Development

Install dependencies:

```bash
npm ci
```

Start PostgreSQL:

```bash
docker compose up -d
```

Generate the Prisma client and apply existing migrations:

```bash
npm run db:setup
```

Start the development server:

```bash
npm run dev
```

The webhook endpoint is available at:

```text
http://localhost:3000/api/webhooks/resend
```

Resend needs a publicly accessible HTTPS endpoint. To receive real webhook
events during local development, expose the local server with a trusted tunnel
and register this path on the tunnel's public URL.

## Webhook API

### `POST /api/webhooks/resend`

The route reads the raw request body, verifies its signature, classifies the
event, and writes it to the corresponding table.

Required request headers:

| Header | Purpose |
| --- | --- |
| `svix-id` | Unique webhook message identifier used for idempotency |
| `svix-timestamp` | Timestamp included in signature verification |
| `svix-signature` | Svix signature for the raw request body |

Responses:

| Status | Body | Meaning |
| --- | --- | --- |
| `200` | `{"received":true}` | The event was stored or had already been stored |
| `400` | Error response | Required headers are missing or the event type is unsupported |
| `401` | Error response | Signature verification failed |
| `500` | Error response | Configuration or database processing failed |

Resend provides at-least-once delivery and does not guarantee event ordering.
Use event timestamps when chronological processing matters. Non-`200`
responses can cause Resend to retry a delivery.

## Configure Resend

1. Deploy the service to a publicly accessible HTTPS URL.
2. Open the [Webhooks page](https://resend.com/webhooks) in Resend.
3. Add `https://webhooks.example.com/api/webhooks/resend` as the endpoint.
4. Select the email, contact, and domain events the service should receive.
5. Copy the webhook signing secret into `RESEND_WEBHOOK_SECRET` on the host.
6. Redeploy or restart the service after setting the secret.

The secret belongs to the configured webhook endpoint. Do not commit it to the
repository or expose it to client-side code.

## Deployment

### Railway (Recommended)

The checked-in `railway.json` uses the Dockerfile builder and runs
`npm run db:migrate:deploy` before each deployment.

1. Create a Railway project.
2. Add a PostgreSQL service to the project.
3. Add this repository as an application service.
4. Set `DATABASE_URL` to a reference to the PostgreSQL service's connection
   URL.
5. Set `RESEND_WEBHOOK_SECRET` to the signing secret from Resend.
6. Deploy the application service.
7. Generate a Railway domain or attach a custom domain.
8. Configure `https://webhooks.example.com/api/webhooks/resend` in Resend.

Railway builds the image from `Dockerfile`, applies migrations through the
pre-deploy command, and starts the standalone Next.js server. The container
listens on port `3000` by default and accepts a platform-provided `PORT`
override.

### Docker and Other Platforms

Build the image from the repository:

```bash
docker build -t resend-service .
```

Apply the checked-in migrations before starting a new application version:

```bash
docker run --rm \
  -e DATABASE_URL="$DATABASE_URL" \
  resend-service npm run db:migrate:deploy
```

Run the application:

```bash
docker run --rm -p 3000:3000 \
  -e DATABASE_URL="$DATABASE_URL" \
  -e RESEND_WEBHOOK_SECRET="$RESEND_WEBHOOK_SECRET" \
  resend-service
```

The image is OCI-compatible and can be deployed to container services other
than Railway. The target platform must provide:

- A reachable PostgreSQL database
- The two required environment variables
- A migration step before the new application version starts
- Public HTTPS routing to the container
- Appropriate restart, health-check, scaling, backup, and secret-management
  policies

This repository does not currently publish a prebuilt image. Build the image
from the checked-in Dockerfile or configure the target platform to do so.

## Development Commands

| Command | Purpose |
| --- | --- |
| `npm run dev` | Start the Next.js development server |
| `npm run dev:test` | Start Next.js with variables from `.env.test` |
| `npm run build` | Generate Prisma Client and create a production build |
| `npm start` | Start an existing Next.js production build |
| `npm run lint` | Check formatting and lint rules with Biome |
| `npm run lint:fix` | Apply Biome formatting and safe fixes |
| `npm test` | Run the Vitest suite once |
| `npm run test:postgresql` | Run the PostgreSQL integration suite |
| `npm run test:watch` | Run Vitest in watch mode |
| `npm run db:generate` | Regenerate Prisma Client |
| `npm run db:migrate:deploy` | Apply pending migrations without creating new ones |
| `npm run db:setup` | Generate Prisma Client and apply pending migrations |
| `npm run db:studio` | Open Prisma Studio using `.env.test` |
| `npm run db:validate` | Validate the Prisma schema and configuration |

## Testing

The integration suite sends signed webhook fixtures to a running application
and verifies the resulting PostgreSQL rows.

Create an ignored `.env.test` file:

```env
RESEND_WEBHOOK_SECRET=whsec_dGVzdF9zZWNyZXRfa2V5X2Zvcl90ZXN0aW5nXzEyMzQ=
DATABASE_URL=postgresql://postgres:postgres@localhost:5432/resend_test
APP_BASE_URL=http://localhost:3000
```

Prepare the database and start the test server:

```bash
docker compose up -d
npx dotenv -e .env.test -- npm run db:setup
npm run dev:test
```

In another terminal, run:

```bash
npm run test:postgresql
```

The test suite covers all declared event types, duplicate deliveries, invalid
signatures, and missing Svix headers. CI also runs schema validation, linting,
and the production build.

## Database Changes

`prisma/schema.prisma` and the files in `prisma/migrations` are the database
sources of truth. After changing the schema, create a new migration and
regenerate Prisma Client:

```bash
npx prisma migrate dev --name describe_the_change
npm run db:generate
```

Do not manually edit `src/generated/prisma`. It is generated locally and is
excluded from version control.

## Project Structure

```text
.
|-- prisma/
|   |-- migrations/                 # Versioned PostgreSQL migrations
|   `-- schema.prisma               # Prisma data model
|-- src/
|   |-- app/api/webhooks/resend/    # Resend webhook route
|   |-- lib/                        # Prisma and webhook handling
|   `-- types/                      # Resend webhook types
|-- tests/
|   |-- helpers/                    # Fixtures, signing, and DB assertions
|   `-- integration/                # PostgreSQL integration tests
|-- Dockerfile                      # Portable production image
|-- docker-compose.yml              # Local PostgreSQL service
|-- prisma.config.ts                # Prisma CLI configuration
`-- railway.json                    # Recommended Railway deployment settings
```

## Operational Notes

- Preserve the raw request body until Svix verification is complete.
- Treat `RESEND_WEBHOOK_SECRET` and `DATABASE_URL` as secrets.
- Restrict direct database access because stored records can contain personal
  data.
- Monitor `500` responses and database errors; repeated failures prevent event
  persistence and trigger webhook retries.
- The root route is empty and is not a dedicated readiness or liveness check.
- The service has no read API, administrative API, retention job, or event
  replay worker.

## Troubleshooting

### Webhooks are not received

- Confirm the configured URL ends with `/api/webhooks/resend`.
- Confirm the service is publicly accessible over HTTPS.
- Check the webhook delivery and retry history in Resend.

### Signature verification fails

- Confirm `RESEND_WEBHOOK_SECRET` belongs to the configured endpoint.
- Redeploy or restart after changing the secret.
- Ensure proxies preserve the request body unchanged.

### Database insertion fails

- Confirm `DATABASE_URL` is available to both migrations and the application.
- Run `npm run db:migrate:deploy` against the target database.
- Confirm the application can reach PostgreSQL from its runtime network.
- Review application logs for the database error.

### Prisma reports `P3005`

A non-empty database created before Prisma migration tracking must be
[baselined](https://www.prisma.io/docs/orm/prisma-migrate/workflows/baselining)
before `prisma migrate deploy` can manage it. Confirm that its existing schema
matches the initial migration before marking that migration as applied. Do not
reset a production database to resolve this error.

## Resources

- [Resend webhooks](https://resend.com/docs/webhooks/introduction)
- [Resend event types](https://resend.com/docs/webhooks/event-types)
- [Verify webhook requests](https://resend.com/docs/webhooks/verify-webhooks-requests)
- [Webhook retries and replays](https://resend.com/docs/webhooks/retries-and-replays)
- [Railway Dockerfiles](https://docs.railway.com/guides/dockerfiles)
- [Prisma migrations](https://www.prisma.io/docs/orm/prisma-migrate)
