"""Read-only per-category preflight matrix shown before transfer starts."""

from __future__ import annotations

from PySide6.QtCore import Qt
from PySide6.QtGui import QColor, QBrush
from PySide6.QtWidgets import QHeaderView, QLabel, QTableWidget, QTableWidgetItem, QVBoxLayout, QWidget

from ..contracts import CategoryId, CapabilityStatus, PlanStatus, TransferPlan


CATEGORY_LABELS = {
    CategoryId.PHOTOS: "Фотографії",
    CategoryId.VIDEOS: "Відео",
    CategoryId.AUDIO: "Аудіо",
    CategoryId.DOCUMENTS: "Документи",
    CategoryId.DOWNLOADS: "Завантаження",
    CategoryId.CONTACTS: "Контакти",
    CategoryId.CALENDAR: "Календар",
    CategoryId.SMS: "SMS",
    CategoryId.CALL_LOG: "Журнал дзвінків",
    CategoryId.APKS: "APK застосунків",
    CategoryId.SETTINGS: "Системні налаштування",
    CategoryId.APP_DATA: "Дані застосунків",
    CategoryId.WIFI_PASSWORDS: "Паролі Wi-Fi",
    CategoryId.LAUNCHER_LAYOUT: "Розкладка launcher",
    CategoryId.ACCOUNTS: "Акаунти",
    CategoryId.DRM: "DRM-вміст",
    CategoryId.BANKING_APPS: "Банківські застосунки",
    CategoryId.TWO_FACTOR_TOKENS: "2FA-токени",
}

CAPABILITY_LABELS = {
    CapabilityStatus.AVAILABLE: "Доступно",
    CapabilityStatus.PARTIAL: "Частково",
    CapabilityStatus.UNAVAILABLE: "Недоступно",
}

PLAN_LABELS = {
    PlanStatus.TRANSFERABLE: "Переноситься",
    PlanStatus.PARTIAL: "Частково",
    PlanStatus.UNAVAILABLE: "Недоступно",
}


class PlanMatrixWidget(QWidget):
    HEADERS = ("Категорія", "Tier", "Джерело", "Ціль", "План", "Потрібні дозволи", "Що зробити")

    def __init__(self, parent: QWidget | None = None):
        super().__init__(parent)
        self.heading = QLabel("Попередній план перенесення")
        self.heading.setObjectName("planHeading")
        self.explanation = QLabel(
            "Перегляньте доступність і причини до початку передачі. На цьому екрані дані не записуються."
        )
        self.explanation.setWordWrap(True)
        self.table = QTableWidget(0, len(self.HEADERS))
        self.table.setHorizontalHeaderLabels(self.HEADERS)
        self.table.setEditTriggers(QTableWidget.EditTrigger.NoEditTriggers)
        self.table.setSelectionBehavior(QTableWidget.SelectionBehavior.SelectRows)
        self.table.setAlternatingRowColors(True)
        self.table.verticalHeader().setVisible(False)
        self.table.horizontalHeader().setSectionResizeMode(0, QHeaderView.ResizeMode.ResizeToContents)
        self.table.horizontalHeader().setSectionResizeMode(1, QHeaderView.ResizeMode.ResizeToContents)
        for column in range(2, len(self.HEADERS)):
            self.table.horizontalHeader().setSectionResizeMode(column, QHeaderView.ResizeMode.Stretch)
        self.status = QLabel("Виберіть два пристрої й запустіть перевірку можливостей.")
        self.status.setWordWrap(True)

        layout = QVBoxLayout(self)
        layout.addWidget(self.heading)
        layout.addWidget(self.explanation)
        layout.addWidget(self.table, 1)
        layout.addWidget(self.status)

    def clear_plan(self, message: str) -> None:
        self.table.setRowCount(0)
        self.status.setText(message)

    def set_plan(self, plan: TransferPlan) -> None:
        self.table.setRowCount(len(CategoryId))
        for row, category_id in enumerate(CategoryId):
            entry = plan.categories[category_id]
            source_capability = CAPABILITY_LABELS[entry.source_status]
            target_capability = CAPABILITY_LABELS[entry.target_status]
            source_text = f"{source_capability} · {entry.source_reason.value}\n{entry.source_method}"
            target_text = f"{target_capability} · {entry.target_reason.value}\n{entry.target_method}"
            permission_lines = []
            if entry.source_permissions:
                permission_lines.append("Джерело: " + ", ".join(entry.source_permissions))
            if entry.target_permissions:
                permission_lines.append("Ціль: " + ", ".join(entry.target_permissions))
            if not permission_lines:
                permission_lines.append("Додаткові дозволи не потрібні")
            permission_lines.extend(entry.preflight_notes)
            values = (
                CATEGORY_LABELS[category_id],
                entry.tier.value,
                source_text,
                target_text,
                f"{PLAN_LABELS[entry.status]} · {entry.reason.value}",
                "\n".join(permission_lines),
                entry.user_action or "—",
            )
            for column, value in enumerate(values):
                item = QTableWidgetItem(value)
                item.setFlags(Qt.ItemFlag.ItemIsSelectable | Qt.ItemFlag.ItemIsEnabled)
                if column in (1,):
                    item.setTextAlignment(Qt.AlignmentFlag.AlignCenter)
                if column == 4:
                    color = {
                        PlanStatus.TRANSFERABLE: QColor("#e6f4ea"),
                        PlanStatus.PARTIAL: QColor("#fff4ce"),
                        PlanStatus.UNAVAILABLE: QColor("#fde8e7"),
                    }[entry.status]
                    item.setBackground(QBrush(color))
                self.table.setItem(row, column, item)
            self.table.setRowHeight(row, max(48, 20 * len(permission_lines) + 12))
        self.status.setText(
            f"План готовий. Переноситься: {sum(row.status == PlanStatus.TRANSFERABLE for row in plan.categories.values())}; "
            f"частково: {sum(row.status == PlanStatus.PARTIAL for row in plan.categories.values())}; "
            f"недоступно: {sum(row.status == PlanStatus.UNAVAILABLE for row in plan.categories.values())}. "
            "Перенесення ще не почалося."
        )
