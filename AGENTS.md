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

## API Documentation

- Treat `public/openapi.json` as the machine-readable contract for every live
  application API endpoint. An endpoint addition or behavioral change is
  incomplete until its OpenAPI definition is updated in the same change.
- Document only behavior that is live in the application. Never add planned,
  proposed, or unavailable endpoints to the OpenAPI document.
- For each operation, document its method, path, parameters, required headers,
  authentication or signature requirements, request body, response bodies,
  status codes, and representative examples.
- Document operational semantics that affect callers, including idempotency,
  duplicate handling, ordering, retry behavior, and relevant side effects.
- Keep schemas and examples aligned with the implementation, TypeScript event
  types, fixtures, and integration assertions. Do not use the OpenAPI contract
  to claim validation or rejection behavior that the route does not implement.
- Keep the public Swagger UI at `/docs` synchronized with
  `/openapi.json`. Preserve `/openapi.json` in the standalone production image.
- Run `npm run api:validate` for every API-related change and resolve all errors
  and warnings before considering the change complete.
- The root path intentionally returns `404`; `/docs` is the canonical
  documentation page. Do not restore an empty root page.

## Webhook Invariants

- The public webhook route is `POST /api/webhooks/v1/resend`.
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
  integration assertions together, as well as `public/openapi.json` when the
  public request or response contract changes.

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
- The root path intentionally returns `404` and is not a dedicated health
  endpoint. API documentation is served at `/docs`.

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
- Update `public/openapi.json` in the same change whenever a live endpoint is
  added, removed, or behaviorally changed.

## Verification

Run the checks relevant to the change. Before completing a substantial change,
prefer the full sequence:

```bash
npm run db:validate
npm run api:validate
npm run lint
npm run build
npm run test:postgresql
```

The integration suite requires PostgreSQL and a running application configured
with the same test signing secret. Use `docker compose up -d`, apply migrations,
and start the server with `npm run dev:test` before running it locally.

Also run `git diff --check` and review the final diff. Do not revert unrelated
worktree changes.
