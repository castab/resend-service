# Webhook Application Rules

- This app is public ingress. Do not add private conversation operations here.
- Keep the route exactly `POST /api/webhooks/resend/v1`.
- Verify the exact raw body before JSON transformation.
- Require `svix-id`, `svix-timestamp`, and `svix-signature`.
- Keep duplicate deliveries idempotent through the database `svix_id`
  constraint and acknowledge completed duplicates with `200`.
- Return `500` when required inbound retrieval or projection fails so Resend can
  retry. Do not acknowledge incomplete projection work.
- Keep Svix verification and webhook-to-database HTTP mapping in this app.
- Put provider-neutral types and threading logic in `packages/email`.
- Do not fetch attachments.
- Update this app's OpenAPI contract, fixtures, and PostgreSQL assertions for
  every webhook request or response change.
- Preserve the public `/docs` and `/openapi.json` assets in the production image.
