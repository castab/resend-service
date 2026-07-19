# Database Package Rules

- This package is the only owner of Prisma schema, migrations, generated client,
  and database client construction.
- Add migrations; never edit deployed migration history.
- Use additive expand/contract rollout steps for shared-schema changes.
- Preserve UUIDv7 primary keys and all webhook/message idempotency constraints.
- Run `npm run db:validate` and `npm run db:generate` after schema changes.
- Never edit or commit `src/generated/prisma`.
- Keep Prisma CLI, config, schema, and migrations usable in both production
  images.
- Do not place HTTP, Svix, or Resend API behavior in this package.
- Treat all email-related columns as sensitive and avoid logging values.
