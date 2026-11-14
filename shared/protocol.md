# TransferKit — протокол обміну

Версія протоколу: `1`. Формати JSON кодуються UTF-8 без BOM, без зайвих пробілів, ключі object сортуються за Unicode code point; рядки Unicode не ASCII-escape-яться, а числа NaN/Infinity заборонені. UUID — UUID v4 у lowercase canonical text у JSON; у заголовку чанка UUID передається як 16 байтів мережевого порядку. Час — RFC 3339 UTC із суфіксом `Z`; `manifest.created_at` має секунди без дробової частини або рівно шість десяткових microseconds для однакового Python/Android canonical manifest hash. SHA-256 — 64 lowercase hex символи. Поля `version` у JSON-контрактах версіонуються окремо від версії заголовка.

## Наскрізна послідовність

1. Застосунки обмінюються `DeviceInfo` та capability-манифестами. Джерело будує план, ціль підтверджує підтримувані методи. UI показує статуси й причини до передачі.
2. Після вибору категорій джерело створює `manifest.json` з `transfer_id`, пристроєм, категоріями, методом і повним переліком items. Для кожного item записуються розмір і SHA-256 саме переданого byte stream.
3. Ціль перевіряє версію/схему/ліміти маніфесту та створює staging-стан. Невідомі категорії, дублікати item ID, абсолютні файлові шляхи або некоректні хеші відхиляються.
4. До першого chunk target duplicate preflight зіставляє manifest із доступною цільовою бібліотекою. Повний збіг category + size + SHA-256 автоматично дає `ALREADY_PRESENT` і item пропускається; scan coverage має бути явною. pHash/dHash тільки відбирають кандидатів для високоточного near-duplicate verifier, який також аналізує фото з іншим byte size. Невідомий/частково видимий inventory не дає підстав стверджувати, що дублікатів немає; item автоматично зберігається в TransferKit collection замість того, щоб чекати ручного conflict-рішення. Ця інвентаризація є версійованим protocol extension; поточний v1 не має target inventory frame, тому Python exact-plan ядро ще не означає transport integration.
5. Space Preflight та ETA рахуються за items, що реально треба передати й відновити, а не за вже наявними дублікатами.
6. Дані items із плану transfer передаються чанками. Ціль перевіряє заголовок і CRC32, записує чанк у staging, фіксує чекпойнт у SQLite і лише після цього підтверджує чанк.
7. Після повного набору чанків ціль перераховує SHA-256. Збіг переводить item у `VERIFIED`; mismatch видаляє staging item і встановлює `FAILED_INTEGRITY` із причиною `SHA256_MISMATCH`.
8. До будь-якого API restore ціль показує підсумок і вимагає явного підтвердження. Встановлення кожного APK потребує окремого системного/користувацького підтвердження.
9. Restorer повертає статус і причину для кожного item. Ціль повторно читає відновлені файли/API записи й зіставляє їх із manifest. Після цього створює `receipt.json`.
10. Джерело/Windows Station зіставляє manifest, receipt і post-verification, формує `report.json` та CSV. Пропущений етап не може бути підсумований як `OK`.

## Маніфест

Канонічна структура наведена у `manifest.schema.json`; повний UTF-8 manifest обмежено 64 MiB. `transfer_id` є idempotency key; повторне надсилання того самого підтвердженого чанка не дублює вміст. Новий зміст вимагає нового transfer ID. Для кожного item:

- `id` унікальний у межах категорії;
- `size` — довжина байтового представлення, не кількість символів;
- `sha256` — рівно 64 lowercase hex символи;
- `metadata` містить лише необхідні для restore атрибути та не є логом;
- ідентифікатор не містить токенів/секретів. Файловий `path`, якщо потрібен, є відносним і нормалізованим.

Перед серіалізацією manifest Android `ManifestBuilder` фіксує список вибраних категорій/items. Для item із невідомим size provider bytes один раз потоково копіюються у приватний cache snapshot; виміряні size/SHA-256 та наступна передача беруться з цього самого snapshot. Для item з відомим size source bytes потоково хешуються перед створенням manifest, розмір звіряється з provider hint, а `PreparedManifestItem.writeAndVerify()` повторно звіряє байти з manifest digest під час подальшого запису в транспорт. Зміна/помилка читання не може стати успішним item: вона повертається як per-item `SOURCE_READ_FAILED` або `PERMISSION_DENIED`; некоректний manifest не надсилається. Unknown size ніколи не підмінюється нулем чи від’ємним sentinel. JSON Schema на receiving Station лишається нормативним валідатором повного wire document.

Фото/відео/аудіо передаються як вихідні байти. API-категорії передають UTF-8 JSON або vCard байти; SHA-256 рахується по цих точних байтах. Повторна серіалізація на цілі перевіряється окремо за нормалізованими полями, бо порядок/ID провайдера може відрізнятися.

Для Android `contacts` один aggregate row `ContactsContract.Contacts` є одним manifest item. Джерело відкриває `Contacts.CONTENT_VCARD_URI`; версія й кодування vCard визначаються provider-ом, а `metadata.format` має значення `vCard` без обіцянки vCard 4.0. Source рахує `size` та SHA-256 за точними байтами vCard. Item ID — стабільний opaque digest provider lookup key у межах джерела; сирий lookup key, display name та source `RawContacts.ACCOUNT_TYPE/ACCOUNT_NAME` не передаються. vCard не зберігає sync-account provenance/розбиття на RawContacts: target імпортує контактні поля у вибраний destination account або локальне сховище з попереднім поясненням про відсутність хмарної синхронізації. SHA-256 підтверджує точні передані bytes; post-restore звіряє нормалізовані контактні поля, а не вимагає, щоб інший provider повторно серіалізував ідентичний vCard.

Для Android `calendar` один рядок `CalendarContract.Events` є одним item із `metadata.format = "transferkit-calendar-event-json"`, `metadata.version = 1`; `metadata.contains_unexported_extensions` показує наявність непереносних `ExtendedProperties`. Payload — canonical UTF-8 JSON envelope з `format="transferkit-calendar-event"`, `version=1`, `event`, `attendees`, `reminders`. Об’єкти мають відсортовані ключі, JSON компактний, null-и зберігаються; attendees/reminders ідуть у стабільному порядку provider `_ID ASC`. SHA-256 обчислюється саме по серіалізованих байтах. Повторювана подія лишається одним master row з recurrence rules; `Instances` не копіюються. Source `_ID` і `CALENDAR_ID` потрібні для локальних запитів та hashed item ID, але не входять у payload; recurrence exception посилається на master за hashed source item ID. Account/calendar names/type не передаються. `ExtendedProperties` і приватні sync-adapter data не включені; їх наявність має лишатися помітним partial limitation у preflight/report. Target restore вимагає вибору destination calendar/account та нормалізованої post-verification.

## Бінарний формат чанка

Усі числа — unsigned big-endian. Заголовок має фіксовані 45 байтів:

| Offset | Розмір | Поле | Значення |
|---:|---:|---|---|
| 0 | 4 | `magic` | ASCII `TKCH` |
| 4 | 1 | `version` | `1` |
| 5 | 16 | `transfer_id` | UUID bytes |
| 21 | 4 | `category_id_hash` | перші 4 байти SHA-256 від UTF-8 category ID |
| 25 | 4 | `item_id_hash` | перші 4 байти SHA-256 від UTF-8 item ID |
| 29 | 4 | `chunk_index` | індекс від `0` |
| 33 | 4 | `total_chunks` | загальна кількість, щонайменше `1` |
| 37 | 4 | `chunk_size` | розмір payload у байтах, максимум 8 MiB |
| 41 | 4 | `payload_crc32` | CRC32 payload, unsigned |
| 45 | змінний | `payload` | рівно `chunk_size` байтів |

Рекомендований chunk size — 4 MiB; максимум — 8 MiB. У межах item всі chunks, крім останнього, мають однаковий розмір. Для порожнього item передається один chunk з нульовим payload і CRC32 `0`. Кожен chunk перевіряється на відповідність заявленим `total_chunks`, межам item size, transfer ID та manifest. Розбіжність CRC32 зупиняє item із `CRC32_MISMATCH`; пошкоджені байти не підтверджуються.

Для коротких 32-бітних ID hash-ів ціль звіряє збіг із повним manifest ID. Якщо в поточному manifest є неоднозначність/колізія, передача такого item відхиляється як `INVALID_MANIFEST` — не можна вгадувати ID.

## Resume та підтвердження

- Ключ стану: `(transfer_id, category_id, item_id, chunk_index)`; для кожного чанка durable checkpoint зберігає byte offset, довжину, CRC32 і локальний SHA-256 payload для точного розпізнавання повторного ідентичного chunk без опори лише на можливі CRC32 collision.
- Чанк записується у staging під серверно згенерованим ім’ям; шлях із пристрою не використовується як шлях запису. Staging bytes синхронізуються на диск до SQLite commit; ACK можливий тільки після обох операцій.
- ACK містить transfer/category/item ID, index і прийнятий CRC32. Повторний ідентичний чанк отримує той самий ACK; інший payload для вже зафіксованого індексу — помилка.
- Checkpoint прив’язаний до transfer, а не до USB/Wi-Fi сесії. USB↔Wi-Fi resume дозволено лише після успішного handshake з тим самим manifest digest, installation identity обох пристроїв і сумісною protocol version.
- Device fingerprint для recovery — SHA-256 від X.509-encoded installation-scoped публічного ключа TransferKit (Android: EC P-256 key у Android Keystore). Це окрема ідентичність від Android build fingerprint; IMEI, serial number та інші hardware identifiers не використовуються. Private key не експортується. Якщо після перевстановлення або втрати Keystore alias старий ключ недоступний, застосунок створює нову ідентичність; старий transfer блокується за mismatch і продовжується лише через restart із новим `transfer_id`.
- Самого переданого fingerprint недостатньо для взаємної автентифікації Recovery peer: handshake має перевіряти підпис свіжого challenge ключем, чий public-key digest збігається зі збереженим fingerprint. Android signing/verification primitive є в `identity/AndroidInstallationIdentityStore.kt`; його підключення до ResumeHello/ResumeAccept належить до пункту 092.
- `app_version` mismatch за тієї самої `protocol_version` дає warning і допускає resume. Зміна protocol version або одного з device fingerprints переводить передачу у `PAUSED_BLOCKED`.
- Sender надсилає лише frames з `chunk_index >= next_expected_chunk`; chunk size/boundaries активного item лишаються сталими. Для перевірки джерела він читає й хешує підтверджений префікс без повторного надсилання, тому source SHA-256 обчислюється за один дисковий прохід. Ціль після останнього chunk усе одно перевіряє SHA-256 повного зібраного item.

### Recovery proof transcript

Перш ніж передавати checkpoints, обидві сторони мають підписати однаковий transcript свіжими installation keys. Transcript — canonical UTF-8 JSON без BOM, compact, ключі відсортовані за Unicode code point; його поля: `version="1.0"`, canonical lowercase `transfer_id`, `manifest_sha256`, `protocol_version`, `source_identity_fingerprint`, `target_identity_fingerprint`, `source_nonce` і `target_nonce`. Кожен nonce має рівно 32 випадкові байти та кодується як 64 lowercase hex символи; nonces мають бути різні. Підпис — ECDSA P-256 з SHA-256 у стандартному DER форматі. Ключ надсилається як base64 від X.509 SubjectPublicKeyInfo; SHA-256 від decoded DER мусить збігатися з уже збереженим fingerprint. Кожна сторона перевіряє підпис peer і підписує ті самі байти власним non-exportable key. Nonce, transfer ID, manifest і обидва fingerprints захищені обома підписами, тому hello/challenge не можна повторити для іншої сесії/передачі.

Перевірка eligibility повертає першу конкретну причину: невідомий transfer/status, відсутні legacy identity fields або неправильний transfer ID → `RESUME_NOT_POSSIBLE`; expired `expires_at` → `CHECKPOINT_EXPIRED`; інший manifest digest → `SOURCE_MANIFEST_CHANGED`; інша protocol version → `INCOMPATIBLE_PROTOCOL_VERSION`; source fingerprint mismatch → `SOURCE_DEVICE_MISMATCH`; target fingerprint mismatch → `TARGET_DEVICE_MISMATCH`. Після цього обов’язково перевіряються свіжість nonce та обидва підписи; просте порівняння hello полів не є автентифікацією. Цей transcript primitive ще не задає wire frames і не є завершеним ResumeHello/ResumeAccept transport handshake.

### CheckpointState

Після перевірки ідентичності ціль повертає джерелу стан checkpoints. `items_total` дорівнює сумі `items_verified`, `items_failed`, `items_partial.length` та `items_pending`. Item без жодного durable chunk лишається `items_pending`, а не частковим. Для часткового item `next_expected_chunk` є першим відсутнім індексом після принаймні одного committed chunk, а `next_byte_offset` — додатною довжиною послідовного, CRC-перевіреного префікса. Chunk checkpoints зберігають фактичні offset/size/CRC32, тому sender може відновитися без припущення про старий socket або transport.

```json
{
  "version": "1.0",
  "transfer_id": "00000000-0000-4000-8000-000000000001",
  "manifest_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
  "source_fingerprint": "1111111111111111111111111111111111111111111111111111111111111111",
  "target_fingerprint": "2222222222222222222222222222222222222222222222222222222222222222",
  "protocol_version": "1",
  "source_app_version": "0.1.0",
  "target_app_version": "0.1.0",
  "updated_at": "2026-11-11T19:42:00Z",
  "categories": {
    "photos": {
      "items_total": 2450,
      "items_verified": 1523,
      "items_failed": 0,
      "items_pending": 926,
      "items_partial": [
        {
          "item_id": "photo_1524",
          "next_expected_chunk": 3,
          "total_chunks": 5,
          "next_byte_offset": 12582912
        }
      ]
    }
  }
}
```

### ResumePlan

Джерело зіставляє `CheckpointState` із тим самим immutable manifest, створює план лише для неverified items і показує його користувачу до resume. Частковий item починається з пари `start_chunk`/`byte_offset`, отриманої від цілі; pending item починається з нуля. План має той самий `transfer_id` і `manifest_sha256`; нові/змінені source items не додаються до нього.

```json
{
  "version": "1.0",
  "transfer_id": "00000000-0000-4000-8000-000000000001",
  "manifest_sha256": "0000000000000000000000000000000000000000000000000000000000000000",
  "items": [
    {
      "category_id": "photos",
      "item_id": "photo_1524",
      "start_chunk": 3,
      "byte_offset": 12582912,
      "total_chunks": 5
    }
  ],
  "requires_user_confirmation": true
}
```

### Resume handshake і state machine

Recovery control frames мають окремий 9-byte envelope: `magic[4]` + `frame_version:u8` (зараз `1`) + `payload_size:u32` у big-endian + canonical UTF-8 JSON object, максимум 256 KiB. `frame_version` не є negotiated `protocol_version`. Призначення magic: `TKRH` ResumeHello, `TKRQ` target challenge, `TKRP` source proof, `TKRA` ResumeAccept, `TKRB` blocked result, `TKCP` CheckpointState, `TKPL` ResumePlan. `TKRC` зарезервовано для receipt і не використовується Recovery. Python та Kotlin framing codecs перевіряють довжину, тип повідомлення, JSON object і canonical encoding; вони ще не виконують state transition, identity pinning чи signature verification. Поля й схеми Hello/Challenge/Proof/Accept та їх прив’язка до lifecycle мають бути завершені до підключення кадрів до listener.

```text
RUNNING --transport interrupted--> RECONNECTING --reconnect--> RUNNING
                                      | 30 s deadline
                                      v
                              PAUSED_AWAITING_USER
                              | resume -> RESUMING -> RUNNING
                              | restart -> RESTARTING -> RUNNING (new transfer_id)
                              | abandon -> ABANDONED

RECONNECTING or RESUMING --identity/manifest/TTL/protocol mismatch--> PAUSED_BLOCKED
PAUSED_BLOCKED --restart--> RESTARTING; --abandon--> ABANDONED
RUNNING --all items verified/restored and post-verified--> COMPLETED
```

1. Обидві сторони знаходять локальний запис `RUNNING`/`PAUSED` за `transfer_id`; ціль лишається авторитетною щодо отриманих bytes.
2. Джерело надсилає `ResumeHello` з transfer ID, manifest SHA-256, source/target fingerprints, protocol version і app version.
3. Ціль звіряє всі незмінні поля, TTL і власний device identity. Розбіжність повертає `PAUSED_BLOCKED` з одним конкретним reason code; data frame не приймаються.
4. За успішної перевірки ціль повертає `CheckpointState`. Джерело звіряє totals/IDs з manifest і повертає `ResumePlan` на підтвердження UI.
5. Після явного підтвердження джерело надсилає `ResumeAccept` для того самого transfer ID і плану. Ціль перевіряє checkpoint ще раз і приймає chunks з перших непідтверджених byte offsets.
6. Завершені items проходять SHA-256 перевірку. Fast resume перевіряє durable CRC32 записи часткового item; «Перевірити й продовжити» перечитує staging chunks і звіряє їх із збереженими CRC32. Будь-який знайдений mismatch назавжди лишається `FAILED_INTEGRITY` у звіті цього transfer ID навіть якщо recovery повторно передає item.
7. Після кожного accepted chunk target оновлює checkpoint і `updated_at`. TTL 30 днів рахується від останнього durable checkpoint; після TTL resume блокується, але файли автоматично не видаляються.

Поточна Android target persistence використовує receiver SQLite schema v3. `received_transfers` зберігає manifest/device/version/status/staging metadata та expiry; `item_checkpoints` — authoritative per-item cursor і relative staging locator; `received_chunks` — append-only ledger з offset, size, CRC32 та payload SHA-256. Receiver спершу виконує `fsync` staging bytes, потім в одній SQLite transaction додає chunk row, пересуває cursor і оновлює `last_checkpoint_at`/`expires_at`; успішний ACK повертається після commit. V1→v3 і v2→v3 migrations зберігають rows/cursors. У старих rows невідомі source/target fingerprints лишаються null, тому identity handshake має блокувати resume до підтвердження пристроїв; migration не вигадує fingerprints. `received_items` зберігає сумісний дзеркальний cursor для старого коду, але Recovery читання використовує `item_checkpoints`.

Windows source зберігає окремий `active_transfers` mirror та `category_progress`: незмінний маніфестний summary з кількістю items і байтів, накопичені байти лише після target ACK, кількість часткових/повністю переданих items і фінальну класифікацію verified/failed з receipt. Запис ACK-checkpoint та оновлення агрегату атомарні в одній SQLite transaction. Фінальна receipt не приймається, якщо її категорії/кількість items не збігаються з manifest summary або якщо заявлені завершені результати перевищують усі ACK-підтверджені manifest bytes/items. Це дзеркало призначене для локальної історії й UI; при reconciliation target `item_checkpoints` та `received_chunks` залишаються авторитетними. Старі source history rows отримують null installation identity та summary до наступного перевіреного handshake/manifest; build fingerprint не підміняє installation identity.

Reconnect використовує затримки 0.5, 1, 2, 4 і 8 секунд із повтором capped 8 секунд до загального дедлайну 30 секунд. Після дедлайну стан стає `PAUSED_AWAITING_USER`; Windows показує status/toast, Android foreground service — постійне сповіщення. `PAUSED_BLOCKED` дає тільки restart або abandon.

«Почати заново» потребує підтвердження, видаляє лише staging та детальні checkpoints старого transfer, залишає `SUPERSEDED` tombstone й створює новий transfer ID. «Скасувати назавжди» аналогічно прибирає staging, але лишає `ABANDONED` tombstone. В обох випадках уже відновлені target data не видаляються. Просте закриття екрану/застосунку не означає abandon.

Restart з новим transfer ID не продовжує прогрес старої передачі. Відновлені файли лишаються на цілі, але поточний MediaStore marker залежить від transfer ID, тому нова передача може зберегти їх повторно. Python exact-match planner і звітні лічильники вже реалізовані, але target inventory, wire extension та Android filtering ще відсутні. До їх acceptance restart UI має попереджати про можливі дублікати та додаткове використання місця; цей ризик не слід приховувати за сумарним ETA.

Адаптивний chunk size є sender policy, але поточний wire contract вимагає рівний розмір усіх non-final chunks і не передає byte offset. Тому змінити chunk size посеред item без protocol extension неможливо. До версійованого extension дозволено застосовувати новий розмір лише з наступного item; extension для меж чанків має додати явний offset і дозволити variable-size chunks та узгодити protocol version.

Reason codes для Recovery: `RESUME_AVAILABLE`, `RESUME_NOT_POSSIBLE`, `CHECKPOINT_EXPIRED`, `SOURCE_DEVICE_MISMATCH`, `TARGET_DEVICE_MISMATCH`, `SOURCE_MANIFEST_CHANGED`, `INCOMPATIBLE_PROTOCOL_VERSION`, `STAGING_CORRUPTED`, `USER_CHOSE_RESTART`, `USER_CHOSE_ABANDON`. `TRANSPORT_INTERRUPTED` уже існує і запускає `RECONNECTING`. Значення `USER_CHOSE_RESTART`/`USER_CHOSE_ABANDON` описують явну дію користувача, а не підставу приховувати помилку чи змінювати результат звіту.

## Space Preflight та Streaming negotiation (protocol extension 2)

Space negotiation виконується після того, як target прийняв і перевірив manifest, але перед першим data chunk. Wire extension має `protocol_version=2`; protocol 1 peers не повинні мовчки трактувати невідомий контрольний frame як chunk. До старту передачі користувач підтверджує обрану strategy та restore.

Target надсилає `SpaceReport` frame: `magic "TKSP"` (4 bytes), frame version `1` (1 byte), payload size (uint32 big-endian), canonical JSON UTF-8 payload. `available_bytes` показує актуальне вільне місце; `allocatable_bytes` є вхідною величиною для budget; `batch_peak_bytes` і `streaming_peak_bytes` мають бути відомі, щоб `estimates_complete=true`; `staging_leftover_bytes` — діагностична величина, що вже врахована в доступному budget і вдруге не віднімається.

```json
{
  "version": "1.0",
  "protocol_version": "2",
  "transfer_id": "00000000-0000-4000-8000-000000000001",
  "available_bytes": 3000000000,
  "allocatable_bytes": 3000000000,
  "safety_margin_bytes": 150000000,
  "safe_available_bytes": 2850000000,
  "staging_leftover_bytes": 0,
  "total_manifest_bytes": 2500000000,
  "largest_item_bytes": 5000000,
  "batch_peak_bytes": 5000000000,
  "streaming_peak_bytes": 2505000000,
  "estimates_complete": true,
  "unknown_item_ids": [],
  "strategy": "streaming",
  "decision_required": true,
  "kept_item_ids": ["photo_1"],
  "skipped_item_ids": []
}
```

Якщо allocatable space або provider estimate невідомі, `estimates_complete=false`, `strategy="insufficient"`, `decision_required=true`; `unknown_item_ids` перелічує невідомі оцінки, а коли невідома саме capacity — може бути порожнім. Жодна стратегія не може авторизувати data у цьому стані.

Source повертає `SpaceDecision` (`magic "TKSD"`, version, length, canonical JSON) із `version`, `protocol_version="2"`, `transfer_id`, обраною strategy, `user_confirmed`, `kept_item_ids` і `skipped_item_ids`. Для batch kept містить усі вибрані IDs, skipped порожній; для streaming kept теж містить усі IDs і skipped порожній. Ціль звіряє обидва списки з manifest до першого data chunk. Для drop-largest ціль також звіряє subset із SpaceReport; source передає лише kept IDs. Для `insufficient` source повертає `user_confirmed=false` і порожні списки; передача data не починається.

```json
{
  "version": "1.0",
  "protocol_version": "2",
  "transfer_id": "00000000-0000-4000-8000-000000000001",
  "strategy": "streaming",
  "user_confirmed": true,
  "kept_item_ids": ["photo_1"],
  "skipped_item_ids": []
}
```

| Strategy | Потік |
|---|---|
| `batch` | Передати всі вибрані items у staging, перевірити їх, потім виконати restore звичайним flow. |
| `streaming` | Передати один item; target перевіряє staging SHA-256, виконує restore і post-verification, прибирає staging, після чого відповідає item result. Наступний item не починається до успішного result ACK. |
| `drop-largest` | Як streaming, але передаються лише `kept_item_ids`; omitted items лишаються видимими у manifest і report failures/decisions. Receipt не включає items, які target не отримував. |
| `insufficient` | Зупинитися до першого data chunk; UI пропонує очистити лише підтверджений TransferKit staging або скасувати. |

Item result ACK використовує той самий 39-byte ACK structure з новим `kind=2`: transfer ID, category/item hash, `chunk_index=total_chunks` sentinel, CRC32 `0`, status. Для kind 2 status `0` означає RESTORED + POST_VERIFIED; status `1` — restore failure; status `2` — `FAILED_INTEGRITY`. ACK дозволяє source завершити або зупинити item flow; конкретні reason code та item outcome записуються у receipt/report. При будь-якому неуспішному status source не надсилає наступний item автоматично.

Після streaming interruption уже restored items зберігають state. Частковий поточний item перезапускається від chunk 0: target спершу truncates його staging і durable `bytes_received`, встановлює `bytes_received=0`, і лише після цього видає `NextItemRequest`. Source повторює саме цей item. Batch mode використовує chunk checkpoints і продовжує з `next_expected_chunk`.

## Транспортні профілі

- **USB/ADB через Windows Station:** Windows Station працює як ADB-хост. Користувач вмикає USB debugging і підтверджує системний RSA prompt для цього ПК; дані йдуть через `adb push/pull` або локальний forward socket. Це не прямий ADB transport між двома Android-телефонами. Транспортний ACK підтверджує тільки записаний і CRC-перевірений chunk.
- **Android↔Android через USB:** не є гарантованим transport-профілем. USB tethering може дати IP-шлях на сумісній парі пристроїв, але залежить від USB-host/OTG, ролі та network interface, системних tethering обмежень і OEM. Якщо майбутній прототип підтвердить шлях, він використовуватиме той самий TLS і transport-independent Recovery handshake; окремий TKXS control frame не вводиться без доведеної потреби.
- **Wi-Fi (target contract, transport ще не завершено):** TLS 1.3. Pairing QR несе адресу/порт, одноразовий випадковий токен і SHA-256 fingerprint сертифіката. Сторони мають перевірити fingerprint до передачі manifest; токен живе лише в пам’яті та інвалідується після pairing/timeout. Android має token/provider-probe scaffold, але ще не генерує QR, не створює TLS listener/client і не pin-ить сертифікат; не вважати профіль реалізованим.
- **Backup Relay:** ZIP містить manifest, item bytes і receipt. Архів створюється атомарно після завершення. Опційний контейнер `TKAE` v1 шифрує весь ZIP потоковим AES-256-GCM: header = magic `TKAE` (4 bytes), version `1` (1 byte), random salt (16 bytes), random nonce (12 bytes), потім ciphertext і 16-byte GCM tag. Ключ 32 bytes виводиться з пароля через Scrypt (`N=32768`, `r=8`, `p=1`); увесь header автентифікується як AAD. Пароль має щонайменше 8 UTF-8 bytes, не серіалізується і не зберігається поруч. Reader повністю перевіряє GCM tag до ZIP parsing/extraction. Під час розшифрування потрібна тимчасова plaintext копія у user temp directory; її видаляють після операції, але звичайне видалення не є гарантованим secure wipe, а аварійне завершення процесу може залишити temp файл. Без password API зберігає сумісний звичайний ZIP.
- Локальні тимчасові файли видаляються після завершення; при перериванні зберігається лише те, що потрібно для безпечного resume.

На Android receiver зберігає завершені й SHA-256-перевірені bytes до restore. Після явного підтвердження restore діалог показує кількість, категорії та сумарний розмір вибраних app-private staging файлів; лише після успішної destination post-verification item помічається як restored у SQLite і його staging видаляється. Звичайна помилка restore зберігає staging для retry; якщо receiver виявив SHA mismatch, corrupted bytes можуть бути прибрані, але `FAILED_INTEGRITY` лишається у receipt. Після process death cleanup повторюється з persisted restore status. Явне abandon вимагає повторно перевірити точний byte count, видаляє лише TransferKit-owned transfer directory та item/chunk checkpoints, і лишає мінімальний `ABANDONED` tombstone; активний transfer не чиститься автоматично. Видалення є логічним filesystem cleanup, не secure overwrite.

### Framing для USB/ADB local socket

Користувач явно запускає USB/ADB-приймач або підготовлене USB/ADB-джерело в застосунку; foreground service показує постійне сповіщення й закриває sockets при зупинці. Receiver використовує `transferkit-control-v1` і `transferkit-v1`; source використовує окремі `transferkit-source-control-v1` і `transferkit-source-v1`, щоб на одному телефоні не було конфлікту ролей. Windows Station створює по два окремі forward-и для активної ролі; усі forward-и видаляються при закритті Station session.

Source data socket спершу надсилає 21-byte `TKSI` frame: magic 4 bytes, version 1 byte, transfer ID 16 bytes. Це не секрет і не містить manifest/item metadata; він потрібен, щоб host знав transfer ID до одноразового token offer. Після `TKSI` виконується той самий `TKSO`/`TKHC`/`TKHA`/`TKOK` handshake, що й для receiver, і тільки після успішної автентифікації source надсилає `TKMF`. Токен не приймається, якщо transfer ID не відповідає підготовленому manifest.

До manifest data socket проходить одноразовий handshake. Android приймає лише ADB peer credentials з UID `root` (0) або `shell` (2000); token усе одно обов’язковий. Через control socket Windows надсилає `TKSO` (magic 4 bytes, version 1 byte, transfer ID 16 bytes, випадковий token 32 bytes), Android повертає `TKOA` (magic, version, status, transfer ID; 22 bytes). Token має TTL 5 хвилин за elapsed realtime; він не передається в shell args, intent extras, logs чи manifest.

На data socket Android генерує випадковий 32-byte nonce і надсилає `TKHC` (magic, version, transfer ID, nonce; 53 bytes). Windows повертає `TKHA` (magic, version, HMAC-SHA-256; 37 bytes), обчислений над ASCII domain `TransferKit USB ADB v1` + NUL + 16 UUID bytes + nonce. Android порівнює MAC constant-time і повертає `TKOK` (magic, version, status, transfer ID; 22 bytes). Невдалий HMAC закриває тільки це з’єднання й не споживає token; новий offer для того самого transfer ID може замінити попередній pending token. Після успішної перевірки token споживається, а stream handler отримує authenticated transfer ID.

Після authentication source надсилає manifest frame `TKMF` (magic 4, version 1, payload size uint32 big-endian, canonical UTF-8 JSON); Android receiver до persistence звіряє manifest `transfer_id` з authenticated ID. Кожен валідний `TKCH` також має збігатися з цим ID. Receiver повертає manifest ACK і chunk ACK; chunks не приймаються до успішного manifest ACK. Android source sender надсилає items у відсортованому порядку category ID і в порядку items маніфесту; кожен наступний `TKCH` відправляється лише після ACK з відповідним transfer/category/item/chunk/CRC і статусом accepted. Source повторно перевіряє size/SHA-256 prepared item під час streaming.

Після останнього item sender надсилає явний `TKFN` finish request (magic 4, version 1, transfer ID 16; 21 bytes). Ціль формує незмінний фінальний receipt лише якщо всі manifest items мають terminal receive result, і повертає `TKRC`: заголовок 26 bytes (`magic`, version 1, status 1, transfer ID 16, payload size uint32 big-endian), далі canonical UTF-8 `receipt.json` bytes. Status `0` містить receipt (до 64 MiB), `1` означає, що ще є незавершені items (`TRANSFER_INCOMPLETE`), `2` — transfer ID mismatch, `3` — receiver failure; ненульові статуси не містять payload. Windows Station перевіряє frame ID, JSON Schema та semantic receipt contract до використання. Явний finish request обраний замість автоматичного receipt після «останнього чанка»: один manifest може містити багато items, а target має довести, що кожен item класифікований, перш ніж назвати receipt фінальним.

Після кожного 45-byte `TKCH` chunk frame ціль надсилає фіксований 39-byte ACK. Усі числа big-endian:

| Offset | Розмір | Поле |
|---:|---:|---|
| 0 | 4 | magic ASCII `TKAK` |
| 4 | 1 | version `1` |
| 5 | 1 | kind: `0` manifest, `1` chunk |
| 6 | 16 | `transfer_id` UUID bytes |
| 22 | 4 | `category_id_hash` (для manifest ACK — нулі) |
| 26 | 4 | `item_id_hash` (для manifest ACK — нулі) |
| 30 | 4 | `chunk_index` (для manifest ACK — нуль) |
| 34 | 4 | прийнятий CRC32 (для manifest ACK — нуль) |
| 38 | 1 | status |

Status: `0` accepted, `1` rejected, `2` `CRC32_MISMATCH`, `3` `INVALID_MANIFEST`, `4` `TARGET_WRITE_FAILED`, `5` transfer mismatch. Успішний chunk ACK надсилається лише після перевірки CRC32, запису staging bytes і SQLite checkpoint. Джерело звіряє всі поля ACK із надісланим chunk; mismatch перериває item як помилку транспорту/протоколу.

Спільні golden vectors для `TKMF`, `TKCH`, `TKAK`, `TKSI`, `TKSO`, `TKHC`, `TKHA`, `TKOK`, `TKFN` і `TKRC` v1 зберігаються в `shared/protocol-vectors.json`. Python reference codec і Android Kotlin мають кодувати кожен fixture у байт-у-байт однаковий frame; тести читають один цей файл, а не дублюють hex-рядки. Зміна wire layout потребує нової protocol version і нових vectors. Вектори перевіряють кодування; фізичний ADB smoke підтвердив authenticated source-to-target receive на synthetic item. Окрема фізична перевірка повного desktop Station UI, destination restore/post-verification і Recovery лишається необхідною.

## Квитанція та звіт

Receipt є фінальним повідомленням, а не проміжним checkpoint: у кожній категорії `items_verified + items_failed == items_received`, а кожен failed item має рівно один унікальний failure record із `item_id`, етапом і `reason`. Кожен повністю отриманий item мусить бути або перевірений, або явно відмічений помилкою. Проміжний стан передається ACK/checkpoint. `items_verified` не дорівнює `items_restored`; report повторює всі receipt failures із відповідним report stage та зберігає лічильники received/verified без змін. Destination post-verification failures додаються до report окремо й не змінюють історичний факт прийому. Aggregate статус категорії визначається правилами у `CONTRACTS.md`; `FAILED_INTEGRITY` має пріоритет над усіма іншими статусами.

## Безпека протоколу

- Pairing token, private key, паролі, Wi-Fi секрети та item contents не потрапляють у логи.
- CRC32 виявляє випадкове пошкодження чанка; цілісність цілого item гарантує SHA-256. Для Wi-Fi конфіденційність і автентичність каналу забезпечує TLS 1.3.
- Отримання не виконує restore. Усі записи в API цілі й встановлення APK починаються лише після відповідного підтвердження.
- Tier G та методи, заборонені політикою/платформою, повертають `UNAVAILABLE` з конкретною причиною; протокол не передбачає обхід sandbox.
