# Changelog

All notable changes to this project will be documented in this file.

The format is based on Keep a Changelog, and this project uses Semantic
Versioning.

## [Unreleased]

### Added

- Added outbound delivery-state projection from Resend lifecycle webhooks while
  preserving provider send acceptance as the existing message `state`.
- Added optional per-message Reply-To display names for conversation sends and
  outbox sends.

## [0.0.1] - 2026-07-21

### Added

- Initial public release process with SemVer metadata, changelog tracking, and
  tag-triggered Docker Hub publication guidance.
