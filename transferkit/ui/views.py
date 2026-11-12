"""Secondary Windows Station screens for progress, reports, history and settings."""

from __future__ import annotations

import json
import sqlite3

from PySide6.QtCore import QSettings, Qt, Signal
from PySide6.QtWidgets import (
    QCheckBox,
    QComboBox,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QLineEdit,
    QMessageBox,
    QPlainTextEdit,
    QProgressBar,
    QPushButton,
    QTableWidget,
    QTableWidgetItem,
    QVBoxLayout,
    QWidget,
)

from ..history import TransferHistory
from .plan_view import CATEGORY_LABELS


TRANSFER_STATUS_LABELS = {
    "RUNNING": "Виконується",
    "RECONNECTING": "Відновлення з’єднання",
    "PAUSED_AWAITING_USER": "Очікує на дію користувача",
    "PAUSED_BLOCKED": "Відновлення заблоковане",
    "RESUMING": "Продовжується",
    "RESTARTING": "Перезапускається",
    "COMPLETED": "Завершено",
    "SUPERSEDED": "Замінено новою передачею",
    "ABANDONED": "Скасовано",
}
PLAN_STATUS_LABELS = {
    "TRANSFERABLE": "Переноситься",
    "PARTIAL": "Частково",
    "UNAVAILABLE": "Недоступно",
}
REPORT_STATUS_LABELS = {
    "OK": "Успішно",
    "PARTIAL": "Частково",
    "UNAVAILABLE": "Недоступно",
    "FAILED": "Помилка",
    "FAILED_INTEGRITY": "Помилка цілісності",
}


class ProgressView(QWidget):
    """Transfer progress display; remains explicit when no transfer is active."""

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.heading = QLabel("Стан передачі")
        self.heading.setObjectName("progressHeading")
        self.status = QLabel("Активної передачі немає. Спочатку підключіть пристрої та побудуйте план.")
        self.status.setWordWrap(True)
        self.progress = QProgressBar()
        self.progress.setRange(0, 100)
        self.progress.setValue(0)
        self.progress.setFormat("%p%")
        self.details = QLabel("Підтверджені чанки та категорії з’являться тут після початку передачі.")
        self.details.setWordWrap(True)
        self.adb_devices = QLabel("ADB: стан вибраних пристроїв ще не перевірявся.")
        self.adb_devices.setObjectName("adbDeviceState")
        self.adb_devices.setWordWrap(True)
        self.transport_reason = QLabel("Остання причина транспорту: не зафіксована.")
        self.transport_reason.setObjectName("transportReason")
        self.transport_reason.setWordWrap(True)
        self.categories = QTableWidget(0, 4)
        self.categories.setHorizontalHeaderLabels(("Категорія", "Передано", "Усього", "Стан"))
        self.categories.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.categories.verticalHeader().setVisible(False)
        self.categories.horizontalHeader().setStretchLastSection(True)
        layout = QVBoxLayout(self)
        layout.addWidget(self.heading)
        layout.addWidget(self.status)
        layout.addWidget(self.progress)
        layout.addWidget(self.details)
        layout.addWidget(self.adb_devices)
        layout.addWidget(self.transport_reason)
        layout.addWidget(self.categories, 1)
        layout.addStretch(1)

    def set_progress(self, percent: int, status: str, details: str = "") -> None:
        if not 0 <= percent <= 100:
            raise ValueError("percent must be between 0 and 100")
        self.progress.setValue(percent)
        self.status.setText(status)
        self.details.setText(details)

    def set_adb_states(
        self,
        source_serial: str,
        source_state: str,
        target_serial: str,
        target_state: str,
    ) -> None:
        labels = {
            "checking": "перевіряється",
            "device": "підключено",
            "offline": "offline",
            "unauthorized": "не авторизовано",
            "no permissions": "немає дозволу ADB на ПК",
            "absent": "не знайдено",
            "adb_unavailable": "ADB недоступний",
            "unknown": "невідомий стан",
        }
        source_label = labels.get(source_state, labels["unknown"])
        target_label = labels.get(target_state, labels["unknown"])
        self.adb_devices.setText(
            f"ADB — джерело: {source_label} ({source_serial}); "
            f"ціль: {target_label} ({target_serial})."
        )

    def set_transport_reason(self, reason: str | None) -> None:
        if reason:
            self.transport_reason.setText(f"Остання причина транспорту: {reason}")
        else:
            self.transport_reason.setText("Остання причина транспорту: не зафіксована.")

    def set_categories(self, rows: list[dict]) -> None:
        self.categories.setRowCount(len(rows))
        for index, row in enumerate(rows):
            completed = int(row["completed"])
            total = int(row["total"])
            if completed < 0 or total < completed:
                raise ValueError("category progress requires 0 <= completed <= total")
            values = (str(row["category"]), str(completed), str(total), str(row.get("status", "У процесі")))
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                item.setFlags(Qt.ItemFlag.ItemIsSelectable | Qt.ItemFlag.ItemIsEnabled)
                self.categories.setItem(index, column, item)


class ReportView(QWidget):
    """Read-only display of a final, schema-validated report from history."""

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.heading = QLabel("Звіт передачі")
        self.heading.setObjectName("reportHeading")
        self.status = QLabel("Оберіть завершену передачу в історії, щоб переглянути її звіт.")
        self.status.setWordWrap(True)
        self.categories = QTableWidget(0, 6)
        self.categories.setHorizontalHeaderLabels(("Категорія", "Tier", "План", "Результат", "Відновлено", "Дублікатів пропущено"))
        self.categories.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.categories.verticalHeader().setVisible(False)
        self.categories.horizontalHeader().setStretchLastSection(True)
        self.contents = QPlainTextEdit()
        self.contents.setReadOnly(True)
        self.contents.setPlaceholderText("Звіт з’явиться після вибору з історії.")
        layout = QVBoxLayout(self)
        layout.addWidget(self.heading)
        layout.addWidget(self.status)
        layout.addWidget(self.categories, 1)
        layout.addWidget(QLabel("Повний JSON звіту"))
        layout.addWidget(self.contents, 1)

    def set_report(self, report: dict | None) -> None:
        if report is None:
            self.status.setText("Для цієї передачі фінального звіту ще немає.")
            self.categories.setRowCount(0)
            self.contents.clear()
            return
        summary = report.get("summary", {})
        self.status.setText(
            f"Передача {report.get('transfer_id', 'невідома')} · "
            f"категорій: {summary.get('total_categories', 0)}, "
            f"переноситься: {summary.get('transferable', 0)}, "
            f"частково: {summary.get('partial', 0)}, "
            f"недоступно: {summary.get('unavailable', 0)}, "
            f"integrity-помилок: {summary.get('failed_integrity', 0)}, "
            f"дублікатів пропущено: {sum(int(row.get('deduplicated_count', 0)) for row in report.get('categories', []))}"
        )
        categories = report.get("categories", [])
        self.categories.setRowCount(len(categories))
        for index, category in enumerate(categories):
            values = (
                CATEGORY_LABELS.get(category.get("category_id"), str(category.get("category_id", ""))),
                str(category.get("tier", "")),
                PLAN_STATUS_LABELS.get(category.get("plan_status"), str(category.get("plan_status", ""))),
                REPORT_STATUS_LABELS.get(category.get("status"), str(category.get("status", ""))),
                str(category.get("restored_count", 0)),
                str(category.get("deduplicated_count", 0)),
            )
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                item.setFlags(Qt.ItemFlag.ItemIsSelectable | Qt.ItemFlag.ItemIsEnabled)
                self.categories.setItem(index, column, item)
        self.contents.setPlainText(json.dumps(report, ensure_ascii=False, indent=2))


class HistoryView(QWidget):
    report_selected = Signal(object)

    HEADERS = ("ID передачі", "Стан", "Створено", "Оновлено", "Протокол")

    def __init__(self, history: TransferHistory, parent: QWidget | None = None):
        super().__init__(parent)
        self.history = history
        self.heading = QLabel("Історія передач")
        self.heading.setObjectName("historyHeading")
        self.status = QLabel("Історія локальна й зберігається на цьому комп’ютері.")
        self.table = QTableWidget(0, len(self.HEADERS))
        self.table.setHorizontalHeaderLabels(self.HEADERS)
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setSelectionMode(QTableWidget.SelectionMode.SingleSelection)
        self.table.verticalHeader().setVisible(False)
        self.table.horizontalHeader().setStretchLastSection(True)
        self.table.cellDoubleClicked.connect(self._open_report)
        self.refresh_button = QPushButton("Оновити історію")
        self.refresh_button.clicked.connect(self.refresh)
        row = QHBoxLayout()
        row.addWidget(self.status, 1)
        row.addWidget(self.refresh_button)
        layout = QVBoxLayout(self)
        layout.addWidget(self.heading)
        layout.addLayout(row)
        layout.addWidget(self.table, 1)
        self.refresh()

    def refresh(self) -> None:
        try:
            records = self.history.list_transfers()
        except (OSError, ValueError, RuntimeError, sqlite3.Error) as exc:
            self.table.setRowCount(0)
            self.status.setText(f"Не вдалося прочитати історію: {exc}")
            return
        self.table.setRowCount(len(records))
        for row, record in enumerate(records):
            values = (
                record.transfer_id,
                TRANSFER_STATUS_LABELS.get(record.status, "Невідомий стан"),
                record.created_at,
                record.updated_at,
                record.protocol_version,
            )
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                item.setFlags(Qt.ItemFlag.ItemIsSelectable | Qt.ItemFlag.ItemIsEnabled)
                if column == 0:
                    item.setData(Qt.ItemDataRole.UserRole, record.transfer_id)
                self.table.setItem(row, column, item)
        self.status.setText(f"Локальна історія: {len(records)} передач.")

    def _open_report(self, row: int, _column: int) -> None:
        transfer_item = self.table.item(row, 0)
        if transfer_item is None:
            return
        transfer_id = transfer_item.data(Qt.ItemDataRole.UserRole)
        self.report_selected.emit(self.history.get_report(transfer_id))


class SettingsView(QWidget):
    settings_saved = Signal()

    def __init__(self, settings: QSettings, parent: QWidget | None = None):
        super().__init__(parent)
        self.settings = settings
        self.heading = QLabel("Налаштування")
        self.heading.setObjectName("settingsHeading")
        self.adb_path = QLineEdit(str(settings.value("adb/executable", "")))
        self.adb_path.setPlaceholderText("Автоматичний пошук ADB, якщо поле порожнє")
        self.mode = QComboBox()
        self.mode.addItem("Швидкий", "quick")
        self.mode.addItem("Повний", "full")
        self.mode.addItem("Професійний", "professional")
        selected_mode = str(settings.value("transfer/default_mode", "quick"))
        selected_index = self.mode.findData(selected_mode)
        self.mode.setCurrentIndex(max(0, selected_index))
        self.confirm_before_transfer = QCheckBox("Завжди питати підтвердження перед передачею")
        self.confirm_before_transfer.setChecked(
            settings.value("transfer/confirm_before_start", True, type=bool)
        )
        form = QFormLayout()
        form.addRow("ADB executable", self.adb_path)
        form.addRow("Режим за замовчуванням", self.mode)
        self.save_button = QPushButton("Зберегти налаштування")
        self.save_button.clicked.connect(self.save)
        self.status = QLabel("Налаштування зберігаються локально.")
        self.status.setWordWrap(True)
        layout = QVBoxLayout(self)
        layout.addWidget(self.heading)
        layout.addLayout(form)
        layout.addWidget(self.confirm_before_transfer)
        layout.addWidget(self.save_button, 0, Qt.AlignmentFlag.AlignLeft)
        layout.addWidget(self.status)
        layout.addStretch(1)

    def save(self) -> None:
        self.settings.setValue("adb/executable", self.adb_path.text().strip())
        self.settings.setValue("transfer/default_mode", self.mode.currentData())
        self.settings.setValue("transfer/confirm_before_start", self.confirm_before_transfer.isChecked())
        self.settings.sync()
        if self.settings.status() != QSettings.Status.NoError:
            self.status.setText("Не вдалося зберегти налаштування.")
            QMessageBox.warning(self, "TransferKit", self.status.text())
            return
        self.status.setText("Налаштування збережено. Новий шлях ADB використовується при наступному оновленні пристроїв.")
        self.settings_saved.emit()
