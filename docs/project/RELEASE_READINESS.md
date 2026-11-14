# TransferKit — release readiness

**Знімок стану:** 2026-11-14

**Висновок:** перевірний release-набір підготовлено, але продукт ще **не готовий до публічного релізу**. Наявний Android APK — debug build; основні migration acceptance і частина transport/restore функцій залишаються відкритими.

Цей документ є контрольним аркушем для відтворення збірки та перевірки доказів. Він не перетворює прототип на реліз і не дозволяє трактувати отриманий `receipt.json` як доказ успішного відновлення даних.

## Ідентифікація та версії

| Компонент | Зафіксована версія/межа | Джерело істини |
|---|---|---|
| TransferKit Windows | `0.1.0-alpha.13` | `VERSION`, mirrored in `transferkit/__init__.py` |
| TransferKit Android | `versionName=0.1.0-alpha.13`, `versionCode=14`, package `com.transferkit.client` | `VERSION`, mirrored in `android-client/app/build.gradle.kts` |
| Python | мінімум 3.11; у цій перевірці Python 3.14.4 | `install.bat`, середовище перевірки |
| Python залежності у requirements | PySide6 ≥6.6; cryptography ≥41; Pydantic ≥2; jsonschema ≥4.20; referencing ≥0.30; PyInstaller ≥6.0 | `requirements.txt` |
| Python середовище цієї перевірки | PySide6 6.11.0, cryptography 43.0.3, Pydantic 2.13.5, jsonschema 4.26.0, referencing 0.37.0, PyInstaller 6.20.0 | активний інтерпретатор користувача |
| Android build | JDK 17; Gradle Wrapper 8.7; Android Gradle Plugin 8.5.2; Kotlin 2.0.20 | `android-client/gradle/wrapper/gradle-wrapper.properties`, Gradle scripts |
| Android SDK | compile/target SDK 34, min SDK 26, Build Tools 34.0.0 | `android-client/app/build.gradle.kts`, `BUILDING_AND_TESTING.md` |
| Wire format | поточні transport/control frames — version 1 | `shared/protocol.md`, Python/Kotlin codecs |

Попередній source-only snapshot `0.1.0-alpha.5` опублікований у приватній гілці GitHub `main`, commit `b9ff696329737adfaff063ad020799797ea94a53`; Android versionCode=6. Перед commit пройшов `git diff --cached --check`; staged tree містив 56 source/config/doc files, без APK/AAB, `local.properties`, build/cache output і без відомих credential-pattern matches. Release tag не створювався; цей alpha не є production release.

Поточний source-only alpha.6 (`versionCode=7`) опубліковано у приватний GitHub `main` комітом `462b482a45a1112ca36c6a1be1d3f02bb76a742e`; віддалений `refs/heads/main` звірено з цим hash. До push: Android unit suite **101/101 passed**, `:app:assembleDebug` та `:app:assembleDebugAndroidTest` успішні, `git diff --cached --check` пройшов для 11 staged files, 0 APK/AAB та 0 відомих credential-pattern matches. Debug APK не є release build і виключений із Git. Release tag не створювався.

Source-only snapshot alpha.7 (`versionCode=8`) опубліковано у приватний GitHub `main` комітом `96b56932365093fa876022690bdedf060b18db6d` після alpha.6; push був fast-forward, попередню історію збережено. Android unit suite **115/115 passed**; `:app:assembleDebug` та `:app:assembleDebugAndroidTest` успішні. Пункт 050 має 14 детермінованих JVM coordinator regressions, але фізичний Android install prompt/final callback та реальний receipt-verified APK лишаються неперевіреними. APK та build artifacts до Git не включалися; release tag не створювався.

Source-only snapshot alpha.8 (`versionCode=9`) опубліковано у приватний GitHub `main` комітом `28e5148afad4fe99cf722b004f1b1ee872f8750a` після alpha.7; push був fast-forward, попередню історію збережено. Android unit suite **117/117 passed**, Python suite **152/152 passed**, debug та AndroidTest APK збираються. Фізичний consent callback не перевірявся, оскільки телефони зараз недоступні; Shizuku manager на попередніх пристроях був відсутній. У Git не включалися APK/build artifacts; release tag не створювався.

Source-only alpha.13 (`versionCode=14`) підготовлений для пункту 048. Durable progress observer ізольовано: помилка checkpoint read або UI callback не перериває transport handler після ACK; додано три JVM regressions для failure recovery і throttle. Android unit **142/142 passed**, Python **152/152 passed**, debug та AndroidTest APK збираються. Жодні телефони не використовувалися. Alpha.12 поки лишається останнім опублікованим snapshot; alpha.13 source-only, без APK/build artifacts та release tag.

Source-only alpha.12 (`versionCode=13`) опубліковано у приватний GitHub `main` комітом `fa77871552eb80a310aa9d16dc0ccb3656f1c4ff` після alpha.11 (`cb02e4d92965621addc1a3479d62f9673ae595d3`); push пройшов fast-forward і remote HEAD звірено. Device Owner probe окремо подає `DevicePolicyManager` query failure і підтверджений non-owner result; додано три синтетичні JVM cases. Android unit **139/139 passed**, Python **152/152 passed**, debug і AndroidTest APK зібрані. Фізичного provisioning/device test не було; source-only без APK/build artifacts, release tag не створювався.

Source-only alpha.11 (`versionCode=12`) опубліковано у приватний GitHub `main` комітом `0c17d38dc0520a312205cfb3b9724de6cb663c00` після alpha.10 (`d76ba83301028ed7f7570c81770303d70a1413ab`); push пройшов fast-forward і remote HEAD звірено. У пункті 046 probe відрізняє помилку читання Shizuku binder/permission state від підтвердженої відсутності дозволу; помилка не запускає повторний permission prompt. Додано два synthetic JVM regressions для fail-closed поведінки. Android unit suite **136/136 passed**; Python suite **152/152 passed**; `:app:assembleDebug` і `:app:assembleDebugAndroidTest` успішні. Телефонні Shizuku consent/service acceptance не запускалися; APK/build artifacts виключені, release tag не створювався.

Source-only alpha.10 (`versionCode=11`) опубліковано у приватний GitHub `main` комітом `6545545c41cd3ca4218ea432a5afc3bf8dd7f241` після alpha.9 (`594e9d81e5bbed7211ff5dc730ffd5c14b2923a5`); push пройшов fast-forward. `MediaStoreRestorer` винесений за injectable platform boundary; 12 synthetic JVM regressions покривають exact write/readback/publish, idempotent retry, corruption, cleanup, approval та API/MIME routing. Виявлене подвійне видалення власного pending row при integrity mismatch прибрано: тепер його один раз очищає спільний `finally`. Android unit suite **134/134 passed**; Python suite **152/152 passed**; `:app:assembleDebug` та `:app:assembleDebugAndroidTest` успішні. Телефони недоступні й фізичні тести не запускались; API 29 та OEM MediaStore acceptance лишаються відкритими. APK/build artifacts не включалися; release tag не створювався.

Source-only snapshot alpha.9 (`versionCode=10`) опубліковано у приватний GitHub `main` комітом `3892748d29c377f927e4743b80ea6d145a7e750c` після alpha.8; push був fast-forward, попередню історію збережено. `ApkInstallStatusReceiver` застосовує суворий receipt-to-ticket binding: cleanup приймає лише точний APK category/item/package/version candidate з matching transfer/manifest/SHA. Додано 5 callback-binding tests, включно з non-APK item collision. Android unit suite **122/122 passed**; Python suite **152/152 passed**; debug та AndroidTest APK збираються. Телефони недоступні; фізичний PackageInstaller prompt/callback і APK-only source flow залишаються відкритими. У Git не включалися APK/build artifacts; release tag не створювався.

Alpha.5 є source-only development snapshot; APK цієї версії не збирався в межах публікації. Раніше зібраний alpha.4 debug APK: `android-client/app/build/outputs/apk/debug/app-debug.apk`, 9 452 756 bytes, SHA-256 `608449FC585E0FD70B72CE74C19BE9E6839849537310405A55C4F2F32547C157`; `aapt dump badging` підтвердив package `com.transferkit.client`, versionCode=5, versionName=`0.1.0-alpha.4`. Build output виключена з Git.

Користувач вручну встановив цю alpha.4 на Redmi 12 (23053RN02Y) 2026-11-13; `versionCode=5`, `versionName=0.1.0-alpha.4`, а SHA-256 установленого `base.apk` збігається з локальним APK: `608449FC585E0FD70B72CE74C19BE9E6839849537310405A55C4F2F32547C157`. У Downloads залишився лише цей TransferKit APK.

Python lock створено для Windows x64/CPython 3.14.4 у `requirements-lock-win-py314.txt`; він містить exact versions усієї виявленої залежнісної гілки, але поки без artifact hashes і без підтвердженої clean-venv інсталяції. Android залежності зафіксовані у `android-client/app/gradle.lockfile`; Gradle dependency locking увімкнено. Потрібно перевірити clean install/build з обох lock-файлів. `.github/workflows/ci.yml` запускає Python suite і Android unit/build; hosted run і branch protection ще не підтверджені.

## Збірочні скрипти та результати

| Дія | Скрипт/команда | Очікуваний вихід |
|---|---|---|
| Створити Python venv та встановити залежності | `D:\Transfer\install.bat` | `.venv\Scripts\python.exe` |
| Запустити Windows Station | `D:\Transfer\run.bat` | графічний застосунок |
| Зібрати Windows onedir | `D:\Transfer\build.bat` | `dist\TransferKit\TransferKit.exe` разом із `_internal` |
| Зібрати Android debug APK | `cd D:\Transfer\android-client; .\build-sideload.bat` | `android-client\dist\TransferKit-Android-debug.apk` |
| Встановити APK на один конкретний пристрій | `android-client\install-apk.bat SERIAL` | встановлення лише на вказаний online ADB serial |
| Перевірити Python suite | `python -m unittest discover -s tests -v` | усі тести пройдені |
| Перевірити Android build/unit tests | `cd android-client; .\gradlew.bat --offline --no-daemon :app:testDebugUnitTest :app:assembleDebug` | `BUILD SUCCESSFUL` і debug APK |
| Запустити Android instrumentation на під’єднаному пристрої | `cd android-client; .\gradlew.bat :app:connectedDebugAndroidTest` | результати на вибраному ADB/device setup |

На цьому ПК `python` launcher може бути недоступний; працює прямий інтерпретатор `C:\Users\ACER\AppData\Local\Python\bin\python.exe`. Якщо Gradle у sandbox не може створити/під’єднатися до daemon, повторити ту саму команду в локальному PowerShell із SDK/JDK, не видаляючи кеші й не вбиваючи процеси. `doctor.ps1 -BuildOnly` є лише preflight і не замінює компіляцію чи тести.

Windows `build.bat` створює PyInstaller **onedir**, а не інсталятор; для запуску потрібна вся папка `dist\TransferKit`, зокрема runtime data з `shared/`. Android `build-sideload.bat` створює **debug-signed** APK і не налаштовує production key. Наявні `dist`-артефакти — локальні build outputs, не доказ актуальної чи підписаної релізної збірки; перед використанням їх потрібно перебудувати з перевіреного source revision.

## Перевірений стан на дату знімка

- Physical navigation follow-up на A27/API 37: головне меню відкривається; після натискання «Стан передачі» accessibility/screenshot показали інший пакет, хоча Activity Manager позначив `MainActivity` resumed. Crash buffer порожній, тому це не класифіковано як підтверджений TransferKit crash або успішна навігація. Тимчасовий APK видалено й `pm path` порожній; причину треба дослідити перед фізичним acceptance пункту 049.
- Calendar Provider synthetic acceptance на A27/API 37 пройшов **1/1** після виправлення Samsung delete semantics; тимчасові app/test APK видалено й runtime calendar grants відкликано. Після cleanup A27 згодом зник із Wi-Fi ADB, тому новий destination chooser UI run не виконано; останній `adb devices -l` показував online лише Redmi 12.

- Python: до alpha.4 — **148/148 passed**; останній повний запуск після локальних fixes — **152/152 passed** (`python -m unittest discover -s tests -v`), включно з planner legacy-Downloads regression, USB ADB smoke error handling, Recovery invalid-header early rejection та автоматичним schema-conformance coverage усіх `ReasonCode`. Android target inventory та transfer filtering для dedup ще не інтегровані.
- Android unit: останній перевірений XML — **88/88 passed**, failures/errors/skips=0, включно з vCard parser boundaries/quoted-printable, calendar payload parser strictness, durable progress, bounded byte counters і APK consent regression. Android 17/API 37 receiver/core instrumentation раніше пройшов **26/26**; Compose UI на API 34/35/37 — **6/6**. Contacts Provider synthetic instrumentation пройшов **1/1** на A27/API 37: local vCard import, selected-field readback, idempotent retry, exact-marker cleanup. Calendar Provider synthetic instrumentation також пройшов **1/1** на A27/API 37: writable destination selection, event import/readback, повтор без дубля, точне очищення власного synthetic marker. Тимчасові APK видалені, test-only permissions відкликані, synthetic контакт і подія відсутні. Device Owner probe на API 37 не запускався: automatic approval review повернув `Bad Request` до виконання команди.
- Android Gradle: full alpha.4 task `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --offline` — `BUILD SUCCESSFUL`; unit — **81/81 passed**. AndroidTest APK compilation succeeded. The user installed alpha.3 after metadata-only preflight; SDK ADB confirms versionCode=4. After the user revoked the old grants, the bundled prompt flow succeeded with READ_MEDIA_VISUAL_USER_SELECTED=true, READ_MEDIA_IMAGES=false, and ACCESS_MEDIA_LOCATION=true. Screenshot shows exactly one 772-byte synthetic item and an original URI prepared after consent. Manifest construction completed with 1 category, 1 item, and 0 problem items; no transfer or restore started. The synthetic item was read once for SHA-256, but the UI does not expose its digest, so exact fixture hash equality remains unverified. Legacy public-directory restore API 26–28 and separate API 29 acceptance remain open. Shared Python/Kotlin manifest rules are aligned and covered by the vector corpus.
- Фізична перевірка: OUKITEL WP23 Plus/Android 15/API 35 пройшов synthetic Device Owner, 25 shared manifest vectors і 3 MediaStore integrity tests; Samsung A04e/API 33 → Motorola moto g06/API 35 пройшов receive acceptance із synthetic payload 65 536 байтів/16 chunks, schema-valid receipt та незалежним SHA-256. Новий тимчасовий Xiaomi Redmi 12 (23053RN02Y, API 35) пройшов read-only ADB/device identity/storage probe. MI Account gate не дозволив встановити тестовий APK, тому app UI/MediaStore instrumentation на ньому не запускали.
- ADB: Redmi 12 is available as shell UID. The user revoked the earlier media grants manually, then accepted limited access to the selected synthetic photo and ACCESS_MEDIA_LOCATION. ADB dumpsys package confirms READ_MEDIA_VISUAL_USER_SELECTED=true, READ_MEDIA_IMAGES=false, ACCESS_MEDIA_LOCATION=true. MIUI does not allow shell to change those runtime grants. Use only SDK ADB 1.0.41; Windows adb.exe 1.0.40 conflicts with it. Earlier S20/M31 and four explicit Wi-Fi ADB serials passed separate checks.
- API coverage: фізичну Android 14/API 34 перевірку пункту 036 завершено на Samsung A72 (SM-A725F); S20/API 33, M31/API 31 та Redmi 12/API 35 мають окремі acceptance записи. Після A72 перевірки TransferKit і AndroidTest APK видалені, granted permissions відкликані, synthetic media очищені; third-party package count = 0. Emulator Medium_Phone_API_37.0 усе ще не запускається через відсутній hardware hypervisor, але це не блокує API 34 acceptance.
- Synthetic EXIF physical acceptance (2026-11-13): `MediaOriginalAccessInstrumentedTest` пройшов **3/3 на S20/API 33 і 3/3 на M31/API 31**. Suite перевіряє звичайні URI для фото/відео, gate `ACCESS_MEDIA_LOCATION` та exact bytes/SHA-256 для bundled 772-byte GPS EXIF fixture. Test asset читається з instrumentation APK; під час тесту використовується scoped `adoptShellPermissionIdentity(ACCESS_MEDIA_LOCATION)` із `dropShellPermissionIdentity()` у `finally`. Після обох запусків `dumpsys package` показує `ACCESS_MEDIA_LOCATION: granted=false`; `pm path com.transferkit.client.test` не повернув APK path. Основний app лишився встановленим. Test code видаляє тільки власний випадковий fixture URI у `finally`; незалежна `content query` listing-перевірка не виконалась через CLI quoting error, тож окремого provider-listing proof немає.
- Android 14/API 34 physical partial-media acceptance (2026-11-13), Samsung A72/SM-A725F: після user-limited picker вибір photo scan показав рівно 1 synthetic JPEG, а video scan — рівно 1 synthetic MP4; у кожному сценарії READ_MEDIA_VISUAL_USER_SELECTED=true, READ_MEDIA_IMAGES=false, READ_MEDIA_VIDEO=false. :app:assembleDebugAndroidTest — BUILD SUCCESSFUL; MediaOriginalAccessInstrumentedTest — 3/3 на API 34, включно з byte-for-byte/SHA-256 рівністю 772-byte synthetic GPS EXIF fixture. Для app-level original-URI scan ACCESS_MEDIA_LOCATION видано тестовим pm grant; UI prompt цього окремого дозволу на A72 не зараховано. Після тесту основний і test APK видалено, runtime grants відкликано, два точні synthetic файли та службові скриншоти прибрано; на пристрої лишилося 0 third-party packages.
- A72/API 34 follow-up: окремі full category grants для фото й відео дали `READ_MEDIA_IMAGES=true` та `READ_MEDIA_VIDEO=true`; metadata-only UI scan показав `photos: 1` і `videos: 1`. `RestorerIntegrityTest` пройшов 5/5 на тому самому API 34: valid synthetic GPS-EXIF JPEG і MP4 опубліковані у правильні collections, SHA-256/байти перечитано точно, retry не створив дублів. Після обох прогонів app/test packages і тільки наші synthetic items прибрані; екранне stay-awake налаштування повернене до початкового `null`.
- A72/API 34 provider restore follow-up: `ContactsRestorerInstrumentedTest` та `CalendarRestorerInstrumentedTest` пройшли по 1/1; додатково `SafDocumentRestorerInstrumentedTest` пройшов 6/6 із synthetic DocumentsProvider на A72/API 34, включно з persistable grant acquire/restore/release. Обидва імпортували лише власний synthetic item, перевірили readback/idempotent retry та видалили exact TransferKit marker у `finally`; після цього app/test packages видалені, отже тимчасові contacts/calendar grants відкликані.
- Source revision: `D:\Transfer` has no Git metadata; the temporary clone holds the Git history. Alpha.4 source-only commit `1fd4a63c29c316e1ef6668d83abb8bfbbc053557` was published to private `main` as a fast-forward child of alpha.3 commit `ee7c641a61d67d17e098b4474bea9fe8d582e67e`; `git ls-remote` matched local HEAD. Alpha.3 commit `448bc180d0e0324a45cffa3e749a82e4675d690f` and earlier versions remain in history. No force-push or tag was used.

### Redmi 12 follow-up — 2026-11-13

- Користувач вручну встановив попередній debug APK. `pm path`, `versionCode=2`/`versionName=0.1.0-alpha.1` і resumed `MainActivity` підтверджені через SDK ADB; скриншот показав головний екран без crash. Дані передачі/restore не запускались.
- MIUI забороняє `adb shell input tap` і `input keyevent` через відсутній shell `INJECT_EVENTS`; для переходу користувач натиснув кнопку вручну. Окрема секція нижче фіксує фізичний результат.
- Скриншот виявив застарілий UI текст про підтримку restore тільки від API 29. `DeviceInfoScreen` виправлено на legacy API 26–28 плюс MediaStore API 29+. Тодішню debug-збірку з SHA-256 `73C005...` пізніше замінено актуальнішою збіркою, описаною нижче.

### APK approval follow-up — 2026-11-13

- У 050 `ApkInstallApprovalGate.consumeForInstall` тепер повертає `INSTALL_PERMISSION_REQUIRED` без споживання одноразового підтвердження. Після повернення з Android Settings повторна спроба може використати його один раз; успішний install attempt усе ще споживає consent.
- Regression-тест пройшов разом із Android unit suite: **62/62**. `:app:assembleDebug :app:assembleDebugAndroidTest` — `BUILD SUCCESSFUL`.
- У 050 після початкового consent regression під’єднано APK inbox/review, per-APK confirmation, explicit `ApkInstallStatusReceiver`, unknown-app settings result та receipt-bound `recordPostVerifiedRestore`. System settings URI спрямований на applicationId TransferKit, а не на встановлюваний пакет. `ApkRestoreViewModel` зберігає точний item і consent під час Activity recreation/повороту, поки відкриті Android Settings; process death може вимагати повторного перегляду/підтвердження, але staging лишається недоторканим.
- Android unit suite — **62/62 passed**. Фізичні instrumentation: A23/API 34 Compose 2/2; OUKITEL/API 35 Compose 2/2 після зняття no-PIN lock screen; A27/API 37 core 26/26 і Compose 2/2 після Espresso update. Всі тимчасові пакети прибрані; Redmi installation/status callback не запускали через відсутність receiver-verified APK item.
- Поточний `D:\Transfer\TransferKit-Android-debug.apk`: 9,270,567 bytes; SHA-256 `5EA6D59D013371B85B85CA18258297D7C42A42AC6577244CB7356411B9B343F7`. Це debug APK; Redmi вручну встановлював alpha.2, але instrumentation install failure видалив main package; APK лишився в Downloads, повторна ручна установка очікується.
- Не виконано фізичний APK install/status callback: на Redmi 12 немає receiver-verified APK item, а automated APK install раніше зупинив MI Account gate. Activity recreation/rotation має retained ViewModel state; process-death handling і APK-only source/Station end-to-end лишаються відкритими.

### Redmi progress-screen follow-up — 2026-11-13

- Після повторного USB-підключення SDK ADB бачить Redmi 12 як `39f502057d76`, model `23053RN02Y`, product `heat_eea`, Android 15/API 35. `com.transferkit.client` versionCode=2 був встановлений; app лишався відкритим на головному екрані. Wireless Debugging під’єднано окремим serial-ом; `get-state=device`, SDK=35, USB і Wi‑Fi serial-и доступні одночасно. Мінливу адресу/порт до документації не додавав.
- Користувач вручну відкрив «Стан передачі». `topResumedActivity` підтвердив `com.transferkit.client/.MainActivity`; screenshot `test-results/redmi12-progress-idle-20261113.png` показує idle status, відсутній progress до durable checkpoint, вимкнені «Пауза»/«Скасувати». Передача чи користувацькі файли не запускались і не читалися.
- У screenshot виявлено, що довгий підпис keep-awake перемикача під ним обрізався. `TransferProgressScreen` виправлено через `Modifier.weight(1f)`/spacing для обох перемикачів; Compose instrumentation тепер також перевіряє, що підпис відображений. `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` — `BUILD SUCCESSFUL`; unit — **62/62**. Новий APK не встановлювався, тому layout correction ще треба підтвердити на Redmi.
- SDK `adb shell input tap` і далі відхиляється MIUI з `INJECT_EVENTS`; фізичні кнопки натискав користувач. Layout fix і новий APK ще не перевірені на Redmi. Збірка з SHA-256 `E8ECB96EEE764A6FB959EA81BA3160A82826A2ADD7E27F5FF73A43ECB54B4720` була наступно замінена новішою збіркою з контрольної точки APK approval вище.
- Для API coverage Samsung SM-A235F/Android 14/API 34, SM-A276B/Android 17/API 37 і OUKITEL WP23 Plus/API 35 тимчасово отримали debug/test APK для ізольованих instrumentation; усі тестові пакети видалені. На час оновлення 2026-11-13 користувач повідомив, що A27/API 37 та A23/API 34 більше недоступні; Redmi 12 доступний через USB ADB, але main package наразі відсутній після невдалої спроби встановити AndroidTest APK; debug APK лишився в Downloads, потрібна ручна перевстановлення. Попередній прогін мав OUKITEL Wi-Fi ADB online, але його поточний стан тут не переперевіряли. На цих пристроях не запускали transfer із користувацькими файлами.

### Restore acceptance follow-up — 2026-11-14

- `SettingsSystemRestorer` writes only the explicit `Settings.System` allowlist after restore consent and `Settings.System.canWrite()`. It validates canonical bytes and SHA-256, reads every written value back, and attempts a guarded rollback after a partial failure. On A72/API 34, the no-access case passed 1/1 without changing the setting or consuming approval; the positive synthetic case passed 1/1 after granting access on Android's `ACTION_MANAGE_WRITE_SETTINGS` screen. `SOUND_EFFECTS_ENABLED` started and ended at `0`; temporary APKs were removed, which removed the test app's special access. The system screen itself is physically confirmed, but the product's pending Restore Inbox → activity-result → review sequence remains untested.
- Synthetic `ContactsRestorer` acceptance passed 1/1 on S20/API 33, M31/API 31, and A72/API 34. `CalendarRestorer` passed 1/1 on all three after fixing Samsung M31/API 31 post-verification: Calendar Provider creates one destination-owner attendee row when `SELF_ATTENDEE_STATUS` is present. The restorer now recognizes only that exact provider-generated row; other attendee/reminder mismatches still fail verification. Diagnostics log field names/counts only, never calendar values.
- Android unit XML: **88/88 passed**, zero failures/errors/skips; `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` completed successfully. S20 alpha.4 was restored with an exact installed-APK SHA-256 match; M31/A72 test packages and temporary grants were removed.
- Remaining for point 045: API 26–28 and API 29 physical restore acceptance, the user-facing WRITE_SETTINGS approval flow, broader OEM/provider coverage, and category limitations documented in `shared/CONTRACTS.md`.

## Відомі обмеження — release blockers

- 2026-11-13 через headless Qt harness фактичного `StationWindow`/worker та фізичні телефони пройдено device selection/plan/receive → receipt → явне Android restore approval → MediaStore post-verification на synthetic item: Samsung S20 Ultra/API 33 → Samsung M31/API 31, 65 536 bytes; receipt verified=1/1, Android restore 1/1 без помилок, незалежне читання точного destination item підтвердило SHA-256 `7daca209…b27e4c2`. Station досі не отримує Android restore outcome і не створює фінальний report; саме report exchange лишається release blocker. Це тест synthetic data, не особистих файлів. Видиму інтерактивну desktop UI, зокрема її візуальне приймання людиною, окремо не перевірено.
- Wi-Fi TLS sockets, certificate pinning і QR pairing не завершені. USB/ADB прототип працює через Windows ADB host; прямий phone-to-phone USB не підтримується як файловий transport.
- Recovery state machine та cross-transport resume не реалізовані наскрізно. Контракти/вимоги є, acceptance обриву кабелю чи Wi-Fi не проводився.
- Space Preflight, streaming restore і drop-largest ще не реалізовані наскрізно.
- Restore поза фото/відео/аудіо/Downloads ще не завершений. SAF documents adapter має synthetic-provider instrumentation 5/5 на S20/API 33, M31/API 31 і 6/6 на A72/API 34. Реальний `ACTION_OPEN_DOCUMENT_TREE` picker і локальний Samsung Documents provider пройшли acceptance на A72/API 34: запис, точний readback/SHA-256, retry без дубля, видалення synthetic-файла та власного persisted grant. Інші OEM/cloud SAF providers ще не перевірені. API 26–28 public-directory adapter реалізований, але очікує фізичного acceptance; API-category adapters потребують роботи. M1 acceptance — 100 фото + 50 контактів із 100% SHA-256 збігом — відкритий.
- Ще потрібні API 29 physical instrumentation, Samsung→Redmi і Pixel→Motorola acceptance. API 34 partial-media gate для 036 закрито на A72. Перед наступною фізичною перевіркою звірити актуальний adb devices -l; пристрої та permission стани змінюються між сесіями.
- SMS/Call Log доступ залишається залежним від платформних hard-restricted grants та розповсюдження/policy. Перевірити відповідність Google Play перед розповсюдженням.
- Production signing/versioning, Windows code signing, provenance та release checksum package відсутні. Репозиторій зараз private і ліцензію не обрано; перед переведенням у public треба отримати вибір власника. Не поширювати debug APK/exe як готовий споживчий продукт.

## Gate перед будь-яким публічним релізом

- [ ] Створити Git commit/tag для конкретного кандидата; робоче дерево має бути чистим.
- [ ] Чисто встановити Python та Android залежності з обох lock-файлів; зафіксувати версії SDK/JDK і SHA-256 candidate artifacts.
- [ ] Прогнати Python 125+ suite та Gradle unit/build з чистої перевіреної revision; зберегти логи й artifact SHA-256.
- [ ] Переконатися в успішному GitHub Actions run для revision-кандидата; увімкнути branch protection після першого проходження.
- [ ] Вибрати й додати ліцензію лише після рішення власника перед будь-якою публічною публікацією.
- [ ] Перевірити Windows onedir запуск поза source tree, включно з доступністю всіх `shared/*.schema.json`.
- [ ] Налаштувати production signing окремо від debug key; звірити app identity/version, Android target API та distribution policy.
- [ ] Пройти Station desktop UI та повний data restore + destination post-verification/report без використання особистих контрольних файлів.
- [ ] Закрити заявлений release scope через hardware/API matrix і критерії у `WORK_CHECKLIST.md`; фіксувати лише реально перевірені моделі/API.
- [ ] Переглянути permissions, приватність/logging, cleanup, помилки й локалізацію; підготувати release notes та відомі обмеження.
- [ ] Зібрати candidate у нову чисту output-папку, створити SHA-256 manifest, перевірити інсталяцію/видалення на тестових пристроях і лише тоді публікувати.

## Що включати в candidate package

Після проходження release gates пакет має містити Windows distribution folder цілком (або installer), окремо підписаний Android release APK, короткі install/run інструкції, сумісність/permissions, release notes, SHA-256 checksums і посилання на source tag. Не включати `.venv`, Gradle/Python build caches, `test-results/`, локальні SQLite history, app-private dumps, device identifiers чи особисті payload-файли.

## Redmi 12 ADB install outcome (2026-11-13)

SDK ADB 1.0.41 identified Redmi 12 model `23053RN02Y` (Android 15, MIUI `V816`). `adb install -r` returned `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`; the install did not complete. The exact alpha.2 debug APK was copied to `/sdcard/Download/TransferKit-Android-debug.apk`, and on-device SHA-256 matched the local APK (`5EA6D59D013371B85B85CA72FB3EC05815F105C45E439D23752E775EC28BE0D09A`). The supported next step is to approve the system request or enable MIUI's **Install via USB** setting. If MIUI requires Xiaomi account authorization, do not bypass it; use the Android Files installer only after the user approves unknown-app installation.

Manual Files installer follow-up: the user installed the copied APK on Redmi 12. ADB confirmed `pm path`, `versionName=0.1.0-alpha.2`, `versionCode=3`, and `MainActivity` as top resumed activity. The APK was not installed through ADB; MIUI continues to return `INSTALL_FAILED_USER_RESTRICTED` for that route.

## Redmi instrumentation install follow-up (2026-11-13)

`:app:connectedDebugAndroidTest` compiled the new GPS EXIF fixture test but MIUI refused to install `app-debug-androidTest.apk` with `INSTALL_FAILED_USER_RESTRICTED`. After that failed install session, `pm path com.transferkit.client` returned no package and `am start` reported no `MainActivity`; the main app package had been removed. The user-owned APK remains at `/sdcard/Download/TransferKit-Android-debug.apk` with the expected SHA-256. Do not rerun `connectedDebugAndroidTest` on this Redmi until its ADB install gate is explicitly enabled; manual reinstall is required first. The instrumentation runner has not executed any test on this device.

## Redmi 12 alpha.3 media permission follow-up (2026-11-13)

The user manually installed alpha.3; SDK ADB confirmed `versionCode=4`. The app's media status then reported 7 accessible photos after the user followed the single synthetic-photo test instructions. The scan completed before this was noticed; the prior implementation queried metadata for the permission-visible category and opened/read up to the first 16 KiB from its first item, without hashing or persisting bytes or coordinates. The app was force-stopped. ADB could not revoke the three runtime permissions (`REVOKE_RUNTIME_PERMISSIONS` is denied to MIUI's shell UID); the user manually revoked them through Android Settings. The updated APK with metadata-only `scanMedia` was installed as an update. The user then selected only the synthetic photo in Android's limited photo access dialog. SDK ADB confirmed `READ_MEDIA_VISUAL_USER_SELECTED=true`, `READ_MEDIA_IMAGES=false`, and `ACCESS_MEDIA_LOCATION=false`; the UI reported exactly 1 visible item (772 bytes) and metadata-only inspection. No further content read has occurred. `DeviceInfoScreen` explains the visible-photo permission scope and that content/SHA-256 are read during manifest construction. Gradle unit/build passed 74/74; the current alpha.3 debug APK is 9,270,771 bytes, SHA-256 `4228B1DC8A9C835ABD3F68893A565A3D3AC5029432C578709A428CC89A5F4061`. The GPS EXIF byte-equality instrumentation remains unexecuted on Redmi.

## Redmi 12 EXIF permission prompt follow-up (2026-11-13)

The user tapped the original-EXIF action after limiting selected media to the single 772-byte synthetic fixture, but Android did not show an `ACCESS_MEDIA_LOCATION` prompt. ADB reports the permission denied and AppOps `ignore`; the old build fell back to the ordinary scan and warned that GPS EXIF might be redacted. The denial callback now stops the original-EXIF request and explains how to grant the permission or choose the standard scan. Gradle unit/build passed 74/74. The newest alpha.3 debug APK (`TransferKit-Android-alpha3-exif-denial-debug.apk`, 9,439,050 bytes, SHA-256 `8BA5770EFBB9C2B34CE5C10D755CE076E7A43E8B5EC91C678B60ED453BEBD7A1`) was manually installed; the three older staged APKs were removed from Downloads. TransferKit app permissions are open in MIUI Settings while the user checks for the EXIF permission. Do not make another original-photo read until the user has explicitly granted the permission. Physical exact-byte acceptance remains open.

## Redmi 12 bundled media-permission build (2026-11-13)

Historical staging snapshot; the later manual install, limited permission flow, manifest build, and selection cleanup are recorded in the follow-up section below.

Android's current partial-photo guidance recommends requesting `READ_MEDIA_VISUAL_USER_SELECTED`, the category read permission, and `ACCESS_MEDIA_LOCATION` in one operation. The latest implementation follows this and has unit coverage for API 28/33/34 permission sets; denial of explicit EXIF access still stops the scan. The user-visible MIUI app-permission screen showed Photos/Videos as “Ask every time” and no separate EXIF row; the Android intent to open the permission controller directly was denied to ADB shell, so no settings were changed. Gradle unit/build passed 77/77. The newest debug APK is 9,439,613 bytes, SHA-256 `7F970A1660F82D67871EFDE74B192EDE11AE6E068AB49CB65DDB08F6959E6A0A`; it is staged as the sole TransferKit APK in Redmi Downloads. The installed app has not yet been updated to this hash. Limited photo access remains one synthetic 772-byte item; no further content bytes were read. Physical `ACCESS_MEDIA_LOCATION` grant and exact-byte verification remain open.

### Redmi 12 combined permission acceptance (2026-11-13)

The user completed the controlled first-time request after revoking prior grants. Only the synthetic 772-byte fixture was selected. ADB confirms READ_MEDIA_VISUAL_USER_SELECTED=true, READ_MEDIA_IMAGES=false, and ACCESS_MEDIA_LOCATION=true; the app UI reports one photo item and says the original URI was prepared after explicit consent. The local screen capture is test-results/redmi12-media-permissions-after-grant.png (not published). Manifest construction completed with 1 category, 1 item, and 0 problem items; no transfer or restore was started. The synthetic item was read once for SHA-256, but its digest is not exposed by the UI, so exact byte equality remains unverified. The user then cleared the selection; the UI showed zero selected categories and confirmed cleanup of the app-owned temporary snapshot. This verifies the limited permission flow on Redmi/Android 15, not GPS byte preservation or restore.

## SAF documents restore physical follow-up (2026-11-13)

- Реалізовано окремий SAF destination restorer: user-selected tree, explicit restore approval, source SHA-256 check, temporary write + sync, provider rename, final destination readback/hash, no-overwrite marker і cleanup лише власного pending item.
- :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest --no-daemon --console=plain завершився BUILD SUCCESSFUL; Android unit XML: 84 tests, 0 failures/errors/skips.
- SafDocumentRestorerInstrumentedTest пройшов 5/5 на Samsung S20 Ultra/API 33 та 5/5 на Samsung M31/API 31. Перевірено write/readback і retry без дубля, source mismatch, захист пошкодженого marker, provider без rename та відсутню destination.
- Після тестів com.transferkit.client.test відсутній на обох пристроях, основний app лишився встановленим, ACCESS_MEDIA_LOCATION лишився denied. Тестові файли створювалися лише у test-app cache та зникли після видалення instrumentation package.
- На момент запису було доведено лише contract-level інтеграцію із synthetic ContentProvider; наступний фізичний follow-up нижче закрив реальний picker flow для Samsung local Documents provider на A72/API 34. Інші OEM/cloud providers та решта adapters залишаються відкритими.

## SAF system picker acceptance (2026-11-14)

- На Samsung A72/SM-A725F/API 34 пройшов `SafSystemPickerAcceptanceTest` — **1/1 passed** через реальний Android `ACTION_OPEN_DOCUMENT_TREE` picker і локальний Documents destination.
- Тест створив лише UUID-іменований synthetic документ, прочитав його назад і звірив точні байти та SHA-256, повторив restore без дубля, видалив тільки власний synthetic документ і звільнив grant, створений цим тестом.
- Під час фізичного прогону виявлено й виправлено помилку: `DocumentsContract.createDocument()` отримував tree URI замість document URI. Реальний provider відхиляв запит. Restorer тепер передає кореневий document URI; provider `IllegalArgumentException` під час запису класифікується як `TARGET_WRITE_FAILED`, а не `INVALID_MANIFEST`. Synthetic provider тепер також вимагає коректний parent document URI.
- Після тесту обидва тимчасові APK були відсутні, TransferKit URI grants не залишилися, а пошук за точними тестовими іменами не знайшов файлів у Documents.
- Це приймає один Samsung local Documents provider на API 34; не доводить поведінку інших OEM/cloud providers, API 29 чи API 26–28. Пункт 045 лишається [~].

### Recovery frame parser follow-up (2026-11-13)

- Потоковий reader перевіряє frame magic/kind до body read. Новий counting-stream test підтверджує, що пошкоджений або неочікуваний header споживає лише 9 bytes, навіть якщо header оголошує body до 256 KiB.
- Повний Python suite після трьох локальних regression fixes пройшов 150/150. Recovery frame parser не є активним мережевим consumer; physical interruption/resume acceptance не змінювався.


### Canonical SHA-256 helper follow-up (2026-11-13)

- `verify_sha256` тепер відхиляє uppercase/non-hex digest input відповідно до manifest.schema.json lower-case pattern. Regression включена у full suite; Python 151/151 passed на момент цього follow-up.

### ReasonCode schema coverage follow-up (2026-11-13)

- Ручний список reason codes у report test замінено циклом по всьому `ReasonCode`: schema pattern, `ReportDecision` construction, JSON Schema і Pydantic report validation. Python suite — 152/152 passed.
- Цей тест не задає семантичний allowlist для `decisions`; звуження reason-кодів потребуватиме окремої контрактної вимоги й окремих прикладів сумісності.
## Restore Inbox settings-only route follow-up (2026-11-14)

Settings-only transfer має окремі лічильники у `ReceivedTransferSummary`; Settings не збільшує `unavailableVerifiedItemCount`, Restore Inbox показує обсяг/обмеження allowlist і відкриває Review, а `MainActivity` застосовує `WRITE_SETTINGS` special-access gate перед записом. Unit suite — **88/88**; build та AndroidTest APK — успішні. На A72/API 34 targeted instrumentation — **19/19**; на S20/API 33 після пробудження UI test пройшов **1/1**. `SettingsRestoreInboxFlowTest` на A72/API 34 — **1/1 passed**: production Inbox/Review відкрив справжній системний special-access екран, Activity Result повернув застосунок до повторного review, тест скасував запис; ключ залишився `0`, app-op і тимчасові APK прибрані. Пункт 045 лишається частковим через API 26–29, інші категорії та OEM/provider acceptance.

## Shizuku bridge verification follow-up (2026-11-14)

`ShizukuBridge` now uses an injectable narrow API adapter; Android unit suite — **94/94 passed**, debug and AndroidTest APKs build. Read-only instrumentation tested the missing/stopped service on S20/API 33 and A72/API 34 — **1/1 on each**. The permission manager prompt remains physically untested because Shizuku is not installed. Gradle's aggregate run also saw a Wi-Fi serial for the same A72; the two unique-device results passed, while the duplicate endpoint failed before tests (0 tests). Filter duplicate physical devices for future multi-device runs.
## Receiver progress callback physical evidence (2026-11-14)

Targeted receiver progress instrumentation passed **1/1 on S20/API 33 and 1/1 on A72/API 34** using synthetic payload. It confirms only durable ACK-based callback totals and final receipt progress. Temporary app/test packages were removed after each run and the monitored system setting remained `0`. This is not end-to-end foreground notification verification during a live desktop transfer; disconnect and recovery testing remain open.
## Receiver full-class follow-up (2026-11-14)

The complete `ReceiverTest` instrumentation class passed **18/18 on A72/API 34** and **18/18 on S20/API 33** by direct, serial-targeted `am instrument` runs. This includes the ACK-confirmed cumulative progress callback and receiver integrity/error cases. Temporary packages were removed; `pm path` returned empty and `SOUND_EFFECTS_ENABLED` remained `0`. This does not cover the foreground notification during a live Windows sender transfer, cable interruption, pause/cancel, or recovery.
## Transfer progress idle-screen physical follow-up (2026-11-14)

`TransferProgressScreenTest#idleScreenDoesNotInventProgressAndLeavesUnsafeActionsDisabled` passed **1/1 on A72/API 34 and 1/1 on S20/API 33**. No progress is fabricated without durable checkpoints; Pause/Cancel remain disabled. Test-only APKs were removed and the monitored system key remained `0`. Live receiver UI, keep-awake/dimmer lifecycle and transport controls remain open.
## Transfer progress keep-awake/dimmer follow-up (2026-11-14)

Page-scoped keep-awake and dimmer interaction passed **1/1 on A72/API 34 and 1/1 on S20/API 33**. The test verified the window flag/brightness restore to their captured starting values after navigation; Android system brightness was not changed. APKs were removed after both runs. Point 049 still needs live receiver snapshots, rotation/process-death and external accessibility acceptance.

## MediaStore integrity physical repeat (2026-11-14)

`RestorerIntegrityTest` passed **5/5 on Samsung A72/API 34 and 5/5 on Samsung S20 Ultra/API 33** using only synthetic JPEG/MP4 payloads and exact random-marker cleanup. Both temporary app/test packages were uninstalled afterward. This strengthens category 045 evidence across API 33/34; it does not close API 29 acceptance or legacy public-directory API 26–28 acceptance.
## Legacy public-directory adapter testability follow-up (2026-11-14)

`LegacyMediaRestorer` now depends on a narrow `LegacyMediaEnvironment` boundary for API level, runtime permission, mounted storage/root, and MediaScanner. Seven deterministic JVM regressions passed as part of Android unit **101/101**; debug and AndroidTest APKs built successfully. They cover verified staging/publish, retry, permission-gate semantics, payload integrity, collision protection, storage-not-mounted, and scanner retry. This does not count as Android API 26–28 device acceptance; available devices are API 33/34 and the installed emulator image is API 37 only.

Після cleanup test scripts SDK `adb devices -l` показав порожній список; після повторного `adb start-server` телефони не з’явилися. Під час кожного фізичного прогону cleanup перевірив відсутність app/test package відразу після uninstall; подальшу перевірку стану телефонів треба виконати після їх повторного підключення.
