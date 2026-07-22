# API Consumer Guide

## Integration status

`resend-service` has completed its runtime migration to Kotlin/http4k. The
`0.3.0-SNAPSHOT` application implements the full conversation, message, outbox,
and webhook surface, and its HTTP API is backward compatible with the previous
Express `0.2.0` runtime — only the underlying stack changed. The authoritative
request/response contract is `src/main/resources/public/openapi.json`
(served at `/openapi.json`).

## Current capabilities

### Health

`GET /api/health/v1` is public and returns only aggregate status:

```json
{"status":"ok"}
```

with `200`, or:

```json
{"status":"unhealthy"}
```

with `503`.

`ok` means all required configuration strings are nonblank, `RESEND_REPLY_TO`
parses as a routing base address, and the configured PostgreSQL connection is
valid. It does not validate the database schema or live Resend access. Any query
string is rejected with `400`.

The database pool is created before the HTTP server. A malformed or initially
unreachable configured database may prevent the process from starting, in which
case there is no health response.

### Static API documentation

- `GET /openapi.json` serves the current OpenAPI 3.1 contract.
- `GET /docs` serves a Swagger UI shell. Its browser assets are loaded from
  unpkg, so the page requires browser access to that CDN.

These supporting routes are public but are not included as API operations in
the OpenAPI contract.

### Authentication boundaries

Conversation operations require:

```http
Authorization: Bearer <CONVERSATION_API_KEY>
```

The outbox drain operation requires a separate credential:

```http
Authorization: Bearer <OUTBOX_DRAIN_API_KEY>
```

The bearer scheme is case-insensitive. Missing, malformed, or incorrect
credentials return:

```http
HTTP/1.1 401 Unauthorized
WWW-Authenticate: Bearer
Content-Type: application/json

{"error":"Unauthorized"}
```

If the relevant server-side credential is blank or absent, the operation
returns:

```http
HTTP/1.1 500 Internal Server Error
Content-Type: application/json

{"error":"Server misconfiguration"}
```

There is no development authentication bypass and no browser-safe credential
mode. Do not embed either bearer credential in frontend code.

The complete header must contain `Bearer`, one or more literal spaces, and a
token containing no literal spaces. Trailing spaces or extra values are
rejected.

## Endpoint summary

| Method | Path | Authentication | Idempotency-Key | Result |
| --- | --- | --- | --- | --- |
| `GET` | `/api/health/v1` | None | No | `200` `ok` or `503` `unhealthy` |
| `POST` | `/api/webhooks/resend/v1` | Svix signature | No | `200` on accepted events |
| `GET` | `/api/conversations/v1` | Conversation bearer | No | Lists unassigned conversations |
| `POST` | `/api/conversations/v1` | Conversation bearer | Yes | Creates a conversation and sends synchronously (`201`) |
| `POST` | `/api/conversations/v1/outbox` | Conversation bearer | Yes | Creates a conversation and enqueues the opening email (`202`) |
| `POST` | `/api/conversations/v1/outbox/drain` | Drain bearer | No | Claims and sends one outbox batch |
| `GET` | `/api/conversations/v1/{conversationId}` | Conversation bearer | No | Returns the conversation and paged messages |
| `PATCH` | `/api/conversations/v1/{conversationId}` | Conversation bearer | No | Assigns a topic to an unassigned conversation |
| `POST` | `/api/conversations/v1/{conversationId}/messages` | Conversation bearer | Yes | Sends a reply synchronously (`201`) |
| `POST` | `/api/conversations/v1/{conversationId}/messages/outbox` | Conversation bearer | Yes | Enqueues a reply (`202`) |
| `GET` | `/api/conversations/v1/topics/{topicType}/{externalTopicId}` | Conversation bearer | No | Returns the conversation for a topic identity |

See `/openapi.json` for full request/response schemas and status codes.

## Webhook ingress

`POST /api/webhooks/resend/v1` verifies the exact raw body against the Svix
`svix-id`, `svix-timestamp`, and `svix-signature` headers, deduplicates by
`svix_id`, projects delivery state for lifecycle events, and projects inbound
email into conversations for `email.received`. It returns `200` for accepted
and duplicate events and `500` (retryable) when required inbound retrieval or
projection cannot complete.

## Request and response conventions

Send bodies are JSON. Requests are limited to 2100 KB and reject compressed
bodies (`415`); message `text`/`html` are limited to 1 MiB each. Malformed JSON
returns `400`.

Controlled application error responses use JSON and an `error` string.
Framework-generated `404` and `405` responses do not use that envelope. The
default catch-all can return exception stack traces for uncaught `500` failures;
keep the service behind the API gateway.

The application enables http4k request tracing, which may add or propagate B3
trace headers. Those headers are not a stable public correlation-ID contract.

Stored and returned HTML is untrusted; consumers must sanitize it before browser
rendering.

## Retry and idempotency guidance

- Health reads may be retried normally.
- Every create, reply, and outbox-enqueue call requires an `Idempotency-Key`
  header (≤256 chars). Retrying with the same key and body returns the stored
  result; reusing a key with a different payload returns `409`.
- Send intent is persisted before any Resend call, so a `502` still leaves a
  durable message record you can query.
- Resend may retry webhook non-2xx responses; the service returns `500` only
  when inbound retrieval or projection cannot complete, so retries are safe and
  idempotent (deduplicated by `svix_id`).

## Behavior guarantees

- One conversation per `(topicType, externalTopicId)` and one external
  participant per conversation.
- Persisted send intent before any Resend call and required idempotency keys on
  send/enqueue operations.
- Server-controlled `RESEND_FROM`; callers cannot select senders.
- Parent selection within the conversation, a required parent RFC Message-ID,
  and ordered `References` ancestry.
- Fixed, bounded outbox batches with persisted membership, bounded retries, and
  provider idempotency safety limits.
- Exact raw-body Svix verification and idempotent webhook projection.
- Retryable failures when required inbound retrieval or projection is incomplete.
- Idempotent projection by Resend email ID and RFC Message-ID.

## Environment and deployment ownership

Operators of the service configure:

- `DATABASE_URL`
- `RESEND_API_KEY`
- `RESEND_WEBHOOK_SECRET`
- `RESEND_FROM`
- `RESEND_REPLY_TO`
- `CONVERSATION_API_KEY`
- `OUTBOX_DRAIN_API_KEY`
- optionally `PORT`

`HOST` is accepted by configuration but is not currently applied to the Jetty
bind address. All Resend settings are used by the ported send and webhook
workflows and contribute to health.

Normal server startup does not run migrations. Operators must run the service
with the single `migrate` argument before startup. The database URL must be a
PostgreSQL URI, and PostgreSQL 18 or newer is required by the UUIDv7 migrations.

## Consumer checks

Before adopting a version:

1. Pin a specific release or image digest.
2. Read its changelog and diff the OpenAPI contract.
3. Supply an `Idempotency-Key` on every send/enqueue call.
4. Exercise the exact workflows in a non-production environment.
5. Keep bearer credentials server-side.
6. Sanitize any returned HTML.

Authenticated request example:

```bash
curl -i \
  -H "Authorization: Bearer $CONVERSATION_API_KEY" \
  "http://localhost:3000/api/conversations/v1?assignment=unassigned"
```

## Compatibility and known gaps

- API path version: `v1`
- OpenAPI version: `3.1.1`
- Application/contract version: `0.3.0-SNAPSHOT`
- Release notes: `CHANGELOG.md`
- Future release image: `castab/resend-service:<version>`; release builds are
  `linux/amd64` only. Stable tags through `0.2.0` contain the former Express
  runtime, not this unreleased Kotlin snapshot.
- No formal deprecation policy exists beyond path versioning and SemVer release
  notes.
- Tests cover both unit-level logic and, under `TEST_DATABASE_URL`, PostgreSQL +
  fake-Resend integration of the conversation, outbox, and webhook workflows.
- The external API gateway configuration is not part of this contract; the
  application itself registers all listed routes.
