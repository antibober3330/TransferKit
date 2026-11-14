# Changelog

This file tracks user-visible and protocol-level changes. The current project is an alpha and is not a production release.

## [0.1.0-alpha.9] - 2026-11-14

Source-only development snapshot prepared for private GitHub `main`; no release tag. Android `versionCode` is 10. This remains an alpha and is not a production release.

### Included

- Hardened `ApkInstallStatusReceiver` receipt binding: the post-install cleanup candidate must match the ticket's transfer, manifest, APK category, item, SHA-256, package name, and version code.
- Added five JVM regressions, including the collision case where a non-APK item shares the APK's item ID and bytes.

### Validation and limits

- Android unit suite: 122/122 passed; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeeded.
- No phone was available. Physical PackageInstaller callback acceptance and APK-only source flow remain open.
- This is a source-only alpha, not a production release.

## [0.1.0-alpha.8] - 2026-11-14

Source-only development snapshot published to the private GitHub `main` as commit `28e5148afad4fe99cf722b004f1b1ee872f8750a`; no release tag. Android `versionCode` is 9. This remains an alpha and is not a production release.

### Included

- Extracted the asynchronous Shizuku permission-result message policy so grant/denial UI text can be checked independently of a running manager service.
- Added explicit JVM checks that granting Shizuku permission does not claim an unimplemented system-settings operation ran, while denial confirms settings remain unchanged.

### Validation and limits

- Android unit suite: 117/117 passed; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeeded.
- Python suite: 152/152 passed.
- Shizuku manager prompt/callback still needs physical acceptance; no phones were available for this run. This is not a production release.

## [0.1.0-alpha.7] - 2026-11-14

Source-only development snapshot published to the private GitHub `main` as commit `96b56932365093fa876022690bdedf060b18db6d`; no release tag. Android `versionCode` is 8. This remains an alpha and is not a production release.

### Included

- Added an injectable `ApkInstallPlatform` boundary around Android `PackageInstaller` to make APK staging and callback policy deterministic in JVM tests.
- Added 14 synthetic APK-install coordinator regressions for exact one-time approval, missing permission/retry, session creation/write/fsync/readback, payload mutation, integrity failures, installer status, and installed-version verification.

### Validation and limits

- Android unit suite: 115/115 passed; `:app:assembleDebug` and `:app:assembleDebugAndroidTest` succeeded.
- No APK content was installed in this run. Physical PackageInstaller prompt/final callback, process-death recovery, receiver-verified APK content, and APK-only source/Station flow remain open.
- No release tag is created. This is a source-only development snapshot, not a production release.

## [0.1.0-alpha.6] - 2026-11-14

Source-only development snapshot published to the private GitHub `main` as commit `462b482a45a1112ca36c6a1be1d3f02bb76a742e`; no release tag. Android `versionCode` is 7. This remains an alpha and is not a production release.

### Included

- Added a narrow `LegacyMediaEnvironment` platform boundary so Android 8–9 public-directory restore behavior can be exercised deterministically without writing to a physical device.
- Added seven legacy-restorer regressions for permission/approval gating, verified publish and retry, SHA-256 mismatch, collision protection, unavailable storage, MediaScanner retry, and API/category routing.
- Repeated MediaStore integrity acceptance on Samsung A72/API 34 and S20 Ultra/API 33; synthetic JPEG/MP4, exact readback, duplicate-free retry, and integrity failures passed on both.

### Validation and limits

- Android unit suite: 101/101 passed; debug and AndroidTest APKs build successfully.
- Android 8–9 public-directory adapter remains physically unaccepted: these deterministic JVM tests do not emulate Android runtime permission enforcement, mounted shared storage, MediaScanner, or OEM behavior.
- No release tag is created. Live transfer/recovery, broader restore coverage, and remaining release gates are still open.

## [0.1.0-alpha.5] - 2026-11-14

Source-only development snapshot published to the private GitHub `main` as commit `b9ff696329737adfaff063ad020799797ea94a53`; no release tag. Android `versionCode` is 6. This remains an alpha and is not a production release.

### Included

- Added the Android SAF document restorer with explicit destination selection, temporary-write and readback verification, SHA-256 checks, and idempotent retry. A real Android system picker and Samsung local Documents provider were accepted on A72/API 34.
- Added the allowlisted Android system-settings restorer and Settings-only Restore Inbox/review route with the `WRITE_SETTINGS` special-access return flow. Physical acceptance used a synthetic setting and restored its original value.
- Hardened Calendar post-verification for provider-generated self-attendee rows and extended synthetic provider coverage for Calendar, Contacts, MediaStore, and receiver integrity.
- Made Shizuku bridge calls injectable and fail-closed, with coverage for missing service, permission, server, and request-error states.
- Added durable ACK-based receiver progress evidence and progress-screen controls for opt-in keep-awake/dimming, restoring the page's original window attributes on exit.
- Added Python regression coverage for Downloads planning, ADB smoke diagnostics, Recovery frame header rejection, canonical SHA-256 validation, and all `ReasonCode` schema values.

### Validation and limits

- Latest recorded Python suite: 152/152 passed. Latest Android unit suite: 94/94 passed; debug and AndroidTest APK builds succeeded.
- Targeted physical tests passed on Samsung A72/API 34 and S20 Ultra/API 33, including receiver core 18/18 on each and progress-screen behavior. Detailed device evidence and cleanup are recorded in `PHONE_TESTING.md` and `docs/project/RELEASE_READINESS.md`.
- The product is not ready for production: live multi-file transfer/recovery, broader category restore, device diversity, and remaining release gates are still open. No release tag is created.

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
