# API Agent Handoff

## Authoritative files

- OpenAPI contract published by this service
- Consumer integration guide published by this service
- Release notes and versioned Docker image tags published by this service

If you are reading this from a consumer repository, treat the upstream
`openapi.json`, consumer guide, and release notes from `resend-service` as the
source of truth. Do not assume local repo scripts, commands, or agent skills
exist unless your repository defines them separately.

## Service purpose

This service is the source of truth for topic-centered email conversations, outbound send intent/state, inbound Resend email projection, outbound delivery-state projection from Resend lifecycle webhooks, and RFC threading ancestry. It is not a general contact or engagement analytics API.

## Primary workflows

1. Start a synchronous conversation with `POST /api/conversations/v1`
2. Send a synchronous reply with `POST /api/conversations/v1/{conversationId}/messages`
3. Enqueue opening messages or replies through the outbox endpoints
4. Drain queued messages with `POST /api/conversations/v1/outbox/drain`
5. Read conversations by service ID or by external topic
6. List and assign unassigned inbound conversations
7. Receive signed Resend webhooks at `POST /api/webhooks/resend/v1`

## Authentication summary

- Conversation API: `Authorization: Bearer <CONVERSATION_API_KEY>`
- Outbox drain only: `Authorization: Bearer <OUTBOX_DRAIN_API_KEY>`
- Webhooks: `svix-id`, `svix-timestamp`, `svix-signature`
- No browser-safe auth mode exists.

## Most important constraints

1. One conversation is authoritative for one `(topicType, externalTopicId)` pair.
2. Topic assignment is one-time only.
3. A topic conversation can reopen only when every existing message is `failed`.
4. Every send/enqueue request must include `Idempotency-Key`.
5. Idempotency keys are retained indefinitely and are globally unique across synchronous and outbox modes.
6. Idempotency compares normalized validated fields, not literal JSON bodies.
7. Replies require a parent message in the same conversation and the service will not send a reply without an RFC parent `Message-ID`.
8. The caller cannot choose the sender mailbox; the service always uses configured `RESEND_FROM`.
9. Outbound messages use a service-generated conversation address based on `RESEND_REPLY_TO`; callers cannot set the address but may set an optional per-message `replyToName` display name.
10. Eligible RFC ancestry wins over address-token routing. Tokens are used only as a fallback and still require the conversation participant's sender address.
11. Returned HTML is untrusted and must be sanitized before rendering.
12. `accepted` means provider API acceptance, not final delivery.
13. `deliveryState: "delivered"` means a matching Resend `email.delivered` webhook was projected.
14. `email.opened` and `email.clicked` are ingested when configured but are not delivery confirmations.
15. `403` does not occur in the current implementation; invalid scoped credentials return `401`.
16. Drain responses can report failure or retry work with `200`, but current batch finalization is normally uniform across all items in one response.

## Retry and idempotency rules

- Reuse the same `Idempotency-Key` when retrying the same logical send.
- Do not automatically retry a `502` with a new key.
- Drain is safe to invoke repeatedly; server-side batching and provider idempotency handle duplicates.
- Webhook deliveries are at-least-once; duplicates are expected.
- `503` on reply send/enqueue means parent threading metadata retrieval failed; retry later.
- Observed outbox retry cadence is 1 minute, 2 minutes, then 5 minutes, with a 23-hour provider idempotency safety window.

## Consumer integration checklist

When copying this handoff into another service, validate your own integration
against the upstream contract and your target environment.

1. Store the correct base URL and bearer credential for the environment.
2. Keep a stable `Idempotency-Key` for each logical send or enqueue attempt.
3. Confirm your request shapes still match the latest upstream OpenAPI contract.
4. Exercise the workflows you use in a non-production environment: create, reply, queue, drain, and fetch afterward as applicable.
5. Sanitize returned HTML before rendering it anywhere user-visible.
6. Diff upstream contract and release-note changes before upgrading versions.

## Known unresolved issues

- External gateway exposure rules are not published with the service contract.
- Runtime request handling is more permissive than the contract in some places,
  especially query `limit` parsing. Consumers should follow the strict OpenAPI
  schema and send integer limits from `1` through `100`.
- Unknown request object properties are allowed by both the contract and the
  implementation, and they do not affect idempotency comparison.
- Topic lookup does not enforce the documented `externalTopicId` max length,
  although create and assignment operations do.
- Webhook runtime validation is prefix-based rather than full schema
  validation; only the documented Resend event enums are supported public
  contract.
- Some uncaught infrastructure failures may not return the documented JSON
  error body.
- No formal deprecation or compatibility policy exists beyond `v1` routing.

See the upstream consumer guide for workflow details, error semantics,
environment setup, and examples.

## Transport implementation

The API is served by Express 5. Route behavior lives under `src/routes`, while route ordering, request-body policy, local Swagger assets, and terminal JSON errors are configured in `src/server.ts`.
