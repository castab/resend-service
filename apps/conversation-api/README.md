# Conversation API

The conversation API is a private Railway service for topic-centered email
threads. It must not have a Railway public domain. Adjacent services call it at:

```text
http://conversation-api.railway.internal:<conversation-service-port>
```

Configure callers with Railway variable references so the target service's
hostname and port are used:

```text
CONVERSATION_API_URL=http://${{conversation-api.RAILWAY_PRIVATE_DOMAIN}}:${{conversation-api.PORT}}
```

Define `PORT` explicitly as a service variable on this service. Railway
reference variables resolve against service variables, not image defaults, so
`${{conversation-api.PORT}}` is empty without it.

All operations require:

```text
Authorization: Bearer <CONVERSATION_API_KEY>
```

Sending operations also require a caller-generated `Idempotency-Key` header.

## Routes

| Method | Route | Purpose |
| --- | --- | --- |
| `POST` | `/api/conversations/v1` | Create a topic conversation and send its opening message |
| `GET` | `/api/conversations/v1?assignment=unassigned` | List unmatched inbound conversations |
| `GET` | `/api/conversations/v1/{conversationId}` | Hydrate by internal conversation ID |
| `PATCH` | `/api/conversations/v1/{conversationId}` | Assign an unassigned conversation to a topic |
| `POST` | `/api/conversations/v1/{conversationId}/messages` | Send a reply |
| `GET` | `/api/conversations/v1/topics/{topicType}/{externalTopicId}` | Hydrate by caller-owned topic |

The full request and response contract is available at `/docs` and
`/openapi.json` on the private host.

`GET /api/health/v1` is the only unauthenticated API route. It returns only
aggregate readiness after checking configuration and database connectivity.

## Semantics

- A `(topicType, externalTopicId)` pair owns at most one conversation.
- A conversation has one external participant and the configured `RESEND_FROM`
  mailbox.
- The topic title is the default initial subject.
- Replies use `Re: <canonical subject>`.
- `replyToMessageId` selects an explicit parent. Otherwise the latest accepted
  or received message is selected.
- `In-Reply-To` contains the parent RFC Message-ID.
- `References` contains the parent's ancestry followed by its RFC Message-ID.
- Reads return up to 50 messages by default and 100 at most. A response cursor
  retrieves older messages.
- Full text and HTML bodies are returned. HTML is untrusted and must be
  sanitized before browser rendering.
- Attachments are out of scope.

## Send Safety

The API stores a pending message intent and SHA-256 request hash before calling
Resend. The same idempotency key and payload returns the existing message.
Reusing a key for another payload returns `409`.

Resend calls abort after 15 seconds. Explicit API failures become `failed`;
ambiguous network failures become `indeterminate`. A retry of a pending request
within 23 hours makes one bounded call with the same provider idempotency key;
older pending intents become indeterminate rather than risking a duplicate.
If sent-message metadata is temporarily unavailable, a later reply attempts one
bounded retrieval before sending.

A topic conversation whose messages have all `failed` is not stuck: creating it
again with a new idempotency key updates the participant, title, and subject,
then sends a fresh opening message. Retrying the original key still reports the
failed result, and any non-failed message keeps the topic conflict a `409`.

Titles, subjects, and participant names are limited to header-safe text (no
ASCII control characters) of at most 255, 255, and 256 characters. Message
`text` and `html` are limited to 1 MiB each.

## Configuration

| Variable | Required | Purpose |
| --- | --- | --- |
| `DATABASE_URL` | Yes | Shared PostgreSQL database |
| `RESEND_API_KEY` | Yes | Send and retrieve email |
| `RESEND_FROM` | Yes | Fixed verified sender identity |
| `CONVERSATION_API_KEY` | Yes | Private API bearer credential |
| `RESEND_API_BASE_URL` | No | Integration-test API substitute |

## Development

From the repository root:

```bash
npm run dev:conversation
npm run build:conversation
npm run api:validate:conversation
```

The development server uses port 3001.

## Railway

Use `/apps/conversation-api/railway.json`, keep the repository root as build
context, and do not generate a public domain. The image is built by
`Dockerfile.conversation-api` and runs shared migrations before deployment.
