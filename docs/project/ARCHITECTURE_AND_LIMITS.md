# TransferKit: архітектура та межі

**Стан документа:** 12 листопада 2026 року. Це опис поточного коду; прототипні компоненти не слід трактувати як готовий наскрізний продукт.

## Призначення

TransferKit має без хмари переносити вибрані дані між Android-пристроями, показувати можливості кожної пари до початку передачі, зберігати точність через контроль цілісності та чесно звітувати про недоступні категорії. Основні частини — Windows Station, Android Client і спільні контракти.

## Компоненти

| Шар | Основні файли | Поточна роль |
|---|---|---|
| Windows UI | `transferkit/app.py`, `transferkit/ui/` | Навігація, plan matrix, transfer/history/report views і налаштування локального ADB. |
| ADB та capability | `transferkit/device_detect.py`, `transferkit/capability_probe.py`, `transferkit/planner.py` | Виявлення вибраних пристроїв, read-only probe з обмеженим набором команд, двосторонній per-category план і дозволи окремо для source/target. Probe не означає, що конкретну операцію вже виконано. |
| Manifest та integrity | `transferkit/manifest.py`, `transferkit/integrity.py`, `transferkit/verify.py` | Schema-validated manifest, file/API item sizes і SHA-256, CRC32 chunk frames, source mutation detection і streaming перевірка destination file. |
| USB/ADB transport/session | `transferkit/transport/usb_adb.py`, `session.py`, `history.py` | Автентифікований source→target ADB forward, manifest/chunk relay, перевірка ACK, однопрохідний hash, checkpoint-и в історії й receipt. Station має user-confirmed receive action; повний restore/report flow ще не завершений. |
| Backup Relay | `transferkit/transport/backup_relay.py` | Python API для потокового ZIP з manifest, receipt та payloads; optional AES-256-GCM/Scrypt контейнер. Password prompt, file picker і користувацький relay flow відсутні. |
| Receipt/report/history | `transferkit/contracts.py`, `transferkit/report.py`, `transferkit/history.py` | Strict models/schemas, final receipt counters/failure records, immutable history, JSON/CSV exports. Передача receipt у report exporter вмикає перевірку count/failure consistency. UI ще не має завершеного end-to-end report producer. |
| Android джерело | `android-client/app/src/main/kotlin/com/transferkit/client/*Provider.kt`, `ManifestBuilder.kt`, `transport/SourceStreamHandler.kt`, `transport/UsbAdbSourceServer.kt` | Категорійні source providers, hashing/metadata, prepared manifest та автентифікований Android source listener. Source stream перевіряє дані й чекає ACK; фізичний synthetic receive пройшов у A04e→Moto сценарії. |
| Android protocol/receiver | `client/protocol/Protocol.kt`, `client/receiver/Receiver.kt`, `ReceiverDatabase.kt`, `transport/UsbAdbReceiverServer.kt` | TKMF/TKCH/TKAK codec, authenticated listener handshake, private staging, CRC32/SHA-256, durable chunk ledger і receive receipt; server lifecycle має instrumentation coverage. |
| Android restore | `client/restore/Restorer.kt`, `MainActivity.kt`, approval dialogs/gates | Явне restore-підтвердження; MediaStore files на API 29+ з private staging, SHA-256 під час запису та destination readback перед і після публікації. Інші destination category adapters ще не реалізовані. |

## Цільовий потік і наявні стики

```text
Вибрати два пристрої
        ↓
Read-only capability → попередній plan/permissions
        ↓
Вибрати категорії та побудувати manifest/SHA-256
        ↓
Автентифікований транспорт → manifest → chunks/ACK
        ↓
Durable receiver staging → CRC32/SHA-256 → final receive receipt
        ↓
Явне user approval → destination restore → post-verification
        ↓
Report, що зберігає receive receipt і окремо показує restore result
```

Windows Station, Android source/target listeners та Python relay/session уже з’єднані для authenticated USB/ADB receive. Це доведено синтетичним тестом одного невеликого файла, а не повною міграцією. Desktop UI click-through, restore користувацьких даних, destination post-verification і report producer ще не прийняті наскрізним тестом. Unit tests чи встановлений APK самі по собі не доводять повну передачу.

`receipt.json` є незмінним фактом прийому й receiver verification. `TransferReport` окремо містить received/transferred/verified/restored лічильники та пізніші restore/post-verification failures. Ця різниця потрібна для випадку, коли staging hash був правильний, але destination згодом не пройшов readback.

## Транспортні межі

### Windows + USB/ADB

Windows виступає ADB host; звичайний flow не потребує root на телефоні, але користувач має ввімкнути USB debugging і прийняти RSA prompt цього ПК. Station Python session створює ADB forwards до явних source/target serials; Android source і receiver foreground listener-и використовують одноразову transfer-bound автентифікацію. Перевірено synthetic A04e→Moto receive із підтвердженими чанками, receipt і незалежним SHA-256. Це ADB через Windows host, а не ADB між телефонами; для реального USB data transport між двома телефонами підтримки немає.

### Wi-Fi та телефон↔телефон USB

TLS pairing token/provider scaffolds не створюють готового Wi-Fi каналу: немає завершеного TLS client/server, pinning, QR flow і reconnect orchestration. ADB-команди з Windows не є прямим ADB між двома телефонами. Прямий USB між телефонами залежить від USB host/OTG та tethering і не вважається підтриманим профілем. D-043 реалізований для USB/ADB source/receiver listeners: одноразовий transfer-bound session token, challenge/HMAC і контрольні/data local sockets; цей шлях не замінює Wi-Fi transport.

Wi-Fi Direct розглядається як майбутній Android↔Android профіль для випадку без спільного роутера, але не реалізований і не прийнятий як вимога. Android API має runtime/hardware availability та permission умови, включно з `NEARBY_WIFI_DEVICES` для Android 13+ target. Wi-Fi Aware теж опційний: API існує з рівня 26, але hardware feature не гарантований, а доступність залежить від стану radio. Перед додаванням будь-якого профілю потрібні автентифікований socket/TLS path та OEM-перевірки. Джерела: [Wi-Fi Direct](https://developer.android.com/develop/connectivity/wifi/wifip2p), [Wi-Fi Aware](https://developer.android.com/develop/connectivity/wifi/wifi-aware).

Акустичний сигнал досліджується лише як одноразовий proximity/bootstrap fallback, якщо камера не працює. Це не файловий транспорт і не заміна довіреного handshake: Android microphone capture потребує окремого `RECORD_AUDIO` runtime permission та явної foreground дії; near-ultrasound залежить від акустики конкретних телефонів. Сигнал не повинен нести bearer secret; потрібні short-lived rendezvous та SAS confirmation на обох екранах. [Опублікована near-ultrasonic робота](https://getreuer.info/papers/getreuer2018ultrasonic/index.html) показує придатність лише для коротких токенів із низькою швидкістю, а не payload-файлів. Android Beam/NDEF push вилучено в API 29; UWB потребує окремого out-of-band discovery та призначений для ranging, не передачі файлів. Ці варіанти не є підтриманими каналами TransferKit.

### Backup Relay

Python API може робити local archive export/import; парольний формат захищає ZIP AES-256-GCM, пароль не зберігається, tag перевіряється перед parsing. Це не автентифікація автора та не інтегрований UI. Тимчасовий plaintext ZIP при імпорті видаляється звичайним unlink; secure erase не гарантується.

## Категорії та фактична готовність

| Tier | Реальна межа |
|---|---|
| A — photos/videos/audio | Джерельний Android MediaStore та Windows file manifest є. Документи/Downloads доступні лише через SAF selection. Android destination MediaStore restore є на API 29+; API 26–28 і SAF destination ще не підтримуються. |
| B — contacts/calendar | Source providers/serializers є. Destination account/calendar restore і destination readback ще не реалізовані. |
| C — SMS/call log | Source read залежить від фактично наданих hard-restricted permissions/installer і політик. MMS і destination restore не реалізовані. Не запитувати Android default app roles і не обходити restrictions. |
| D — APK | Джерельний scan обмежений видимими single-file APK; системні/split/unreadable packages пропускаються. PackageInstaller scaffold є, але user picker/status wiring і повний APK restore flow відсутні. |
| E — settings | Поточний Android provider читає лише обмежений `Settings.System` allowlist. Write adapters відсутні; Secure/Global не стають доступними через Shizuku чи Device Owner самі по собі. |
| F/G | Публічні Android API не дають загального доступу до приватних даних інших apps, DRM, акаунтних секретів, банківських credentials чи 2FA. Відсутність API не компенсується root або OEM-only шляхом у звичайному продукті. |

## Безпека та життєвий цикл

- Capability probe — read-only, bounded і без `su`, reboot чи запису налаштувань.
- Root не є передумовою звичайного користувацького режиму. ADB/RSA згода — окремий дозвіл комп’ютеру налагоджувати телефон.
- CRC32 виявляє випадкове пошкодження чанка; SHA-256 перевіряє точні item bytes.
- Restore запускається лише після явної згоди; кожен APK потребує окремого підтвердження.
- `OK` означає успішний target operation і post-verification, а не лише отримання байтів чи receipt.
- `FAILED_INTEGRITY` не понижується до `PARTIAL`/`OK` і не приховується retry.
- Застосунок не повинен логувати файли, контакти, номери, координати, паролі чи pairing tokens.
- Cleanup стосується лише TransferKit-owned staging і не є криптографічним стиранням flash storage.

## Перевірки й release boundary

На 2026-11-12 Android unit suite пройшла 43/43; останній записаний instrumentation — 21/21 на кожному з Samsung S20 Ultra/API 33, Samsung M31/API 31, Samsung A04e/API 33 і Motorola moto g06/API 35. Перевірено Recovery identity, SQLite v1/v2→v3 migration, exact chunk ledger, fsync/commit failure ACK semantics, partial checkpoint, staging locator, status guards і 30-денні retention timestamps. Тимчасові APK прибрані з A04e/Moto; наявні S20/M31 пакети залишилися встановленими. Synthetic A04e→Moto receive: 65 536 bytes, 16 chunks, schema-valid TKRC та незалежний SHA-256 збіг. Python suite пройшла 144/144, включно з source-side category aggregate, receipt reconciliation, legacy migration та exact dedup planning/report tests. Python exact dedup ядро ще не інтегроване з Android target inventory чи transport. Після версії `0.1.0-alpha.1` локальні `:app:testDebugUnitTest :app:assembleDebug` завершилися `BUILD SUCCESSFUL`; усі чотири Wi-Fi ADB serial-и повернули `device` після раніше записаних тестів.  Ці результати не є proof повного Recovery handshake/resume; деталі у [`RELEASE_READINESS.md`](RELEASE_READINESS.md).

Окремий headless Qt harness фактичного `StationWindow`/worker і фізичний сценарій 2026-11-13 на Samsung S20 Ultra/API 33 → Samsung M31/API 31 пройшли receive, receipt, явне restore approval і destination post-verification для одного synthetic Downloads item розміром 65 536 bytes. Receipt verified=1/1; Android UI restored=1/1 із нульовими integrity/restore/cleanup помилками; independent MediaStore read SHA-256 збігся (`7daca209…b27e4c2`). Windows Station ще не отримує restore outcome від Android і не створює фінальний report. Видима інтерактивна desktop UI не перевірялась. Після evidence synthetic source/destination copies мають бути прибрані; це не cable disconnect/recovery acceptance.

APK наразі debug-signed; production signing, повний Wi-Fi transport, restore adapters, API 29/34 та заявлені M1–M5 hardware acceptance ще потрібні. Не розповсюджувати наявний debug APK як реліз. Повний gate-list і відтворювані команди наведені у [`RELEASE_READINESS.md`](RELEASE_READINESS.md), критерії продукту — у [`WORK_CHECKLIST.md`](../../WORK_CHECKLIST.md).
