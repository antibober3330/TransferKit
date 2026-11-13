import os
import tempfile
import unittest
from pathlib import Path

os.environ.setdefault("QT_QPA_PLATFORM", "offscreen")

from PySide6.QtCore import QSettings
from PySide6.QtWidgets import QApplication

from transferkit.device_detect import AdbDevice, AdbState
from transferkit.history import TransferHistory
from transferkit.monitor import TransferProgressSnapshot
from transferkit.ui.station import AdbStateWatcher, StationWindow
from transferkit.ui.views import ProgressView, ReportView, SettingsView


class StationUiTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.application = QApplication.instance() or QApplication([])

    def test_station_has_navigation_for_all_required_screens(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            settings = QSettings(str(Path(folder) / "settings.ini"), QSettings.Format.IniFormat)
            history = TransferHistory(Path(folder) / "history.sqlite3")
            window = StationWindow(settings=settings, history=history, auto_refresh=False)

            self.assertEqual(window.navigation.count(), 5)
            self.assertEqual(window.pages.count(), 5)
            for index in range(5):
                window.navigation.setCurrentRow(index)
                self.assertEqual(window.pages.currentIndex(), index)
            self.assertIsInstance(window.progress_view, ProgressView)
            self.assertIsInstance(window.report_view, ReportView)
            self.assertFalse(window.transfer_button.isEnabled())
            window.close()

    def test_transfer_requires_a_current_plan_for_the_selected_serials(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            settings = QSettings(str(Path(folder) / "settings.ini"), QSettings.Format.IniFormat)
            history = TransferHistory(Path(folder) / "history.sqlite3")
            window = StationWindow(settings=settings, history=history, auto_refresh=False)
            window.source_combo.addItem("Source", ("SOURCE", "device"))
            window.target_combo.addItem("Target", ("TARGET", "device"))
            window._update_scan_enabled()
            self.assertTrue(window.scan_button.isEnabled())
            self.assertFalse(window.transfer_button.isEnabled())

            window._planned_serials = ("SOURCE", "TARGET")
            window._update_scan_enabled()
            self.assertTrue(window.transfer_button.isEnabled())

            window.target_combo.addItem("Other target", ("OTHER", "device"))
            window.target_combo.setCurrentIndex(1)
            window._update_scan_enabled()
            self.assertFalse(window.transfer_button.isEnabled())
            self.assertIsNone(window._planned_serials)
            self.assertIn("змінився", window.plan_view.status.text())
            window.close()

    def test_progress_and_report_views_show_real_state(self) -> None:
        progress = ProgressView()
        progress.set_progress(62, "Передачу призупинено", "Фото: 1523 із 2450")
        progress.set_categories(
            [{"category": "Фото", "completed": 1523, "total": 2450, "status": "Призупинено"}]
        )
        self.assertEqual(progress.progress.value(), 62)
        self.assertEqual(progress.status.text(), "Передачу призупинено")
        self.assertIn("1523", progress.details.text())
        self.assertEqual(progress.categories.item(0, 1).text(), "1523")
        progress.set_adb_states("SOURCE", "device", "TARGET", "unauthorized")
        self.assertIn("SOURCE", progress.adb_devices.text())
        self.assertIn("підключено", progress.adb_devices.text())
        self.assertIn("не авторизовано", progress.adb_devices.text())
        progress.set_transport_reason("З’єднання перервано")
        self.assertIn("З’єднання перервано", progress.transport_reason.text())
        with self.assertRaisesRegex(ValueError, "between 0 and 100"):
            progress.set_progress(101, "invalid")

        report = ReportView()
        report.set_report(
            {
                "transfer_id": "transfer-1",
                "summary": {
                    "total_categories": 2,
                    "transferable": 1,
                    "partial": 1,
                    "unavailable": 0,
                    "failed_integrity": 1,
                },
                "categories": [
                    {
                        "category_id": "photos",
                        "tier": "A",
                        "plan_status": "TRANSFERABLE",
                        "status": "FAILED_INTEGRITY",
                        "restored_count": 5,
                    }
                ],
            }
        )
        self.assertIn("integrity-помилок: 1", report.status.text())
        self.assertEqual(report.categories.item(0, 3).text(), "Помилка цілісності")
        self.assertIn("transfer-1", report.contents.toPlainText())
        progress.close()
        report.close()

    def test_station_renders_ack_speed_and_bounded_eta_snapshot(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            settings = QSettings(str(Path(folder) / "settings.ini"), QSettings.Format.IniFormat)
            history = TransferHistory(Path(folder) / "history.sqlite3")
            window = StationWindow(settings=settings, history=history, auto_refresh=False)
            window._on_transfer_progress(
                TransferProgressSnapshot(
                    confirmed_bytes=50,
                    total_bytes=100,
                    active_seconds=2.0,
                    bytes_per_second=1024.0,
                    eta_seconds=5,
                    is_running=True,
                    is_complete=False,
                )
            )
            self.assertEqual(window.progress_view.progress.value(), 50)
            self.assertIn("ACK цілі", window.progress_view.details.text())
            self.assertIn("1.0 KiB/с", window.progress_view.details.text())
            self.assertIn("близько 5 с", window.progress_view.details.text())
            window.close()

    def test_adb_state_watcher_resolves_only_the_requested_serial(self) -> None:
        devices = [
            AdbDevice(serial="SOURCE", state=AdbState.OFFLINE),
            AdbDevice(serial="OTHER", state=AdbState.DEVICE),
        ]
        self.assertEqual(AdbStateWatcher.state_for_serial(devices, "SOURCE"), "offline")
        self.assertEqual(AdbStateWatcher.state_for_serial(devices, "TARGET"), "absent")

    def test_settings_save_local_adb_and_confirmation_preferences_without_claiming_inactive_modes(self) -> None:
        with tempfile.TemporaryDirectory(dir=Path.cwd()) as folder:
            settings = QSettings(str(Path(folder) / "settings.ini"), QSettings.Format.IniFormat)
            view = SettingsView(settings)
            view.adb_path.setText(r"C:\Android\platform-tools\adb.exe")
            view.confirm_before_transfer.setChecked(False)
            view.save()
            self.assertEqual(settings.value("adb/executable"), r"C:\Android\platform-tools\adb.exe")
            self.assertIsNone(settings.value("transfer/default_mode"))
            self.assertFalse(settings.value("transfer/confirm_before_start", type=bool))
            self.assertIn("ще не під’єднані", view.mode_status.text())
            self.assertIn("збережено", view.status.text())
            view.close()


if __name__ == "__main__":
    unittest.main()
