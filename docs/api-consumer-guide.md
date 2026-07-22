# API Consumer Guide

## Service purpose

`resend-service` owns email conversation state for one external participant per conversation, plus signed Resend webhook ingestion for inbound email and provider event ledgers.

This service is authoritative for:

- Conversation identity by `(topicType, externalTopicId)`
- Message threading by stored parent relationships and RFC `Message-ID` ancestry
- Message send state (`received`, `pending`, `accepted`, `failed`, `indeterminate`)
- Outbound delivery state projected from Resend lifecycle webhooks
- Idempotent send intent persistence
- Projection of inbound Resend email into conversations

This service does not own:

- Browser-facing authentication flows
- Recipient pricing, billing, or quota decisions
- Contact management beyond recording provider webhook events
- Attachment retrieval or storage

Consumers should treat conversation IDs, message IDs, topic assignment, stored threading ancestry, and message state as authoritative here. Consumers should not infer conversation membership from subject lines alone or trust local copies of send state over this service.

## Supported use cases

### 1. Start a synchronous topic conversation

Preconditions:

- Caller has the bearer credential for conversation operations.
- Caller can supply a unique `Idempotency-Key`.
- No existing conversation for the topic has a non-`failed` message.

Sequence:

1. `POST /api/conversations/v1`
2. Optionally specify `message.replyToName` to send the opening message with a display name for the generated Reply-To address.
3. Store returned `conversationId` and `message.id`.
4. If later needed, hydrate with `GET /api/conversations/v1/topics/{topicType}/{externalTopicId}` or `GET /api/conversations/v1/{conversationId}`.

Expected outcome:

- `201` when the opening email is accepted by the provider.
- `200` when the same normalized request is replayed with the same idempotency key.
- `202` when the same stored request is still pending.

Important failure conditions:

- `400` validation failure or missing idempotency header
- `401` missing or invalid bearer token
- `409` topic already has a live conversation, or same key was used for a different normalized request
- `500` server misconfiguration before send intent creation
- `502` provider rejection or indeterminate send outcome after send intent persistence

Consistency:

- Immediate for persisted send intent and returned state.
- Not a delivery confirmation; `accepted` means provider accepted the send API request.
- Delivery confirmation appears later as `deliveryState: "delivered"` after the matching Resend `email.delivered` webhook is projected.

### 2. Send a synchronous reply in an existing conversation

Preconditions:

- Caller has the bearer credential.
- Caller can supply a unique `Idempotency-Key`.
- Conversation exists.
- The selected parent message belongs to the conversation, or the conversation already has at least one `accepted` or `received` message.
- The selected parent can supply an RFC `Message-ID`.

Sequence:

1. `POST /api/conversations/v1/{conversationId}/messages`
2. Optionally specify `replyToMessageId`; otherwise the service selects the latest `accepted` or `received` message.
3. Optionally specify `replyToName` to send that message with a display name for the generated Reply-To address.
4. Read the updated conversation if needed.

Expected outcome:

- `201` for a newly accepted reply.
- `200` or `202` for same-key replay depending on stored state.

Important failure conditions:

- `404` conversation missing or reply parent missing
- `409` selected parent has no RFC `Message-ID`, or idempotency/topic conflict
- `503` parent threading metadata could not be retrieved
- `502` provider rejection or indeterminate send outcome

Consistency:

- Immediate for persisted reply intent.
- `lastMessageAt` updates when the reply intent is created, not when provider delivery completes.

### 3. Queue an opening message or reply for asynchronous delivery

Preconditions:

- Caller has the bearer credential.
- Caller can supply a unique `Idempotency-Key`.
- A separate trusted scheduler or worker can call the drain endpoint.

Sequence:

1. `POST /api/conversations/v1/outbox` for a new conversation, or `POST /api/conversations/v1/{conversationId}/messages/outbox` for a reply.
2. Store returned identifiers.
3. A separate trusted caller invokes `POST /api/conversations/v1/outbox/drain`.
4. Poll conversation state with a GET endpoint if you need the final message state.

Expected outcome:

- `202` with a `pending` message when enqueue succeeds.
- Later drain calls transition the message to `accepted`, `failed`, remain `pending` with retry scheduled, or `indeterminate`.

Important failure conditions:

- Same validation/auth failures as synchronous sends
- `503` for reply parent metadata retrieval failures before enqueue
- Drain returns `200` even when individual messages fail or are rescheduled

Consistency:

- Eventually consistent. Enqueue is immediate; provider delivery happens only when drain is called.

### 4. Read and page through a conversation

Preconditions:

- Caller has the bearer credential.

Sequence:

1. `GET /api/conversations/v1/{conversationId}` or `GET /api/conversations/v1/topics/{topicType}/{externalTopicId}`
2. Optionally send `limit` and `before` to page older messages.

Expected outcome:

- `200` with conversation metadata, chronological message page, and pagination state.

Important failure conditions:

- `400` invalid conversation ID, topic identity, or message cursor
- `404` conversation not found

Consistency:

- Reads usually reflect committed writes immediately.
- Message ordering can shift later when missing outbound provider metadata is hydrated and the provider timestamp replaces the placeholder timestamp.

### 5. List and assign unassigned inbound conversations

Preconditions:

- Caller has the bearer credential.
- Inbound email has already been projected by a signed webhook and has not been matched to a topic-backed conversation.

Sequence:

1. `GET /api/conversations/v1?assignment=unassigned`
2. Select a returned conversation.
3. `PATCH /api/conversations/v1/{conversationId}` with a topic object.

Expected outcome:

- `200` from list and `200` from assignment when successful.

Important failure conditions:

- `400` unsupported assignment filter, invalid cursor, invalid topic object, or invalid conversation ID
- `404` conversation not found
- `409` conversation already assigned or topic already belongs to another conversation

Consistency:

- Immediate for successful assignment.
- Assignment is one-time only.

### 6. Receive signed Resend webhooks

This workflow is for Resend, not for a normal adjacent service client.

Preconditions:

- Caller can generate valid Svix headers for the exact raw body.

Sequence:

1. `POST /api/webhooks/resend/v1` with `svix-id`, `svix-timestamp`, and `svix-signature`.
2. For `email.received`, the service fetches message content and headers from Resend and projects inbound messages. Eligible RFC ancestry is authoritative; a conversation token in `to` or `received_for` is a participant-checked fallback.
3. For outbound lifecycle events, the service stores the webhook ledger and projects delivery status by Resend email ID when a matching outbound message exists.

Expected outcome:

- `200 {"received":true}` on success, including completed duplicate deliveries.

Important failure conditions:

- `400` missing Svix headers or unknown non-family event type
- `401` invalid signature
- `500` projection or inbound retrieval failure; provider should retry

Consistency:

- Eventually consistent for inbound projection repair, outbound metadata hydration, and outbound delivery-state projection.

## Endpoint summary

| Method | Path | Purpose | Authentication | Important success responses | Important error responses | Idempotency |
| --- | --- | --- | --- | --- | --- | --- |
| `GET` | `/api/health/v1` | Readiness check | None | `200`, `503` | `400` | None |
| `POST` | `/api/webhooks/resend/v1` | Receive signed Resend events | Svix headers | `200` | `400`, `401`, `500` | Duplicate `svix-id` is acknowledged after required work succeeds |
| `POST` | `/api/conversations/v1` | Start and send an opening message | Bearer `CONVERSATION_API_KEY` | `201`, `200`, `202` | `400`, `401`, `409`, `500`, `502` | Required `Idempotency-Key`; key is retained indefinitely |
| `GET` | `/api/conversations/v1?assignment=unassigned` | List unassigned inbound conversations | Bearer `CONVERSATION_API_KEY` | `200` | `400`, `401`, `500` | None |
| `POST` | `/api/conversations/v1/outbox` | Enqueue opening message | Bearer `CONVERSATION_API_KEY` | `202`, `200` | `400`, `401`, `409`, `500`, `502` | Required `Idempotency-Key`; global across send modes |
| `POST` | `/api/conversations/v1/outbox/drain` | Deliver one bounded outbox batch | Bearer `OUTBOX_DRAIN_API_KEY` | `200` | `400`, `401`, `500` | Batch membership and provider idempotency are persisted server-side |
| `GET` | `/api/conversations/v1/{conversationId}` | Read a conversation by service ID | Bearer `CONVERSATION_API_KEY` | `200` | `400`, `401`, `404`, `500` | None |
| `PATCH` | `/api/conversations/v1/{conversationId}` | Assign an unassigned conversation to a topic | Bearer `CONVERSATION_API_KEY` | `200` | `400`, `401`, `404`, `409`, `500` | None |
| `POST` | `/api/conversations/v1/{conversationId}/messages` | Send a reply | Bearer `CONVERSATION_API_KEY` | `201`, `200`, `202` | `400`, `401`, `404`, `409`, `500`, `502`, `503` | Required `Idempotency-Key`; key is retained indefinitely |
| `POST` | `/api/conversations/v1/{conversationId}/messages/outbox` | Enqueue a reply | Bearer `CONVERSATION_API_KEY` | `202`, `200` | `400`, `401`, `404`, `409`, `500`, `502`, `503` | Required `Idempotency-Key`; global across send modes |
| `GET` | `/api/conversations/v1/topics/{topicType}/{externalTopicId}` | Read a conversation by external topic | Bearer `CONVERSATION_API_KEY` | `200` | `400`, `401`, `404`, `500` | None |

The machine-readable OpenAPI contract published by `resend-service` is authoritative for field schemas.

## Authentication and authorization

### Conversation API

- Header: `Authorization: Bearer <CONVERSATION_API_KEY>`
- Applies to all conversation read, write, and enqueue operations.
- Missing or invalid credential returns `401 {"error":"Unauthorized"}` and `WWW-Authenticate: Bearer`.
- If the server-side expected credential is unset, the service returns `500 {"error":"Server misconfiguration"}`.

### Outbox drain

- Header: `Authorization: Bearer <OUTBOX_DRAIN_API_KEY>`
- The ordinary conversation credential is not accepted.
- This endpoint is intended for a trusted scheduler or worker, not a normal application client.

### Webhook ingress

- Required headers: `svix-id`, `svix-timestamp`, `svix-signature`
- There is no bearer token.
- Invalid signature returns `401 {"error":"Invalid webhook signature"}` without `WWW-Authenticate`.

### Browser usage

- Do not embed these credentials in browser code.
- The repository supports service-to-service usage only.

### Local development behavior

- There is no auth bypass in development.
- Local runs still require configured credentials in the environment.
- `403` does not occur in the current application implementation. Valid shared credentials authorize every operation within their scope; invalid credentials return `401`.

## Request conventions

- Content type: JSON bodies are accepted by application routes through `request.json()`. The implementation does not enforce `Content-Type`, but clients should send `Content-Type: application/json`.
- Health checks: `GET /api/health/v1` accepts no query parameters; any query string returns `400`.
- Identifiers:
  - `conversationId` and `messageId` are UUIDs.
  - `topicType` matches `^[a-z][a-z0-9_-]{0,63}$`.
  - `externalTopicId` is a nonempty string and is documented as max 255 characters.
- Timestamps: ISO 8601 strings with timezone offsets; stored and returned values are observed as UTC `Z` timestamps.
- Time zone expectations: use absolute timestamps; do not assume local server time.
- Currency: not applicable; this API exposes no money fields.
- Null versus omitted fields:
  - Request: optional fields may be omitted.
  - Response: several nullable fields are always present as `null` when absent.
- Unknown fields:
  - Request validators ignore unknown object properties.
  - Ignored fields do not change idempotency comparison.
- Case sensitivity:
  - `topicType` is case-sensitive and lower-case by validation.
  - Bearer scheme matching is case-insensitive.
  - Message IDs and topic external IDs should be treated as exact strings.
- Validation rules not obvious from schema alone:
  - `participant.email` is limited to 320 characters by runtime validation.
  - Topic titles, participant names, and subjects reject ASCII control characters.
  - Topic titles must contain at least one non-whitespace character after trimming.
  - Message `text` and `html` must be nonempty strings when present.
  - Optional `replyToName` is per message, rejects ASCII control characters and `<`/`>`, is limited to 256 characters, and blank or null values are omitted.
  - Blank optional `participant.name` becomes `null`.
  - Blank optional `subject` is omitted and defaults to the normalized topic title.
  - String length checks use JavaScript string length, including the 1 MiB body limit and the 255/256-character header-field limits.
  - Subject normalization strips leading `Re:`, `Fw:`, and `Fwd:` prefixes case-insensitively.

## Response conventions

- Standard envelope: most successful conversation write operations return:

```json
{
  "conversationId": "019...",
  "message": { "...": "..." }
}
```

- Conversation reads return the conversation object directly; there is no outer envelope.
- Error envelope: controlled application errors use:

```json
{
  "error": "Human-readable message"
}
```

- Correlation/request IDs: none are defined by the application contract.
- Pagination:
  - Conversation message pagination uses `limit` and `before=<message UUID>`.
  - Unassigned-conversation listing uses an opaque `before` cursor returned by the service.
- Sorting:
  - Conversation messages are returned oldest-to-newest within each page.
  - Unassigned conversations are returned newest-first by `lastMessageAt`.
- Filtering:
  - The only supported list filter is `assignment=unassigned`.
- Empty results:
  - Unassigned list returns an empty `conversations` array.
  - Conversation reads return `404`, not an empty object, when missing.
- Partial success:
  - Drain responses are aggregate results for a batch and may report failed, retried, or indeterminate work while still returning `200`.
  - The current implementation normally finalizes an entire batch uniformly, so mixed per-item outcomes in one response should not be treated as a stable expectation.

## Error semantics

There is no separate machine-readable error code field. The `error` string is the only controlled error discriminator.

### `400 Bad Request`

- Causes: malformed JSON, unsupported query/filter values, invalid IDs, invalid headers, invalid topic/message payloads, missing Svix headers.
- Retry: only after changing the request.
- End-user safe: generally yes, after mapping to friendly copy.

Common observed `error` values:

- `Request body must be valid JSON`
- `A valid Idempotency-Key header is required`
- `topic and participant objects are required`
- `topic type, externalId, and title are invalid`
- `participant.email must be a valid email address`
- `message.text or message.html is required`
- `text or html is required`
- `Invalid conversation ID`
- `Invalid message cursor`
- `Only assignment=unassigned is supported`
- `Invalid conversation cursor`
- `Missing required Svix headers`

### `401 Unauthorized`

- Causes: missing or invalid bearer token; invalid webhook signature.
- Retry: only with corrected credentials or regenerated signature.
- End-user safe: do not expose raw secrets; a generic unauthorized message is safe.
- There is no separate authorization failure status in the current implementation; scoped shared credentials either pass or return `401`.

### `404 Not Found`

- Causes: missing conversation, missing reply parent, missing topic-backed conversation.
- Retry: only after using a valid identifier or after the resource exists.

### `409 Conflict`

- Causes:
  - same idempotency key used for a different normalized request
  - topic already has a conversation with at least one non-`failed` message
  - assignment race or already-assigned conversation
  - explicit or implicit reply parent lacks an RFC `Message-ID`
- Retry: only after changing the request or resolving state.
- End-user safe: usually yes, in simplified form.

Common observed `error` values:

- `Idempotency key was already used for a different request`
- `Idempotency key is already in use`
- `A conversation already exists for this topic`
- `Conversation is already assigned to a topic`
- `Reply parent threading metadata is unavailable`

### `500 Internal Server Error`

- Causes: missing required server configuration, persistence failures, webhook projection or inbound retrieval failures, uncaught infrastructure/database failures.
- Retry: maybe. Safe only when the operation is idempotent or uses the same idempotency key.
- End-user safe: use generic failure text.

Common observed `error` values:

- `Server misconfiguration`
- `Failed to process webhook`
- `Failed to drain email outbox`

### `502 Bad Gateway`

- Causes: provider rejected the send, or the send outcome is indeterminate after intent persistence.
- Retry: do not automatically retry with a new idempotency key. First replay the same request with the same key or inspect the stored message state.
- End-user safe: use generic send failure text.

Observed response shapes include the stored identifiers:

```json
{
  "error": "Failed to send email",
  "conversationId": "019...",
  "message": {
    "id": "019...",
    "state": "failed"
  }
}
```

or, on replay of a failed or indeterminate stored request:

```json
{
  "error": "Email was not confirmed as sent",
  "conversationId": "019...",
  "message": {
    "id": "019...",
    "state": "failed"
  }
}
```

### `503 Service Unavailable`

- Causes: reply parent threading metadata could not be retrieved.
- Retry: yes, with backoff, reusing the same idempotency key if the request was already persisted or simply retrying if it failed before persistence.
- End-user safe: generic retry-later message is safe.

## Retry, timeout, and idempotency guidance

- Recommended client timeout: unresolved by the public contract. The implementation uses a 15-second outbound provider timeout and some longer database windows, but no official client-facing timeout recommendation is published.
- Safe retry guidance:
  - For send or enqueue operations, always reuse the same `Idempotency-Key` when retrying the same logical action.
  - For reads, normal HTTP retries are safe.
  - For webhook deliveries, the provider should retry non-200 responses.
- Backoff expectations:
  - No client backoff policy is published.
  - Outbox retries are server-managed, not client-managed.
  - Observed implementation behavior: the outbox uses a 2-minute lease, then retries after 1 minute, 2 minutes, and 5 minutes, bounded by a 23-hour provider idempotency safety window.
- Idempotency-key support:
  - Required on every operation that creates or enqueues an outbound message.
  - Keys are retained indefinitely.
  - Keys are globally unique across synchronous and outbox modes.
- Duplicate-request behavior:
  - Same key + same normalized request returns existing persisted state.
  - Same key + different normalized request returns `409`.
  - Omitted, blank, and null `replyToName` values are normalized as absent; a nonblank alias participates in idempotency comparison.
- At-least-once effects:
  - Webhooks are at-least-once and duplicate deliveries are expected.
  - Outbox drain is designed for repeated invocation.
- Operations that must not be automatically retried with a new idempotency key:
  - `POST /api/conversations/v1`
  - `POST /api/conversations/v1/outbox`
  - `POST /api/conversations/v1/{conversationId}/messages`
  - `POST /api/conversations/v1/{conversationId}/messages/outbox`

## Consistency and lifecycle behavior

- Eventual consistency:
  - Inbound `email.received` webhook projection may repair earlier inbound/outbound relationships later.
  - Outbound provider metadata hydration can update stored RFC `Message-ID` and timestamps later.
  - Outbound delivery state updates when matching Resend lifecycle webhooks are projected by Resend email ID.
- Asynchronous processing:
  - Outbox delivery only occurs when the drain endpoint is called.
  - Drain processes at most one bounded batch per request.
- Message states:
  - `received`: inbound projected email
  - `pending`: persisted outbound send intent waiting on confirmation or outbox delivery
  - `accepted`: provider accepted the send API request
  - `failed`: terminal send failure
  - `indeterminate`: terminal ambiguous outcome; provider acceptance could not be confirmed safely
- Outbound delivery states:
  - `unknown`: no delivery lifecycle webhook has been projected yet
  - `delivered`: Resend `email.delivered` webhook was projected; `deliveredAt` is set from that event timestamp
  - `delivery_delayed`: Resend reported a temporary delivery delay and no terminal delivery outcome has superseded it
  - `bounced`, `complained`, `suppressed`, `failed`: Resend reported that delivery lifecycle outcome and it must not be shown as delivered
  - `null`: inbound message; delivery state is outbound-only
- Engagement webhooks:
  - `email.opened` and `email.clicked` are ingested into the webhook ledger when configured in Resend, but they are not delivery confirmations and do not change `deliveryState`.
- Terminal vs nonterminal:
  - Nonterminal: `pending`
  - Terminal: `received`, `accepted`, `failed`, `indeterminate`
- Races:
  - Concurrent identical idempotent requests collapse to one persisted message.
  - Concurrent topic assignment allows one winner and one `409` loser.
- Read-after-write:
  - Usually immediate for committed state.
  - Conversation ordering can later change when the provider timestamp replaces an earlier placeholder timestamp.
- Assignment caveat:
  - `PATCH /api/conversations/v1/{conversationId}` commits the assignment before serializing the response. A rare post-commit `500` can therefore leave the assignment applied, and a safe retry may then return `409`.
- Deletion/cancellation/expiration:
  - No public delete or cancel API exists.
  - Webhook ledgers and projected conversations are retained indefinitely per repository docs.

## Business invariants

- One conversation is authoritative for one `(topicType, externalTopicId)` pair.
- A topic cannot be assigned to two conversations.
- Topic assignment is one-time only.
- A topic conversation can only be reopened when every existing message for that topic is `failed`.
- One external participant address belongs to a conversation.
- Reply parents must belong to the conversation.
- The service never sends a reply without an RFC parent `Message-ID`.
- `References` are built from the selected parent’s stored ancestry.
- The caller cannot choose the sender mailbox; the service always uses configured `RESEND_FROM`.
- Returned HTML is untrusted and must be sanitized before rendering in a browser.

## Compatibility and versioning

- Current API version: `v1`
- Contract file: published upstream `openapi.json`
- OpenAPI version: `3.1.1`
- Contract/package version observed in repository: `0.2.0`
- Release notes: `CHANGELOG.md`
- Docker image tags: `castab/resend-service:<version>`
- Backward-compatibility expectations: not formally documented beyond path versioning and SemVer release notes.
- Deprecation process: unresolved; no `deprecated` markers or sunset policy were found.
- Breaking changes: consumers should monitor `public/openapi.json`, changelog entries, and route version changes.
- Consumer pinning guidance: pin to a specific Docker or contract version and diff future updates before upgrading.

## Environment and integration setup

If you are consuming this API from another repository, treat this section as an
integration checklist rather than a promise that local `resend-service`
repository scripts are available.

Base URL:

- Use the deployed host for your target environment.
- If you are running a private copy of the service yourself, `GET /api/health/v1`
  is the readiness endpoint.

Required runtime configuration on the `resend-service` side:

- `DATABASE_URL`
- `RESEND_API_KEY`
- `RESEND_WEBHOOK_SECRET`
- `RESEND_FROM`
- `RESEND_REPLY_TO`
- `CONVERSATION_API_KEY`
- `OUTBOX_DRAIN_API_KEY`

Configuration names observed for maintainers and private test environments, not normal consumer integrations:

- `RESEND_API_BASE_URL`
- `TEST_DATABASE_URL`
- `APP_BASE_URL`

Recommended consumer-side validation before using a new environment or
upgrading versions:

1. Confirm the base URL and bearer credential for the target environment.
2. Check `GET /api/health/v1` if the endpoint is reachable from your network.
3. Compare your request payloads against the latest upstream OpenAPI contract.
4. Exercise the exact workflows your system uses in a non-production
   environment.
5. Review upstream release notes and contract diffs before adopting a new
   version.

## Consumer examples

### Start a synchronous conversation

```bash
curl -i \
  -X POST http://localhost:3000/api/conversations/v1 \
  -H "Authorization: Bearer <CONVERSATION_API_KEY>" \
  -H "Idempotency-Key: booking-4821-opening" \
  -H "Content-Type: application/json" \
  -d '{
    "topic": {
      "type": "booking",
      "externalId": "4821",
      "title": "Booking 4821"
    },
    "participant": {
      "email": "person@example.com",
      "name": "Person"
    },
    "message": {
      "text": "Your booking request was received."
    }
  }'
```

Success response:

```json
{
  "conversationId": "019808b9-37c4-7ed7-93c5-8960f0b690aa",
  "message": {
    "id": "019808b9-37c4-7ed7-93c5-8960f0b690ab",
    "parentMessageId": null,
    "direction": "outbound",
    "state": "accepted",
    "stateDetail": null,
    "deliveryState": "unknown",
    "deliveryStateDetail": null,
    "deliveredAt": null,
    "resendEmailId": "sent_123",
    "internetMessageId": "<sent-1@resend.test>",
    "from": {
      "address": "mailbox@example.com",
      "name": "Mailbox"
    },
    "to": "person@example.com",
    "replyTo": "mailbox+c_8f2a1b9d4f8c4fd2a7319df35a6c041e@replies.example.com",
    "replyToName": null,
    "subject": "Booking 4821",
    "text": "Your booking request was received.",
    "html": null,
    "createdAt": "2026-07-18T12:00:00.000Z"
  }
}
```

Important failure response:

```json
{
  "error": "A conversation already exists for this topic"
}
```

### Send a reply with explicit retry discipline

Pseudocode:

```text
generate idempotency key once
POST reply
if 201/200/202: store message ID and state
if 502: do not generate a new key yet
if 502: GET conversation or replay same request with same key to reconcile stored state
if 503: retry later with backoff
```

Example request:

```bash
curl -i \
  -X POST http://localhost:3000/api/conversations/v1/<conversationId>/messages \
  -H "Authorization: Bearer <CONVERSATION_API_KEY>" \
  -H "Idempotency-Key: booking-4821-reply-1" \
  -H "Content-Type: application/json" \
  -d '{
    "text": "Here is the requested update.",
    "replyToName": "Brayan"
  }'
```

### Enqueue and drain asynchronously

Enqueue:

```bash
curl -i \
  -X POST http://localhost:3000/api/conversations/v1/outbox \
  -H "Authorization: Bearer <CONVERSATION_API_KEY>" \
  -H "Idempotency-Key: booking-4821-queued-opening" \
  -H "Content-Type: application/json" \
  -d '{
    "topic": {
      "type": "booking",
      "externalId": "4821",
      "title": "Booking 4821"
    },
    "participant": {
      "email": "person@example.com",
      "name": "Person"
    },
    "message": {
      "text": "Opening message",
      "replyToName": "Brayan"
    }
  }'
```

Drain:

```bash
curl -i \
  -X POST http://localhost:3000/api/conversations/v1/outbox/drain \
  -H "Authorization: Bearer <OUTBOX_DRAIN_API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{"limit":100}'
```

Representative drain response:

```json
{
  "batchId": "019808b9-37c4-7ed7-93c5-8960f0b690ff",
  "claimed": 2,
  "accepted": 2,
  "failed": 0,
  "retryScheduled": 0,
  "indeterminate": 0,
  "results": [
    {
      "messageId": "019808b9-37c4-7ed7-93c5-8960f0b690ab",
      "state": "accepted",
      "resendEmailId": "batch-1"
    },
    {
      "messageId": "019808b9-37c4-7ed7-93c5-8960f0b690ac",
      "state": "accepted",
      "resendEmailId": "batch-2"
    }
  ]
}
```

### List and assign an unassigned inbound conversation

List:

```bash
curl -i \
  -X GET "http://localhost:3000/api/conversations/v1?assignment=unassigned" \
  -H "Authorization: Bearer <CONVERSATION_API_KEY>"
```

Assign:

```bash
curl -i \
  -X PATCH http://localhost:3000/api/conversations/v1/<conversationId> \
  -H "Authorization: Bearer <CONVERSATION_API_KEY>" \
  -H "Content-Type: application/json" \
  -d '{
    "topic": {
      "type": "booking",
      "externalId": "9911",
      "title": "Booking 9911"
    }
  }'
```

## Known gaps and unresolved questions

- Service documentation says an external API gateway controls public exposure,
  but no gateway config is published with the service contract. The
  application itself exposes all routes on the application host.
- The implementation accepts some more-permissive inputs than the contract advertises, especially lenient `limit` parsing on GET endpoints. The public contract remains strict: consumers should send integer `limit` values from `1` through `100`.
- Unknown request object properties are allowed by both the schema and the implementation, and they do not affect idempotency comparison.
- Topic lookup does not enforce the documented 255-character maximum for `externalTopicId`; create and assignment operations do enforce it. The public contract remains 255 characters across topic identities.
- Webhook runtime validation is prefix-based, not full schema validation. Signed `email.*`, `contact.*`, or `domain.*` events outside the documented enum can reach persistence and fail with implementation-dependent results; consumers should only send the documented Resend event types.
- Some uncaught infrastructure failures may not return the documented JSON error envelope.
- No formal client timeout recommendation is published.
- No formal backward-compatibility or deprecation policy is published beyond `v1` path versioning.
- No correlation/request ID header is defined.
- Health-check behavior, successful unassigned-list pagination, and several input edge cases are not covered by integration tests.
- Provider-sourced inbound address fields can be malformed or empty in edge cases, while the contract documents the intended normal shape.

## Service transport

The service uses an Express 5 HTTP transport. This runtime detail does not change the documented URLs, authentication schemes, request bodies, or response contracts.
