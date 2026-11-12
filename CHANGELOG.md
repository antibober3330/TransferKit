# Changelog

This file tracks user-visible and protocol-level changes. The current project is an alpha and is not a production release.

## Unreleased

Changes for the next version will be recorded here.

## [0.1.0-alpha.1] - 2026-11-12

Initial versioned development snapshot.

### Included

- Python Windows Station foundation, shared JSON contracts, ADB/USB sender and receiver framing, integrity checks, reports, and transfer history.
- Android source/receiver scaffolding, durable chunk checkpoints, installation identity primitives, and Recovery frame codecs.
- Space preflight and automatic duplicate-planning requirements; Python exact-match planning/report counters are present.
- Build and device evidence is recorded in `WORK_CHECKLIST.md` and `docs/project/RELEASE_READINESS.md`.

### Not a stable release

Full Recovery listener/Station integration, Android target duplicate inventory, near-duplicate image verification, complete restore/post-verification, Wi-Fi transfer, and the remaining hardware acceptance gates are still open.
