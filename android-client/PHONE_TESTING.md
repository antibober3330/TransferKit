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
