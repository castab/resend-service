# API Agent Handoff

## Authoritative files

- Contract: `public/openapi.json`
- Consumer guide: `docs/api-consumer-guide.md`

## Service purpose

This service is the source of truth for topic-centered email conversations, outbound send intent/state, inbound Resend email projection, and RFC threading ancestry. It is not a general contact or delivery-reporting API.

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
9. Outbound messages use a service-generated conversation address based on `RESEND_REPLY_TO`; callers cannot set it.
10. Eligible RFC ancestry wins over address-token routing. Tokens are used only as a fallback and still require the conversation participant's sender address.
11. Returned HTML is untrusted and must be sanitized before rendering.
12. `accepted` means provider API acceptance, not final delivery.

## Retry and idempotency rules

- Reuse the same `Idempotency-Key` when retrying the same logical send.
- Do not automatically retry a `502` with a new key.
- Drain is safe to invoke repeatedly; server-side batching and provider idempotency handle duplicates.
- Webhook deliveries are at-least-once; duplicates are expected.
- `503` on reply send/enqueue means parent threading metadata retrieval failed; retry later.

## Validation commands

```bash
npm run api:validate
npm run lint
npm run build
```

## Integration test commands

```bash
npm run db:setup
npm run dev:test
npm run test:postgresql
```

## Known unresolved issues

- External gateway exposure rules are not present in the repository.
- Runtime request handling is more permissive than the contract in some places, especially query `limit` parsing and ignored unknown fields.
- Topic lookup does not enforce the documented `externalTopicId` max length.
- Webhook runtime validation is prefix-based rather than full schema validation.
- Some uncaught infrastructure failures may not return the documented JSON error body.
- No formal deprecation or compatibility policy exists beyond `v1` routing.

See `docs/api-consumer-guide.md` for workflow details, error semantics, local setup, and examples.
