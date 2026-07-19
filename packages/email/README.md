# Email Package

`@resend-service/email` contains server-side email behavior shared by the
webhook and conversation applications.

## Responsibilities

- Resend webhook event types
- Bounded Resend send and retrieve calls
- RFC Message-ID extraction
- Subject normalization
- In-Reply-To and References construction
- Inbound conversation projection
- Out-of-order parent/child reconciliation
- Shared conversation content types and request hashing

It does not verify Svix requests or own HTTP authentication and response
mapping. Those remain application responsibilities.

## Thread Projection

For inbound email, the projector reads `In-Reply-To` and `References` from the
retrieved headers. It prefers the immediate parent, then the nearest known
ancestor. If no message is known, it creates an unassigned conversation using
the normalized subject. Processing is capped at the most recent 100 valid
References identifiers to bound public-ingress database work.

When a parent arrives after a child, unassigned child conversations are merged
into the selected conversation and unresolved parent links are repaired. A
topic-assigned conversation is preferred over an unassigned one.

For outbound replies, References is the selected parent's existing References
plus the parent's RFC Message-ID. This preserves branches rather than treating
every message in a conversation as ancestry.

## Resend Client

The client uses native `fetch` and aborts each request after 15 seconds. Its
base URL defaults to `https://api.resend.com`; tests override it with a local
fake server. Attachments are never requested.
