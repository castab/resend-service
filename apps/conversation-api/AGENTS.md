# Conversation API Rules

- This application is private Railway traffic only. Do not configure or
  document a public domain.
- Require bearer authentication on every conversation operation, including
  reads and documentation-independent endpoints.
- Keep `GET /api/health/v1` unauthenticated for Railway readiness checks; it may
  return only aggregate health status.
- Require `Idempotency-Key` on every operation that can send email.
- Persist a send intent before calling Resend and never add unbounded retries.
- Use only the server-configured `RESEND_FROM`; callers cannot choose senders.
- Preserve one conversation per `(topicType, externalTopicId)` and one external
  participant per conversation.
- An explicit reply parent must belong to the conversation. Otherwise use the
  latest accepted or received message.
- Never send a reply without a parent RFC Message-ID. Return a controlled error
  when it cannot be retrieved.
- Build `References` from the selected parent's ancestry, not from unrelated
  messages in the conversation.
- Do not fetch, accept, or return attachments.
- Treat stored and returned HTML as untrusted.
- Keep validation and bearer authentication in this app; keep RFC behavior in
  `packages/email`.
- Update this app's OpenAPI contract and integration tests with every route or
  behavioral change.
