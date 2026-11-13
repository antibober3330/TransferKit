# Changelog

This file tracks user-visible and protocol-level changes. The current project is an alpha and is not a production release.

## Unreleased

Changes for the next version will be recorded here.

## [0.1.0-alpha.2] - 2026-11-13

Development snapshot published to `main`; this is not a release candidate and has no Git tag.

### Included

- Android Calendar restore with explicit writable-calendar selection, strict payload validation, SHA-256 checking, idempotent event markers, and provider readback for events, attendees, and reminders.
- Calendar restore confirmation repeats the selected destination and warns that account synchronization follows the calendar's settings.
- Calendar recurrence masters are ordered before their exceptions during a restore batch.
- Synthetic Samsung Calendar Provider acceptance and regression coverage for strict payload parsing and recurrence ordering.
- Documented the Redmi/MIUI USB-install restriction and supported manual installer fallback; ADB installation is blocked by `INSTALL_FAILED_USER_RESTRICTED`, while manual Files installation and alpha.2 app launch were confirmed.

### Remaining release gates

Calendar destination UI still needs physical acceptance on another reachable device; recurrence exception behavior needs wider provider coverage; the project has additional incomplete restore, transport, Recovery, duplicate-inventory, and release-readiness work.

## [0.1.0-alpha.1] - 2026-11-12

Initial versioned development snapshot.

### Included

- Python Windows Station foundation, shared JSON contracts, ADB/USB sender and receiver framing, integrity checks, reports, and transfer history.
- Android source/receiver scaffolding, durable chunk checkpoints, installation identity primitives, and Recovery frame codecs.
- Space preflight and automatic duplicate-planning requirements; Python exact-match planning/report counters are present.
- Build and device evidence is recorded in `WORK_CHECKLIST.md` and `docs/project/RELEASE_READINESS.md`.

### Not a stable release

Full Recovery listener/Station integration, Android target duplicate inventory, near-duplicate image verification, complete restore/post-verification, Wi-Fi transfer, and the remaining hardware acceptance gates are still open.
