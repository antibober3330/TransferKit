# PROJECT_SPEC — TransferKit: Універсальна міграція Android → Android

Версія: 1.0
Дата: 2026-11-11
Статус: канонічне ТЗ. Джерело істини для Codex і для розробників.

---

## 1. Мета і бачення

Створити продукт для перенесення даних між Android-пристроями різних виробників, який:

1. Працює без ПК (APK-only) і з ПК (Windows-станція).
2. Переносить максимально можливий обсяг даних офіційними механізмами Android.
3. **Чесно** показує, що переноситься, що частково, а що неможливо — до початку перенесення.
4. Дає per-category звіт із SHA-256 перевіркою й конкретною причиною невдач.
5. Не імітує успіх. SHA-256 mismatch = помилка, а не «частково успішно».

**Головний принцип:** працювати *з* моделлю безпеки Android, не *проти* неї.

---

## 2. Цільові користувачі й режими

### 2.1 Режим «Швидкий»

Для звичайного користувача. Два пристрої, вибір категорій, перенесення. Один екран налаштувань, один екран прогресу, один екран звіту.

### 2.2 Режим «Повний»

Максимально можливе перенесення: усі Tier A–F, Shiuku, Device Owner (якщо доступно). Показ capability-матриці з причиною недоступності кожної категорії.

### 2.3 Режим «Професійний»

Для сервісних центрів: детальне сканування, вибіркове перенесення per-item, історія операцій, експорт звіту в JSON/CSV, порівняння двох прогонів.

### 2.4 Режим відновлення (Recovery)

Recovery — це стан життєвого циклу передачі, а не четвертий режим поряд із «Швидким», «Повним» і «Професійним». Будь-яка передача може перейти у Recovery після короткого обриву зв’язку або завершитися в стані очікування користувача. На головному екрані також є окремий вхід «Відновити перервану передачу» для раніше збережених передач.

Чекпойнт прив’язаний до `transfer_id`, канонічного `manifest_sha256`, джерела, цілі та версії протоколу, а не до USB/Wi-Fi сесії. Ціль є авторитетним джерелом стану отриманих чанків; джерело зберігає дзеркало для історії та UI. Це дає змогу відновити передачу через інший транспорт, зокрема перейти з Wi-Fi на USB, якщо обидва пристрої та незмінений маніфест збігаються.

Recovery має стани `RUNNING`, `RECONNECTING`, `PAUSED_AWAITING_USER`, `PAUSED_BLOCKED`, `RESUMING`, `RESTARTING`, `COMPLETED`, `SUPERSEDED` і `ABANDONED`. Короткі обриви відновлюються автоматично; тайм-аут, невідповідність пристрою/маніфесту, вичерпаний TTL або несумісна версія показуються з конкретною причиною. Перехід у Recovery є штатним станом, а не провалом передачі.

«Продовжити» перевіряє контрольну суму завершених items і останні збережені CRC32 часткового item. «Перевірити й продовжити» повторно читає staging і звіряє всі збережені CRC32 чанків та SHA-256 завершених items. Виявлене пошкодження фіксується як `FAILED_INTEGRITY`, не може бути приховане успішною агрегацією; повторна передача пошкодженого item виконується як окрема recovery спроба й не стирає запис про mismatch.

«Почати заново» очищує тільки staging/checkpoint цієї передачі й створює новий `transfer_id`; уже відновлені дані користувача не видаляються. Попередня передача отримує термінальний стан `SUPERSEDED`. «Скасувати назавжди» очищує staging і деталізовані checkpoints та лишає мінімальний tombstone `ABANDONED` на обох сторонах. Обидві операції вимагають підтвердження й мають різні пояснення.

Важливе обмеження: restart із новим `transfer_id` не переносить checkpoints старої передачі. Уже відновлені дані залишаються на цілі, але поточний MediaStore marker включає старий `transfer_id`; нова передача не впізнає його автоматично і може створити дублікати. Python exact-match planner і звітні лічильники вже є, але target inventory, wire extension та Android integration ще не реалізовані; до їх acceptance UI restart має попереджати про можливе дублювання та повторне використання місця. Це не стосується resume того самого незміненого `transfer_id`/manifest.

Після 30 днів без нового durable checkpoint передача блокується як `CHECKPOINT_EXPIRED`; файли не видаляються автоматично. Зміна app version за незмінної protocol version допускає resume з попередженням. Зміна `protocol_version` вимагає почати заново.

Відновлення не повертає дані з пристрою, який не завантажується; не переносить прогрес між різними `transfer_id`; не гарантується після видалення застосунку з будь-якого пристрою. Частковий результат лишається `PARTIAL`, а mismatch — `FAILED_INTEGRITY`.

### 2.5 Моніторинг передачі й стану ADB

Windows Station показує фактично підтверджений прогрес передачі: байти, для яких ціль повернула валідний ACK, швидкість корисного навантаження та приблизний ETA. Швидкість обчислюється за ACK-підтвердженими байтами й активним часом передачі; пауза та період перепідключення не мають штучно занижувати її. ETA до накопичення достатньої кількості вимірів показує стан розрахунку, а за відсутності прогресу — зупинку/очікування замість необмеженого прогнозу. Це метрики прикладної передачі, не фізичної шини.

Стан кожного вибраного ADB-пристрою відображається окремо за точним serial: доступний, offline, unauthoried або відсутній. Якщо користувач заздалегідь спарив Wireless debugging і цей незалежний ADB transport лишився доступним після від'єднання USB, Station може використати його лише для спостереження за станом пристрою й діагностичними даними. Сам факт доступного Wi-Fi ADB не означає, що активна передача автоматично перейшла на Wi-Fi. Перемикання data transport дозволяється лише окремо підтриманим transport/recovery handshake із перевіркою transfer ID, manifest, fingerprint і durable checkpoint.

Застосунок не називає виміряну прикладну швидкість USB 1.1/2.0/3.x, не визначає максимум телефона з SoC як доведений факт і не звинувачує кабель, хаб чи порт без прямого доказу. Android `UsbDeviceConnection.bulkTransfer()` призначений для Android-пристрою в USB host mode, який спілкується з підключеною периферією; він не є API для перехоплення ADB bytes на звичайному USB-підключенні телефона до Windows. Повторне фізичне узгодження лінії застосунок не ініціює. Для спеціалізованого USB analyer потрібне окреме апаратне/host-side дослідження поза MVP.

---

## 3. Скоуп

### 3.1 У скоупі

* **Джерело й ціль:** будь-який Android 8.0+ пристрій. Виробники: Samsung, Xiaomi/Redmi/Poco, Motorola, Infinix, Tecno, Oppo, Vivo, Realme, Honor, Google Pixel, OnePlus, Huawei, ZTE.
* **Транспорт:** Android↔Android — локальна Wi-Fi мережа або тимчасова точка доступу; USB/ADB — лише через Windows Station як ADB-хост; проміжна резервна копія на ПК. Пряме Android↔Android USB-tethering не є гарантованим transport-профілем і потребує окремого фізичного прототипу.
* **Категорії:** Tier A–G (див. §6). Реальний статус визначається runtime-probe.
* **Windows-станція:** Python + PySide6.
* **Android-клієнт:** Kotlin, окремий APK на кожному пристрої.
* **Shiuku:** опційний доступ до окремих Android API без постійного ПК на Android 11+; це не ADB-клієнт для підключення до другого телефона й не обов’язкова умова базового перенесення.
* **Device Owner:** як опційний режим через `dpm set-device-owner`.
* **Звітність:** per-category `TransferReport` з SHA-256.

### 3.2 Поза скоупом (явно)

* iOS, HarmonyOS, KaiOS, Windows Phone.
* Обхід Android sandbox, rootkit, exploit-и, Magisk-модулі.
* Перенесення DRM-вмісту (Netflix, Spotify офлайн), банківських застосунків, 2FA-токенів, паролів Wi-Fi без Shiuku, ключів шифрування акаунтів.
* Злам OEM-захистів або підпису.
* Хмарний сервіс (усе локально, без серверів).
* Рекламні трекери, телеметрія, будь-який «прихований збір даних».

### 3.3 Відкриті питання (блокують старт)

Ці п’ять рішень мають бути зафіксовані до початку роботи над `capability_probe`, `planner`, `transport`:

1. **Мінімальна версія Android** — 8.0 чи 10 чи 12? Впливає на Shiuku (11+), MediaStore scoped storage (10+), `READ_MEDIA_*` (13+).
2. **Root у скоупі?** Якщо так — окремий «Професійний» профіль із явним попередженням.
3. **Shiuku — обов’язковий чи опційний?** Якщо опційний — два UI-режими: «Базовий» і «Розширений».
4. **Дистрибуція** — Play Store чи тільки сайдлоад. Впливає на `READ_SMS`, `READ_CALL_LOG`, `QUERY_ALL_PACKAGES`.
5. **MVP vs повний продукт** — що входить у першу версію.

До відповідей на ці питання діє наступне припущення (default): **minSdk=26 (Android 8.0), root відсутній, Shiuku опційний, тільки сайдлоад, MVP = Tier A+B+D+E**.

---

## 4. Архітектура

### 4.1 Загальна схема

```

┌─────────────────────────────────────────────────────────────┐
│  Windows Station (Python + PySide6)                          │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌─────────────┐  │
│  │ UI       │  │ Planner  │  │ Verify   │  │ History/DB  │  │
│  └────┬─────┘  └────┬─────┘  └────┬─────┘  └──────┬──────┘  │
│       └─────────────┴─────────────┴────────────────┘         │
│                            │                                 │
│  ┌─────────────────────────▼──────────────────────────────┐ │
│  │ Transport Layer: USB/ADB via PC · Wi-Fi · Backup Relay  │ │
│  └─────────────────────────┬──────────────────────────────┘ │
└────────────────────────────┼────────────────────────────────┘
│
┌──────────────┼──────────────┐
│              │              │
┌────────▼────────┐    │    ┌─────────▼────────┐
│ Android Source  │◄───┴───►│ Android Target   │
│ (APK, Kotlin)   │         │ (APK, Kotlin)    │
│ ┌─────────────┐ │         │ ┌──────────────┐ │
│ │ Manifest    │ │         │ │ Receiver     │ │
│ │ Builder     │ │         │ │ Restorer     │ │
│ ├─────────────┤ │         │ ├──────────────┤ │
│ │ Category    │ │         │ │ Verify       │ │
│ │ Providers   │ │         │ │ (SHA-256)    │ │
│ ├─────────────┤ │         │ ├──────────────┤ │
│ │ Shiuku     │ │         │ │ Shiuku      │ │
│ │ Bridge      │ │         │ │ Bridge       │ │
│ └─────────────┘ │         │ └──────────────┘ │
└─────────────────┘         └──────────────────┘

```

### 4.2 Модулі

#### Windows Station

|Модуль|Призначення|
|-|-|
|`ui/`|PySide6 екрани: пристрої, план, прогрес, звіт, історія, налаштування.|
|`device_detect.py`|Виявлення через ADB, збір моделі/версії/виробника/пам’яті.|
|`capability_probe.py`|Read-only probe: які категорії доступні методом яким.|
|`planner.py`|Побудова `TransferPlan` з двох capability-манифестів.|
|`space_preflight.py`|Бюджет storage volume і стратегія batch/streaming/drop-largest.|
|`manifest.py`|Побудова/читання `manifest.json`, SHA-256.|
|`transport/`|`usb_adb.py`, `wifi.py`, `backup_relay.py`. Резюмовані чанки.|
|`verify.py`|Перевірка SHA-256 на цілі, зіставлення з маніфестом.|
|`report.py`|Генерація `TransferReport` (JSON + CSV + GUI).|
|`history.py`|SQLite історія операцій.|
|`vendor_adapters/`|Capability-only registry та curated quirk catalog Samsung, Xiaomi, Motorola, Google Pixel, Oppo, Realme, OnePlus, Honor, Huawei, ZTE, Infinix, Tecno, Vivo. Статичний OEM запис не доводить runtime-доступність; read-only probe лишається авторитетним.|

#### Android Client

|Модуль|Призначення|
|-|-|
|`android-client/app/src/main/kotlin/com/transferkit/client/TransferForegroundService.kt`|Foreground Service notification shell для user-started transfer; він підвищує пріоритет і показує стан, але не гарантує, що OS/OEM не зупинить процес.|
|`SpacePreflight.kt`|Оцінка allocatable bytes і SpaceReport до першого data chunk.|
|`ManifestBuilder.kt`|Збір маніфесту з provider-ів категорій.|
|`Receiver.kt`|Прийом чанків, перевірка SHA-256.|
|`android-client/app/src/main/kotlin/com/transferkit/client/restore/Restorer.kt`|Per-category restore contract, registry та MediaStore adapter; інші adapters мають звітувати недоступність, доки не реалізовані.|
|`android-client/app/src/main/kotlin/com/transferkit/client/restore/ApkInstallCoordinator.kt`|Підготовка одного user-confirmed APK у `PackageInstaller.Session`, stream hash до та після write, commit через caller-owned status `IntentSender`; результат не є `RESTORED` до OS success і package/version readback.|
|`category/`|Провайдери: `MediaProvider`, `ContactsProvider`, `SmsProvider`, `CalendarProvider`, `ApkProvider`, `SettingsProvider`.|
|`android-client/app/src/main/kotlin/com/transferkit/client/shiuku/ShiukuBridge.kt`|Read-only Shiuku availability/permission probe і попередження; не виконує shell чи elevated operations.|
|`android-client/app/src/main/kotlin/com/transferkit/client/deviceowner/DoBridge.kt`|Read-only Device Owner state probe; не provision-ить пристрій і не змінює політики.|
|`protocol/Protocol.kt`|Реалізація контракту §7.|
|`ui/`|Compose-екрани: onboarding, pairing, вибір категорій і звіт; `TransferProgressScreen.kt` поки є окремим scaffold, не під’єднаним до sender flow.|

#### Спільні контракти

* `shared/protocol.md` — мережевий/файловий протокол.
* `shared/manifest.schema.json` — JSON Schema маніфесту.
* `shared/receipt.schema.json` — JSON Schema квитанції приймача.
* `shared/report.schema.json` — JSON Schema звіту.
* `shared/capability.schema.json` — JSON Schema capability-манифесту.

---

## 5. Потік перенесення (наскрізний)

Кожен етап обов’язковий. Пропуск етапу → звіт не може мати статус `OK`.

### Етап 1: Discovery

* **Windows:** `adb devices`, збір `ro.product.manufacturer`, `ro.product.model`, `ro.build.version.sdk`, `/proc/meminfo`, `df` по розділах.
* **Android:** `Build.MANUFACTURER`, `Build.MODEL`, `Build.VERSION.SDK_INT`, `ActivityManager.MemoryInfo`, `StatFs`.
* **Вихід:** `DeviceInfo` для обох сторін. Поля shared contract — manufacturer, model, SDK та build fingerprint; Android release, RAM і внутрішнє сховище — локальні діагностичні метрики й не додаються до wire JSON без версійованої зміни схеми. Серійні номери та IMEI не збираються.

### Етап 2: Capability Probe (read-only)

Для кожної категорії на кожному пристрої:

* **Windows:** ADB-команди (`content query`, `dumpsys`, `pm list packages`, `settings get`) з малими тайм-аутами.
* **Android:** `ContentResolver.query`, `PackageManager`, `Settings.System` (без запису).

**Вихід:** `CapabilityManifest` — `{category, status, method, reason, evidence}`.

**Заборонено в probe:** будь-який запис, `root`, `su`, `reboot`, зміна стану.

### Етап 3: Побудова плану

`planner.py` зіставляє два manifest-и:

* Якщо категорія `TRANSFERABLE` на обох — `TRANSFERABLE`.
* Якщо на джерелі `TRANSFERABLE`, на цілі частково — `PARTIAL` з поясненням.
* Якщо хоч одна сторона `UNAVAILABLE` — `UNAVAILABLE` з пріоритетом причини (`PERMISSION_DENIED` → `UNSUPPORTED` → `UNAVAILABLE`).
* Якщо метод вимагає Shiuku, а він не активований — `PARTIAL` з підказкою активувати.

**Показ користувачу:** матриця «що перенесеться / що ні / чому» **до** початку передачі.

Матриця окремо показує точні дозволи й системні дії для джерела та цілі. `permission_required` у capability описує потреби читання на відповідному пристрої; планувальник додає окремі вимоги запису для цільового пристрою (наприклад, `WRITE_CONTACTS`, `WRITE_CALENDAR`, `REQUEST_INSTALL_PACKAGES` або спеціальний доступ `WRITE_SETTINGS`). Також до запуску показуються API-рівневі обмеження, доступ через SAF і необхідність системного підтвердження встановлення кожного APK. Дозволи запитуються лише після вибору відповідної категорії; сам план не змінює стан пристроїв.

### Етап 4: Збір маніфесту (джерело)

`ManifestBuilder` обходить вибрані категорії, формує `manifest.json`:

```json
{
  "version": "1.0",
  "transfer_id": "550e8400-e29b-41d4-a716-446655440000",
  "created_at": "2026-11-11T12:00:00Z",
  "source_device": {
    "manufacturer": "Samsung",
    "model": "SM-A556E",
    "sdk": 34,
    "fingerprint": "samsung/SM-A556E/example-build"
  },
  "categories": {
    "photos": {
      "provider": "MediaStore",
      "method": "ContentResolver",
      "items": [
        {
          "id": "media_123",
          "sie": 4096000,
          "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
          "metadata": {
            "path": "DCIM/Camera/IMG_001.jpg",
            "mtime": 1699999999
          }
        }
      ]
    },
    "contacts": {
      "provider": "ContactsContract",
      "method": "ContactsContract/vCard",
      "items": [
        {
          "id": "c_42",
          "sie": 128,
          "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
          "metadata": {"format": "vCard"}
        }
      ]
    }
  }
}
```

У кожному item `path`, дата та інші атрибути розміщуються в `metadata`. SHA-256 у цьому ілюстративному прикладі заповнений нулями лише для показу форми поля; реальний маніфест містить хеш точних переданих байтів.

Для API-категорій (контакти, SMS) елемент — серіалізований запис із власним SHA-256.

Для `contacts` один item відповідає одному агрегованому запису з `ContactsContract.Contacts`; основне байтове представлення — platform-generated vCard через `Contacts.CONTENT_VCARD_URI`. Версію vCard визначає системний provider, тому продукт не обіцяє vCard 4.0. SHA-256 рахується за точними vCard bytes. Джерельні `RawContacts.ACCOUNT_TYPE/ACCOUNT_NAME` не додаються до item metadata: vCard переносить контактні дані, не sync identity чи доступ до облікового запису.

Перед створенням `manifest.json` ManifestBuilder має отримати точний невід’ємний `sie` і SHA-256 кожного item. Якщо provider не знає sie, ManifestBuilder потоково створює власний immutable snapshot, рахує байти й хеш та лише тоді додає item у manifest. `null`, `-1` чи підстановка `0` не є допустимим `manifest item sie`; при помилці читання item отримує явний per-item failure і не потрапляє в невалідний manifest.

### Порівняння з ціллю та автоматичне пропускання дублікатів

Після побудови manifest, але **до Space Preflight і першого chunk**, ціль інвентаризує доступні destination файли/медіа та порівнює їх із джерелом. Простий повтор запуску з новим `transfer_id` не повинен повторно займати місце вже відновленими файлами. Автоматичний точний match потребує однакових category, byte sie і SHA-256; ім’я, шлях і дата — лише допоміжні ознаки, не доказ. Метадані sie одразу відсіюють більшість items; source partial fingerprint перших/останніх 4 KiB формується в тій самій обов’язковій source read, що й SHA-256, та передається окремим версійованим dedup extension. Target рахує такий самий fingerprint лише для items-кандидатів однакового розміру; повний target SHA-256 рахується тільки при збігу partial fingerprint. Partial fingerprint ніколи не є доказом skip. Точний duplicate не передається й не створюється вдруге, а позначається `ALREADY_PRESENT` у звіті. Після порівняння Space Preflight і ETA рахуються лише для items, що залишились. Preflight має видимий progress і окрему scan ETA; він може бути скасований до першого запису.

Пропуск дозволений лише для категорії з повним scan coverage. Якщо URI/permission відсутній, Android 14 photo access обмежений вибраними фото або scan перервався, результат цієї категорії — `UNKNOWN/INCOMPLETE`, а не «дублікатів немає»; для невідомих items не вигадувати skip. Scan/verification time показується окремо від data-transfer ETA. Хеш-кеш можна повторно використовувати лише коли валідовано identity/sie/modified time та MediaStore generation; кеш не скасовує фінальну перевірку SHA-256 для кандидатів.

Для перекодованих/масштабованих Viber та інших фото додається другий рівень, який аналізує **всі фото незалежно від byte sie**: pHash/dHash формують кандидатні пари, а окремий image verifier перевіряє crop/rotation, геометрію, візуальне співпадіння та якість. Один perceptual hash або схожість сцени не є достатнім доказом: схожі кадри серії можуть бути різними фотографіями. Після локального benchmark високоточні підтверджені повтори автоматично пропускаються, якщо на цілі вже є рівноцінна або краща версія. Якщо source-версія краща, система автоматично зберігає її як upgrade у TransferKit collection; сторонній файл не перезаписується й не видаляється. Невпевнена схожість автоматично копіюється в окрему TransferKit collection, щоб не вимагати від користувача ручного розбору й не втратити кадр. Для завершення звичайного плану не допускається `conflict`, який чекає на item-by-item рішення користувача; потрібні дозволи Android або системне підтвердження на пакетну операцію можуть залишатися платформною вимогою, але не ручним пошуком дублікатів.

Перша реалізація охоплює MediaStore фото/відео/аудіо, які застосунок справді може прочитати; SAF documents/downloads та API-категорії додаються окремо з відповідними scope/permission rules. Сканування працює локально, не завантажує зображення в мережу, не видаляє і не перезаписує звичайні файли користувача автоматично. `ALREADY_PRESENT` та `NEAR_DUPLICATE_SKIPPED` — рішення плану/звіту, а не помилки receipt і не невдалий restore.

### Етап 4a: Space Preflight

Після валідації manifest і до першого chunk target оцінює, чи вистачить місця для обраних items. Windows Station виконує еквівалентну перевірку для Backup Relay. Preflight — оцінка, а не гарантія: FUSE, provider overhead, паралельне використання диска та квоти можуть змінити фактичну доступність, тому кожен write однаково обробляє `ENOSPC`/write failure.

Для Android staging обирається на тому самому storage volume, куди пише цільовий provider. `getExternalFilesDir()` може вказувати на окремий/знімний volume, тому volume перевіряється; якщо staging і destination розміщені на різних volume, бюджет рахується окремо для кожного. Для рішення про алокацію використовувати `StorageManager.getAllocatableBytes()` для відповідного UUID, отриманого через `getUuidForPath()`; `StatFs.getAvailableBytes()` можна показувати в UI як поточний вільний обсяг. Якщо API для volume недоступний — використовувати консервативний fallback і збільшити safety margin.

`staging_leftover` — розмір власних staging files від попередніх передач. Ці байти вже займають файлову систему й уже враховані у поточному allocatable/free значенні; їх не можна додавати вдруге до потрібного піку. UI показує їх окремо. Видаляти можна лише власний staging за окремим підтвердженням; активний resumable checkpoint не видаляється цим flow.

Обчислення для одного спільного volume:

```text
total_sie = Σ selected item.sie
largest_item = max(item.sie), або 0 для порожнього manifest
safety_margin = max(100 MiB, allocatable_bytes / 20)
safe_available = max(0, allocatable_bytes - safety_margin)

batch_peak = total_staging_bytes + total_restore_estimate
streaming_peak = total_restore_estimate + largest_item_staging
```

Для звичайних файлів `total_staging_bytes = total_sie`, а `total_restore_estimate ≈ total_sie`, тому batch оцінюється як `2 × total_sie`, streaming — як `total_sie + largest_item`. Для API-записів provider адаптер додає консервативну оцінку restore storage; невідома оцінка не дає системі права обіцяти, що всі дані помістяться. Для різних volume перевіряються окремі піки staging і destination.

Для streaming формула враховує момент копіювання зі staging у destination: до копії вже відновлено `total_restore_estimate - current_item`, а під час копії одночасно існують staging і destination copy цього item. Для найбільшого item пік дорівнює `(total_restore_estimate - largest_item) + 2 × largest_item = total_restore_estimate + largest_item`.

Якщо batch влазить — за замовчуванням використовується batch без діалогу, з інформаційною оцінкою. Якщо batch не влазить, але streaming усіх items влазить — показати вибір streaming, звільнити місце або скасувати. Якщо всі items не влазять потоково — відсортувати items від найменшого до найбільшого. Для candidate item перевірка має бути `candidate_restore_sum + max(candidate_staging_sie) <= safe_available`; за однакового item sie для staging і restore це спрощується до `running_sum + 2 × item.sie <= safe_available`. Це гарантує максимальну кількість найменших items і не відкидає item-и, які фізично вміщуються. Якщо не вміщується навіть найменший item — запропонувати лише звільнення місця або скасування. `TARGET_ITEM_TOO_LARGE` означає, що окремий item не проходить бюджет; `TARGET_SPACE_INSUFFICIENT` означає, що всі вибрані items не вміщуються, хоча підмножина може вміститися. Реальна помилка запису лишається `TARGET_NO_SPACE`.

SpaceReport передає source обсягів, вибрану стратегію `batch`, `streaming`, `drop-largest` або `insufficient`, чи потрібне рішення користувача, та явні `kept_item_ids`/`skipped_item_ids` для drop-largest. У пропущених items немає receipt про отримані bytes: вони залишаються у manifest, відображаються як `SKIPPED_NO_SPACE` у локальному плані та як per-item failure/decision у report. Повторна спроба пропущених items — окрема передача з новим `transfer_id`; звіт і фінальний receipt попереднього ID не переписуються.

Для streaming target приймає один item, записує staging, перевіряє розмір і SHA-256 та лише тоді викликає restore. Перед публікацією MediaStore item перевіряється SHA-256 bytes, записаних у destination. Android 10+ використовує `IS_PENDING=1` до успішної перевірки та змінює на `0` лише після неї; Android 8–9 використовує власний `.pending_` файл на тому самому volume, перевіряє його й виконує контрольований rename/scan. Після process death очищуються тільки прострочені власні pending files, не чужі файли; автоматичне очищення MediaStore не є єдиним механізмом recovery.

Контакти, календар і SMS лишаються у batch, доки adapter не забезпечить idempotent restore після process death. Самого `transfer_id + item_id` у metadata недостатньо, якщо target provider не може зберегти/знайти цей marker. APK можна потік віддавати в `PackageInstaller.Session`, порахувати SHA-256 записаних bytes через `openRead()` і викликати commit лише після збігу та потрібного підтвердження користувача; окремий duplicate staging file для цього не є обов’язковим.

Після user-confirmation стратегія не знижує integrity rules: `FAILED_INTEGRITY` має однаковий пріоритет у batch і streaming, а `OK` вимагає post-verification. Streaming відрізняється піком диска та resume granularity: якщо обрив стався всередині item, target truncates його staging до нуля, встановлює `received_items.bytes_received=0` і просить source повторити цей item від chunk 0; цей reset виконується до відправки нового `NextItemRequest`, щоб UI не показував stale bytes. Уже відновлені items не передаються повторно. Batch продовжується з durable checkpoint.

### Етап 5: Транспорт

**Статус Android service:** `TransferForegroundService` створює видиме сповіщення, має bounded transfer phases/progress і явні start/update/stop entry points. Реальний worker, durable progress source, transport socket та виклики з UI ще не підключені; notification shell сам по собі не означає, що transfer виконується.

**Статус USB receiver transport:** Android `ProtocolCodec.readNextReceiverFrame` та `ReceiverStreamHandler` мають bounded TKMF/TKCH framing і маршрутизацію ACK до durable receiver. Вони працюють із переданими streams і не відкривають listener. `adb forward` host sender існує у Python, але повний flow не готовий, доки Android listener/service wiring і автентифікація peer не реалізовані; raw protocol frames не є pairing/authentication.

**Статус Wi-Fi pairing:** `TlsPairing` має runtime TLS 1.3 provider probe і in-memory one-time token primitive (256-bit, bounded TTL, single-use, redacted `toString`). Він не генерує сертифікат, не відкриває sockets, не робить pin validation і не кодує QR; token helper не означає, що peers уже автентифіковані.

**Статус Backup Relay:** Python `backup_relay.py` має потоковий ZIP writer і безпечний extract-to-new-staging primitive. Він приймає лише manifest-verified successful item set, повторно хешує bytes під час створення, schema-validates manifest/receipt; extraction не довіряє ZIP paths, вимагає user-supplied total budget і перевіряє кожний item sie/SHA-256 до повернення staging. Опційний парольний контейнер `TKAE` v1 потоково шифрує весь ZIP AES-256-GCM; ключ виводиться Scrypt (`N=32768`, `r=8`, `p=1`), header автентифікується як AAD, а password не зберігається. Під час імпорту потрібен тимчасовий plaintext ZIP у user temp directory; він видаляється після операції, але аварійне завершення може лишити тимчасові bytes, а звичайне видалення не є secure wipe. Парольний file-picker/UI flow ще не підключений; ciphertext захищає конфіденційність/цілісність, але не доводить автора архіву.

**USB/ADB через Windows Station:** Windows є ADB-хостом; після ввімкнення USB debugging користувач підтверджує RSA-довіру цьому комп’ютеру. Чанки йдуть через `adb push/pull` або `adb forward` до локального сокета Android-приймача. Це не пряме ADB-з’єднання між двома телефонами. Для Android↔Android типовий шлях — TLS через локальну Wi-Fi мережу або точку доступу, без ADB.

Пряме підключення двох стокових Android-пристроїв кабелем не обіцяється через ADB. USB-tethering/RNDIS/NCM може стати додатковим IP-шляхом лише після перевірки USB-host/OTG, ролей, доступності tethered network і локального TCP/TLS на конкретних моделях. APK не намагається сам вмикати tethering: якщо системна дія потрібна, її виконує користувач у налаштуваннях. Це дослідницький варіант поза поточним MVP; за успішного прототипу він повторно використовуватиме Recovery checkpoint handshake, без окремого TKXS frame до появи доведеної потреби.

**Wi-Fi:** джерело піднімає `ServerSocket`, показує QR з `ip:port + одноразовий токен + SHA-256 сертифіката`. Ціль сканує й підключається по TLS.

**Backup Relay (ПК):** проміжний ZIP + JSON на ПК, відновлення пізніше.

**Чанкування:** кожен файл — чанками 4–8 МБ. Чекпоінт у SQLite (`transfer_state` таблиця) після кожного чанка. Перерва → резюм з того ж чанка.

**Контроль:** кожен чанк має CRC32; файл — SHA-256 після останнього чанка.

Під час активної передачі UI може запропонувати опцію «Не гасити екран у цій передачі». Вона вимкнена за замовчуванням і діє лише поки екран TransferKit видимий: `FLAG_KEEP_SCREEN_ON` утримує його активним; за окремим бажанням користувача вікно може тимчасово застосувати вибрану яскравість через `screenBrightness` (повзунок 10–30%, початкове значення 15% після ввімкнення опції). Коли опцію вимкнено або передача завершується/скасовується, застосунок відновлює попередні параметри вікна; системну яскравість не змінює і спеціального дозволу не просить. Кнопка живлення все одно може вимкнути екран; у фоні цей прапорець не утримує його активним. Foreground Service, durable checkpoints і Recovery залишаються основою довгої передачі. UI не обіцяє, що ця опція обходить OEM-обмеження чи гарантує роботу мережі в Doe; читабельність і поведінку перевірити на фізичних пристроях.

### Етап 5a: Recovery

Recovery може початися з будь-якого режиму та транспортного профілю. При втраті зв’язку передача переходить `RUNNING → RECONNECTING`; клієнт намагається під’єднатися з backoff 0.5, 1, 2, 4 і 8 секунд, повторюючи затримку до загального дедлайну 30 секунд. Успішне перепідключення повертає її в `RUNNING`. Після дедлайну стан стає `PAUSED_AWAITING_USER`, UI показує збережений прогрес і дію «Продовжити». Неможливість зіставити пристрої, маніфест, TTL або версію протоколу переводить її у `PAUSED_BLOCKED` з конкретним reason code; доступні лише «Почати заново» та «Скасувати назавжди».

Ціль зберігає SQLite receiver schema v3: `received_transfers` має `transfer_id`, manifest SHA-256, source/target fingerprints, protocol/app version, `created_at`, `updated_at`, status, root-relative server-generated `staging_dir`, `last_checkpoint_at` і `expires_at`; identity поля можуть бути null у старих/ще не прив’язаних до handshake записах, і такий запис не допускається до resume. `item_checkpoints` є authoritative item cursor: `next_expected_chunk`, `next_byte_offset`, total/nominal chunk parameters, relative staging locator і timestamp останнього checkpoint. `received_chunks` зберігає кожен прийнятий chunk index, byte offset, length, CRC32 та payload SHA-256. Staging bytes `fsync`-яться до транзакції, яка атомарно оновлює ledger, item cursor і parent retention timestamp; ACK надсилається лише після commit. SQLite v1 додає restore status перед v3 migration; v2 cursor-и копіюються до v3 без втрати. TTL — 30 днів від останнього durable chunk, але не запускає автоматичне видалення staging. Старі checkpoint-поля в `received_items` лишаються синхронізованою migration-compatibility mirror; recovery read path використовує `item_checkpoints`.

Джерело дзеркалить `transfer_id`, `manifest_sha256`, необов’язкові ще не підтверджені identity fingerprints, status і per-category progress aggregates: manifest totals, ACK-підтверджені байти, partial/chunk-complete items та verified/failed з фінальної receipt. Snapshot категорій незмінний, включно з порожнім manifest summary; ACK checkpoint та агрегат зберігаються атомарно. Source history є локальним cache/UI mirror, а не джерелом істини: під час reconciliation авторитетні target `item_checkpoints` і chunk ledger. Фінальна receipt перевіряється проти manifest summary та ACK progress. Перед resume сторони звіряють transfer ID, manifest SHA-256, protocol version, source і target identity fingerprints. Для Android installation identity створюється EC P-256 keypair у Android Keystore; private key не експортується, а fingerprint — lowercase SHA-256 від X.509-encoded public key. Це окрема ідентичність від Android build fingerprint і вона не може бути IMEI/серійним номером. Recovery handshake має доводити володіння private key підписом свіжого challenge, а не довіряти надісланому fingerprint. Якщо після перевстановлення/втрати Keystore alias старий ключ недоступний, генерується новий fingerprint; старі transfer-и переходять у `PAUSED_BLOCKED` з device mismatch і можуть продовжитися лише як новий transfer після restart. Копіювання staging без відповідного ключа/ідентичності не дає змоги продовжити передачу на іншому пристрої.

Після handshake ціль надсилає `CheckpointState` з кількістю verified/failed items і частковими item (`next_expected_chunk`, `total_chunks`, byte offset). Джерело перевіряє його проти незмінного manifest SHA-256, готує `ResumePlan` лише з непідтверджених чанків і чекає підтвердження користувача. Resume є транспорт-незалежним; після підтвердження chunks можуть іти через інший транспорт. Завершені items перевіряються повним SHA-256. Часткові chunks у fast resume звіряються з durable CRC32 checkpoint-ами; full verification перечитує їх усі. Якщо виявлено пошкодження, воно лишається `FAILED_INTEGRITY` у receipt/report навіть коли recovery повторно передає байти.

Для `SOURCE_DEVICE_MISMATCH`, `TARGET_DEVICE_MISMATCH`, `SOURCE_MANIFEST_CHANGED`, `INCOMPATIBLE_PROTOCOL_VERSION`, `CHECKPOINT_EXPIRED` або неконсистентного staging стан стає `PAUSED_BLOCKED`. Manifest змінюється — лише restart із новим ID. TTL відлічується від останнього durable checkpoint; вичерпання не видаляє файли, UI показує їх розмір і пропонує очистити. Після restart попередній ID стає `SUPERSEDED`; після abandon — `ABANDONED`, а подробиці checkpoint очищуються.

Windows Station показує статус у рядку стану та toast; Android foreground service — постійне сповіщення з прогресом і причиною. Recovery screen показує пару пристроїв, час початку/переривання, прогрес по категоріях, останній item/chunk і чотири дії: «Продовжити», «Перевірити й продовжити», «Почати заново», «Скасувати назавжди». Restart має окреме підтвердження: буде втрачено лише тимчасовий прогрес цієї передачі, цільові дані користувача не змінюються.

Адаптивна політика може зменшити chunk sie 4 MiB → 1 MiB після понад трьох розривів за п’ять хвилин, а потім до 256 KiB після понад п’яти; після п’яти хвилин без розривів поступово повертає 4 MiB. Поточний wire format вимагає однакового розміру всіх chunk-ів, крім останнього, і не містить byte offset; тому зміна розміру всередині item потребує версійованого protocol extension із явним offset та підтримкою variable-sie chunks. До цього extension політика змінює розмір тільки на межі item, а вже частковий item продовжується з чинними параметрами.

### Етап 6: Прийом і перевірка (ціль)

`Receiver`:

1. Пише чанк у `.tmp/` каталог.
2. Після останнього чанка — перераховує SHA-256, порівнює з маніфестом.
3. Збіг → переміщає у фінальний каталог, оновлює `receipt.json`.
4. Розбіжність → `FAILED_INTEGRITY`, видаляє, не показує користувачу як «перенесено».

### Етап 7: Відновлення в API цілі

Per-category restorer:

* **MediaStore:** `ContentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)` з потоковим записом. Після запису — `MediaScannerConnection.scanFile` для індексації галереєю.
* **Contacts:** batch `ContentProviderOperation` з `ContactsContract.RawContacts`.
* **SMS:** `ContentResolver.insert(Telephony.Sms.CONTENT_URI, values)` — потребує дозволу `READ_SMS`/`WRITE_SMS` (привілейований, лише сайдлоад).
* **Calendar:** `CalendarContract.Events`.
* **APK:** `PackageInstaller` — потребує підтвердження користувача per-APK, або тихо при Device Owner.
* **Settings:** `Settings.System.putString` (обмежено), `Settings.Secure.putString` (потребує WRITE_SECURE_SETTINGS або Shiuku/DO).

Кожен restorer повертає `{item_id, status, error_reason}`.

**Статус реалізації:** Android має `CategoryRestorer` registry та `MediaStoreRestorer` для фото, відео, аудіо й Downloads на Android 10+. Він створює власне ім’я у `TransferKit` папці, тримає `IS_PENDING=1`, потоково звіряє SHA-256 при записі, перечитує destination і публікує item лише після збігу. Повтор після process death знаходить детермінований TransferKit destination marker і перевіряє його bytes замість створення дубліката. Receiver має inbox лише для завершених передач із фінальним receipt і лише items зі станом `VERIFIED`; manifest identity, розмір і SHA-256 звіряються перед підготовкою кандидата. Діалог показує точний сумарний розмір вибраних staging items і повідомляє, що після успішної post-перевірки ці власні копії будуть видалені; при невдалому restore bytes зберігаються для retry. Restore result записується у SQLite перед очищенням, щоб після process death повторити cleanup без дублювання user restore. Новий inbox/cleanup flow та instrumentation cases поки не зібрані через локальну помилку з’єднання Gradle daemon. Android 8–9 повертає `API_UNAVAILABLE`; Documents потребують SAF destination picker. Contacts, Calendar, SMS/Call Log, APK і Settings не мають завершених restore adapters; end-to-end приймання даних також чекає на authenticated receiver transport.

Позитивний file restore означає: source bytes збіглися зі staging SHA-256, destination URI перечитано до публікації та ще раз після `IS_PENDING=0`; лише тоді item отримує `RESTORED`. `receipt.json` фіксує прийом і receiver verification та не переписується після restore; усі receipt counters/failures мають бути повторені у final report, а restore/post-verification failures додаються до report окремо. Python export API може перевірити цю узгодженість, коли йому передано receipt. Автоматизований API readback реалізований лише для MediaStore; решта категорій не можуть отримати `OK` без власного adapter і destination readback.

**APK install scaffold:** `ApkInstallCoordinator` тепер приймає лише короткоживучий одноразовий approval token, прив’язаний до transfer, manifest, одного item, sie та SHA-256; довільний boolean більше не дає права створити `PackageInstaller.Session`. Є окремий діалог підтвердження одного пакета, але його ще не під’єднано до APK inbox, Activity status callback чи sender. Coordinator потоково записує APK у session, виконує `fsync` і повторно хешує session bytes до commit. Android може показати системне підтвердження; `RESTORED` дозволено лише після `STATUS_SUCCESS` та query package/version. Збірка gate і dialog заблокована локальним з’єднанням Gradle daemon; APK-only transfer ще не готовий до end-to-end використання.

### Етап 8: Пост-перевірка й звіт

`verifier.py`:

1. Перечитує цільові файли, рахує SHA-256, зіставляє з маніфестом.
2. Для API-категорій — повторний `query` на цілі, зіставлення за полями.
3. Будує `TransferReport`.

**Правило:** якщо хоч один файл має `FAILED_INTEGRITY`, категорія не може бути `OK`.

---

## 6. Матриця категорій (Tier A–G)

Це орієнтир. Реальний статус — runtime-probe.

### Tier A — Спільні медіа й файли (фото, відео, аудіо, документи, завантаження)

* **API:** `MediaStore.Images`, `.Video`, `.Audio` для медіафайлів; Storage Access Framework (SAF) для документів і завантажень, вибраних користувачем. Не використовувати `MediaStore.Files` як спосіб читати будь-які чужі documents/Downloads.
* **Дозволи читання медіа:** Android 13+ — `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`; Android 10–12 — `READ_EXTERNAL_STORAGE`; Android 8–9 — `READ_EXTERNAL_STORAGE`. Для Android 14+ підтримати частковий доступ через `READ_MEDIA_VISUAL_USER_SELECTED`. На Android 8–9 для запису в спільне медіасховище потрібен `WRITE_EXTERNAL_STORAGE`; Android 10+ створює нові записи власника через `MediaStore` без широкого дозволу запису.
* **Документи й Downloads:** SAF picker видає URI-доступ до вибраних файлів/теки; не просити `MANAGE_EXTERNAL_STORAGE` і не припускати, що звичайні медіадозволи відкривають довільні документи.
* **Метод:** читання `ContentResolver`/URI + потокове копіювання через `ParcelFileDescriptor`. Вбудовані metadata (EXIF/XMP та інші байти контейнера) передаються разом із файлом і зберігаються, якщо source URI повертає оригінальні байти; застосунок не витягує й не реконструює окремі Gallery/app-private теги. На Android 10+ GPS location у MediaStore може бути redacted: для оригінальних координат потрібні окрема згода/runtime grant `ACCESS_MEDIA_LOCATION` і `MediaStore.setRequireOriginal(uri)`. Поточний `MediaProvider.openStream()` ще не виконує цей opt-in, тому GPS EXIF зараз не гарантовано; якщо користувач відмовився або URI повернув redacted bytes, звіт не має обіцяти координати. Поведінку API 26–28 слід перевірити окремо.
* **Відновлення:** `insert` у `MediaStore` + `MediaScannerConnection` для медіа; SAF destination URI для документів, якщо користувач вибрав місце.
* **Доступно:** для медіа — за відповідним дозволом, для документів — за URI-грантом користувача.

### Tier B — Акаунтні дані (контакти, календар)

* **API:** `ContactsContract`, `CalendarContract`.
* **Дозволи:** `READ_CONTACTS`, `WRITE_CONTACTS`, `READ_CALENDAR`, `WRITE_CALENDAR`.
* **Метод:** для контактів один агрегований `Contacts` row = один vCard item; хешується точний provider-generated vCard stream, версію якого визначає Android/OEM. Відновлення не вважає source contact ID чи source sync account частиною wire identity.
* **Календар:** один рядок `CalendarContract.Events` = один UTF-8 JSON item `transferkit-calendar-event` v1 з полями події, її учасниками та нагадуваннями. Повторювана подія залишається одним master event із recurrence rules; `Instances` не розгортаються у дублікати. Source `_ID`/`CALENDAR_ID` застосовуються лише всередині provider; посилання винятку recurrence передається як hashed item ID, а назви/type акаунтів і календарів не передаються. `ExtendedProperties` та sync-adapter приватні поля не є portable; якщо подія сигналізує про такі властивості, категорія має показати обмеження й не обіцяти повне перенесення.
* **Обліковий запис цілі:** контакти відновлюються у default destination account, якщо система його визначає; інакше UI до запису просить вибрати доступний cloud account або local/device-only storage. Local збереження може не синхронізуватися в хмару, про це повідомляється користувачу. Не переносити source account name/type як metadata й не вимагати на цілі той самий обліковий запис.
* **Календар цілі:** перед записом користувач вибирає доступний destination calendar/account; source calendar/account identity не задає ціль. Точна передача attendee invitations, sync-adapter metadata та vendor-specific extensions залежить від destination provider.
* **Відновлення:** batch `ContentProviderOperation` (контакти), `CalendarContract` inserts. `USER_ACTION_REQUIRED` застосовується, коли вибір destination account ще не зроблений; capability не оголошує джерельні Google/Samsung accounts недоступними лише через їх відсутність на цілі.
* **Доступно:** лише за фактичної наявності Calendar Provider і наданого `READ_CALENDAR`; runtime probe визначає status для кожного пристрою. Успішний порожній запит — `NO_DATA`; відмова дозволу, null cursor або помилка provider не трактуються як порожній календар.

### Tier C — Повідомлення й дзвінки

* **API:** `Telephony.Sms` для текстових SMS і `CallLog.Calls` для журналу дзвінків. MMS та вкладення — окремий формат і не входять до поточного text-SMS export.
* **Дозволи читання:** `READ_SMS` і `READ_CALL_LOG` є небезпечними hard-restricted permissions. Реальний доступ можливий лише коли Android надав застосунку відповідний grant; сама декларація в manifest або sideload цього не гарантує. Provider не обходить обмеження через root, інші URI чи альтернативні API.
* **Політика Google Play:** backup/restore та device migration прямо перелічені серед use cases, на які можна подати permission exception, але потрібні окрема Permissions Declaration і схвалення Google Play. Це не автоматичний дозвіл. Для sideload доступ однаково залежить від Android installer allowlist/restricted-permission grant.
* **Метод:** `ContentResolver.query` викликається лише після окремого вибору категорії та перевірки фактичного grant. SMS export створює один canonical JSON item на текстове повідомлення; call log — один item на дзвінок. UI показує агреговані лічильники, не номери, тексти чи інші поля.
* **Відновлення:** ще не реалізовано. Запис потребуватиме окремої перевірки `WRITE_SMS`/`WRITE_CALL_LOG`, цільового handler або іншої схваленої platform/policy процедури, явного підтвердження та post-verification. Не оголошувати restore доступним лише тому, що читання працює.
* **Доступність:** runtime capability може бути `AVAILABLE` лише за наданого permission; без нього — `UNAVAILABLE/PERMISSION_DENIED`. Категорія `sms` наразі часткова, бо MMS не включено. Null cursor/provider error не трактувати як `NO_DATA`.

### Tier D — APK-файли

* **API:** `PackageManager.getInstalledPackages()`, `ApplicationInfo.publicSourceDir`.
* **Дозволи:** `QUERY_ALL_PACKAGES` (Android 11+).
* **Метод:** для явно вибраного APK пункту прочитати `ApplicationInfo.sourceDir`, потоково виміряти sie/SHA-256 і повторно перевірити digest під час копіювання. Не включати локальний source path у manifest. Перша реалізація включає лише user-installed single-file APK; system/updated-system, split та unreadable packages не передаються й показуються окремими лічильниками. Кожний manifest item містить package name і version, потрібні для адресного restore.
* **Відновлення:** `PackageInstaller` — явне підтвердження користувача per-APK. `REQUEST_INSTALL_PACKAGES`.
* **Доступно:** лише для видимих і читабельних package sources; `QUERY_ALL_PACKAGES` має окремі вимоги Play policy. `PackageManager` visibility не є гарантією, що package APK повний або встановлюваний на цілі. Split APK поки відкидаються як група; системні APK не експортуються.

### Tier E — Системні налаштування

* **Звичайний API:** read-only allowlist публічних `Settings.System` ключів. Зміни `Settings.System` дозволені лише після окремого user approval через `ACTION_MANAGE_WRITE_SETTINGS` і `Settings.System.canWrite()`; наявність permission у manifest недостатня.
* **Secure/Global:** не читати/записувати довільні keys. Частина значень недоступна звичайному APK або керується конкретними системними API/OEM policy. `WRITE_SECURE_SETTINGS` не додається у звичайний manifest.
* **Shiuku:** окремий шлях під user-approved shell identity; доступ перевіряється на кожній конкретній операції. Наявність Shiuku чи його permission не гарантує, що конкретна настройка writable або переносна.
* **Device Owner:** окремий provisioned-device шлях; застосовувати лише документовані `DevicePolicyManager` методи для відповідної політики. Device Owner не є загальним дозволом на довільні `Settings.Secure/Global` keys.
* **Поточний статус:** SettingsProvider v1 читає лише явний public `Settings.System` allowlist і фіксує окремо WRITE_SETTINGS approval, Shiuku connection/grant та Device Owner state. Він нічого не змінює; secure/global та elevated operations поки не реалізовані. Capability — `PARTIAL` з конкретним методом/evidence, не «повністю доступно».

### Tier F — Дані застосунків

* **API:**
* `allowBackup=true` → `adb backup` (де ще працює; Android 12+ часто повертає порожній файл).
* `run-as <pkg>` — тільки для `debuggable`.
* `Auto Backup` — хмарний, не керується стороннім APK.
* `D2D Transfer API` — **немає публічного API** для сторонніх.
* `Device Owner` — політики, не файловий доступ.
* `Shiuku` — рівень shell (uid 2000), не `/data/data` не-debuggable.
* `Root` — повний доступ.
* **Метод:** залежить від доступного рівня.
* **Доступно:** ~85% без root, ~98% з root.
* **Заборонено:** обхід sandbox через exploit-и, Magisk-модулі, kernel module.

### Tier G — Об’єктивно недоступне

* DRM-вміст (Netflix, Spotify офлайн) — hardware-ключі, не переноситься.
* Банківські застосунки — прив’язка до пристрою, часто неможливо навіть з root.
* 2FA-токени (Google Authenticator, Authy) — потребують експортного механізму застосунку.
* Пароли Wi-Fi без Shiuku — недоступно.
* Розкладка іконок launcher без OEM-API.
* Акаунти Google — сторонній APK не може переносити системні акаунти. Показувати `UNAVAILABLE` з причиною `OEM_RESTRICTION`; користувач повторно входить у систему або запускає офіційний OEM transfer. Smart Switch використовує OEM-підписані системні привілеї, які сторонньому APK недоступні.

**Усі Tier G показуються як `UNAVAILABLE` з причиною в capability-манифесті.**

---

## 7. Протокол обміну

### 7.1 Контракт

Контракт — наскрізний: **маніфест → чанки → квитанція → звіт**.

### 7.2 `manifest.json`

JSON Schema: `shared/manifest.schema.json`.

```
{
  "version": "1.0",
  "transfer_id": "00000000-0000-4000-8000-000000000001",
  "created_at": "2026-11-11T12:00:00Z",
  "source_device": {
    "manufacturer": "Example",
    "model": "Android",
    "sdk": 34,
    "fingerprint": "example/device/fingerprint"
  },
  "categories": {
    "photos": {
      "provider": "MediaStore",
      "method": "ContentResolver",
      "items": [
        {
          "id": "photo-1",
          "sie": 0,
          "sha256": "0000000000000000000000000000000000000000000000000000000000000000",
          "metadata": {"path": "DCIM/Camera/photo-1.jpg", "mtime": 0}
        }
      ]
    }
  }
}
```

### 7.3 Чанк

Заголовок чанка:

```
magic: "TKCH" (4 bytes)
version: 1 (1 byte)
transfer_id: 16 bytes (UUID)
category_id_hash: 4 bytes
item_id_hash: 4 bytes
chunk_index: uint32
total_chunks: uint32
chunk_sie: uint32
payload_crc32: uint32
payload: bytes
```

### 7.4 `receipt.json` (ціль → джерело)

```
{
  "version": "1.0",
  "transfer_id": "00000000-0000-4000-8000-000000000001",
  "received_at": "2026-11-11T12:05:00Z",
  "categories": {
    "photos": {
      "items_received": 100,
      "items_verified": 98,
      "items_failed": 2,
      "failures": [
        {"item_id": "photo-99", "stage": "SHA256_VERIFY", "reason": "SHA256_MISMATCH"},
        {"item_id": "photo-100", "stage": "SHA256_VERIFY", "reason": "SHA256_MISMATCH"}
      ]
    }
  }
}
```

Receipt є фінальним для receiver receive/verification: для кожної категорії `items_verified + items_failed == items_received`, а кожен failed item має окремий унікальний failure record. Він не змінюється після restore. `TransferReport.transferred_count`/`verified_count` мають збігатися відповідно з `items_received`/`items_verified`; receipt failures зберігають ті самі item IDs та reason у report stage. Restore і destination post-verification відображаються окремо у report. Проміжний прогрес передається ACK/checkpoint, а не фінальним `receipt.json`.

### 7.5 `report.json`

JSON Schema: `shared/report.schema.json`.

```
{
  "version": "1.0",
  "transfer_id": "00000000-0000-4000-8000-000000000001",
  "started_at": "2026-11-11T12:00:00Z",
  "finished_at": "2026-11-11T12:05:00Z",
  "mode": "quick",
  "source_device": {"manufacturer": "Samsung", "model": "SM-A556E", "sdk": 34, "fingerprint": "samsung/SM-A556E/example-build"},
  "target_device": {"manufacturer": "Xiaomi", "model": "Redmi Note", "sdk": 34, "fingerprint": "xiaomi/redmi/example-build"},
  "categories": [
    {
      "category_id": "photos",
      "tier": "A",
      "source_count": 2450,
      "manifest_count": 2450,
      "transferred_count": 2450,
      "verified_count": 2450,
      "restored_count": 2450,
      "deduplicated_count": 0,
      "deduplicated_bytes": 0,
      "plan_status": "TRANSFERABLE",
      "status": "OK",
      "reason": "OK",
      "failures": []
    }
  ],
  "summary": {
    "total_categories": 1,
    "transferable": 1,
    "partial": 0,
    "unavailable": 0,
    "failed_integrity": 0
  },
  "decisions": [
    {"reason": "USER_CHOSE_STREAMING", "item_ids": [], "category_id": null}
  ]
}
```

`decisions` є необов’язковим audit-полем для вибору space strategy/очищення та автоматичного dedup; записи містять reason, item IDs і необов’язкову категорію, не додаються до `failures`. `deduplicated_count`/`deduplicated_bytes` у category є необов’язковими backward-compatible лічильниками фактично пропущених дублікатів. Через `additionalProperties: false` у схемі будь-яке нове поле потребує узгодженого schema/model/test оновлення; несумісні зміни вимагають version bump.

---

## 8. Формат звіту (для користувача)

GUI-таблиця:

|Категорія|На джерелі|Передано|Перевірено SHA-256|Відновлено|Дублікатів пропущено|Статус|
|-|-|-|-|-|-|
|Фотографії|2450|2420|2420|2420|30|OK|
|Відео|320|320|320|320|0|OK|
|Контакти|418|418|418|418|0|OK|
|Документи|128|126|120|120|0|Увага (2 файли пошкоджено)|
|SMS|1250|1250|1250|1250|0|OK|
|Дані додатків|84|62|62|61|0|Частково (22 недоступні)|

Експорт: JSON, CSV.

---

## 9. Vendor Adaptors

Кожен адаптер реалізує інтерфейс:

```
class VendorAdapter:
    def matches(self, device_info: DeviceInfo) -> bool: ...
    def capabilities(self, device_info: DeviceInfo) -> Mapping[CategoryId, CapabilityEntry]: ...
    def quirks(self) -> dict[str, str]: ...
```

`capabilities` повертає лише додаткові vendor-specific capability hints, ключовані стабільним `CategoryId`; записи мають містити evidence і правильний Tier. Adapter не може підвищити доступність усупереч фактичному probe чи платформним обмеженням. Якщо статичних vendor-відомостей недостатньо, повертається порожня mapping. Registry вибирає перший відповідний adapter у визначеному порядку, інакше використовує generic Android adapter. Curated quirk notes можуть доповнити `CapabilityEntry.quirk`, але не змінюють runtime status, method, permission, fallback чи reason. У цьому інтерфейсі немає методів передачі або запису даних.

|Адаптер|Квірки|
|-|-|
|`SamsungAdapter`|Samsung Gallery використовує стандартний MediaStore, але альбоми можуть бути в окремій БД. Samsung Calendar — стандартний `CalendarContract`.|
|`XiaomiAdapter`|MIUI може мати кастомні дозволи; перевіряти `READ_EXTERNAL_STORAGE` окремо.|
|`MotorolaAdapter`|Стандартний Android, мінімум квірків.|
|`GooglePixelAdapter`|Найбільш стандартний; перевіряти D2D availability.|
|`OppoAdapter`, `RealmeAdapter`, `OnePlusAdapter`|ColorOS/OxygenOS специфічні для налаштувань.|
|`HonorAdapter`, `HuaweiAdapter`|HMS замість GMS; відмінності в акаунтах.|
|`InfinixAdapter`, `TecnoAdapter`, `VivoAdapter`, `ZTEAdapter`|Стандартний + OEM-специфічні налаштування.|

**Правило:** адаптер повертає capability-записи, а не виконує перенесення. Транспортний шар — спільний.

---

## 10. Безпека

### 10.1 Що заборонено

* Записи, `root`, `su`, `reboot` у фазі probe.
* Прихований збір даних, телеметрія, аналітика.
* Збереження паролів Wi-Fi, токенів акаунтів, координат GPS у логах.
* Збереження повного `getprop` snapshot.

### 10.2 Що обов’язково

* Явне підтвердження користувача перед фазою відновлення (запис). Підтвердження має показати обсяг вибору, кількість items і загальний розмір без зайвого розкриття вмісту; execution layer приймає лише короткоживучий одноразовий дозвіл, прив’язаний до transfer, digest маніфесту та вибраних item IDs/sie/SHA-256. Дозвіл споживається до першого запису кожного item; скасування, зміна вибору, process death або закінчення строку вимагають нового підтвердження. Невідомий/повторно використаний дозвіл не дозволяє запис.
* Окреме підтвердження перед встановленням APK.
* Перед transfer — Space Preflight; strategy selection не знімає явного підтвердження restore.
* Batch і streaming проходять однакові per-item SHA-256 та post-verification; для MediaStore item лишається непублічним до перевірки записаного destination.
* Не видаляти файли користувача автоматично. Видалення власного TransferKit staging дозволене лише після явного підтвердження з показом точного розміру; активні resumable checkpoints не видаляти.
* Окреме попередження перед активацією Shiuku.
* Окреме попередження перед root-режимом (профіль «Професійний»).
* Видалення тимчасових файлів (чанки, кеш) після завершення.
* TLS для Wi-Fi передачі.
* Одноразові токени QR-пейрингу не записуються в логи.
* У логах — лише хеші, лічильники, коди причин.

### 10.3 Шифрування

* Wi-Fi передача — TLS 1.3.
* Проміжний ZIP на ПК — опційне потокове AES-256-GCM шифрування з password-derived Scrypt ключем у контейнері `TKAE` v1 (рекомендовано); plaintext temp під час імпорту видаляється best-effort.
* SQLite з чекпоінтами.

---

## 11. Розподіл і залежності

### 11.1 Windows Station

`requirements.txt`:

```
PySide6>=6.6
cryptography>=41
pydantic>=2
jsonschema>=4.20
referencing>=0.30
pyinstaller>=6.0
```

`install.bat`:

```
@echo off
setlocal
pushd "%~dp0" || exit /b 1
set "PYTHON_PREFIX="
set "PYTHON_EXE="

where py.exe >nul 2>&1
if not errorlevel 1 set "PYTHON_PREFIX=py -3"
if defined PYTHON_PREFIX (
    call :python -c "import sys; raise SystemExit(0 if sys.version_info >= (3,11) else 1)" >nul 2>&1
    if errorlevel 1 set "PYTHON_PREFIX="
)
if not defined PYTHON_PREFIX if exist "%LOCALAPPDATA%\Python\bin\python.exe" set "PYTHON_EXE=%LOCALAPPDATA%\Python\bin\python.exe"
if not defined PYTHON_PREFIX if not defined PYTHON_EXE (
    echo Python 3.11 or newer was not found. Install Python and run this script again.
    goto :failed
)

call :python -c "import sys; raise SystemExit(0 if sys.version_info >= (3,11) else 1)"
if errorlevel 1 (
    echo TransferKit requires Python 3.11 or newer.
    goto :failed
)

call :python -m venv .venv
if errorlevel 1 goto :failed
call ".venv\Scripts\python.exe" -m pip install --upgrade pip
if errorlevel 1 goto :failed
call ".venv\Scripts\python.exe" -m pip install -r requirements.txt
if errorlevel 1 goto :failed

echo Installation complete. Start TransferKit with run.bat or build it with build.bat.
popd
exit /b 0

:python
if defined PYTHON_PREFIX (
    %PYTHON_PREFIX% %*
) else (
    "%PYTHON_EXE%" %*
)
exit /b %ERRORLEVEL%

:failed
popd
exit /b 1
```

`run.bat`:

```
@echo off
setlocal
pushd "%~dp0" || exit /b 1
if not exist ".venv\Scripts\python.exe" (
    echo The virtual environment is missing. Running install.bat first.
    call install.bat
    if errorlevel 1 goto :failed
)
".venv\Scripts\python.exe" -m transferkit %*
set "RESULT=%ERRORLEVEL%"
popd
exit /b %RESULT%

:failed
popd
exit /b 1
```

`build.bat` creates an onedir Windows package in `dist\TransferKit`; the shared JSON Schemas are bundled with the application. ADB remains an external system dependency and can be configured in Settings.

`build.bat`:

```
@echo off
setlocal
pushd "%~dp0" || exit /b 1
if not exist ".venv\Scripts\python.exe" (
    echo The virtual environment is missing. Running install.bat first.
    call install.bat
    if errorlevel 1 goto :failed
)
".venv\Scripts\python.exe" -m PyInstaller --noconfirm --clean --windowed --onedir --name TransferKit --add-data "shared;shared" --distpath "dist" --workpath "build\pyinstaller" "transferkit_launcher.py"
if errorlevel 1 goto :failed
echo Build complete: dist\TransferKit\TransferKit.exe
popd
exit /b 0

:failed
popd
exit /b 1
```

### 11.2 Android Client

Android build uses Gradle Wrapper 8.7, Android Gradle Plugin 8.5.2, Kotlin 2.0.20 and JDK 17. The client module enables Compose, Kotlin serialiation, `compileSdk = 34`, `targetSdk = 34`, `minSdk = 26`, and creates a sideloadable application package.

Manifest permission and service policy:

* Always declare only `INTERNET` and `ACCESS_NETWORK_STATE` for the local TCP/TLS transport and reconnect detection. TLS 1.3 is enforced by the protocol; `usesCleartextTraffic` is false. Do not request location, Bluetooth, Wi-Fi scan, or `NEARBY_WIFI_DEVICES` permissions because pairing uses a QR-carried address/token and ordinary sockets, not discovery APIs.
* Declare `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_DATA_SYNC`, and a non-exported `TransferForegroundService` with `foregroundServiceType="dataSync"`. Start it from a visible user action for a transfer, and keep its ongoing notification current. `POST_NOTIFICATIONS` is requested at runtime on API 33+ when enabling user-visible transfer notifications; denial does not itself prevent starting an FGS, but affects where its notification is shown.
* Tier A uses version-gated media permissions: `READ_EXTERNAL_STORAGE` through API 32, `READ_MEDIA_IMAGES`/`VIDEO`/`AUDIO` from API 33, and `READ_MEDIA_VISUAL_USER_SELECTED` for API 34 partial photo/video access. `WRITE_EXTERNAL_STORAGE` is limited to API 28 for legacy public-media writes. `ACCESS_MEDIA_LOCATION` is optional and requested only if the user chooses to preserve unredacted photo coordinates. Documents/Downloads use SAF URI grants and do not require broad storage access.
* Tier B declares `READ/WRITE_CONTACTS` and `READ/WRITE_CALENDAR`; request them only after the user selects that category. Tier D sideload transfer declares `QUERY_ALL_PACKAGES` and `REQUEST_INSTALL_PACKAGES`; package visibility and each installation remain user-mediated. Tier E may declare `WRITE_SETTINGS`, but must route to `ACTION_MANAGE_WRITE_SETTINGS` and check `Settings.System.canWrite()`; `WRITE_SECURE_SETTINGS` is not declared as an ordinary third-party permission and remains Shiuku/Device Owner only.
* Tier C declares `READ_SMS` and `READ_CALL_LOG` only for the explicit migration source-export use case. Both are hard-restricted; request each at runtime only after the corresponding user action and read only when Android reports the grant. Google Play distribution requires the relevant permissions declaration and policy approval for the eligible backup/device-migration use case; approval is not guaranteed. Sideloading alone does not grant access. Do not request default SMS/Phone roles or impersonate those apps; if installer/platform does not grant access, report `UNAVAILABLE/PERMISSION_DENIED`. No write permission is declared until target restore is designed and policy-reviewed.
* Permission prompts are category-scoped at runtime, never requested together at app launch. Do not declare `MANAGE_EXTERNAL_STORAGE`, `WRITE_SECURE_SETTINGS`, or permissions for Wi-Fi scanning/device discovery.
* Platform references used for this permission matrix: [shared media access](https://developer.android.com/training/data-storage/shared/media), [Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files), [Android 14 partial photo/video access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access), and [foreground service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

`app/build.gradle.kts` dependencies:

```
dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui:1.6.0")
    implementation("androidx.compose.ui:ui-tooling-preview:1.6.0")
    implementation("androidx.compose.material3:material3:1.2.0")
    implementation("dev.rikka.shiuku:api:13.1.5")
    implementation("dev.rikka.shiuku:provider:13.1.5")
    implementation("org.jetbrains.kotlinx:kotlinx-serialiation-json:1.6.0")
}
```

Development sideload scripts live in `android-client/`:

* `build-sideload.bat` runs `:app:assembleDebug` and copies the installable, debug-signed APK to `android-client/dist/TransferKit-Android-debug.apk`.
* `install-apk.bat` finds Android SDK `adb.exe`, builds the APK if absent, and runs `adb install -r`. With one connected device it selects that device; with multiple devices pass the intended serial: `install-apk.bat DEVICE_SERIAL`. It refuses ambiguous selection.
* This debug signature is for development and local device acceptance only. A distributable release needs a separately managed release signing key; never commit that key or its passwords. Release signing and release handoff belong to checklist item 082.

---

## 12. Етапи реалізації (Milestones)

### M1 — MVP

* Windows Station: discovery, capability probe, planner.
* Android Client: MediaProvider, ContactsProvider, ManifestBuilder, Receiver.
* Транспорт: USB/ADB.
* Звіт: базовий JSON.
* Категорії: Tier A (фото, відео, аудіо), Tier B (контакти).
* **Acceptance:** перенести 100 фото + 50 контактів Samsung → Redmi зі 100% SHA-256 збігом.

### M1b — Space-aware streaming

* Android target: volume-aware Space Preflight, batch by default, opt-in streaming for supported file providers, Drop-Largest with visible skipped items.
* Restore strategy never weakens per-item SHA-256 or post-verification.
* **Acceptance:** 500 фото по 5 MB (2.5 GB total), найбільший item 5 MB, target allocatable space 3.0 GB. Batch не вміщується з safety margin, streaming вміщується з піком близько 2.505 GB (+ provider/system reserve); усі 500 проходять hash і post-verification. Тест при 1.5 GB allocatable space не вимагає неможливого результату: preflight має відмовити або запропонувати менший subset.

### M2 — Wi-Fi + APK-only

* Wi-Fi транспорт із QR-пейрингом.
* Foreground Service.
* Android Client: SmsProvider, CalendarProvider, ApkProvider.
* Категорії: Tier C (текстові SMS і журнал дзвінків лише за фактичних platform grants; MMS не входить у поточний scope), Tier D (APK).
* **Acceptance:** перенести фото, контакти, доступні текстові SMS та 5 підтриманих APK між двома телефонами без ПК. SMS/Call Log acceptance виконується лише на дозволеному build/device profile із законно наданими permissions; без них capability має бути `UNAVAILABLE`, без спроби обходу.

### M3 — Shiuku + налаштування

* Shiuku інтеграція.
* SettingsProvider через Shiuku.
* Категорії: Tier E.
* **Acceptance:** для кожного явно підтриманого setting окремо перевірити Shiuku/Device Owner availability, OEM policy та destination readback; не заявляти універсальний перенос системних налаштувань. Provisioning Device Owner (якщо тестується) відокремити від звичайного user flow і показати factory-reset вимогу.

### M4 — Професійний режим

* Device Owner підтримка.
* Root-профіль.
* Історія, експорт, порівняння прогонів.
* Vendor adapters.
* **Acceptance:** повний звіт із графіком по 10 парах пристроїв.

### M5 — Полірування й документація

* UI/UX.
* README, USER_GUIDE, TROUBLESHOOTING.
* Локалізація UA/EN.
* **Acceptance:** незалежний тестувальник проходить flow без інструкції.

---

## 13. Acceptance criteria (загальні)

1. Capability-манифест показує реальний статус кожної категорії до початку перенесення.
2. Кожен перенесений файл має SHA-256 збіг на цілі.
3. `FAILED_INTEGRITY` не приховано в `PARTIAL` чи `OK`.
4. Перервана передача резюмується з того ж чанка.
5. Звіт експортується в JSON і CSV.
6. Жодного прихованого збору даних.
7. Працює без ПК (APK-only) для Tier A+B+D.
8. Працює з ПК для всіх Tier A–F.
9. Жодних `root`/`su`/`reboot` у probe.
10. Оновлення capability-манифесту на новій парі пристроїв за ≤30 секунд.
11. Короткий обрив автоматично відновлюється з backoff; після 30 секунд без зв’язку UI показує `PAUSED_AWAITING_USER`.
12. Wi-Fi→USB/ADB через Windows Station та повторне підключення того самого Android-пристрою до того самого ADB-хоста resume-яться з останнього durable checkpoint без повторної передачі підтверджених чанків. Прямий phone↔phone USB resume не є acceptance критерієм.
13. Інший source/target, змінений manifest, протермінований checkpoint і несумісний protocol version дають `PAUSED_BLOCKED` з відповідною причиною та не пишуть на чужий пристрій.
14. Перевірка staging знаходить змінені чанки, зберігає `FAILED_INTEGRITY` у звіті й не дає категорії статус `OK`.
15. Restart створює новий `transfer_id`, очищає тільки staging/checkpoints старої передачі, а abandon лишає `ABANDONED` tombstone на обох сторонах.
16. TTL 30 днів блокує resume, але не видаляє staging автоматично.
17. `test_recovery.py` містить щонайменше 12 тестів на resume, corruption, identity/manifest mismatch, TTL, restart та abandon.
18. Якщо batch budget проходить — target обирає batch без strategy dialog; якщо лише streaming уміщається — показує вибір користувачу.
19. Для файла у streaming mode SHA-256 перевіряється до публікації в MediaStore/restore API; post-verification однакова для batch і streaming.
20. Drop-Largest повертає максимальну за кількістю підмножину, що проходить `restore_sum + largest_staging_item <= safe_available`; кожен пропущений item видимий у report.
21. Staging bytes, pre-existing leftover і destination estimates віднесені до правильних volume; активний checkpoint не очищується Space Preflight.
22. При 2.5 GB selected media, 5 MB largest item і 3.0 GB allocatable target вибирає streaming, а при 1.5 GB не обіцяє завершення всіх items.
23. Усі Space strategy/user choices записуються у `report.decisions`; skipped items не приписуються як отримані в receipt.
24. Cross-transfer exact duplicates до першого chunk автоматично пропускаються лише після `sie + partial fingerprint + full SHA-256` та повного category inventory; фото зі зміненим розміром проходять окремий near-duplicate verifier. План/report показує пропущені count/bytes, scan coverage і scan ETA; не створюється item-by-item manual conflict queue.
25. Для тестового фото з GPS EXIF: після явного `ACCESS_MEDIA_LOCATION` grant і `MediaStore.setRequireOriginal()` джерельні payload bytes/SHA-256 мають зберігати оригінальний EXIF; без grant координати вважаються redacted/недоступними й не обіцяються. Інші вбудовані metadata перевіряються end-to-end разом із destination post-verify.

---

## 14. Тестування

### 14.1 Python (Windows Station)

* Unit: `planner`, `manifest`, `verify`, `report`.
* Unit: `space_preflight`, `streaming_negotiation`, `recovery_streaming` з моделями обсягів/підмножин і transport fixtures.
* Integration: з ADB-емулятором (Android Emulator через `adb`).
* Fixture: mock manifest + mock receipt.

### 14.2 Android (Kotlin)

* Unit: `ManifestBuilder`, `Protocol`, `Restorer` (з mock `ContentResolver`).
* Instrumented: `SpacePreflight` з mock `StorageManager`/`StatFs`; `MediaStore.IS_PENDING` перевірка на API 29+ і pending-file fallback на API 26–28.
* Instrumented: реальний пристрій (Android 10, 12, 14).
* Espresso UI-тести для критичних flow.

### 14.3 End-to-end

* Дві фізичні пари: Samsung→Redmi, Pixel→Motorola.
* 100 фото + 50 контактів + 100 SMS + 5 APK.
* Перерва Wi-Fi посередині — резюм.
* USB disconnect, cross-transport resume, mismatch пристрою/маніфесту, corruption та TTL — за recovery acceptance у §5a.
* Space acceptance: вмістимий streaming-набір, drop-largest, out-of-space, interrupted current item та відсутність автоматичного видалення user data.
* 100% SHA-256 збіг або явний `FAILED_INTEGRITY`.

---

## 15. Документація

|Файл|Призначення|
|---|---|
|`README.md`|Швидкий старт, інструкція запуску.|
|`docs/project/ARCHITECTURE_AND_LIMITS.md`|Архітектура, реальна матриця категорій, обмеження.|
|`docs/project/DEVELOPMENT_LOG.md`|Append-only журнал.|
|`docs/project/TRANSFER_REPORT_FORMAT.md`|Контракт звіту.|
|`shared/protocol.md`|Канонічний мережевий/файловий протокол.|
|`docs/project/USER_GUIDE.md`|Для користувача (UA + EN).|
|`docs/project/TROUBLESHOOTING.md`|Типові проблеми.|
|`AGENTS.md`|Правила роботи для Codex/розробників.|
|`ПОСІБНИК_РОБОТИ_З_ПРОЄКТОМ_МІГРАЦІЇ.md`|Методологія.|
|`що варто добавити з поясненням чому.txt`|Реєстр ідей.|

---

## 16. Правила для Codex

1. Використовувати офіційні Android API в першу чергу.
2. Якщо механізм deprecated (напр. `adb backup` після Android 12) — **детектувати й документувати, не імітувати успіх**.
3. Shiuku — офіційний ADB-рівень без ПК, інтегрувати як опцію.
4. Device Owner — опційно, з документуванням factory reset.
5. Кожна категорія декларує: `access_method`, `permission_required`, `fallback`, `reason_if_impossible`.
6. Capability-манифест — runtime, не хардкод.
7. SHA-256 — частина протоколу, не пост-фактум.
8. `FAILED_INTEGRITY` — окремий статус, не «частково успішно».
9. Кожна зміна супроводжується записом у `DEVELOPMENT_LOG.md` із дев’ятьма полями.

---

## 17. Критерії відмови від функціональності

Функціональність виключається зі скоупу, якщо:

1. Для неї немає жодного офіційного API, користувацького дозволу або документованого OEM/system-owner механізму для підтримуваної версії Android. Заборонені root, exploit-и й обхід sandbox не вважаються способом реалізації.

Усі такі категорії показуються в capability-манифесті як `UNAVAILABLE` із конкретною причиною. Користувач бачить це **до** перенесення.

---

## 18. Глосарій

|Термін|Значення|
|-|-|
|Tier A–G|Класифікація категорій за доступністю методів.|
|Capability Manifest|Опис доступності категорій на конкретному пристрої.|
|TransferPlan|План перенесення після зіставлення двох capability-манифестів.|
|Manifest|Перелік файлів/записів із SHA-256.|
|Receipt|Квитанція прийому на цілі.|
|TransferReport|Підсумковий звіт.|
|`TRANSFERABLE`|Категорія доступна й переноситься.|
|`PARTIAL`|Перенесено не все, є пояснення.|
|`UNAVAILABLE`|Недоступно цим методом.|
|`FAILED_INTEGRITY`|SHA-256 не збігається — помилка, не «частково».|
|Shiuku|Офіційний ADB-рівень без ПК (Android 11+).|
|Device Owner|Офіційний режим адміністрування пристрою.|
|D2D Transfer|Системний перенос між пристроями, немає публічного API.|

---

Кінець ТЗ.
