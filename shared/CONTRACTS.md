# Спільні ідентифікатори й статуси

Цей файл є довідником машинних значень для Windows Station та Android Client. Людинозрозумілі назви локалізуються в UI; JSON-контракти використовують наведені нижче ASCII ID.

## Категорії

| ID | Tier | Дані | Примітка |
|---|---|---|---|
| `photos` | A | Фото | MediaStore Images |
| `videos` | A | Відео | MediaStore Video |
| `audio` | A | Аудіо | MediaStore Audio |
| `documents` | A | Документи, доступні через SAF/MediaStore | Доступ залежить від дозволів і URI |
| `downloads` | A | Завантаження | Не гарантує доступ до довільних каталогів |
| `contacts` | B | Контакти | ContactsContract або vCard |
| `calendar` | B | Події календаря | Один `CalendarContract.Events` item з attendees/reminders; recurrence master зберігається; потрібні runtime `READ_CALENDAR` і доступний provider |
| `sms` | C | Текстові SMS | Hard-restricted `READ_SMS`; лише за фактичного grant. MMS/вкладення не включені, тому capability лишається `PARTIAL` до окремої реалізації. |
| `call_log` | C | Журнал дзвінків | Hard-restricted `READ_CALL_LOG`; лише за фактичного grant. Номери й деталі не показуються в UI/logs. |
| `apks` | D | Видимі одиночні user-installed APK | `QUERY_ALL_PACKAGES`; system/split/unreadable packages звітуються окремо й пропускаються |
| `settings` | E | Allowlisted системні налаштування | Public `Settings.System` read-only; Shizuku/Device Owner — окремі method-specific capability, не загальний доступ |
| `app_data` | F | Дані застосунків | Не обходити sandbox; доступ визначається легальним API |
| `wifi_passwords` | G | Паролі Wi-Fi | Недоступно без дозволеного системного рівня |
| `launcher_layout` | G | Розкладка launcher | OEM API відсутній/неуніверсальний |
| `accounts` | G | Акаунти | Повторний вхід або системний D2D flow |
| `drm` | G | DRM-вміст | Не переноситься |
| `banking_apps` | G | Стан/секрети банківських застосунків | Потрібна повторна активація; секрети не копіювати |
| `two_factor_tokens` | G | 2FA-токени | Лише офіційна функція експорту конкретного застосунку |

Наявність категорії у списку не означає, що вона доступна на конкретному пристрої. Capability завжди визначається runtime probe.

Поле capability `permission_required` перелічує Android permissions, потрібні для читання/сканування категорії на цьому пристрої. План перенесення показує ці вимоги окремо для джерела й цілі та додає дозволи запису, потрібні destination adapter-у; список дозволів читання не можна повторно трактувати як вимоги цілі. Спеціальні системні дії (SAF picker, `WRITE_SETTINGS` app-op, підтвердження PackageInstaller) показуються окремо від звичайних runtime permissions.

### Контакти

У Android одному агрегованому `ContactsContract.Contacts` запису відповідає один item з platform-generated vCard bytes (`metadata.format = "vCard"`). Item size/hash описують точні передані байти; повторна серіалізація на іншому пристрої не зобов’язана мати ті самі байти, тому post-restore перевірка зіставляє нормалізовані поля. Джерельний sync-account type/name і сирий lookup key не входять у manifest. Імпорт відбувається в destination account, який вибрав користувач, або у локальне сховище після попередження про відсутність хмарної синхронізації. Сам vCard не зберігає зв’язок агрегованого запису з усіма source `RawContacts` акаунтами.

Для `calendar` один `CalendarContract.Events` row є одним item з `metadata.format = "transferkit-calendar-event-json"`, `metadata.version = 1` і `metadata.contains_unexported_extensions` boolean; payload — canonical UTF-8 JSON envelope з `event`, `attendees` та `reminders`. Recurrence зберігається як master event rules, без копіювання похідних `Instances`; exception master reference кодується його hashed source item ID. Source `_ID`, `CALENDAR_ID`, account name/type не входять у payload. `ExtendedProperties` і приватні sync-adapter fields не portable; якщо provider позначає їх наявність, capability/report зберігає обмеження. Restore потребує user-selected destination calendar/account і нормалізованої post-verification.

Для `sms` поточний source provider експортує лише text rows із `Telephony.Sms`: один canonical UTF-8 JSON item `transferkit-sms` v1 з address, body, source timestamps, read/seen flags, type, subject і status. Local row ID застосовується тільки як hashed item ID; thread ID, subscription ID та account identity не передаються. MMS і вкладення не включені. Для `call_log` один `CallLog.Calls` row є canonical UTF-8 JSON item `transferkit-call-log` v1 із номером, датою, тривалістю, типом, read/new та presentation/country fields. Дані не виводяться в UI або logs; payload призначений лише для явно вибраної передачі.

`READ_SMS` і `READ_CALL_LOG` є Android hard-restricted permissions: provider перевіряє фактичний grant перед кожним query і не намагається обходити відмову іншими API. У Google Play для backup/restore або device migration можна подати окрему permissions declaration на exception; це не гарантує схвалення й може вимагати installer allowlist. Поточний manifest не оголошує write permissions; Tier C restore лишається недоступним до окремого permission/policy/target-handler рішення. Null cursor або permission-denied не можна інтерпретувати як `NO_DATA`.

Для `apks` один доступний non-system package з єдиним `ApplicationInfo.sourceDir` є одним item. `metadata` включає `format = "apk"`, package name, version code/name і `split = false`; source filesystem path не експортується. Item ID — SHA-256 package name з `apk_` prefix. Перед копіюванням provider потоково хешує файл, а під час копіювання повторно перевіряє digest проти preflight hash. System/updated-system packages, packages з `splitSourceDirs` і unreadable sources не входять до transferable count; UI показує їхні агреговані лічильники. Інсталяція кожного APK потребує окремої user confirmation.

Для `settings` v1 дозволений лише конкретний allowlist public `Settings.System` keys. Snapshot має canonical JSON payload, item SHA-256 та metadata з кількістю полів. Значення, що не читаються або відсутні, не підмінюються default values; capability evidence показує read count із total allowlist keys. Probe окремо повідомляє `Settings.System.canWrite()`, доступність/permission Shizuku та факт Device Owner. Ці сигнали не доводять, що інше конкретне значення можна записати. `Settings.Secure/Global` довільно не читаються/не змінюються; `WRITE_SECURE_SETTINGS` не оголошується у manifest. Device Owner використовує лише документовані per-policy APIs, Shizuku — лише явно дозволені user-approved shell operations. Поточний SettingsProvider є read-only; безпечні зміни й target readback — наступні роботи.

## Capability та план

- `CapabilityStatus`: `AVAILABLE`, `PARTIAL`, `UNAVAILABLE`.
- `PlanStatus`: `TRANSFERABLE`, `PARTIAL`, `UNAVAILABLE`.
- `ReportStatus`: `OK`, `PARTIAL`, `UNAVAILABLE`, `FAILED`, `FAILED_INTEGRITY`.
- `ItemStatus`: `PENDING`, `TRANSFERRED`, `VERIFIED`, `RESTORED`, `SKIPPED`, `SKIPPED_NO_SPACE`, `FAILED`, `FAILED_INTEGRITY`.
- У кожному capability записі присутні `category_id`, `status`, `method`, `reason`, `evidence`; `evidence` містить лише безпечні факти/лічильники, не секрети чи вміст.
- `manifest.json` item metadata містить лише JSON-сумісні restore-атрибути та має ліміт 64 KiB канонічного UTF-8 JSON на item.
- Android `ManifestBuilder` повертає окремі `ManifestBuildFailure` для джерельного read/permission error; такі item не входять до manifest і мають бути відображені у фінальному report як failed до transfer. Невалідні category IDs, duplicate item IDs, metadata >64 KiB і schema-limit violations abort-ять побудову manifest.

## Коди причин

Код причини є стабільним machine-readable рядком; детальний текст для UI окремий.

| Код | Значення |
|---|---|
| `OK` | Операція виконана та перевірена |
| `PERMISSION_DENIED` | Не видано потрібний runtime або системний дозвіл |
| `UNSUPPORTED` | OS/API/формат не підтримується |
| `UNAVAILABLE` | Механізм відсутній або дані недоступні |
| `SHIZUKU_REQUIRED` | Для методу потрібен Shizuku |
| `DEVICE_OWNER_REQUIRED` | Для методу потрібен Device Owner |
| `USER_ACTION_REQUIRED` | Очікується явна дія користувача |
| `INSTALL_CONFIRMATION_REQUIRED` | Android вимагає підтвердження встановлення APK |
| `OEM_RESTRICTION` | Застосовано документоване OEM-обмеження |
| `SYSTEM_APP` | Системний пакет не можна експортувати цим методом |
| `SPLIT_APK` | Застосунок має split APK і набір не є повним |
| `NO_DATA` | На джерелі немає елементів у категорії |
| `TARGET_NO_SPACE` | На цільовому пристрої недостатньо місця |
| `TARGET_SPACE_INSUFFICIENT` | Вільного місця не вистачає для всіх вибраних items, але частина може вміститися |
| `TARGET_ITEM_TOO_LARGE` | Окремий item не вміщується у безпечний staging/restore budget |
| `ALREADY_PRESENT` | На цілі знайдений точний дублікат: category, byte size і SHA-256 збігаються; item автоматично пропущений |
| `NEAR_DUPLICATE_SKIPPED` | Локальний image verifier підтвердив безпечне автоматичне пропускання візуального дубліката |
| `TRANSPORT_INTERRUPTED` | Зв’язок перервано; можна відновити з чекпойнта |
| `TRANSFER_INCOMPLETE` | Ціль не може сформувати фінальну квитанцію, бо не всі manifest items класифіковані |
| `RESUME_AVAILABLE` | Для цієї передачі є придатний checkpoint |
| `RESUME_NOT_POSSIBLE` | Resume заблокований; показати конкретну причину, якщо вона відома |
| `CHECKPOINT_EXPIRED` | Від останнього durable checkpoint минуло понад 30 днів |
| `SOURCE_DEVICE_MISMATCH` | Підключене джерело має іншу TransferKit installation identity |
| `TARGET_DEVICE_MISMATCH` | Підключена ціль має іншу TransferKit installation identity |
| `SOURCE_MANIFEST_CHANGED` | Джерельний manifest digest змінився після початку передачі |
| `INCOMPATIBLE_PROTOCOL_VERSION` | Збережений checkpoint створений несумісною wire protocol version |
| `STAGING_CORRUPTED` | Staging bytes не відповідають durable chunk CRC32 або item SHA-256 |
| `USER_CHOSE_RESTART` | Користувач підтвердив restart з новим `transfer_id` (подія, не причина успіху) |
| `USER_CHOSE_ABANDON` | Користувач підтвердив остаточне скасування (подія, не причина успіху) |
| `STAGING_CLEARED` | Користувач підтвердив видалення лише TransferKit staging data |
| `USER_CHOSE_STREAMING` | Користувач обрав per-item streaming стратегію |
| `USER_CHOSE_DROP_LARGEST` | Користувач обрав перенесення лише вмістимого набору items |
| `USER_CHOSE_CLEAR_SPACE` | Користувач обрав звільнення місця через системні налаштування |
| `CRC32_MISMATCH` | Чанк пошкодився під час транспорту |
| `SHA256_MISMATCH` | Перевірка повного елемента не збіглася |
| `RESTORE_FAILED` | API цілі відхилив відновлення |
| `IO_ERROR` | Помилка читання/запису |
| `INVALID_MANIFEST` | Маніфест невалідний або не відповідає версії |
| `CANCELLED` | Користувач скасував операцію |
| `PROBE_TIMEOUT` | Read-only probe перевищив встановлений timeout |
| `DEVICE_NOT_FOUND` | Вибраний пристрій не підключений |
| `API_UNAVAILABLE` | Потрібний Android API не надає доступу |
| `USER_DECLINED` | Користувач відмовив у підтвердженні |
| `SOURCE_READ_FAILED` | Джерело не змогло прочитати елемент |
| `TARGET_WRITE_FAILED` | Ціль не змогла записати елемент |
| `ADB_UNAVAILABLE` | ADB executable/server недоступний |
| `ADB_COMMAND_FAILED` | ADB-команда завершилася помилкою |
| `DEVICE_UNAUTHORIZED` | Пристрій очікує ADB RSA-підтвердження |
| `DEVICE_OFFLINE` | ADB бачить пристрій у стані offline |

## Recovery state

`RecoveryStatus` використовує значення `RUNNING`, `RECONNECTING`, `PAUSED_AWAITING_USER`, `PAUSED_BLOCKED`, `RESUMING`, `RESTARTING`, `COMPLETED`, `SUPERSEDED`, `ABANDONED`. `RESUMING` і `RESTARTING` — короткі транзитні стани; `SUPERSEDED` та `ABANDONED` — різні термінальні стани старої передачі. `RESUME_AVAILABLE`/`RESUME_NOT_POSSIBLE` описують можливість дії, а `USER_CHOSE_RESTART`/`USER_CHOSE_ABANDON` — події користувача, не підстави для `OK`.

Resume дозволяється лише за збігу `transfer_id`, SHA-256 канонічного manifest, installation-scoped source/target identity fingerprints і protocol version. Target checkpoint є авторитетним; source база — дзеркало. TTL становить 30 днів від останнього durable checkpoint. Вичерпання TTL блокує resume, але не запускає автоматичне видалення staging.

Форми wire/application payload описані у `checkpoint-state.schema.json` і `resume-plan.schema.json`, а Python semantic validators додатково вимагають, щоб лічильники категорії дорівнювали `verified + failed + pending + partial`, partial IDs були унікальні, а chunk/offset межі були допустимими. Item без підтверджених chunks є pending. Кожен `ResumePlan` вимагає явного user confirmation; target offset додатково зіставляється з незмінним manifest і durable chunk ledger під час побудови плану.

Обгортка Recovery control frame має 9-byte заголовок `magic[4] + frame_version:u8 + payload_size:u32` (big-endian) і canonical UTF-8 JSON object до 256 KiB. `TKRH/TKRQ/TKRP/TKRA/TKRB/TKCP/TKPL` позначають Hello/Challenge/Proof/Accept/Blocked/CheckpointState/ResumePlan; `TKRC` лишається receipt. Python і Kotlin зараз реалізують лише bounded framing/serialization, а не handshake security чи listener dispatch. До transport integration мають з'явитися окремі payload schemas і signature/state-machine checks.

`TransferReport.decisions` є необов’язковим backward-compatible списком `{reason, item_ids, category_id?}` для виборів користувача, space strategy й автоматичної deduplication. `USER_CHOSE_*`, `STAGING_CLEARED`, `ALREADY_PRESENT` та `NEAR_DUPLICATE_SKIPPED` записуються тут, не як item failures. Items, які не надсилалися через Drop-Largest або вже наявний дублікат, не приписуються receipt як отримані. Кожна category може показати backward-compatible `deduplicated_count` і `deduplicated_bytes`; вони вимірюють items, пропущені автоматично як duplicates, а не transferred/restored items. `deduplicated_count` не може перевищити manifest items, що не були передані.

Точний дублікат автоматично пропускається тільки за однакового category, size і SHA-256 у повністю просканованій категорії цілі. Неповний/обмежений permission-ами inventory не доводить відсутність item-а і не дає права заявляти повне dedup coverage. Перцептивний hash є лише кандидатоутворювачем; `NEAR_DUPLICATE_SKIPPED` дозволений лише після окремого високоточного verifier і перевіреної політики збереження якості.

## Правила агрегування

1. Будь-який `SHA256_MISMATCH` для елемента встановлює статус елемента й категорії `FAILED_INTEGRITY`; категорія не може бути `OK` або лише `PARTIAL`.
2. Якщо обидва пристрої підтримують потрібний метод і дозволи є, план — `TRANSFERABLE`.
3. Якщо доступ є лише частково або потрібен неактивний Shizuku/Device Owner, план — `PARTIAL` з конкретною дією/причиною.
4. Якщо одна зі сторін `UNAVAILABLE`, план — `UNAVAILABLE`. Для основної причини застосовується порядок пріоритету: `PERMISSION_DENIED` → `UNSUPPORTED` → `UNAVAILABLE`; інші причини не відкидаються, а лишаються у per-side details.
5. `OK` у звіті означає: передано, перевірено SHA-256/поля API і успішно відновлено. Сам факт отримання чанків не є `OK`.
6. Відсутність source items (`NO_DATA`) не маскує відсутню можливість; UI показує окремо нуль елементів і capability.
7. Фінальний receipt класифікує кожен повністю прийнятий item: `items_verified + items_failed == items_received`; `items_failed` дорівнює кількості унікальних failure records із `item_id`. Проміжний стан передається ACK/checkpoint, а не фінальним `receipt.json`. Звіт зберігає для кожної receipt-помилки той самий item ID і reason з відповідним report stage.
