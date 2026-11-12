# Versioning and Git workflow

TransferKit is in pre-release development. The version in the root `VERSION` file is the source of truth and is mirrored by the Python and Android package metadata.

## Version rules

- Use Semantic Versioning: `MAJOR.MINOR.PATCH`.
- Until the first stable release, use explicit prereleases such as `0.1.0-alpha.1`, `0.1.0-beta.1`, and `0.1.0-rc.1`.
- Patch increments are for compatible fixes; minor increments are for compatible features; major increments are for incompatible public contract changes.
- Keep Android `versionCode` strictly increasing for every installable release. Android `versionName` and Python `__version__` must match `VERSION`.
- Every version gets a `CHANGELOG.md` entry. Create a Git tag `v<VERSION>` only after its release gates pass; never tag an incomplete build as stable.

## Branch and tag workflow

- Keep `main` as the reviewed, buildable line.
- Use short-lived branches named `feature/<checklist-id>-<topic>` or `fix/<topic>`.
- Merge a coherent, tested change set into `main`; do not create a permanent branch for every version.
- Use annotated tags such as `v0.1.0-alpha.1` for immutable version points. Do not rewrite a published tag.
- For a release, update `VERSION`, Python/Android package versions, `CHANGELOG.md`, and `WORK_CHECKLIST.md` together.

## Before publishing

- Run the full Python suite and the Android unit/instrumentation tasks that are available.
- Keep `local.properties`, Gradle/Kotlin caches, test databases, logs, APK/AAB outputs, signing keys, and device data out of Git.
- Record physical-device acceptance separately; a passing local build does not imply release readiness.
- Review the staged file list and secrets scan before the first push and every release tag.
