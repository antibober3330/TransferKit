# TransferKit — release readiness

**Знімок стану:** 2026-11-13

**Висновок:** перевірний release-набір підготовлено, але продукт ще **не готовий до публічного релізу**. Наявний Android APK — debug build; основні migration acceptance і частина transport/restore функцій залишаються відкритими.

Цей документ є контрольним аркушем для відтворення збірки та перевірки доказів. Він не перетворює прототип на реліз і не дозволяє трактувати отриманий `receipt.json` як доказ успішного відновлення даних.

## Ідентифікація та версії

| Компонент | Зафіксована версія/межа | Джерело істини |
|---|---|---|
| TransferKit Windows | `0.1.0-alpha.1` | `VERSION`, mirrored in `transferkit/__init__.py` |
| TransferKit Android | `versionName=0.1.0-alpha.1`, `versionCode=2`, package `com.transferkit.client` | `VERSION`, mirrored in `android-client/app/build.gradle.kts` |
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

- Python: останній повний запуск — **146/146 passed** (`python -m unittest discover -s tests -v`), включно з Recovery frame codecs, version consistency і shared manifest contract vectors. Android target inventory та transfer filtering для dedup ще не інтегровані.
- Android unit: останній локальний запуск — **50/50 passed**. Новий shared manifest parser instrumentation — **25/25 vectors на кожному** з Samsung S20 Ultra/API 33 і Samsung M31/API 31; окремо original-photo URI gate пройшов 2/2 tests у granted і denied станах на обох телефонах. Legacy instrumentation для ширших receiver/restore suites — 21/21 на кожному з чотирьох телефонів у попередній сесії.
- Android Gradle: `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` — `BUILD SUCCESSFUL`. Manifest schema/Pydantic/Kotlin rules вирівняні; shared vector corpus перевіряє canonical UUID, integer bounds/forms, timestamp precision, Unicode lengths, strict properties, SHA, duplicate/wire-ID collisions і 64 KiB metadata boundary.
- Фізична перевірка: Samsung A04e/API 33 → Motorola moto g06/API 35, synthetic payload 65 536 байтів/16 chunks, schema-valid receive receipt і незалежний SHA-256 збіг. Тимчасові APK після instrumentation прибрані з A04e/Moto; pre-existing S20/M31 installations intentionally preserved.
- ADB: усі чотири explicit Wi-Fi ADB serials повернули `get-state=device` після тестів. `pm path` порожній на A04e/Moto; S20/M31 зберегли свої існуючі app/test packages.
- Source revision: каталог `D:\Transfer` не є Git repository; немає commit/tag і чистого-tree доказу, до якого прив’язати candidate.

## Відомі обмеження — release blockers

- 2026-11-13 через headless Qt harness фактичного `StationWindow`/worker та фізичні телефони пройдено device selection/plan/receive → receipt → явне Android restore approval → MediaStore post-verification на synthetic item: Samsung S20 Ultra/API 33 → Samsung M31/API 31, 65 536 bytes; receipt verified=1/1, Android restore 1/1 без помилок, незалежне читання точного destination item підтвердило SHA-256 `7daca209…b27e4c2`. Station досі не отримує Android restore outcome і не створює фінальний report; саме report exchange лишається release blocker. Це тест synthetic data, не особистих файлів. Видиму інтерактивну desktop UI, зокрема її візуальне приймання людиною, окремо не перевірено.
- Wi-Fi TLS sockets, certificate pinning і QR pairing не завершені. USB/ADB прототип працює через Windows ADB host; прямий phone-to-phone USB не підтримується як файловий transport.
- Recovery state machine та cross-transport resume не реалізовані наскрізно. Контракти/вимоги є, acceptance обриву кабелю чи Wi-Fi не проводився.
- Space Preflight, streaming restore і drop-largest ще не реалізовані наскрізно.
- Restore поза MediaStore фото/відео/аудіо/Downloads на API 29+ не завершений; API 26–28 і API-category destination adapters потребують роботи. M1 acceptance — 100 фото + 50 контактів із 100% SHA-256 збігом — відкритий.
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
