# Посібник користувача TransferKit

**Поточний стан:** TransferKit — прототип, не готовий засіб міграції. Код має authenticated USB/ADB source→target receive path, а його вузький синтетичний сценарій фізично перевірений на A04e→Moto. Повний Windows Station click-through, restore користувацьких даних із destination post-verification і завершений report ще не пройшли acceptance. Wi-Fi transport не готовий.

## Перед початком

- Для Windows Station потрібні Windows 10/11, Python 3.11+ і ADB. Root на телефонах для звичайного сценарію не потрібен.
- Для поточної розробницької Android-збірки потрібні JDK 17 та Android SDK Platform/Build Tools 34.
- Залиште резервну копію важливих даних. Не використовуйте прототип як єдину копію.
- Не надсилайте код блокування, паролі чи pairing tokens у чат або журнали.

## Запустити Android-клієнт

1. Увімкніть USB debugging лише для власного комп’ютера та прийміть його ADB RSA-запит на телефоні.
2. Зберіть `TransferKit-Android-debug.apk` за інструкцією [`android-client/PHONE_TESTING.md`](android-client/PHONE_TESTING.md), або встановіть готову debug-збірку.
3. Якщо під’єднано кілька телефонів, завжди вкажіть потрібний serial. Не запускайте команду без `-s SERIAL` для багатьох пристроїв.
4. Відкрийте TransferKit. Екран показує модель, версію Android, пам’ять і доступні перевірки.

## Перевірити категорії та підготувати manifest

1. Перегляньте опис дозволів і обмежень біля категорії.
2. Натисніть кнопку перевірки лише для потрібної категорії. Android може показати runtime permission або системний picker.
3. Для фото, відео та аудіо дозвіл запитується для вибраного типу. На Android 14+ можна надати доступ лише до вибраних візуальних файлів. Документи й Downloads вибираються окремо через системне вікно SAF.
4. Contacts і Calendar читаються тільки після надання відповідного дозволу. Копіювання в інший account/calendar потребує окремої реалізації й підтвердження.
5. SMS і call log мають обмежені Android дозволи, які залежать від інсталятора та політик системи. Успішна збірка або звичайний runtime prompt не гарантують доступ.
6. Manifest включає лише категорії, які ви успішно перевірили та вибрали. Відомі розміри/хеші будуються для конкретних байтів; unknown-size джерело тимчасово snapshot-иться у приватний cache.
7. Кнопка «Підготувати маніфест» створює локальний інвентар і SHA-256; **вона не передає дані**.

Підготовка manifest snapshot локальна: сама кнопка не запускає передачу. Окремий authenticated USB/ADB receive шлях існує для контрольованого синтетичного тесту; desktop Station click-through та повний restore не пройшли acceptance. Не використовуйте його для перенесення єдиної копії чи важливих особистих даних.

## Запустити Windows Station

З кореня `D:\Transfer` відкрийте PowerShell:

```powershell
.\install.bat
.\run.bat
```

Підключіть власні Android-пристрої, дозвольте саме цей комп’ютер у RSA prompt, а в Station виберіть кожен пристрій окремо. Capability probe має бути read-only. Station має підтверджуваний receive action для authenticated USB/ADB backend, але фізичний desktop click-through, destination restore/post-verification і фінальний migration report іще не прийняті. Не вважайте отриману квитанцію успішним restore.

## Дозволи й приватність

- Надавайте тільки дозволи для категорій, які хочете перевірити.
- Відмова в дозволі має залишити категорію недоступною/частковою, не приховувати причину.
- Передача задумана як локальна, без хмарної телеметрії.
- Не передавайте чутливі дані через тестовий інструмент, поки не завершені автентифікований транспорт, restore adapters і release перевірки.
- Якщо manifest/snapshot або staging важливий для діагностики, не очищайте дані застосунку й не видаляйте його без перевірки.

## Обмеження

Наразі MediaStore restore-компоненти для фото/відео/аудіо/Downloads перевірялися на Android 12/13, але повний source→restore→destination post-verification flow не пройшов acceptance. Android 8–9, SAF destination restore, Contacts/Calendar/SMS/call-log/APK/Settings restore, Wi-Fi TLS/QR, Wi-Fi Direct і акустичне pairing не заявлені готовими. Див. [межі архітектури](docs/project/ARCHITECTURE_AND_LIMITS.md), [release gates](docs/project/RELEASE_READINESS.md) та [формат звіту](TRANSFER_REPORT_FORMAT.md).

## TransferKit User Guide

**Current status:** TransferKit is a prototype, not a ready migration product. An authenticated USB/ADB source-to-target receive path exists, and a narrow synthetic A04e-to-Moto scenario was physically checked. The full Windows Station click-through, restore of user data with destination post-verification, and final report have not passed acceptance. Wi-Fi transport is not ready.

### Before you begin

- Windows Station requires Windows 10/11, Python 3.11+ and ADB. Root is not required for the ordinary product flow.
- The current Android development build requires JDK 17 and Android SDK Platform/Build Tools 34.
- Keep a separate backup of important data; do not rely on this prototype as your only copy.
- Never share a lock-screen code, password or pairing token in chat or logs.

### Start the Android client

1. Enable USB debugging only for your own computer and approve its ADB RSA prompt on the phone.
2. Build `TransferKit-Android-debug.apk` using [`android-client/PHONE_TESTING.md`](android-client/PHONE_TESTING.md), or install the existing debug build.
3. If multiple phones are connected, use the exact device serial. Always select a device explicitly in ADB commands.
4. Open TransferKit. The screen shows the model, Android version, memory and available checks.

### Check categories and prepare a manifest

1. Read the permission and platform-limit note beside each category.
2. Start only the checks you need. Android may show a runtime permission prompt or system picker.
3. Media permissions are requested for the selected type. Android 14+ can grant access only to chosen visual files. Documents and Downloads are selected through the system SAF picker.
4. Contacts and Calendar are read only after their respective permission is granted. Restoring into another account/calendar requires separate implementation and confirmation.
5. SMS and call-log permissions are restricted and depend on installer and system policy. A successful build or ordinary runtime prompt does not guarantee access.
6. The manifest includes only categories you successfully checked and selected. It records exact byte sizes and SHA-256 digests; an unknown-size source is temporarily snapshotted in the app-private cache.
7. “Prepare manifest” creates a local inventory and hashes; **it does not transfer data**.

Manifest preparation is local and does not itself start a transfer. A separate authenticated USB/ADB receive path exists for controlled synthetic testing; the desktop Station click-through and complete restore have not passed acceptance. Do not use it to move the only copy of important personal data.

### Start Windows Station

From `D:\Transfer`, open PowerShell:

```powershell
.\install.bat
.\run.bat
```

Connect devices you own, approve this computer in the ADB RSA prompt, and select each device explicitly. Capability probing is read-only. Station has a confirmed-action entry point for the authenticated USB/ADB backend, but its physical desktop click-through, destination restore/post-verification and final migration report have not passed acceptance. A receive receipt alone is not a successful restore.

### Permissions and privacy

- Grant only permissions needed for categories you intend to check.
- A denied permission should leave a category unavailable/partial with a visible reason.
- TransferKit is designed for local use without cloud telemetry.
- Do not use sensitive data in a test transfer until authenticated transport, restore adapters and release checks are complete.
- If a manifest snapshot or staging area may be needed for diagnosis, do not clear app data or uninstall before checking.

### Limits

MediaStore photo/video/audio/Downloads restore components have been exercised on Android 12 and 13, but the complete source→restore→destination post-verification flow has not passed acceptance. Android 8–9, SAF destination restore, Contacts/Calendar/SMS/call-log/APK/Settings restore, Wi-Fi TLS/QR, Wi-Fi Direct and acoustic pairing are not claimed as ready. See [architecture limits](docs/project/ARCHITECTURE_AND_LIMITS.md), [release gates](docs/project/RELEASE_READINESS.md) and the [report format](TRANSFER_REPORT_FORMAT.md).
