# API Agent Handoff

## Blocking status

The current `0.3.0-SNAPSHOT` Kotlin/http4k runtime is a partial port. Do not
integrate it for production email traffic and do not use historical conversation
success schemas. Health and authentication boundaries are implemented; email
workflows are not.

This source version is unreleased. Stable images through `0.2.0` contain the
former Express runtime.

Use the upstream OpenAPI contract, consumer guide, and changelog together. The
OpenAPI contract is intentionally limited to behavior reachable in the current
runtime.

## Current operations

- `GET /api/health/v1`: public; returns `200 {"status":"ok"}` or
  `503 {"status":"unavailable"}`.
- Conversation reads/writes and outbox enqueue: require
  `Authorization: Bearer <CONVERSATION_API_KEY>`, then return `501`.
- `POST /api/conversations/v1/outbox/drain`: requires the dedicated
  `OUTBOX_DRAIN_API_KEY`, then returns `501`.
- `POST /api/webhooks/resend/v1`: always returns `501`; it does not verify Svix
  headers or process the body.
- `/openapi.json` and `/docs` are public supporting resources.

Invalid bearer credentials return `401` with `WWW-Authenticate: Bearer`. A
missing server-side credential returns `500 {"error":"Server misconfiguration"}`.
There is no browser-safe authentication mode.

## Integration rules

1. Do not treat health `200` as email-workflow readiness; it checks configured
   strings and database connectivity only.
2. Do not send real webhook traffic to this runtime.
3. Do not expect request-body, identifier, query, content-type, or idempotency
   validation from a stubbed operation.
4. Do not depend on B3 trace headers; they are implementation behavior, not a
   stable public correlation contract.
5. Wait for an upstream release and OpenAPI change that replaces `501` before
   implementing conversation, send, outbox, or webhook workflows.
6. Keep bearer credentials in server-side configuration only.
7. Sanitize returned HTML when conversation reads are eventually restored.

## Target acceptance criteria

The retained schema and repository rules describe future behavior, not current
behavior. The completed port must restore tested guarantees for:

- one conversation per external topic and one external participant;
- persisted, idempotent send intent using only the configured sender;
- same-conversation reply parents, RFC Message-ID requirements, and ordered
  References ancestry;
- bounded and retry-safe outbox processing;
- exact raw-body Svix verification and duplicate-safe webhook projection;
- provider-bounded calls and idempotent inbound/delivery projection.

Do not encode these target rules as active client behavior until the upstream
contract and release notes confirm their implementation.

## Consumer checklist

1. Pin the upstream release or image digest.
2. Review the changelog and OpenAPI diff.
3. Confirm required routes have functional non-`501` responses.
4. Test required workflows in a non-production environment.
5. Preserve credential scope and sanitize any HTML content.

## Known gaps

- No PostgreSQL/fake-Resend integration suite currently exists in the Kotlin
  runtime.
- Webhook verification, provider calls, persistence, idempotency, threading
  workflow integration, and outbox processing are pending.
- The default catch-all can expose exception stack traces in uncaught `500`
  responses instead of the controlled JSON error envelope.
- No formal deprecation policy exists beyond v1 paths, SemVer, and release notes.
