# TransferKit

**Поточний development snapshot:** `0.1.0-alpha.5` ([`VERSION`](VERSION)); правила випуску й changelog: [`VERSIONING.md`](VERSIONING.md), [`CHANGELOG.md`](CHANGELOG.md).

TransferKit — проєкт локального перенесення даних між Android-пристроями через Windows Station або майбутній автономний Android flow. Станом на 12 листопада 2026 року це **робочий прототип і набір перевірених компонентів**, а не готовий наскрізний засіб міграції.

## Що вже є

- Windows Station на Python/PySide6: виявлення ADB-пристроїв, read-only capability probe, план категорій, manifest/SHA-256, chunk framing, історія, звіти та зашифрований Backup Relay API.
- Android Client: device info, source providers і ManifestBuilder, authenticated source/receiver LocalSocket servers, durable receiver checkpoints, consent gates і media restore для Android 8+ (legacy public folders на API 26–28, MediaStore на API 29+).
- Спільні JSON Schemas і wire contracts у [`shared/`](shared/), детальні вимоги — у [`PROJECT_SPEC.md`](PROJECT_SPEC.md), послідовність робіт — у [`WORK_CHECKLIST.md`](WORK_CHECKLIST.md).

**Це ще не готовий міграційний продукт.** Автентифікований USB/ADB source→target receive flow уже є: Windows Station запускає Python session backend, який передає manifest/chunks між Android listener-ами, зберігає підтверджені ACK/checkpoints і receipt. На Samsung A04e → Motorola moto g06 фізично пройшов тест лише з одним згенерованим payload на 65 536 байтів і звіркою SHA-256. Це підтверджує прийом та integrity, але не повний desktop click-through, restore користувацьких даних, destination post-verification чи фінальний report. Android media restore для фото/відео/аудіо/Downloads має legacy public-directory шлях на API 26–28 і MediaStore на API 29+. Contacts та Calendar мають окремі restore adapters із permission/destination flow і readback, але їхня OEM acceptance обмежена. SAF destination для довільних documents, SMS/Call Log restore, Settings write-back і повний APK install UI ще не завершені. Wi-Fi TLS sockets і QR pairing ще не завершені; пряме телефон↔телефон USB не є підтриманим transport-профілем.

## Швидкий запуск Windows Station

Потрібні Windows 10/11 і Python 3.11 або новіший. ADB потрібен для USB/ADB-виявлення пристроїв; Android звичайного користувача не потребує root. Для поточного прототипу на телефоні треба ввімкнути USB debugging і підтвердити RSA-запит цього комп’ютера.

У PowerShell з кореня проєкту:

```powershell
.\install.bat
.\run.bat
```

`install.bat` створює `.venv` і встановлює залежності. `run.bat` запускає графічну Station. Не підтверджуй ADB RSA-запит для незнайомого комп’ютера.

## Android Client: збірка й встановлення

Потрібні JDK 17, Android SDK Platform/Build Tools 34 і Gradle Wrapper, що входить до `android-client`.

```powershell
cd android-client
.\gradlew.bat :app:assembleDebug
.\install-apk.bat SERIAL_ТЕЛЕФОНА
```

При двох підключених телефонах передай точний serial. Перевірка середовища й відомих build/ADB помилок описана в [`android-client/BUILDING_AND_TESTING.md`](android-client/BUILDING_AND_TESTING.md). Не видаляй застосунок з телефона, якщо важливі його app-private дані.

Поточний записаний результат: Python suite — **144/144**; останні Android unit tests — **43/43**; останній записаний instrumentation — **21/21 на кожному з чотирьох телефонів**: Samsung S20 Ultra/API 33, Samsung M31/API 31, Samsung A04e/API 33 і Motorola moto g06/API 35. Додано cross-language Recovery frame codec; він ще не підключений до listeners і не є повним Recovery handshake. Перевірено Android Keystore identity, SQLite v1/v2→v3 migration, durable partial checkpoint, точний chunk ledger і відхилення ACK при тестовому commit failure. Python dedup planner і звітні лічильники автоматичного skip покриті unit tests, але Android target inventory та transport integration ще не зроблені. Тимчасові APK прибрані з A04e/Moto; наявні S20/M31 інсталяції збережені. Фізичний A04e→Moto receive перевірив synthetic payload 65 536 байтів, schema-valid `TKRC` та незалежний SHA-256 збіг. Ці перевірки не підтверджують user-data restore, post-verification/report, Recovery handshake/resume чи активний кабельний обрив. Деталі й обмеження наведені в [`docs/project/RELEASE_READINESS.md`](docs/project/RELEASE_READINESS.md).

## Перевірки Python

З кореня проєкту:

```powershell
python -m unittest discover -s tests -v
```

У цьому середовищі Windows Store alias `python` може бути недоступний. Тоді використовуй Python із `.venv`, створеного `install.bat`, або встановлений прямий шлях до Python 3.11+.

## Категорії та реальні межі

- **Tier A:** медіадані — залежно від API та дозволу; документи/Downloads — лише вибрані користувачем через системний picker.
- **Tier B:** джерельні Contacts і Calendar мають providers; destination restore ще не завершений.
- **Tier C:** SMS і журнал дзвінків потребують фактичних hard-restricted Android grants; доступність залежить від способу встановлення та політик Android/магазину. Їх restore не реалізований.
- **Tier D/E:** APK scan і read-only allowlist Settings мають scaffolds; встановлення APK і запис налаштувань не є готовим наскрізним flow.
- **Tier F/G:** не обходити Android sandbox, не копіювати секрети банківських застосунків, акаунтів, DRM чи 2FA. Категорії без доступного публічного API чесно позначаються недоступними.

ADB debugging потрібен для теперішньої Windows Station/USB розробки. Root не потрібен для звичайного продуктового сценарію й не є способом обходити Android permission model.

## Приватність і цілісність

TransferKit задуманий для локального перенесення без хмарної телеметрії. Файлові item-и перевіряються SHA-256; chunk-и мають CRC32 для виявлення транспортної корупції. `OK` має означати фактичний destination restore і post-verification. Не вважай лише отриману квитанцію доказом успішного відновлення. Backup Relay підтримує зашифрований AES-256-GCM контейнер у Python API; інтегрованого password/file-picker UI поки немає.

## Документи проєкту

- [`PROJECT_SPEC.md`](PROJECT_SPEC.md) — повне продуктове ТЗ.
- [`WORK_CHECKLIST.md`](WORK_CHECKLIST.md) — виконуваний чекліст і перевірений прогрес.
- [`AGENTS.md`](AGENTS.md) — правила реалізації для розробників і агентів.
- [`docs/project/ARCHITECTURE_AND_LIMITS.md`](docs/project/ARCHITECTURE_AND_LIMITS.md) — компоненти, потоки даних і обмеження.
- [`PROTOCOL.md`](PROTOCOL.md) — стислий огляд протоколу та реалізованої межі transport.
- [`TRANSFER_REPORT_FORMAT.md`](TRANSFER_REPORT_FORMAT.md) — receipt, report і CSV формати.
- [`USER_GUIDE.md`](USER_GUIDE.md) — посібник українською й англійською.
- [`TROUBLESHOOTING.md`](TROUBLESHOOTING.md) — типові проблеми Windows, Android, ADB і дозволів.
- [`shared/protocol.md`](shared/protocol.md) та [`shared/CONTRACTS.md`](shared/CONTRACTS.md) — wire format і машинні контракти.
- [`docs/project/DEVELOPMENT_LOG.md`](docs/project/DEVELOPMENT_LOG.md) — журнал змін і результатів перевірок.
- [`docs/project/RELEASE_READINESS.md`](docs/project/RELEASE_READINESS.md) — версії інструментів, збірочні скрипти, відомі обмеження та критерії релізної готовності.
- [`що варто добавити з поясненням чому.txt`](що%20варто%20добавити%20з%20поясненням%20чому.txt) — відкриті пропозиції, не прийняті вимоги.
