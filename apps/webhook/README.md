# Webhook Application

The webhook application is the repository's only public ingress. It accepts
signed Resend webhook deliveries at:

```text
POST /api/webhooks/resend/v1
```

The former `/api/webhooks/v1/resend` path returns `404`.

## Processing

The route reads the exact raw request body, verifies it with all three Svix
headers, classifies the event, and appends selected fields to the corresponding
webhook ledger table.

For `email.received`, it also calls the Resend Receiving API with a 15-second
timeout to obtain text, HTML, and headers. It projects the message into an
existing RFC thread or creates an unassigned conversation. Attachment content
and metadata are not retrieved or projected.

If an inbound reply references an accepted outbound message whose provider
`Message-ID` was not available immediately after sending, the route retrieves a
bounded set of missing outbound records before projection. A matching message
is attached to its parent conversation. Incomplete recovery for recent sends
returns `500` instead of acknowledging a potentially incorrect unassigned
conversation.

Resend delivery is at least once and unordered. A unique `svix_id` prevents a
second ledger row. Projection uses the Resend email identifier for idempotency
and can resolve replies that arrive before their parent. Replaying a completed
`email.received` webhook also reruns parent recovery, allowing an already
unassigned reply to be repaired without creating a duplicate message.

## Responses

| Status | Meaning |
| --- | --- |
| `200` | Event and required projection processing completed, or were already completed |
| `400` | Required Svix headers are missing or the event family is unsupported |
| `401` | Signature verification failed |
| `500` | Configuration, Resend retrieval, or persistence failed; Resend may retry |

## Configuration

| Variable | Required | Purpose |
| --- | --- | --- |
| `DATABASE_URL` | Yes | Shared PostgreSQL database |
| `RESEND_WEBHOOK_SECRET` | Yes | Svix signature verification |
| `RESEND_API_KEY` | Yes for inbound email | Retrieve inbound content and headers |
| `RESEND_API_BASE_URL` | No | Integration-test API substitute |

## Development

From the repository root:

```bash
npm run dev:webhook
npm run build:webhook
npm run api:validate:webhook
```

Swagger UI is at `/docs`; its source is `public/openapi.json` in this workspace.
Railway readiness uses `GET /api/health/v1`, which checks required configuration
and database connectivity without exposing details.

## Railway

Use `/apps/webhook/railway.json` and keep the repository root as build context.
This service requires a public domain. Configure the exact public URL in Resend:

```text
https://<host>/api/webhooks/resend/v1
```

Subscribe the endpoint to `email.received`. The domain used by the conversation
API's `RESEND_FROM` must use Resend Receiving or forward incoming replies to a
Resend receiving address.

The image is built by `Dockerfile.webhook` and runs shared migrations before
deployment.
