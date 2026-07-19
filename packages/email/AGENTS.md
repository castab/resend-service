# Email Package Rules

- Keep this package server-only and independent of Next.js route APIs.
- Keep RFC Message-ID parsing provider-neutral.
- Preserve ordered References ancestry and selected-parent semantics.
- Expect missing, malformed, duplicated, and out-of-order webhook metadata.
- Keep projection idempotent by Resend email ID and RFC Message-ID.
- Never infer thread membership from subject alone when RFC ancestry is
  available.
- Subject normalization may provide a title for a new unassigned conversation;
  it is not a universal thread key.
- Keep Resend calls bounded with an abort timeout. Do not add unbounded polling
  or retries.
- Do not add attachment retrieval without an explicit scope change.
- Do not log message bodies, addresses, subjects, headers, or API credentials.
