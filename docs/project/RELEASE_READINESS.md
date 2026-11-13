# TransferKit — release readiness

**Знімок стану:** 2026-11-13

**Висновок:** перевірний release-набір підготовлено, але продукт ще **не готовий до публічного релізу**. Наявний Android APK — debug build; основні migration acceptance і частина transport/restore функцій залишаються відкритими.

Цей документ є контрольним аркушем для відтворення збірки та перевірки доказів. Він не перетворює прототип на реліз і не дозволяє трактувати отриманий `receipt.json` як доказ успішного відновлення даних.

## Ідентифікація та версії

| Компонент | Зафіксована версія/межа | Джерело істини |
|---|---|---|
| TransferKit Windows | `0.1.0-alpha.2` | `VERSION`, mirrored in `transferkit/__init__.py` |
| TransferKit Android | `versionName=0.1.0-alpha.2`, `versionCode=3`, package `com.transferkit.client` | `VERSION`, mirrored in `android-client/app/build.gradle.kts` |
| Python | мінімум 3.11; у цій перевірці Python 3.14.4 | `install.bat`, середовище перевірки |
| Python залежності у requirements | PySide6 ≥6.6; cryptography ≥41; Pydantic ≥2; jsonschema ≥4.20; referencing ≥0.30; PyInstaller ≥6.0 | `requirements.txt` |
| Python середовище цієї перевірки | PySide6 6.11.0, cryptography 43.0.3, Pydantic 2.13.5, jsonschema 4.26.0, referencing 0.37.0, PyInstaller 6.20.0 | активний інтерпретатор користувача |
| Android build | JDK 17; Gradle Wrapper 8.7; Android Gradle Plugin 8.5.2; Kotlin 2.0.20 | `android-client/gradle/wrapper/gradle-wrapper.properties`, Gradle scripts |
| Android SDK | compile/target SDK 34, min SDK 26, Build Tools 34.0.0 | `android-client/app/build.gradle.kts`, `BUILDING_AND_TESTING.md` |
| Wire format | поточні transport/control frames — version 1 | `shared/protocol.md`, Python/Kotlin codecs |

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

- Python: останній повний запуск — **146/146 passed** (`python -m unittest discover -s tests -v`), включно з Recovery frame codecs, version consistency і shared manifest contract vectors. Android target inventory та transfer filtering для dedup ще не інтегровані.
- Android unit: останній локальний запуск — **72/72 passed**, включно з vCard parser boundaries/quoted-printable, calendar payload parser strictness, durable progress, bounded byte counters і APK consent regression. Android 17/API 37 receiver/core instrumentation раніше пройшов **26/26**; Compose UI на API 34/35/37 — **6/6**. Contacts Provider synthetic instrumentation пройшов **1/1** на A27/API 37: local vCard import, selected-field readback, idempotent retry, exact-marker cleanup. Новий Calendar Provider synthetic instrumentation також пройшов **1/1** на A27/API 37: writable destination selection, event import/readback, повтор без дубля, точне очищення власного synthetic marker. Під час першого запуску тест знайшов обмеження Samsung Provider на delete з item URI; виправлено на collection URI з одночасними ID і marker predicates. Обидва набори test-only permissions відкликані, тимчасові APK видалені, synthetic контакт і подія відсутні. Device Owner probe на API 37 не запускався: automatic approval review повернув `Bad Request` до виконання команди.
- Android Gradle: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` — `BUILD SUCCESSFUL`; Android unit — **72/72**; AndroidTest APK збирається з Espresso 3.7.0 / AndroidX Test Runner 1.7.0 lockfile. Espresso 3.5.1 на API 37 падав через reflection до `InputManager.getInstance`; оновлення до 3.7.0 усунуло проблему. Redmi install gate `INSTALL_FAILED_USER_RESTRICTED` лишається для ADB; користувач вручну встановив alpha.2 через Files, запуск підтверджено. Legacy public-directory restore API 26–28 та окремий API 29 acceptance відкриті. Manifest schema/Pydantic/Kotlin rules вирівняні; shared vector corpus перевіряє canonical UUID, integer bounds/forms, timestamp precision, Unicode lengths, strict properties, SHA, duplicate/wire-ID collisions і 64 KiB metadata boundary.
- Фізична перевірка: OUKITEL WP23 Plus/Android 15/API 35 пройшов synthetic Device Owner, 25 shared manifest vectors і 3 MediaStore integrity tests; Samsung A04e/API 33 → Motorola moto g06/API 35 пройшов receive acceptance із synthetic payload 65 536 байтів/16 chunks, schema-valid receipt та незалежним SHA-256. Новий тимчасовий Xiaomi Redmi 12 (23053RN02Y, API 35) пройшов read-only ADB/device identity/storage probe. MI Account gate не дозволив встановити тестовий APK, тому app UI/MediaStore instrumentation на ньому не запускали.
- ADB: Redmi 12 доступний як звичайний `shell` UID; `pm path` для `com.transferkit.client` і test package порожній після відхиленої інсталяції. На ПК `C:\Windows\adb.exe` 1.0.40 і Android SDK ADB 1.0.41 конфліктують і при змішуванні перезапускають daemon; для подальших фізичних тестів слід користуватися одним SDK ADB executable. Раніше S20/M31 та чотири explicit Wi-Fi ADB serials пройшли окремі тести.
- Source revision: каталог `D:\Transfer` не є Git repository; немає commit/tag і чистого-tree доказу, до якого прив’язати candidate.

### Redmi 12 follow-up — 2026-11-13

- Користувач вручну встановив попередній debug APK. `pm path`, `versionCode=2`/`versionName=0.1.0-alpha.1` і resumed `MainActivity` підтверджені через SDK ADB; скриншот показав головний екран без crash. Дані передачі/restore не запускались.
- MIUI забороняє `adb shell input tap` і `input keyevent` через відсутній shell `INJECT_EVENTS`; для переходу користувач натиснув кнопку вручну. Окрема секція нижче фіксує фізичний результат.
- Скриншот виявив застарілий UI текст про підтримку restore тільки від API 29. `DeviceInfoScreen` виправлено на legacy API 26–28 плюс MediaStore API 29+. Тодішню debug-збірку з SHA-256 `73C005...` пізніше замінено актуальнішою збіркою, описаною нижче.

### APK approval follow-up — 2026-11-13

- У 050 `ApkInstallApprovalGate.consumeForInstall` тепер повертає `INSTALL_PERMISSION_REQUIRED` без споживання одноразового підтвердження. Після повернення з Android Settings повторна спроба може використати його один раз; успішний install attempt усе ще споживає consent.
- Regression-тест пройшов разом із Android unit suite: **62/62**. `:app:assembleDebug :app:assembleDebugAndroidTest` — `BUILD SUCCESSFUL`.
- У 050 після початкового consent regression під’єднано APK inbox/review, per-APK confirmation, explicit `ApkInstallStatusReceiver`, unknown-app settings result та receipt-bound `recordPostVerifiedRestore`. System settings URI спрямований на applicationId TransferKit, а не на встановлюваний пакет. `ApkRestoreViewModel` зберігає точний item і consent під час Activity recreation/повороту, поки відкриті Android Settings; process death може вимагати повторного перегляду/підтвердження, але staging лишається недоторканим.
- Android unit suite — **62/62 passed**. Фізичні instrumentation: A23/API 34 Compose 2/2; OUKITEL/API 35 Compose 2/2 після зняття no-PIN lock screen; A27/API 37 core 26/26 і Compose 2/2 після Espresso update. Всі тимчасові пакети прибрані; Redmi installation/status callback не запускали через відсутність receiver-verified APK item.
- Поточний `D:\Transfer\TransferKit-Android-debug.apk`: 9,270,567 bytes; SHA-256 `5EA6D59D013371B85B85CA18258297D7C42A42AC6577244CB7356411B9B343F7`. Це debug APK; на Redmi вручну встановлено `0.1.0-alpha.2` (`versionCode=3`), запуск `MainActivity` перевірено.
- Не виконано фізичний APK install/status callback: на Redmi 12 немає receiver-verified APK item, а automated APK install раніше зупинив MI Account gate. Activity recreation/rotation має retained ViewModel state; process-death handling і APK-only source/Station end-to-end лишаються відкритими.

### Redmi progress-screen follow-up — 2026-11-13

- Після повторного USB-підключення SDK ADB бачить Redmi 12 як `39f502057d76`, model `23053RN02Y`, product `heat_eea`, Android 15/API 35. `com.transferkit.client` versionCode=2 був встановлений; app лишався відкритим на головному екрані. Wireless Debugging під’єднано окремим serial-ом; `get-state=device`, SDK=35, USB і Wi‑Fi serial-и доступні одночасно. Мінливу адресу/порт до документації не додавав.
- Користувач вручну відкрив «Стан передачі». `topResumedActivity` підтвердив `com.transferkit.client/.MainActivity`; screenshot `test-results/redmi12-progress-idle-20261113.png` показує idle status, відсутній progress до durable checkpoint, вимкнені «Пауза»/«Скасувати». Передача чи користувацькі файли не запускались і не читалися.
- У screenshot виявлено, що довгий підпис keep-awake перемикача під ним обрізався. `TransferProgressScreen` виправлено через `Modifier.weight(1f)`/spacing для обох перемикачів; Compose instrumentation тепер також перевіряє, що підпис відображений. `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` — `BUILD SUCCESSFUL`; unit — **62/62**. Новий APK не встановлювався, тому layout correction ще треба підтвердити на Redmi.
- SDK `adb shell input tap` і далі відхиляється MIUI з `INJECT_EVENTS`; фізичні кнопки натискав користувач. Layout fix і новий APK ще не перевірені на Redmi. Збірка з SHA-256 `E8ECB96EEE764A6FB959EA81BA3160A82826A2ADD7E27F5FF73A43ECB54B4720` була наступно замінена новішою збіркою з контрольної точки APK approval вище.
- Для API coverage Samsung SM-A235F/Android 14/API 34, SM-A276B/Android 17/API 37 і OUKITEL WP23 Plus/API 35 тимчасово отримали debug/test APK для ізольованих instrumentation; усі тестові пакети видалені. На час оновлення 2026-11-13 користувач повідомив, що A27/API 37 та A23/API 34 більше недоступні; Redmi 12 підключений через USB ADB для alpha.2 install smoke. Попередній прогін мав OUKITEL Wi-Fi ADB online, але його поточний стан тут не переперевіряли. На цих пристроях не запускали transfer із користувацькими файлами.

## Відомі обмеження — release blockers

- 2026-11-13 через headless Qt harness фактичного `StationWindow`/worker та фізичні телефони пройдено device selection/plan/receive → receipt → явне Android restore approval → MediaStore post-verification на synthetic item: Samsung S20 Ultra/API 33 → Samsung M31/API 31, 65 536 bytes; receipt verified=1/1, Android restore 1/1 без помилок, незалежне читання точного destination item підтвердило SHA-256 `7daca209…b27e4c2`. Station досі не отримує Android restore outcome і не створює фінальний report; саме report exchange лишається release blocker. Це тест synthetic data, не особистих файлів. Видиму інтерактивну desktop UI, зокрема її візуальне приймання людиною, окремо не перевірено.
- Wi-Fi TLS sockets, certificate pinning і QR pairing не завершені. USB/ADB прототип працює через Windows ADB host; прямий phone-to-phone USB не підтримується як файловий transport.
- Recovery state machine та cross-transport resume не реалізовані наскрізно. Контракти/вимоги є, acceptance обриву кабелю чи Wi-Fi не проводився.
- Space Preflight, streaming restore і drop-largest ще не реалізовані наскрізно.
- Restore поза фото/відео/аудіо/Downloads ще не завершений. API 26–28 public-directory adapter реалізований, але очікує фізичного acceptance; SAF destination та API-category adapters потребують роботи. M1 acceptance — 100 фото + 50 контактів із 100% SHA-256 збігом — відкритий.
- API 29 та API 34 physical instrumentation, Samsung→Redmi і Pixel→Motorola acceptance ще потрібні. На 2026-11-13 S20 Ultra та M31 доступні через USB/Wi-Fi ADB; інші телефони в цьому тесті не використовувалися.
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
