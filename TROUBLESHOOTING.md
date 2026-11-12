# TransferKit troubleshooting

This guide separates Windows/ADB setup issues from prototype limitations. For Android toolchain details, see [`android-client/BUILDING_AND_TESTING.md`](android-client/BUILDING_AND_TESTING.md). For device test commands, see [`android-client/PHONE_TESTING.md`](android-client/PHONE_TESTING.md).

## Windows Station

| Symptom | Likely cause | What to do |
|---|---|---|
| `python` opens Microsoft Store or is not found | Windows Store alias is active, or Python is not on PATH | Install Python 3.11+ or use the project `.venv\Scripts\python.exe`; run commands from `D:\Transfer`. |
| `ModuleNotFoundError: transferkit` | Command runs outside the project or wrong interpreter | `cd D:\Transfer`, then use the same interpreter that ran `install.bat`. |
| `ModuleNotFoundError: PySide6` | Dependencies were installed into another Python | Run `install.bat` from the project root; do not mix global Python and `.venv`. |
| JSON Schema says a field is invalid | Payload differs from the shared contract, or has a stale field/enum | Read the path in the error and compare with the named file under `shared/`; do not disable validation to hide it. |
| Station cannot find shared schemas after packaging | Packaged application omitted its `shared/` data directory | Rebuild with the checked-in packaging script that bundles `shared/`. |

## ADB and connected phones

Use the SDK copy consistently, for example:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $adb version
& $adb devices -l
```

| Symptom | Meaning | Action |
|---|---|---|
| `more than one device/emulator` | ADB sees multiple USB/Wi-Fi devices | Use `adb -s SERIAL ...` with the chosen serial. This is expected with two phones or USB plus Wireless debugging. |
| `unauthorized` | The phone has not approved this computer's RSA key | Unlock the phone and accept the prompt on-device. Never send the screen-lock code in chat. |
| `offline` | ADB transport/session is stale or the cable/USB mode changed | Keep the same ADB executable, reconnect the cable, unlock the phone and query the chosen serial again. |
| ADB server/client version mismatch | Different ADB executables are alternately restarting the shared daemon | Stop switching between `C:\Windows\adb.exe` and SDK `platform-tools\adb.exe`; use one full path for all commands. |
| `no devices/emulators found` | The selected ADB server cannot see an authorized target | Check cable, USB debugging and RSA approval; this is not a Kotlin compile error. |

## Android build and installation

Use the checked-in Gradle Wrapper from `D:\Transfer\android-client`, JDK 17 and the SDK selected by `local.properties`:

```powershell
.\gradlew.bat --offline :app:assembleDebug
```

| Symptom | Likely cause | Action |
|---|---|---|
| `Android Gradle plugin requires Java 17` | Another JDK is selected | Set `JAVA_HOME` to JDK 17, open a new terminal and check `java -version`. |
| `Failed to find target android-34` | Gradle points to an SDK without Platform 34 | Install Platform 34 into the configured SDK, and align `local.properties`, `ANDROID_HOME` and `ANDROID_SDK_ROOT`. |
| `Build Tools revision 34.0.0 not found` | Build Tools are absent from the selected SDK | Install 34.0.0 into that SDK. |
| `Could not connect to Gradle daemon` / localhost socket denied | Local daemon socket is inaccessible from this terminal, or a stale daemon is involved | Retry in a normal local PowerShell session using the wrapper. Do not delete SDK/cache/lock files as a first response. |
| Global Gradle reports `native-platform` failure | System Gradle/JDK mismatch | Use `android-client\gradlew.bat`; do not replace the pinned wrapper with a global Gradle version. |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | Existing app has a different signing key | Uninstall only if app-private data can be lost; otherwise install using the matching signing key. |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | Phone has a newer version code | Build a higher version code or keep the installed version; do not clear user data to bypass it. |

## Permissions and category results

- `PERMISSION_DENIED`: grant the named Android runtime/special permission only if you choose to use that category; otherwise leave it unavailable.
- `USER_ACTION_REQUIRED`: complete the system picker or confirmation shown by Android. The app cannot silently grant the permission.
- `NO_DATA`: the provider returned no readable items; it does not prove that another app's private data is absent.
- `PARTIAL`: inspect per-category/per-item reasons. Some items may be unreadable or unsupported while others remain available.
- SMS and call log: hard-restricted grants may depend on how the APK was installed and Android/OEM policy. Do not switch the app into a default SMS/dialer role merely to make a test pass.
- APK listing: Android package visibility and single-file/split APK limitations can omit packages. A package list is not a guarantee that an app can be installed on another device.
- `TARGET_NO_SPACE` / `TARGET_SPACE_INSUFFICIENT`: review the preflight before any future transfer. Do not delete user files automatically.

## Integrity and transfer state

- `CRC32_MISMATCH`: a chunk was corrupted. The target must not acknowledge it or advance the durable checkpoint.
- `FAILED_INTEGRITY` / `SHA256_MISMATCH`: complete item bytes differ from the manifest. Do not report this as `OK` or ordinary `PARTIAL`; retain the integrity failure for retry/reporting.
- A received/verified receipt does not prove destination restore. `OK` requires the destination operation and its post-verification.
- Authenticated Android source/receiver listeners and the Windows-hosted USB/ADB relay exist. A synthetic A04e→Moto receive was physically verified, but this does not prove user-data restore, destination post-verification, final report or recovery. See [`docs/project/RELEASE_READINESS.md`](docs/project/RELEASE_READINESS.md) for the current verification snapshot.
- If the app is force-stopped or staging is needed for diagnosis, do not clear app data or uninstall it. Cleanup applies only to TransferKit-owned staging after the specified user confirmation.
