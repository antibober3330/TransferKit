# Збірка й перевірка на Android телефонах

Ця інструкція призначена для локальної розробки TransferKit на Windows. Для звичайного користувача ADB не потрібен.

## Зібрати APK

Відкрийте PowerShell у `D:\Transfer\android-client` і виконайте:

```powershell
.\build-sideload.bat
```

Готовий debug APK з'явиться у `D:\Transfer\android-client\dist\TransferKit-Android-debug.apk`. Альтернативна команда Gradle — `.\gradlew.bat :app:assembleDebug`; її сирий результат лежить у `app\build\outputs\apk\debug\app-debug.apk`.

Якщо sandbox Codex не бачить Gradle lock file або локальний ADB daemon, запустіть саме локальний PowerShell від імені адміністратора. Не перевстановлюйте Android SDK чи Gradle через такі `Access Denied` помилки: у цьому проєкті підвищений локальний PowerShell уже зібрав APK.

## Вибрати правильний ADB

Використовуйте той самий ADB executable для перевірки, встановлення та запуску. На типовому локальному налаштуванні:

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
if (-not (Test-Path $adb)) { $adb = (Get-Command adb.exe -ErrorAction Stop).Source }
& $adb version
& $adb devices -l
```

Для кожного підключеного телефона візьміть serial із першої колонки `devices -l` і виконайте адресні команди:

```powershell
$serial = "SERIAL_FROM_DEVICES_LIST"
& $adb -s $serial get-state
& $adb -s $serial shell echo ADB_OK
```

Очікувано: `device` та `ADB_OK`. Коли підключено більше одного пристрою, команда без `-s SERIAL` на кшталт `adb get-state` правильно повертає `more than one device/emulator` — це неоднозначність вибору, а не поломка ADB. Для `unauthorized` розблокуйте телефон і підтвердіть RSA prompt цього ПК; serial і код розблокування нікому не надсилайте.

Якщо вивід повідомляє, що версія ADB server не збігається з client, daemon буде перезапущений клієнтом іншої версії. Саме по собі це не означає, що телефони недоступні. Уникайте перемикання між `C:\Windows\adb.exe` і SDK `platform-tools\adb.exe`; один шлях для всіх команд прибирає зайві перезапуски.

## Встановити й відкрити APK на вибраному телефоні

Спершу перевірте, що `devices -l` показує потрібний телефон як `device`. Потім з `D:\Transfer\android-client`:

```powershell
.\install-apk.bat $serial
& $adb -s $serial shell am start -n com.transferkit.client/.MainActivity
& $adb -s $serial shell pidof com.transferkit.client
```

`install-apk.bat` вимагає serial, якщо ADB бачить кілька телефонів; інсталяція `-r` оновлює існуючий пакет і зберігає його app data. Щоб бачити екран застосунку, телефон має бути розблокований. Дозволи на SMS, call log, contacts чи інші категорії надавайте вручну лише для конкретного тесту, який ви обрали.

### Xiaomi / Redmi: `INSTALL_FAILED_USER_RESTRICTED`

На деяких MIUI/HyperOS збірках USB debugging не дає права встановлювати APK через ADB. Це окреме системне обмеження, а не помилка збірки чи підпису APK.

1. Розблокуйте телефон і підтвердьте будь-який системний запит інсталятора на екрані.
2. Перевірте **Налаштування → Програми → Дозволи → Встановлення через USB**. У версіях MIUI, де цей пункт перенесено, він може бути в **Параметри розробника**. Назви й розташування залежать від версії; дивіться також [офіційний посібник MIUI 13](https://alsgp0.fds.api.xiaomi.com/gl123/Generic%20User%20Guide%20for%20MIUI%2013/Generic%20User%20Guide%20for%20MIUI%2013.pdf).
3. Якщо перемикач доступний, увімкніть його та повторіть `install-apk.bat SERIAL` або `adb -s SERIAL install -r app.apk`.
4. Якщо MIUI вимагає Mi/Xiaomi account і перемикач не вмикається, зупиніться: у цьому проєкті немає команди обходу цієї системної вимоги. Для тесту можна передати APK у Downloads та відкрити його через Files; Android покаже штатне підтвердження і, за потреби, окремий дозвіл **Install unknown apps** для Files.

На Redmi 12 `23053RN02Y` (Android 15, MIUI `V816`) SDK ADB 1.0.41 побачив телефон, але `adb install -r` повернув `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`. Після цієї спроби користувач установив APK вручну з Files; `pm path`, `versionName=0.1.0-alpha.2`, `versionCode=3` і запуск `MainActivity` підтверджено через SDK ADB. ADB install route залишається заблокованим MIUI. Один раз використовуємо SDK `platform-tools\adb.exe` для всіх команд: Windows ADB 1.0.40 може перезапустити ADB server 1.0.41 і тимчасово обірвати Wi-Fi ADB.

## Wi-Fi ADB для моніторингу під час кабельного тесту

Android 11+ підтримує Wireless debugging. Телефон і ПК мають бути в одній Wi-Fi мережі. Спарте кожен телефон один раз:

1. На телефоні відкрийте **Параметри розробника → Налагодження через Wi-Fi → Підключити пристрій за допомогою коду підключення**.
2. У локальному PowerShell виконайте `& $adb pair IP:PAIRING_PORT` і введіть шестизначний код у запит ADB. Не додавайте код до журналів або повідомлень.
3. На сторінці Wireless debugging візьміть окремий `IP:PORT` із рядка «IP-адреса та порт» і виконайте `& $adb connect IP:CONNECT_PORT`.
4. Перевірте `& $adb devices -l`. Після pairing той самий телефон може з’явитися двічі: USB serial і Wi-Fi `IP:PORT`. Для кожної команди використовуйте явний `-s SERIAL`.

Під час прямого кабельного тесту між телефонами запускайте logcat і перевірки через Wi-Fi serial. Це залишає USB-C порти вільними для кабелю між телефонами. Наприклад:

```powershell
$wifiSerial = "IP:CONNECT_PORT"
& $adb -s $wifiSerial shell get-state
& $adb -s $wifiSerial logcat -v time -s AndroidRuntime:E
```

Після вимкнення Wi-Fi на Samsung Wireless debugging може вимкнутися окремо. Pairing-запис ПК зберігається, але потрібно знову ввімкнути **Налагодження через Wi-Fi**, прочитати актуальний `IP:PORT` і виконати `adb connect` ще раз. Під час перевірок 2026-11-12 це було підтверджено на S20 Ultra/API 33 і M31/API 31: USB ADB лишався доступним, а після повторного pairing-перемикача обидва канали знову працювали одночасно.

Wi-Fi ADB тут є каналом розробницького моніторингу. Він не є Wi-Fi транспортом TransferKit і не доводить відновлення передачі після обриву.

Початкова перевірка 2026-11-12 на Samsung A04e (`R8YTC096BBD`) та Motorola
moto g06 (`ZY32M7NJHN`) показала вимкнений Wireless debugging. Пізніше обидва
телефони під'єднали до тієї самої Wi-Fi мережі, Wireless debugging ввімкнули,
а A04e спарили з SDK ADB через системний шестизначний pairing code. Обидва
пристрої тепер мають окремі Wi-Fi ADB serials; S20 Ultra та M31 лишилися online.
IP/порт є динамічними — щоразу перевіряйте `adb devices -l` і `adb mdns services`.
Не вмикайте legacy `adb tcpip 5555` як обхід системного pairing.

## Автентифікований USB/ADB receive smoke

Це тестує лише synthetic Windows → Android receive path, не міграцію особистих даних і не restore.

1. Розблокуй цільовий телефон, відкрий TransferKit і натисни **Запустити приймач USB/ADB**. Залиш foreground notification видимим.
2. У PowerShell відкрий `D:\Transfer` і запусти:

```powershell
$python = "C:\Users\ACER\AppData\Local\Python\bin\python.exe"
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
& $python -m transferkit.usb_adb_smoke --serial R58NA50R7LZ --adb $adb
```

Заміни serial на точне значення потрібного пристрою з `adb devices -l`. Команда генерує 64 KiB deterministic test bytes, передає їх 16 чанками, проходить одноразову token/HMAC перевірку та забирає schema-validated `TKRC` receipt. Успішний рядок має містити `verified=1/1`. Щоб зберегти receipt атомарно як канонічний JSON, додай `--receipt D:\Transfer\test-results\receipt.json`.

Synthetic item і receipt залишаються у приватному inbox TransferKit на цілі; restore не запускається й файл не видаляється автоматично. Для очищення використовуй лише явний cleanup flow, коли він буде доступний. Не використовуй тестову команду на іншому serial без перевірки вибору пристрою.


Актуальний стан S20 Ultra/M31, 2026-11-12: Wireless debugging спочатку був вимкнений на обох. Його ввімкнено через Developer options UI й одноразово дозволено для поточної Wi-Fi мережі; SDK ADB 1.0.41 підключив обидва телефони. Перевірено `get-state=device` та модель через кожен окремий Wi-Fi serial. Не запускайте `adb kill-server` під час моніторингу; після фізичної зміни USB використовуйте саме Wi-Fi serial-и з `adb devices -l`.


## S20 Ultra ↔ M31: USB-C і OTG role test (2026-11-12)

Усі команди діагностики виконувалися через Wi-Fi ADB serial-и; Wireless debugging лишався доступним під час перепідключення кабелів.

| Конфігурація | S20 Ultra (SM-G988B) | M31 (SM-M315F) | Спостереження |
|---|---|---|---|
| USB-C↔USB-C | device/sink | host/source | M31 enumerував S20 як Samsung Android/MTP. Інтерфейс MTP мав bulk IN та OUT endpoints. |
| USB-C↔USB-C, кінці переставлені | device/sink | host/source | Ролі не змінилися; перестановка кінців не розвернула Type-C role negotiation. |
| OTG на S20 | host/source | device/sink | S20 enumerував M31 як Samsung Android/MTP. |
| OTG на M31 | device/sink | host/source | M31 enumerував S20 як Samsung Android/MTP. |

Це підтверджує виявлення пристрою, USB role negotiation та MTP інтерфейс у двох напрямках. Файл через MTP не копіювали; це не є TransferKit direct-USB acceptance. У застосунку зараз немає Android `UsbManager`/USB-host transport; його USB/ADB data path використовує ADB forwarding через станцію. Для пункту 124 потрібен окремий тест активної передачі, фізичного обриву кабелю та recovery через checkpoints.

## Samsung A04e ↔ Motorola moto g06: USB-C і OTG role test (2026-11-12)

Діагностику виконано через окремі Wireless debugging serials; фізичні USB-порти лишалися лише між телефонами. Перед і після кожної конфігурації всі чотири Wi-Fi ADB serials (S20 Ultra, M31, A04e, Moto) мали стан `device`.

| Конфігурація | A04e (SM-A042F, API 33) | Moto g06 (API 35) | Спостереження |
|---|---|---|---|
| USB-C↔USB-C | device/sink, MTP | host/source, DFP | Moto enumerував Samsung VID:PID `04E8:6860`. |
| USB-C↔USB-C, кінці переставлені | device/sink, MTP | host/source, DFP | Ролі та Samsung VID:PID не змінилися. |
| USB-A↔USB-C, OTG на A04e | host/source, DFP | device/sink, UFP | A04e host manager виявив Motorola VID:PID `22B8:2E81`. |
| USB-A↔USB-C, OTG на Moto | device/sink, MTP | host/source, DFP | Moto host manager виявив Samsung VID:PID `04E8:6860`; A04e `UsbDeviceManager` показував `connected=true`, `current_functions=MTP`. |

Ці результати підтверджують USB role negotiation та виявлення USB пристрою на другій парі телефонів. Файли не копіювали; USB MTP payload і TransferKit direct-USB transport не перевіряли. Як і для S20/M31, пункт 124 потребує окремого активного transfer з контрольованим обривом і перевіркою checkpoints.

## Redmi 12 instrumentation install caution

On Redmi 12 (Android 15, MIUI V816), `:app:connectedDebugAndroidTest` failed when MIUI refused the test APK with `INSTALL_FAILED_USER_RESTRICTED`. The failed Gradle install session also left the main `com.transferkit.client` package absent, although the debug APK remained in Downloads. Do not rerun connected instrumentation on this device until ADB installation is permitted. Reinstall the main app manually from Downloads first; the Gradle runner has not executed tests on this Redmi.

## Redmi 12 alpha.3 media permission follow-up (2026-11-13)

The user manually installed alpha.3 (`versionCode=4`), verified by SDK ADB. The original-EXIF photo scan then reported 7 visible photos after instructions intended to limit the test to one synthetic image. The app was force-stopped. Before the fix, this scan read category metadata and opened the first source stream for up to 16 KiB; it did not hash or persist those bytes or log coordinates. The current `scanMedia` implementation now reads metadata only; content and SHA-256 are read when the user explicitly builds a manifest. `DeviceInfoScreen` says that the permission covers the photos Android exposes and that content is read during manifest construction. The latest debug APK is 9,270,771 bytes, SHA-256 `4228B1DC8A9C835ABD3F68893A565A3D3AC5029432C578709A428CC89A5F4061`; the user installed it as an update. The user manually denied Photos/Videos and original-media-location permissions, confirmed by ADB. Do not run another scan until Android grants access limited to the synthetic fixture. GPS EXIF byte-equality instrumentation remains unexecuted.

After that denial, the user granted only Android 15's selected-visual-media permission and selected the synthetic fixture. ADB showed `READ_MEDIA_VISUAL_USER_SELECTED=true`, `READ_MEDIA_IMAGES=false`; the UI reported exactly one visible item of 772 bytes and metadata-only inspection. The `ACCESS_MEDIA_LOCATION` prompt did not appear; ADB still shows it denied and appops ignore. The app previously fell back silently to the redacted scan on denial; alpha.3 now stops with an explicit status message instead. Latest APK after that fix: 9,439,050 bytes, SHA-256 `8BA5770EFBB9C2B34CE5C10D755CE076E7A43E8B5EC91C678B60ED453BEBD7A1`; it was installed manually, and older APKs were removed from Downloads. Do not attempt another original-photo read until the permission is explicitly granted. The exact-byte instrumentation still has not run.

Follow-up: Android's partial-photo guidance recommends requesting the image, selected-media, and `ACCESS_MEDIA_LOCATION` permissions in one operation. `MediaOriginalAccessPolicy.permissionsToRequest` now composes that set and unit tests cover API 28/33/34 boundaries; an EXIF denial remains fail-closed. The user-visible MIUI app-permissions screen showed only Photos/Videos and no separate EXIF row. Gradle unit tests are 77/77 and Android debug/AndroidTest builds succeed. The newest APK (9,439,613 bytes, SHA-256 `7F970A1660F82D67871EFDE74B192EDE11AE6E068AB49CB65DDB08F6959E6A0A`) is staged as the only TransferKit APK in Redmi Downloads; the installed app still needs manual update. No new image bytes have been read. See [Android Developers: partial photo/video access](https://developer.android.com/about/versions/14/changes/partial-photo-video-access#best-practices).

## Redmi 12 alpha.3 combined media permission retest (2026-11-13)

After the user revoked the previous grants in Settings, the newest alpha.3 build was opened and the original-EXIF action was selected. The Android 15 limited picker was used to select only transferkit-synthetic-gps-0bc7779.jpg from Pictures/TransferKitTest; broad READ_MEDIA_IMAGES remained denied while READ_MEDIA_VISUAL_USER_SELECTED and ACCESS_MEDIA_LOCATION were granted. ADB dumpsys package confirmed the runtime permission states. The screen showed exactly one item (772 bytes), metadata-only inspection, and an original URI prepared after explicit consent. Screenshot evidence is kept locally at test-results/redmi12-media-permissions-after-grant.png and is excluded from Git. Manifest construction completed with 1 category, 1 item, 0.0 MiB and 0 problem items; transfer and restore were not started. The 772-byte synthetic item was read once to compute SHA-256. The UI does not expose the digest, so exact equality with the fixture hash is not independently confirmed. The user then cleared the selection; the UI showed zero selected categories and confirmed cleanup of the app-owned temporary snapshot.

## Samsung A72 API 34: full and limited media read, plus MediaStore restore (2026-11-13)

Усі медіафайли для цієї перевірки були синтетичними та лежали тільки в `Pictures/TransferKitTest`: 772-byte GPS EXIF JPEG і 1001-byte synthetic MP4. Перевірено Android 14 permission modes окремо: у limited mode системний picker показав тільки synthetic item відповідної категорії, а TransferKit metadata-only scan відобразив `photos: 1` та `videos: 1`; у full mode `READ_MEDIA_IMAGES=true` і `READ_MEDIA_VIDEO=true`, ті самі категорійні scans також дали рівно один item кожен. UI має дві окремі кнопки категорій, а не одну спільну дію; Android prompt окремо формулював доступ як «photo and video». Жоден повний manifest, міжпристроєвий transfer або restore цих JPEG/MP4 не запускався.

`MediaOriginalAccessInstrumentedTest` пройшов 3/3 на A72/API 34, включно з exact-byte/SHA-256 перевіркою синтетичного GPS EXIF stream. `RestorerIntegrityTest` пройшов 3/3 на A72/API 34: marker з іншими bytes лишається `FAILED_INTEGRITY`, опублікований item читається назад, після publish tampering виявляється на retry. `TemporaryMediaScannerTest` був тимчасовим helper-ом, пройшов 1/1 для індексації тільки двох заданих шляхів і після цього його source видалено. Gradle `:app:assembleDebug :app:assembleDebugAndroidTest` — `BUILD SUCCESSFUL`.

Cleanup перевірено: точні synthetic MediaStore rows і файли видалені, test та app APK на A72 видалені, `Pictures/TransferKitTest` відсутня, `stay_on_while_plugged_in` повернуте до `null`. UI-скан був metadata-only; особисті медіафайли не читалися. Окремий combined-permission action/reselection flow лишається UX-покращенням для майбутнього проходу.
На цьому ж A72/API 34 додатково пройшли `ContactsRestorerInstrumentedTest` 1/1 і `CalendarRestorerInstrumentedTest` 1/1 із тимчасовими READ/WRITE grants; обидва створили, зчитали, перевірили retry без дубля й прибрали лише свій marker. Після прогону app/test APK видалені, grants відкликані uninstall-ом.

Redmi 12 повернувся в Wi-Fi ADB як `192.168.1.196:34515` після pairing; model `23053RN02Y`. Alpha.4 debug APK у Downloads має назву `TransferKit-Android-0.1.0-alpha.4-debug.apk`; після ручного встановлення ADB підтвердив `versionCode=5`, `versionName=0.1.0-alpha.4`, а SHA-256 встановленого `base.apk` збігся з локальною збіркою (`608449FC585E0FD70B72CE74C19BE9E6839849537310405A55C4F2F32547C157`). Старий alpha.3 APK перед копіюванням alpha.4 видалений; у Downloads лишився один TransferKit APK.


## Samsung A72 API 34: exact synthetic media restore acceptance (2026-11-13)

`RestorerIntegrityTest` розширено до **4/4 passed**. Новий case читає тільки bundled 772-byte `synthetic-gps-exif.jpg`, створює випадковий TransferKit transfer/item marker, відновлює валідний JPEG у `Pictures/TransferKit/`, перевіряє назву/MIME/розмір/`IS_PENDING=0`, exact byte array і SHA-256 після publish, повторює restore з новим одноразовим approval і підтверджує один MediaStore row. `finally` видаляє точний URI/marker та приватний synthetic payload. Після прогона фільтрований MediaStore query повернув `No result found`; app і test APK видалені, A72 лишився ADB `device`. Жодні інші медіа не відкривалися.

Відео follow-up: AndroidTest містить 1001-byte generated MPEG-4 fixture `synthetic-video.mp4` (ffprobe: MPEG-4, 64×64, 1 s). Доданий case відновлює його у `Movies/TransferKit/`, перевіряє MIME, розмір, `IS_PENDING=0`, точні bytes/SHA-256 після публікації й retry без дубля. Після `RestorerIntegrityTest` **5/5 passed**, точний query за випадковим marker для JPEG і MP4 повернув `No result found`; app/test APK видалені. Fixture SHA-256: `60781004A2A09E15B77B6217A2C64D078885099056C78B689EE5B7F226AD2C56`.


SAF API 34 follow-up (2026-11-13): `SafDocumentRestorerInstrumentedTest` пройшов **5/5** на A72 із test-only `SyntheticDocumentsProvider`: exact write/readback, retry без дубля, source mismatch, пошкоджений marker, provider без rename та missing destination. Цей прогін перевіряє restorer на API 34, але не реальний системний picker/OEM DocumentsUI. Після тесту provider очистив fixtures, app/test APK видалені; A72 лишився ADB `device`.

Persistable SAF grant follow-up: suite розширено до **6/6**. Новий тест проходить `SafDocumentTreeAccess.acquire` → перевіряє read/write persisted grants і `restoreSaved` → `releaseIfOwned`; після release grant відсутній і saved selection очищена. Всі кроки були ізольовані на synthetic DocumentsProvider; після прогону A72 app/test packages видалені, що відкликало всі залишкові дозволи; пристрій лишився доступним.

## Samsung A72 API 34: real SAF system picker acceptance (2026-11-14)

`SafSystemPickerAcceptanceTest` пройшов **1/1** на Samsung SM-A725F/API 34 через справжній `ACTION_OPEN_DOCUMENT_TREE` picker і локальну Documents папку. В межах тесту створено тільки UUID-іменований synthetic документ; restorer виконав точний readback/SHA-256, retry залишив один файл, після чого видалено саме цей документ і звільнено лише grant, створений тестом. Після завершення обидва тимчасові APK були видалені, TransferKit URI grants відсутні, точний пошук тестових імен у Documents не знайшов залишків, а екран повернувся до Samsung My Files.

Фізичний provider виявив дефект, якого не ловив synthetic provider: `createDocument()` отримував tree URI замість кореневого document URI й відхиляв його. Виправлено `SafDocumentRestorer`; помилка provider тепер репортується як `TARGET_WRITE_FAILED`, а synthetic provider перевіряє URI так само строго. Приймання покриває один Samsung local Documents provider/API 34; інші OEM/cloud providers, API 29 та API 26–28 залишаються неперевіреними.

## Android instrumentation regression run (2026-11-14)

Повний `:app:connectedDebugAndroidTest --no-daemon --console=plain` на Samsung A72/API 34 і Samsung M31/API 31 завершився `BUILD SUCCESSFUL`: **40 passed, 0 failed, 3 skipped на кожному пристрої** (43 тест-кейси). Пропущені тести вимагають окремо наданих READ/WRITE Contacts і Calendar grants або ручного вибору папки в реальному SAF picker; їх не рахувати пройденими. Екрани тимчасово утримувалися активними, після прогону `stay_on_while_plugged_in` повернуто до початкового `0`; `pm path` не знайшов ані app, ані test APK, ADB обидва пристрої лишився online.

## Restore gate follow-up: settings, contacts, and calendar (2026-11-14)

- SDK ADB 1.0.41 saw Samsung S20 Ultra/API 33, Samsung M31/API 31, and Samsung A72/API 34. Only S20 had TransferKit alpha.4 installed before this run; its original APK was backed up, then restored with an exact SHA-256 match and the temporary backup was deleted.
- `SettingsSystemRestorerInstrumentedTest`: A72/API 34 no-access case **1/1 passed**; it left `SOUND_EFFECTS_ENABLED` unchanged and did not consume restore approval. Positive synthetic allowlisted write **1/1 passed** after enabling WRITE_SETTINGS on Android Settings special-access screen; the original value was `0` and remained `0` after cleanup. The screen was `com.android.settings/.Settings$AppWriteSettingsActivity` for `com.transferkit.client`, and `appops` reported `allow` before the test. Removing the temporary app package removed its special access (`No UID` afterward). This confirms the OS special-access screen and write gate, but the app's pending Restore Inbox → activity-result → retry/review sequence still needs acceptance.
- `ContactsRestorerInstrumentedTest#importsOnlySyntheticLocalContactAndRetryDoesNotDuplicateIt`: **1/1 passed** on S20/API 33, M31/API 31, and A72/API 34. Each test used a generated vCard, verified one marker-owned contact after first import and retry, then removed only its own marker.
- `CalendarRestorerInstrumentedTest#restoresSyntheticEventOnceAndRemovesOnlyItsOwnMarker`: **1/1 passed** on all three devices after a real failure surfaced on M31/API 31. Samsung Calendar Provider generated one owner attendee row from `SELF_ATTENDEE_STATUS`; post-verification now recognizes only the exact row derived from the selected destination calendar owner and source status. Other attendee/reminder mismatches still fail. Diagnostic log records schema field names/counts only; no event or attendee values are logged.
- App/test APKs and temporary Contacts/Calendar grants were removed from M31/A72. S20 was returned to alpha.4; `SOUND_EFFECTS_ENABLED` is `0` on all three; all three USB ADB serials remained online.
- Android unit suite: **88/88 passed**, zero failures/errors/skips. Full build and AndroidTest APK assembly succeeded. The default full instrumentation suite still has permission-gated Contacts/Calendar and manual SAF picker cases; the targeted acceptance above is the positive synthetic-provider evidence.
## Restore Inbox settings-only regression (2026-11-14)

Знайшовся dead path: `SettingsSystemRestorer` і системна згода `WRITE_SETTINGS` були реалізовані, але `availableRestoreTransfers()` не враховував категорію `settings`, через що settings-only передача показувалася як недоступна і кнопка Review була вимкнена. Виправлено окремі settings item/byte counters, Inbox summary/copy та enabled умову Review. `MainActivity` порожній стан також уточнено.

Gradle `:app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest` — `BUILD SUCCESSFUL`; Android unit XML **88/88 passed**. На Samsung A72/API 34 таргетований receiver + Compose instrumentation пройшов **19/19**. На S20/API 33 перший запуск дав **18/19**, бо screen був неактивний (`No compose hierarchies found`); після `KEYCODE_WAKEUP` сфокусований `RestoreInboxSettingsTest` пройшов **1/1**, отже всі 19 унікальних cases мають позитивний результат. Пакети app/test відсутні після прогонів на обох пристроях. `SOUND_EFFECTS_ENABLED` у цьому прогоні не чіпався.

Ці тести підтверджують Receiver summary та Compose Inbox кнопку. Окремий `SettingsRestoreInboxFlowTest` пройшов **1/1** на Samsung A72/API 34: створив локальний synthetic settings-only transfer, відкрив реальний `ACTION_MANAGE_WRITE_SETTINGS` через production Inbox/Review flow, повернувся до повторного review через Activity Result і скасував запис. `SOUND_EFFECTS_ENABLED` залишився `0`; test-only app-op та APK видалені після тесту. Це приймає lifecycle і gate, але не є позитивним UI підтвердженням реального користувацького запису; positive synthetic writer перевірений окремим instrumentation вище.

## Shizuku bridge read-only acceptance (2026-11-14)

На Samsung S20 Ultra/API 33 та Samsung A72/API 34 Shizuku package відсутній. `ShizukuBridgeInstrumentedTest` перевірив реальний no-service стан: `SERVICE_NOT_RUNNING`, `SHIZUKU_REQUIRED`, permission=false, privileged operations=false; явний request повернув `SERVICE_NOT_RUNNING` і не стартував сервіс. Результат — **1/1 passed на кожному фізичному телефоні**. Загальний Gradle task завершився помилкою лише на повторному Wi-Fi ADB endpoint того самого A72: цей дубль мав 0 тестів і instrumentation process crash; XML результати обох унікальних пристроїв успішні. Після запуску `pm path` не показав app/test APK, `SOUND_EFFECTS_ENABLED` лишився `0`. Local Android unit suite — **94/94 passed**. Shizuku manager prompt і реальний permission callback ще не перевірені, бо сервіс не встановлений/запущений.

## Receiver durable-progress callback acceptance (2026-11-14)

На Samsung A72/API 34 та S20 Ultra/API 33 targeted `ReceiverTest#receiverStreamReportsOnlyDurableCumulativeBytesAndFinalReceiptProgress` пройшов **1/1 на кожному**. Тест використовує synthetic payload і перевіряє лише cumulative bytes після durable ACK та фінальний progress callback. AndroidTest APK встановлювався тільки на час перевірки; після кожного запуску `pm path` для `com.transferkit.client` і `.test` порожній, `SOUND_EFFECTS_ENABLED=0`, ADB serials залишилися online. Це фізично приймає receiver callback, але не доводить actual foreground notification progress під час desktop USB transfer, cable interruption чи recovery.
## Receiver full-class physical follow-up (2026-11-14)

Після точкового callback acceptance повний `ReceiverTest` клас пройшов **18/18 на Samsung A72/API 34 та 18/18 на Samsung S20 Ultra/API 33**. Обидва адресні `am instrument` запуски повернули exit code 0. Пакети app/test видалені; `pm path` порожній, контрольне `SOUND_EFFECTS_ENABLED` на обох пристроях дорівнює `0`. Це приймає receiver/core regressions на двох API рівнях; live notification із desktop sender, кабельний обрив, pause/cancel і recovery лишаються окремими acceptance.
## Transfer progress idle-screen physical follow-up (2026-11-14)

`TransferProgressScreenTest#idleScreenDoesNotInventProgressAndLeavesUnsafeActionsDisabled` passed **1/1 on Samsung A72/API 34 and 1/1 on Samsung S20 Ultra/API 33**. It verified no fabricated progress before a checkpoint and disabled Pause/Cancel controls. The phone was only woken for UI instrumentation; temporary app/test packages were removed, `pm path` returned empty, and `SOUND_EFFECTS_ENABLED=0`. This expands point 049 idle-screen coverage, but does not validate live progress updates, keep-awake/dimmer interaction, rotation/process death, or pause/cancel transport semantics.
## Transfer progress keep-awake/dimmer lifecycle acceptance (2026-11-14)

New `TransferProgressScreenTest#keepAwakeAndDimmerAreOptInAndRestoreOriginalWindowAttributes` passed **1/1 on A72/API 34 and 1/1 on S20/API 33**. It enabled the page-scoped keep-awake toggle and dimmer, confirmed window brightness moved to the bounded default, then navigated back and asserted the original keep-screen flag and window brightness were restored. It did not change the Android system brightness setting. Test APKs were removed; `pm path` returned empty and the test ADB transports remained online.