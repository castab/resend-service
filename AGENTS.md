# Agent Guide

## Repository Scope

- This is an npm-workspaces monorepo with two deployable applications and two
  shared server packages.
- `apps/webhook` is public ingress. `apps/conversation-api` is private Railway
  API traffic only.
- `packages/database` owns all database schema and client generation.
- `packages/email` owns shared email types, Resend access, threading, and
  projection logic.
- Do not document proposed behavior as live. Update documentation in the same
  change that makes behavior live.

## Technology

- Use Node.js 22 or newer and npm. Keep the root `package-lock.json` current.
- Both applications use Next.js App Router and TypeScript.
- Use Prisma with the PostgreSQL driver adapter and PostgreSQL 18 or newer.
- Use Svix only in the webhook application.
- Follow the root Biome configuration.

## Boundaries

- Do not import source code across app directories. Move shared behavior into a
  specifically named package.
- Do not import database runtime code into browser components.
- Keep webhook verification and HTTP mapping in `apps/webhook`.
- Keep private API authentication and route validation in
  `apps/conversation-api`.
- Keep provider-neutral RFC threading behavior in `packages/email`.

## API Convention

- Use `/api/{capability}/{integration?}/v{major}`.
- The public webhook route is exactly `POST /api/webhooks/resend/v1`.
- Conversation routes use `/api/conversations/v1`.
- Do not restore `/api/webhooks/v1/resend` without explicit direction.
- Each application owns a complete OpenAPI contract in its `public` directory.
- Every live route change must update that application's contract and tests.
- Run `npm run api:validate` after API changes.

## Database Rules

- Treat `packages/database/prisma/schema.prisma` and checked-in migrations as
  authoritative.
- Add migrations; never rewrite migrations that may have been deployed.
- Use additive, expand/contract changes because Railway services deploy
  independently.
- Run `npm run db:generate` after schema changes.
- Never edit or commit `packages/database/src/generated/prisma`.
- Preserve webhook and send idempotency constraints.
- Treat addresses, subjects, bodies, headers, and delivery metadata as
  sensitive.

## Deployment

- Keep both Dockerfiles and app-specific Railway configs aligned with workspace
  scripts.
- Keep the repository root as Docker and Railway build context.
- Both production images must retain Prisma CLI, schema, and migrations for the
  pre-deploy command.
- The webhook app may have a public domain. The conversation app must not.
- Both apps must continue to honor Railway's `PORT` variable.

## Documentation

- Keep the root README architectural and operational.
- Put app-specific routes, variables, and behavior in the app README.
- Put schema and generation details in the database package README.
- Put RFC threading and projection details in the email package README.
- Nested `AGENTS.md` files add local invariants; do not duplicate or contradict
  root rules.

## Workflow

- Prefer bounded network calls and test commands. Do not introduce unbounded
  polling or retries.
- Keep integration tests isolated from the real Resend API.
- Do not edit generated `.next`, Prisma client, or `node_modules` output.
- Do not revert unrelated worktree changes.

## Verification

Run relevant checks with explicit time bounds. For substantial changes run:

```bash
npm run db:validate
npm run api:validate
npm run lint
npm run build
npm run test:postgresql
git diff --check
```

The PostgreSQL suite requires both applications, PostgreSQL, and the test
environment described in the root README.
