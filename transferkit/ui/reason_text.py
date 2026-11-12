"""Short Ukrainian explanations for technical errors shown in the UI."""

from __future__ import annotations


_REASON_MESSAGES = {
    "ADB_UNAVAILABLE": "ADB недоступний. Перевірте Android Platform Tools і повторіть спробу.",
    "ADB_COMMAND_FAILED": "Команда ADB не виконалася. Перевірте налаштування USB debugging і повторіть спробу.",
    "DEVICE_NOT_FOUND": "Пристрій не знайдено. Перевірте USB кабель і натисніть «Оновити пристрої».",
    "DEVICE_UNAUTHORIZED": "Розблокуйте пристрій і підтвердіть запит USB debugging.",
    "DEVICE_OFFLINE": "Пристрій тимчасово недоступний. Перепід’єднайте кабель і повторіть спробу.",
    "TRANSFER_INCOMPLETE": "Ціль ще не класифікувала всі елементи й не сформувала фінальну квитанцію. Перевірте стан прийому та повторіть завершення передачі.",
    "PROBE_TIMEOUT": "Перевірка пристрою тривала надто довго. Перепід’єднайте його й повторіть спробу.",
    "PERMISSION_DENIED": "Android не надав потрібний дозвіл. Перевірте дозволи на пристрої.",
    "API_UNAVAILABLE": "Цей механізм недоступний на поточній версії Android.",
    "UNSUPPORTED": "Цей спосіб не підтримується пристроєм.",
    "DEVICE_SELECTION_INVALID": "Виберіть два різні підключені пристрої.",
    "PROBE_FAILED": "Не вдалося перевірити можливості пристрою. Перепід’єднайте його й повторіть спробу.",
}


def reason_message(reason: str) -> str:
    """Return localized guidance without exposing an internal enum to users."""
    return _REASON_MESSAGES.get(
        reason,
        "Не вдалося завершити перевірку. Перевірте підключення обох пристроїв і повторіть спробу.",
    )
