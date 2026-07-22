# API Agent Handoff

## Status

The `0.3.0-SNAPSHOT` Kotlin/http4k runtime is a complete port of the Express
service. Its HTTP API is backward compatible with `0.2.0`; only the underlying
stack changed. This source version is unreleased — stable images through `0.2.0`
contain the former Express runtime.

Use the OpenAPI contract, consumer guide, and changelog together;
`/openapi.json` is the authoritative request/response contract.

## Operations

- `GET /api/health/v1`: public; returns `200 {"status":"ok"}` or
  `503 {"status":"unhealthy"}`.
- Conversation create/list/get/assign, topic lookup, synchronous reply, and
  outbox enqueue: require `Authorization: Bearer <CONVERSATION_API_KEY>`.
- `POST /api/conversations/v1/outbox/drain`: requires the dedicated
  `OUTBOX_DRAIN_API_KEY`; claims and sends one outbox batch.
- `POST /api/webhooks/resend/v1`: verifies the Svix signature over the raw body,
  deduplicates by `svix_id`, and projects delivery state and inbound email.
- `/openapi.json` and `/docs` are public supporting resources.

Invalid bearer credentials return `401` with `WWW-Authenticate: Bearer`. A
missing server-side credential returns `500 {"error":"Server misconfiguration"}`.
There is no browser-safe authentication mode.

## Integration rules

1. Health `200` means configuration is complete and the database is reachable;
   it is not a claim about Resend availability.
2. Send/enqueue operations require an `Idempotency-Key` (≤256 chars); reusing a
   key with a different payload returns `409`.
3. Callers cannot choose the sender; `RESEND_FROM` is server-controlled.
4. Do not depend on B3 trace headers; they are implementation behavior, not a
   stable public correlation contract.
5. Keep bearer credentials in server-side configuration only.
6. Treat stored and returned HTML as untrusted; sanitize it before rendering.

## Target acceptance criteria

The runtime enforces the schema and repository rules, including:

- one conversation per external topic and one external participant;
- persisted, idempotent send intent using only the configured sender;
- same-conversation reply parents, RFC Message-ID requirements, and ordered
  References ancestry;
- bounded and retry-safe outbox processing;
- exact raw-body Svix verification and duplicate-safe webhook projection;
- provider-bounded calls and idempotent inbound/delivery projection.

## Consumer checklist

1. Pin the upstream release or image digest.
2. Review the changelog and OpenAPI diff.
3. Supply an `Idempotency-Key` on every send/enqueue call.
4. Test required workflows in a non-production environment.
5. Preserve credential scope and sanitize any returned HTML content.

## Notes

- The port's PostgreSQL/fake-Resend integration suite runs in CI against a
  PostgreSQL 18 service container and is gated on `TEST_DATABASE_URL` locally.
- The default catch-all can expose exception stack traces in uncaught `500`
  responses instead of the controlled JSON error envelope; keep the service
  behind the API gateway.
- No formal deprecation policy exists beyond v1 paths, SemVer, and release notes.
