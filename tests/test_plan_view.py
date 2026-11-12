import os
import unittest
from datetime import datetime, timezone

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtWidgets import QApplication

from transferkit.contracts import (
    CATEGORY_TIER,
    CategoryId,
    CapabilityStatus,
    DeviceInfo,
    PlanEntry,
    PlanStatus,
    ReasonCode,
    TransferPlan,
)
from transferkit.ui.plan_view import PlanMatrixWidget


class PlanMatrixWidgetTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.application = QApplication.instance() or QApplication([])

    def test_matrix_shows_all_categories_both_devices_and_actions(self) -> None:
        categories = {
            category_id: PlanEntry(
                category_id=category_id,
                tier=CATEGORY_TIER[category_id],
                source_status=CapabilityStatus.AVAILABLE,
                target_status=CapabilityStatus.PARTIAL if category_id == CategoryId.CONTACTS else CapabilityStatus.AVAILABLE,
                source_reason=ReasonCode.OK,
                target_reason=ReasonCode.USER_ACTION_REQUIRED if category_id == CategoryId.CONTACTS else ReasonCode.OK,
                status=PlanStatus.PARTIAL if category_id == CategoryId.CONTACTS else PlanStatus.TRANSFERABLE,
                reason=ReasonCode.USER_ACTION_REQUIRED if category_id == CategoryId.CONTACTS else ReasonCode.OK,
                source_method="source-api",
                target_method="target-api",
                method="source-api -> target-api",
                source_permissions=["android.permission.READ_CONTACTS"] if category_id == CategoryId.CONTACTS else [],
                target_permissions=["android.permission.WRITE_CONTACTS"] if category_id == CategoryId.CONTACTS else [],
                user_action="Grant contacts permission." if category_id == CategoryId.CONTACTS else None,
            )
            for category_id in CategoryId
        }
        plan = TransferPlan(
            version="1.0",
            created_at=datetime.now(timezone.utc),
            source_device=DeviceInfo(manufacturer="Samsung", model="Source", sdk=34, fingerprint="source"),
            target_device=DeviceInfo(manufacturer="Xiaomi", model="Target", sdk=34, fingerprint="target"),
            categories=categories,
        )

        widget = PlanMatrixWidget()
        widget.set_plan(plan)

        self.assertEqual(widget.table.rowCount(), len(CategoryId))
        self.assertEqual(widget.table.item(0, 0).text(), "Фотографії")
        self.assertIn("source-api", widget.table.item(0, 2).text())
        self.assertIn("target-api", widget.table.item(0, 3).text())
        self.assertIn("Частково", widget.table.item(5, 4).text())
        self.assertIn("Джерело: android.permission.READ_CONTACTS", widget.table.item(5, 5).text())
        self.assertIn("Ціль: android.permission.WRITE_CONTACTS", widget.table.item(5, 5).text())
        self.assertEqual(widget.table.item(5, 6).text(), "Grant contacts permission.")
        self.assertIn("Перенесення ще не почалося", widget.status.text())
        self.assertTrue(widget.table.item(5, 4).flags())
        widget.close()


if __name__ == "__main__":
    unittest.main()
