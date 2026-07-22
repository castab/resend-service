---
description: Research and update this repository's API contract, consumer guide, and agent handoff from the implemented behavior.
agent: build
---

Use the `api-integration-package` skill and update this repository's consumer-facing API integration package.

Scope:

- `public/openapi.json`
- `docs/api-consumer-guide.md`
- `docs/api-agent-handoff.md`

Requirements:

- Treat the implementation, route handlers, serializers, validators, tests, and checked-in API documentation as evidence.
- Do not change runtime API behavior unless explicitly requested.
- Clearly distinguish supported behavior, observed implementation behavior, and unresolved issues.
- Preserve strict public contract semantics when runtime behavior is more permissive, then document the discrepancy in the guides.
- Keep `docs/api-consumer-guide.md` and `docs/api-agent-handoff.md` portable when copied into another repository. Do not require downstream readers to have this repository's `npm` scripts, Docker Compose setup, OpenCode skill files, or command files.
- Use explicit timeouts on shell commands because long-running processes can hang on this machine.
- Confirm the test database is disposable before destructive integration-test commands.

Validation target:

```text
npm run db:validate
npm run api:validate
npm run lint
npm run build
npm run test:postgresql
```

Additional user instructions:

```text
$ARGUMENTS
```
