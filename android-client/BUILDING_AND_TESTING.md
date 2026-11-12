# Android build and test troubleshooting

This checklist is for the Windows development setup. It does not install packages, change phone settings, start Gradle, or run tests. `doctor.ps1` is a read-only preflight; pass `-Devices` only when you explicitly want it to start/query ADB.

## Known project toolchain

- Use the checked-in Gradle Wrapper: Gradle **8.7**, Android Gradle Plugin **8.5.2**, Kotlin **2.0.20**, compile SDK **34**, Build Tools **34.0.0**, Java **17**.
- Do not substitute a globally installed Gradle. The machine has had a separate system Gradle/native-platform issue; the project wrapper avoids that version drift.
- Keep `local.properties` `sdk.dir`, `ANDROID_HOME`, and `ANDROID_SDK_ROOT` pointed at the same SDK. A stale environment variable can make Gradle and the install script use different SDK copies.
- Run commands from their documented directory. In PowerShell, prefix local scripts with `.` (for example `.gradlew.bat`); from Command Prompt use `gradlew.bat`.
- Do not delete Gradle caches, lock files, databases, or build directories while their owning process is running. An `AccessDenied`/daemon socket error does not by itself mean the SDK needs reinstalling.

Run the environment-only preflight before starting an Android build:

```powershell
cd D:\Transfer\android-client
.\doctor.ps1 -BuildOnly
```

To additionally list connected ADB devices (this starts the selected SDK ADB server if necessary):

```powershell
.\doctor.ps1 -Devices
```

## Common Android failures

| Symptom | Likely cause | First action |
|---|---|---|
| `Android Gradle plugin requires Java 17` / unsupported class version | `JAVA_HOME` or `PATH` selects another JDK | Point `JAVA_HOME` to JDK 17, open a fresh terminal, inspect `java -version`. |
| `Failed to find target android-34` | Platform 34 is absent from the SDK selected by Gradle | Install Android SDK Platform 34 into the single SDK path used by `local.properties` and the environment. |
| `Build Tools revision 34.0.0 not found` | Build Tools are absent from that same SDK | Install Build Tools 34.0.0 with SDK Manager; do not switch SDK paths to hide the error. |
| Gradle distribution download timeout | First wrapper launch needs the 8.7 distribution and network access | Restore network access and rerun `gradlew.bat`; do not replace wrapper with system Gradle 9.x. |
| `Could not resolve ...` / dependency download timeout | Maven/Google repository access, proxy, or DNS is unavailable | Restore repository access and retry; do not delete or arbitrarily bump pinned Android plugin versions. |
| `Could not create service ... native-platform` | System Gradle/JDK/native library mismatch or damaged global daemon, not Android source | Use `gradlew.bat`; close stale Gradle processes only if they are yours, then retry in a normal local terminal. |
| `Could not connect to Gradle daemon` / localhost socket denied | The terminal/sandbox cannot use local daemon sockets or a daemon is stale | Retry in the user's local PowerShell session. Do not delete the SDK or project files. |
| Build succeeds with a deprecation warning | Compiler warning only | Record it for cleanup; a warning is not a failed build unless Gradle reports a failed task. |
| APK builds but install says `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Installed package was signed with another key | Uninstall only if the user accepts loss of app-private data; otherwise use the matching signing key. |

## ADB and physical-device setup

- Use one ADB binary for every command. The SDK copy is `%LOCALAPPDATA%\Android\Sdk\platform-tools\adb.exe`; another `C:\Windows\adb.exe` can restart the shared server with a different version.
- Always select a device explicitly when more than one is connected: `adb -s SERIAL ...`. Bare `adb get-state` is expected to fail with `more than one device/emulator` in that situation.
- `unauthorized` means the phone has not approved this computer's RSA key. Unlock it and accept the Android prompt on-device; never send a screen-lock code in chat.
- `offline` usually means a cable/USB mode/ADB session issue. Reconnect, unlock, and query again with the same ADB executable.
- `no devices/emulators found` is a device/ADB prerequisite failure, not a Kotlin compilation failure. An instrumentation task needs a connected authorized phone or a configured emulator.
- UI visibility checks require the phone to be unlocked. A locked phone is not permission to bypass its lock.
- Do not grant root, SMS, contacts, calendar, or call-log access just to make a build pass. Runtime permission/device acceptance is a separate step and must be explicit.

## Python Station setup and tests

- Use Python 3.11 or newer and the project's `.venv`; the Windows Store `python` alias may be unavailable even when Python is installed.
- After `install.bat`, prefer `.venv\Scripts\python.exe` so test discovery and GUI imports use the same environment as the app.
- Run Python commands from `D:\Transfer`. Running from another directory can cause `ModuleNotFoundError: transferkit` or make relative `shared/` schema paths resolve incorrectly.
- `ModuleNotFoundError: PySide6` means the active interpreter is not the project's venv or dependency installation was interrupted. Do not install into a different global Python.
- JSON Schema failures should include a path-qualified field; compare the payload against the matching file in `shared/` before relaxing validation.
- PyInstaller builds must include `shared/` alongside the app; the build script already passes it as data. A packaged `shared schema not found` problem is a packaging path issue, not a reason to weaken contract validation.
- For future test runs, the canonical Python command is `python -m unittest discover -s tests -v`; the result is meaningful only if `python` points to the intended interpreter. Verify that separately with `python --version` and `where.exe python`.

## Current verification boundary

`doctor.ps1` is an environment preflight only. A preflight does not prove source compiles, tests pass, an APK installs, permissions are granted, or a device flow works. The latest local Gradle run passed `:app:testDebugUnitTest :app:assembleDebug`; Android unit tests passed 43/43 and the alpha APK was rebuilt successfully. The last recorded instrumentation run passed 21/21 on each of Samsung S20 Ultra/API 33, Samsung M31/API 31, Samsung A04e/API 33 and Motorola moto g06/API 35; it predates the framing-only change and was not repeated because no receiver/listener integration changed. The latest Python suite passed 144/144. SQLite v1/v2→v3 migrations, item checkpoints, exact chunk ledger metadata, failed-commit ACK behavior, staging locator, retention and Recovery identity are covered. Python exact dedup planning/report counters and Recovery frame codecs do not mean Android target inventory, transport filtering or the full Recovery handshake are integrated. Temporary APKs were removed from A04e/Moto; existing S20/M31 installations were preserved. See [`../docs/project/RELEASE_READINESS.md`](../docs/project/RELEASE_READINESS.md) for release limitations.

Authenticated USB/ADB receive smoke ran on four physical targets with only 64 KiB of generated bytes each: the target returned a schema-valid `TKRC` final receipt and an independent SHA-256 readback of the app-private payload matched the source. New-device receipts are in `test-results/receipt-a04e.json` and `test-results/receipt-moto-g06.json`; the S20 receipt is `test-results/receipt-s20.json`. The newer-device APK installations were uninstalled after testing and `pm path` confirmed the package was absent. M31 previously rejected an incorrect HMAC without consuming the token, then accepted the correct proof with that token. Tests used explicit USB serials. The two new devices reported Wireless ADB disabled (`adb_wifi_enabled=0`), no wlan0 IPv4 address and no mDNS services, so Wi-Fi cable-disconnect monitoring could not be tested. The listener status is process-local so a forced process restart cannot leave the UI displaying stale `LISTENING`.

These tests prove the authenticated Windows-host → Android receive/receipt path for synthetic items. They do not prove authenticated Android-source streaming, full Windows Station orchestration, user-data restore, report completion after destination post-verification, disconnect/resume behavior, or compatibility beyond the tested APIs 31/33/35. Test items from the new devices were removed with TransferKit uninstall; older S20/M31 synthetic inbox items remain pending the cleanup UI.
