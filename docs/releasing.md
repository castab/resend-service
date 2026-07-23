# Releasing

## Versioning policy

- Git tags use stable Semantic Versioning with a `v` prefix, such as `v0.3.0`.
- Repository metadata uses the same version without the `v` prefix.
- Until `1.0.0`, treat `0.x.0` releases as the place for contract or behavior
  changes that may require consumer updates.
- `x.y.z` tags are immutable release identifiers. Docker aliases such as `x.y`,
  `x`, and `latest` move forward with each stable release.

## Files that must stay aligned

- `build.gradle.kts`
- `src/main/resources/public/openapi.json`
- `docs/api-consumer-guide.md`
- `docs/api-agent-handoff.md`
- `CHANGELOG.md`

Run the Gradle and container verification steps before opening or merging a
release PR.

## Release workflow

1. Update repository metadata to the next version.
2. Move the release notes from `## [Unreleased]` into a new dated section in
   `CHANGELOG.md`.
3. Run verification locally:

   ```bash
   ./gradlew test shadowJar
   ./gradlew nativeCompile --no-configuration-cache
   docker build -t resend-service:test .
   npx --yes markdownlint-cli2 "**/*.md" "#.opencode/node_modules/**"
   npx --yes @redocly/cli lint src/main/resources/public/openapi.json \
     --skip-rule info-license \
     --skip-rule operation-2xx-response \
     --skip-rule operation-4xx-response
   ```

4. Open a pull request into `main` with the version and changelog updates.
5. Create and push an annotated tag for the target commit. For prerelease
   validation, use an RC tag such as `v0.3.0-rc.1`; for a stable release, use
   the final SemVer tag from a commit already merged into `main`.

   For an RC tag from the current branch:

   ```bash
   VERSION=v0.3.0-rc.1
   git tag -a "$VERSION" -m "Release $VERSION"
   git push origin "$VERSION"
   ```

   For a stable tag from `main`:

   ```bash
    git checkout main
    git pull --ff-only
    VERSION=v0.3.0
   git tag -a "$VERSION" -m "Release $VERSION"
   git push origin "$VERSION"
   ```

6. The tag-triggered publish workflow builds and pushes `linux/amd64` Docker
   tags to Docker Hub.

   For RC and other prerelease tags, it publishes only the exact version tag:

   - `castab/resend-service:x.y.z-rc.n`

   For stable releases, it also updates the moving aliases:

   - `castab/resend-service:x.y.z`
   - `castab/resend-service:x.y`
   - `castab/resend-service:x`
   - `castab/resend-service:latest`

7. After Docker publication succeeds, create a GitHub Release from the matching
   `CHANGELOG.md` section and include the published image digest.

## Required repository settings

- Protect `main` with required pull request reviews and required status checks.
- Protect `v*` tags so only release maintainers can create or update them.
- Set these GitHub Actions secrets before publishing:
  - `DOCKERHUB_USERNAME`
  - `DOCKERHUB_TOKEN`

The publish workflow does not run tests or a container smoke test. Before
publishing, verify the native `resend-service` image with a non-default `PORT`
and verify the `migrate` command against a disposable PostgreSQL 18+ database.
The normal test workflow runs on branch pushes and pull requests, not tag
pushes, so the tagged commit must already have passed `main` CI.

The workflow trigger accepts both stable `v*.*.*` tags and prerelease
`v*.*.*-*` tags and does not enforce annotated tags, SemVer validity, or
metadata alignment. Maintainers must verify those release-policy requirements
before pushing a tag. Stable tags must point to commits already on `main` and
update the moving Docker aliases; prerelease tags publish only the exact
version tag.
