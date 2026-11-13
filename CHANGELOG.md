# Changelog

This file tracks user-visible and protocol-level changes. The current project is an alpha and is not a production release.

## [0.1.0-alpha.4] - 2026-11-13

Source-only development snapshot published to the private GitHub `main` as commit `1fd4a63c29c316e1ef6668d83abb8bfbbc053557`; no release tag. This remains an alpha and is not a production release.

### Fixed

- Fixed Android receiver classification for CRC32 mismatches so corrupt chunks are reported as integrity failures rather than transfer-ID mismatches.
- Use integer ceiling arithmetic for chunk counts and byte ranges; avoid floating-point rounding at large file sizes.
- Deliver the accepted manifest total to Station progress before the first chunk ACK, and prevent closing the window while worker threads are active.
- Remove settings controls that were not connected to transfer behavior; expose the active confirmation setting honestly.
- Align receipt categories and failure stages across Python models and JSON Schema, bound manifest item counts and metadata nesting, and make Android/Python canonical JSON ordering consistent for Unicode keys.
- Correct API 26–28 restore, Shizuku, and adapter-status documentation where it overstated current behavior.

### Validation

- Python: 148/148 passed.
- Android unit: 81/81 passed before the version bump; instrumentation APK compilation passed after it.
- Physical acceptance remains open where noted in `WORK_CHECKLIST.md` and `docs/project/RELEASE_READINESS.md`.

## [0.1.0-alpha.3] - 2026-11-13

Development snapshot published to GitHub `main` in source commit `448bc180d0e0324a45cffa3e749a82e4675d690f`; no Git tag and not a release candidate.

### Included

- Centralized Android 14+ selected-visual-media permission policy and added unit coverage proving that selected photo/video access never implies audio access.
- Added a deterministic 772-byte synthetic JPEG with GPS EXIF coordinates `0,0` and an instrumentation test asserting that `MediaStore.setRequireOriginal()` returns exact source bytes and SHA-256 when `ACCESS_MEDIA_LOCATION` is granted.
- Corrected the specification to describe the implemented opt-in and documented Redmi MIUI ADB installation limitations.
- Changed media preflight to enumerate metadata without opening a source stream; full content reads and SHA-256 begin only when the user builds a manifest. The Android UI now explains the visible-photo permission scope.
- A denied original-EXIF permission now stops that explicit scan with guidance instead of silently switching to the redacted-photo path.
- Bundled photo, selected-visual-media, and original-location runtime permissions in one request when the original-EXIF flow starts without an existing photo grant, following Android's partial-access guidance.

### Validation and remaining work

- Android unit tests: 77/77 passed; debug and AndroidTest APKs compile.
- Redmi 12/API 35 accepted the bundled first-time request with limited selected-photo access and original-media-location grant. ADB shows `READ_MEDIA_VISUAL_USER_SELECTED=true`, `READ_MEDIA_IMAGES=false`, and `ACCESS_MEDIA_LOCATION=true`; the screenshot shows exactly one visible 772-byte synthetic item and an original URI prepared after explicit consent. The newest alpha.3 APK (9,439,613 bytes, SHA-256 `7F970A1660F82D67871EFDE74B192EDE11AE6E068AB49CB65DDB08F6959E6A0A`) was manually installed and confirmed by SDK ADB; it is the only TransferKit APK in Downloads. Manifest construction completed with 1 category, 1 item, 0.0 MiB, and 0 problem items; no transfer or restore was started. The single synthetic item was read once to compute SHA-256, but the UI does not expose the item digest, so equality with the expected fixture hash is not independently confirmed. The user then cleared the selection; TransferKit reported that its temporary snapshot was removed.
- Physical GPS EXIF byte-equality instrumentation has not run: Redmi MIUI rejected the AndroidTest APK with `INSTALL_FAILED_USER_RESTRICTED`; the main app package was removed by the failed Gradle install session, then alpha.3 was manually reinstalled. A prior photo permission flow exposed 7 visible items; the app was stopped, and the current code no longer opens a probe stream during preflight. MIUI requires manual permission changes because its ADB shell cannot call `pm revoke`.
- API 34 partial-photo/video physical acceptance and original EXIF exact-byte acceptance remain open. No release tag is created until release gates pass.
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
