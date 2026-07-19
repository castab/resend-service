# Database Package

`@resend-service/database` owns the PostgreSQL schema, migration history,
generated Prisma client, and process-local client factory used by both apps.

## Sources of Truth

```text
prisma/schema.prisma
prisma/migrations/
prisma.config.ts
```

The generated client is written to `src/generated/prisma`, ignored by Git, and
created before application builds.

## Models

- `EmailWebhookEvent`, `ContactWebhookEvent`, and `DomainWebhookEvent` are the
  append-only webhook ledger.
- `EmailConversation` associates one optional external topic with a canonical
  subject and participant.
- `EmailMessage` stores conversation relationships, RFC identifiers, bodies,
  direction, and send state.

Attachments are not represented. Existing webhook idempotency constraints and
message/send idempotency constraints are database-backed.

## Commands

Run from the repository root:

```bash
npm run db:validate
npm run db:generate
npm run db:migrate:deploy
npm run db:studio
```

Both Railway application images retain Prisma CLI, this config, schema, and
migrations. Both run `migrate deploy`; concurrent attempts are coordinated by
Prisma.

Because services deploy independently, use expand/contract migrations. Add new
migrations and never rewrite a migration that may have reached any environment.
