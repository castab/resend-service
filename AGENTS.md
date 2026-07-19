# Agent Guide

## Project Scope

- Keep this repository focused on the `resend-service` application.
- The current application receives Resend webhooks and stores normalized event
  data in PostgreSQL.
- Railway is the recommended deployment target, but the Docker image must
  remain portable to other OCI-compatible container platforms.
- Do not restore database connectors or deployment integrations from the
  upstream `resend-webhooks-ingester` template without explicit direction.
- Do not document planned capabilities as if they are already implemented.

## Technology

- Use Node.js 22 or newer and npm. Keep `package-lock.json` synchronized.
- The application uses Next.js App Router, React, TypeScript, Prisma, the
  PostgreSQL driver adapter, and Svix.
- Use PostgreSQL as the only application database unless the project scope is
  explicitly changed.
- Follow the checked-in Biome configuration for formatting and lint rules.

## Webhook Invariants

- The public webhook route is `POST /api/webhooks/resend`.
- Verify the exact raw request body before parsing or transforming it. Calling
  `request.json()` before Svix verification breaks signature validation.
- Require `svix-id`, `svix-timestamp`, and `svix-signature` headers.
- Preserve `svix_id` uniqueness and duplicate acknowledgement behavior unless
  webhook retry semantics are deliberately being changed.
- Resend delivery is at least once and unordered. Do not assume a single
  delivery or chronological arrival.
- Keep response behavior intentional: malformed requests are `400`, invalid
  signatures are `401`, accepted or duplicate events are `200`, and processing
  failures are `500` so Resend can retry.
- When adding or changing supported webhook data, update the event types,
  preparation logic, route mapping, Prisma schema, migration, fixtures, and
  integration assertions together.

## Database Rules

- Treat `prisma/schema.prisma` and checked-in migrations as the database
  sources of truth.
- Add a new migration for schema changes. Do not rewrite a migration that may
  already have been deployed.
- Run `npm run db:generate` after changing the Prisma schema.
- Never manually edit or commit `src/generated/prisma`.
- Preserve the database-level uniqueness constraints used for webhook
  idempotency.
- The service currently stores selected normalized fields rather than raw
  webhook payloads. Make any change to that policy explicit.
- Records are retained indefinitely because the application has no deletion,
  truncation, archival, or expiration workflow.
- Treat stored email addresses, names, subjects, IP addresses, user agents, and
  related fields as potentially sensitive data.

## Deployment Rules

- Keep `railway.json` aligned with the Dockerfile and package scripts.
- Railway applies migrations with the pre-deploy command. Preserve the Prisma
  CLI, `prisma.config.ts`, and migration files in the runtime image.
- Generic Docker instructions must include a separate migration step before
  application startup.
- Do not claim that a container image is published unless a working image
  publishing workflow exists.
- Do not couple the application to a specific DNS provider or custom domain.
- The root route is empty and is not a dedicated health endpoint.

## Environment and Secrets

- `RESEND_WEBHOOK_SECRET` and `DATABASE_URL` are required at runtime.
- Prisma CLI commands load `.env` through `prisma.config.ts`; do not assume that
  `.env.local` is available to Prisma.
- Never commit `.env`, `.env.test`, credentials, signing secrets, database URLs,
  or production payloads.
- Do not add a Resend API key unless a new feature actually calls the Resend
  API.

## Development Workflow

- Prefer the smallest correct change and avoid abstractions without a current
  use case.
- Keep webhook and database changes covered by integration tests.
- Use the existing npm scripts rather than introducing another package manager.
- Do not edit generated output in `.next`, `node_modules`, or
  `src/generated/prisma`.
- Update README sections when changing routes, environment variables, stored
  fields, migrations, commands, or deployment behavior.

## Verification

Run the checks relevant to the change. Before completing a substantial change,
prefer the full sequence:

```bash
npm run db:validate
npm run lint
npm run build
npm run test:postgresql
```

The integration suite requires PostgreSQL and a running application configured
with the same test signing secret. Use `docker compose up -d`, apply migrations,
and start the server with `npm run dev:test` before running it locally.

Also run `git diff --check` and review the final diff. Do not revert unrelated
worktree changes.
