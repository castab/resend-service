# API Consumer Guide

## Integration status

`resend-service` is undergoing a runtime migration to Kotlin/http4k. The current
`0.3.0-SNAPSHOT` application is not ready for production email traffic.

Only aggregate health, static API documentation, and bearer-authentication
boundaries are implemented. Conversation, message, outbox, and webhook
operations are registered but unavailable. Do not build a new email integration
against their former success schemas or treat a healthy response as evidence
that email processing is operational.

## Current capabilities

### Health

`GET /api/health/v1` is public and returns only aggregate status:

```json
{"status":"ok"}
```

with `200`, or:

```json
{"status":"unavailable"}
```

with `503`.

`ok` means all required configuration strings are nonblank and the configured
PostgreSQL connection passes `Connection.isValid(2)`. It does not validate the
database schema, Resend access, webhook verification, or availability of the
stubbed email workflows. Query parameters are currently ignored.

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

| Method | Path | Authentication | Current result |
| --- | --- | --- | --- |
| `GET` | `/api/health/v1` | None | `200` `ok` or `503` `unavailable` |
| `POST` | `/api/webhooks/resend/v1` | None | Always `501`; request is not verified or processed |
| `GET` | `/api/conversations/v1` | Conversation bearer | `501` after authentication |
| `POST` | `/api/conversations/v1` | Conversation bearer | `501` after authentication |
| `POST` | `/api/conversations/v1/outbox` | Conversation bearer | `501` after authentication |
| `POST` | `/api/conversations/v1/outbox/drain` | Drain bearer | `501` after authentication |
| `GET` | `/api/conversations/v1/{conversationId}` | Conversation bearer | `501` after authentication |
| `PATCH` | `/api/conversations/v1/{conversationId}` | Conversation bearer | `501` after authentication |
| `POST` | `/api/conversations/v1/{conversationId}/messages` | Conversation bearer | `501` after authentication |
| `POST` | `/api/conversations/v1/{conversationId}/messages/outbox` | Conversation bearer | `501` after authentication |
| `GET` | `/api/conversations/v1/topics/{topicType}/{externalTopicId}` | Conversation bearer | `501` after authentication |

All authenticated conversation and outbox stubs return:

```json
{"error":"Operation is pending in the Kotlin data-layer port"}
```

The webhook stub returns:

```json
{"error":"Webhook projection is pending in the Kotlin port"}
```

## Webhook warning

`POST /api/webhooks/resend/v1` currently returns `501` without reading the
request body or checking `svix-id`, `svix-timestamp`, or `svix-signature`. It
does not persist a delivery, retrieve inbound content, project a message, or
acknowledge duplicates.

Do not expose this runtime as a functioning Resend webhook destination. The
non-2xx response intentionally avoids acknowledging incomplete work.

## Request and response conventions

The unavailable operations do not currently parse or validate request bodies,
query parameters, path values, `Content-Type`, or `Idempotency-Key`. Their route
templates accept path segments as strings and then apply authentication before
returning `501`.

Controlled application error responses use JSON and an `error` string.
Framework-generated `404` and `405` responses do not use that envelope. The
current default catch-all can return exception stack traces for uncaught `500`
failures; operators must not expose the partial runtime as a public production
service.

The application enables http4k request tracing, which may add or propagate B3
trace headers. Those headers are not a stable public correlation-ID contract.

Stored or returned HTML will be untrusted when conversation reads are restored;
consumers must sanitize it before browser rendering.

## Retry and idempotency guidance

- Health reads may be retried normally.
- Do not retry a stubbed operation expecting a different result; `501` means the
  capability is not implemented in this runtime.
- Resend may retry webhook non-2xx responses, but this service cannot process
  them yet.
- The current runtime does not read `Idempotency-Key` and provides no active
  send or enqueue idempotency guarantee.
- No current provider timeout, outbox lease, retry cadence, or delivery
  projection behavior exists in the Kotlin implementation.

## Target behavior after the port

The retained migrations and repository acceptance criteria define the intended
future behavior, but they are not part of the current executable contract.
Before the corresponding routes can be considered supported, the port must
restore and test at least these guarantees:

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

Consumers must wait for a future contract and release note that moves an
operation from `501` to documented functional responses before using it.

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
bind address. Resend settings other than the conversation credentials currently
contribute to health only because provider and webhook behavior is not ported.

Normal server startup does not run migrations. Operators must run the service
with the single `migrate` argument before startup. The database URL must be a
PostgreSQL URI, and PostgreSQL 18 or newer is required by the retained UUIDv7
migrations.

## Consumer checks

Before adopting a future version:

1. Pin a specific release or image digest.
2. Read its changelog and diff the OpenAPI contract.
3. Confirm that every required operation no longer documents `501`.
4. Exercise the exact workflows in a non-production environment.
5. Keep bearer credentials server-side.
6. Sanitize any returned HTML.

Current authentication-boundary example:

```bash
curl -i \
  -H "Authorization: Bearer $CONVERSATION_API_KEY" \
  http://localhost:3000/api/conversations/v1
```

With a valid configured credential, the expected current result is `501`, not a
conversation payload.

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
- Current tests are unit-level and do not cover PostgreSQL, Resend, Svix,
  migrations, or the intended email workflows.
- The external API gateway configuration is not part of this contract; the
  application itself registers all listed routes.
