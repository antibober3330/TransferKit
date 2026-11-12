"""Windows Station preflight UI for device selection and capability planning."""

from __future__ import annotations

from concurrent.futures import ThreadPoolExecutor
from threading import Event

from pathlib import Path

from PySide6.QtCore import QSettings, QStandardPaths, QThread, Signal
from PySide6.QtWidgets import (
    QComboBox,
    QFormLayout,
    QHBoxLayout,
    QLabel,
    QListWidget,
    QMainWindow,
    QMessageBox,
    QPushButton,
    QStackedWidget,
    QVBoxLayout,
    QWidget,
)

from ..capability_probe import probe_capabilities
from ..device_detect import AdbClient, AdbDevice, AdbError, AdbState
from ..history import TransferHistory
from ..monitor import TransferMonitor, TransferProgressSnapshot
from ..planner import build_transfer_plan
from ..transport.session import UsbAdbTransferResult, run_authenticated_usb_adb_transfer
from ..transport.usb_adb import TransportError
from .plan_view import PlanMatrixWidget
from .reason_text import reason_message
from .views import HistoryView, ProgressView, ReportView, SettingsView


class DeviceRefreshWorker(QThread):
    devices_ready = Signal(object)
    failed = Signal(str)

    def __init__(self, adb_executable: str | None = None, parent: QWidget | None = None):
        super().__init__(parent)
        self.adb_executable = adb_executable

    def run(self) -> None:
        try:
            devices = AdbClient(executable=self.adb_executable).list_devices()
            self.devices_ready.emit(devices)
        except AdbError as exc:
            self.failed.emit(exc.reason.value)
        except Exception:
            self.failed.emit("ADB_UNAVAILABLE")


class AdbStateWatcher(QThread):
    """Poll only the selected ADB serials while a Station transfer is active."""

    state_changed = Signal(str, str)

    def __init__(
        self,
        source_serial: str,
        target_serial: str,
        adb_executable: str | None = None,
        parent: QWidget | None = None,
        *,
        poll_interval: float = 1.0,
    ) -> None:
        super().__init__(parent)
        if not source_serial or not target_serial or source_serial == target_serial:
            raise ValueError("watcher requires distinct, explicit source and target serials")
        if not 0.2 <= poll_interval <= 30:
            raise ValueError("poll_interval must be between 0.2 and 30 seconds")
        self.source_serial = source_serial
        self.target_serial = target_serial
        self.adb_executable = adb_executable
        self.poll_interval = poll_interval
        self._stop_event = Event()

    @staticmethod
    def state_for_serial(devices: list[AdbDevice], serial: str) -> str:
        device = next((entry for entry in devices if entry.serial == serial), None)
        return device.state.value if device is not None else "absent"

    def stop(self) -> None:
        self._stop_event.set()

    def run(self) -> None:
        adb = AdbClient(executable=self.adb_executable, command_timeout=2.0)
        serials = (self.source_serial, self.target_serial)
        last_states: dict[str, str] = {}
        while not self._stop_event.is_set():
            try:
                devices = adb.list_devices()
                states = {serial: self.state_for_serial(devices, serial) for serial in serials}
            except AdbError:
                states = {serial: "adb_unavailable" for serial in serials}
            except Exception:
                states = {serial: "unknown" for serial in serials}

            for serial in serials:
                state = states[serial]
                if last_states.get(serial) != state:
                    self.state_changed.emit(serial, state)
                    last_states[serial] = state
            if self._stop_event.wait(self.poll_interval):
                break


class PlanBuildWorker(QThread):
    plan_ready = Signal(object)
    failed = Signal(str)

    def __init__(self, source_serial: str, target_serial: str, adb_executable: str | None = None, parent=None):
        super().__init__(parent)
        self.source_serial = source_serial
        self.target_serial = target_serial
        self.adb_executable = adb_executable

    @staticmethod
    def _probe(serial: str, adb_executable: str | None):
        client = AdbClient(executable=adb_executable, command_timeout=4.0)
        snapshot = client.discover(serial)
        return probe_capabilities(client, snapshot)

    def run(self) -> None:
        try:
            with ThreadPoolExecutor(max_workers=2, thread_name_prefix="transferkit-probe") as pool:
                source_future = pool.submit(self._probe, self.source_serial, self.adb_executable)
                target_future = pool.submit(self._probe, self.target_serial, self.adb_executable)
                source = source_future.result()
                target = target_future.result()
            self.plan_ready.emit(build_transfer_plan(source, target))
        except AdbError as exc:
            self.failed.emit(exc.reason.value)
        except Exception:
            self.failed.emit("PROBE_FAILED")


class TransferWorker(QThread):
    progress = Signal(object)
    completed = Signal(object)
    failed = Signal(str)

    def __init__(
        self,
        source_serial: str,
        target_serial: str,
        history: TransferHistory,
        adb_executable: str | None = None,
        parent: QWidget | None = None,
    ):
        super().__init__(parent)
        self.source_serial = source_serial
        self.target_serial = target_serial
        self.history = history
        self.adb_executable = adb_executable

    def run(self) -> None:
        try:
            adb = AdbClient(executable=self.adb_executable, command_timeout=10.0)
            monitor = TransferMonitor()
            monitor.start()
            total_initialized = False

            def on_progress(_category, _item, confirmed: int, total: int) -> None:
                nonlocal total_initialized
                if not total_initialized:
                    monitor.set_total_bytes(total)
                    total_initialized = True
                self.progress.emit(monitor.acknowledge(confirmed))

            result = run_authenticated_usb_adb_transfer(
                adb,
                self.history,
                self.source_serial,
                self.target_serial,
                on_progress=on_progress,
            )
            monitor.finish()
            self.completed.emit(result)
        except TransportError as exc:
            self.failed.emit(exc.reason.value)
        except AdbError as exc:
            self.failed.emit(exc.reason.value)
        except Exception:
            self.failed.emit("IO_ERROR")


class StationWindow(QMainWindow):
    NAVIGATION = ("Пристрої та план", "Стан передачі", "Звіти", "Історія", "Налаштування")

    def __init__(self, settings: QSettings | None = None, history=None, *, auto_refresh: bool = True):
        super().__init__()
        self.setWindowTitle("TransferKit — міграція Android")
        self.setMinimumSize(1080, 720)
        self.settings = settings or QSettings("TransferKit", "TransferKit")
        if history is None:
            state_dir = QStandardPaths.writableLocation(QStandardPaths.StandardLocation.AppDataLocation)
            if not state_dir:
                state_dir = str(Path.home() / ".transferkit")
            history = TransferHistory(Path(state_dir) / "history.sqlite3")
        self.history = history
        self._devices: list[AdbDevice] = []
        self._refresh_worker: DeviceRefreshWorker | None = None
        self._plan_worker: PlanBuildWorker | None = None
        self._transfer_worker: TransferWorker | None = None
        self._adb_state_watcher: AdbStateWatcher | None = None
        self._active_serials: tuple[str, str] | None = None
        self._adb_states: dict[str, str] = {}
        self._plan_selection: tuple[str, str] | None = None
        self._planned_serials: tuple[str, str] | None = None

        self.heading = QLabel("Перенесення Android → Android")
        self.heading.setObjectName("stationHeading")
        self.intro = QLabel(
            "Спочатку виберіть обидва пристрої та перевірте можливості. До показу плану жодні дані не копіюються."
        )
        self.intro.setWordWrap(True)

        self.source_combo = QComboBox()
        self.source_combo.setObjectName("sourceDevice")
        self.target_combo = QComboBox()
        self.target_combo.setObjectName("targetDevice")
        self.refresh_button = QPushButton("Оновити пристрої")
        self.refresh_button.clicked.connect(self.refresh_devices)
        self.scan_button = QPushButton("Перевірити й показати план")
        self.scan_button.setEnabled(False)
        self.scan_button.clicked.connect(self.build_plan)
        self.transfer_button = QPushButton("Передати підготовлені дані")
        self.transfer_button.setEnabled(False)
        self.transfer_button.clicked.connect(self.start_transfer)

        device_form = QFormLayout()
        device_form.addRow("Джерело", self.source_combo)
        device_form.addRow("Ціль", self.target_combo)
        action_row = QHBoxLayout()
        action_row.addWidget(self.refresh_button)
        action_row.addWidget(self.scan_button)
        action_row.addWidget(self.transfer_button)

        self.plan_view = PlanMatrixWidget()
        self.devices_page = QWidget()
        layout = QVBoxLayout(self.devices_page)
        layout.addWidget(self.heading)
        layout.addWidget(self.intro)
        layout.addLayout(device_form)
        layout.addLayout(action_row)
        layout.addWidget(self.plan_view, 1)
        self.progress_view = ProgressView()
        self.report_view = ReportView()
        self.history_view = HistoryView(self.history)
        self.settings_view = SettingsView(self.settings)
        self.history_view.report_selected.connect(self._on_report_selected)
        self.settings_view.settings_saved.connect(self.refresh_devices)

        self.navigation = QListWidget()
        self.navigation.setFixedWidth(190)
        self.navigation.addItems(self.NAVIGATION)
        self.pages = QStackedWidget()
        for page in (
            self.devices_page,
            self.progress_view,
            self.report_view,
            self.history_view,
            self.settings_view,
        ):
            self.pages.addWidget(page)
        self.navigation.currentRowChanged.connect(self._on_navigation_changed)
        self.navigation.setCurrentRow(0)

        root = QWidget()
        root_layout = QHBoxLayout(root)
        root_layout.addWidget(self.navigation)
        root_layout.addWidget(self.pages, 1)
        self.setCentralWidget(root)

        self.source_combo.currentIndexChanged.connect(self._update_scan_enabled)
        self.target_combo.currentIndexChanged.connect(self._update_scan_enabled)
        if auto_refresh:
            self.refresh_devices()

    def refresh_devices(self) -> None:
        if self._refresh_worker is not None and self._refresh_worker.isRunning():
            return
        self._planned_serials = None
        self.refresh_button.setEnabled(False)
        self.scan_button.setEnabled(False)
        self.transfer_button.setEnabled(False)
        self.plan_view.clear_plan("Оновлюю список ADB пристроїв…")
        worker = DeviceRefreshWorker(self._adb_executable(), self)
        self._refresh_worker = worker
        worker.devices_ready.connect(self._on_devices_ready)
        worker.failed.connect(self._on_refresh_failed)
        worker.finished.connect(self._on_refresh_finished)
        worker.start()

    def _on_devices_ready(self, devices: list[AdbDevice]) -> None:
        self._devices = devices
        self.source_combo.clear()
        self.target_combo.clear()
        if not devices:
            self.source_combo.addItem("Пристроїв не знайдено", None)
            self.target_combo.addItem("Пристроїв не знайдено", None)
            self.plan_view.clear_plan("Підключіть два Android пристрої та ввімкніть USB debugging.")
            return
        for device in devices:
            name = device.model or device.product or device.serial
            label = f"{name} · {device.serial} · {device.state.value}"
            record = (device.serial, device.state.value)
            self.source_combo.addItem(label, record)
            self.target_combo.addItem(label, record)
        ready_indices = [index for index, device in enumerate(devices) if device.state == AdbState.DEVICE]
        if ready_indices:
            self.source_combo.setCurrentIndex(ready_indices[0])
            target_index = ready_indices[1] if len(ready_indices) > 1 else ready_indices[0]
            self.target_combo.setCurrentIndex(target_index)
        self.plan_view.clear_plan("Оберіть різні підключені пристрої, потім перевірте можливості.")
        self._update_scan_enabled()

    def _on_refresh_failed(self, reason: str) -> None:
        self._planned_serials = None
        self.source_combo.clear()
        self.target_combo.clear()
        self.source_combo.addItem("ADB недоступний", None)
        self.target_combo.addItem("ADB недоступний", None)
        self.plan_view.clear_plan(f"Не вдалося отримати список пристроїв. {reason_message(reason)}")

    def _on_refresh_finished(self) -> None:
        self.refresh_button.setEnabled(True)
        self._refresh_worker = None
        self._update_scan_enabled()

    def _update_scan_enabled(self, *_args) -> None:
        source = self.source_combo.currentData()
        target = self.target_combo.currentData()
        selection = (source[0], target[0]) if isinstance(source, tuple) and isinstance(target, tuple) else None
        if self._planned_serials is not None and selection != self._planned_serials:
            self._planned_serials = None
            self.plan_view.clear_plan("Вибір пристроїв змінився. Перевірте можливості ще раз.")
        ready = (
            isinstance(source, tuple)
            and isinstance(target, tuple)
            and source[1] == AdbState.DEVICE.value
            and target[1] == AdbState.DEVICE.value
            and source[0] != target[0]
        )
        busy = any(
            worker is not None and worker.isRunning()
            for worker in (self._refresh_worker, self._plan_worker, self._transfer_worker)
        )
        self.scan_button.setEnabled(bool(ready) and not busy)
        self.transfer_button.setEnabled(
            bool(ready) and not busy and self._planned_serials is not None and selection == self._planned_serials
        )
        self.refresh_button.setEnabled(not busy)

    def build_plan(self) -> None:
        source = self.source_combo.currentData()
        target = self.target_combo.currentData()
        if not isinstance(source, tuple) or not isinstance(target, tuple) or source[0] == target[0]:
            return
        self.scan_button.setEnabled(False)
        self.transfer_button.setEnabled(False)
        self.refresh_button.setEnabled(False)
        self.plan_view.clear_plan("Виконую read-only перевірки обох пристроїв. Жодних даних не записується…")
        self._plan_selection = (source[0], target[0])
        self._planned_serials = None
        worker = PlanBuildWorker(source[0], target[0], self._adb_executable(), self)
        self._plan_worker = worker
        worker.plan_ready.connect(self._on_plan_ready)
        worker.failed.connect(self._on_plan_failed)
        worker.finished.connect(self._on_plan_finished)
        worker.start()

    def _on_plan_ready(self, plan) -> None:
        if self._plan_selection == self._selected_serials():
            self.plan_view.set_plan(plan)
            self._planned_serials = self._plan_selection
        else:
            self.plan_view.clear_plan("Вибір пристроїв змінився під час перевірки. Запустіть перевірку ще раз.")

    def _on_plan_failed(self, reason: str) -> None:
        self._planned_serials = None
        self.plan_view.clear_plan(f"Не вдалося побудувати план. {reason_message(reason)}")

    def _on_plan_finished(self) -> None:
        self.refresh_button.setEnabled(True)
        self._plan_worker = None
        self._plan_selection = None
        self._update_scan_enabled()

    def _selected_serials(self) -> tuple[str, str] | None:
        source = self.source_combo.currentData()
        target = self.target_combo.currentData()
        if not isinstance(source, tuple) or not isinstance(target, tuple):
            return None
        return source[0], target[0]

    def start_transfer(self) -> None:
        serials = self._selected_serials()
        if serials is None or serials != self._planned_serials or self._transfer_worker is not None:
            return
        source_serial, target_serial = serials
        answer = QMessageBox.question(
            self,
            "Почати прийом підготовлених даних?",
            "На джерелі має бути підготовлений маніфест і запущений USB/ADB source listener; "
            "на цілі — запущений USB/ADB receiver.\n\n"
            f"Джерело: {self.source_combo.currentText()}\nЦіль: {self.target_combo.currentText()}\n\n"
            "Station передасть лише вибрані на джерелі дані в приватне сховище TransferKit на цілі. "
            "Автоматичне відновлення у сховище Android не запускається; після прийому буде збережена квитанція, "
            "а не звіт про restore.",
            QMessageBox.StandardButton.Yes | QMessageBox.StandardButton.No,
            QMessageBox.StandardButton.No,
        )
        if answer != QMessageBox.StandardButton.Yes:
            return

        self._set_transfer_controls_enabled(False)
        self.progress_view.set_categories([])
        self.progress_view.set_progress(0, "Підключаюся до джерела й цілі через USB/ADB…", "Підтвердженого прогресу поки немає.")
        self.progress_view.set_transport_reason(None)
        self._active_serials = (source_serial, target_serial)
        self._adb_states = {source_serial: "checking", target_serial: "checking"}
        self._render_adb_states()
        self.navigation.setCurrentRow(1)
        watcher = AdbStateWatcher(
            source_serial,
            target_serial,
            self._adb_executable(),
            self,
        )
        self._adb_state_watcher = watcher
        watcher.state_changed.connect(self._on_adb_state_changed)
        watcher.start()
        worker = TransferWorker(
            source_serial,
            target_serial,
            self.history,
            self._adb_executable(),
            self,
        )
        self._transfer_worker = worker
        worker.progress.connect(self._on_transfer_progress)
        worker.completed.connect(self._on_transfer_completed)
        worker.failed.connect(self._on_transfer_failed)
        worker.finished.connect(self._on_transfer_finished)
        worker.start()

    def _set_transfer_controls_enabled(self, enabled: bool) -> None:
        self.source_combo.setEnabled(enabled)
        self.target_combo.setEnabled(enabled)
        self._update_scan_enabled()
        if not enabled:
            self.refresh_button.setEnabled(False)
            self.scan_button.setEnabled(False)
            self.transfer_button.setEnabled(False)

    def _on_transfer_progress(self, snapshot: TransferProgressSnapshot) -> None:
        total_bytes = snapshot.total_bytes
        confirmed_bytes = snapshot.confirmed_bytes
        if total_bytes is None or total_bytes <= 0:
            percent = 99 if snapshot.is_complete else 0
        else:
            percent = min(99, max(0, int(confirmed_bytes * 100 / total_bytes)))
        total_text = self._format_bytes(total_bytes) if total_bytes is not None else "невідомий"
        if snapshot.bytes_per_second is None:
            speed_text = "ще визначається"
        else:
            speed_text = f"{self._format_bytes(int(snapshot.bytes_per_second))}/с"
        if snapshot.eta_seconds is None:
            eta_text = "невідомий"
        else:
            eta_text = f"близько {self._format_duration(snapshot.eta_seconds)}"
        details = (
            f"ACK цілі: {self._format_bytes(confirmed_bytes)} із {total_text}; "
            f"швидкість підтверджених байтів: {speed_text}; залишилося: {eta_text}."
        )
        self.progress_view.set_progress(percent, "Передаю й перевіряю чанки через USB/ADB…", details)

    def _on_adb_state_changed(self, serial: str, state: str) -> None:
        if self._active_serials is None or serial not in self._active_serials:
            return
        self._adb_states[serial] = state
        self._render_adb_states()

    def _render_adb_states(self) -> None:
        if self._active_serials is None:
            return
        source_serial, target_serial = self._active_serials
        self.progress_view.set_adb_states(
            source_serial,
            self._adb_states.get(source_serial, "checking"),
            target_serial,
            self._adb_states.get(target_serial, "checking"),
        )

    @staticmethod
    def _format_duration(seconds: int) -> str:
        if seconds < 60:
            return f"{seconds} с"
        minutes, remaining_seconds = divmod(seconds, 60)
        if minutes < 60:
            return f"{minutes} хв {remaining_seconds} с"
        hours, remaining_minutes = divmod(minutes, 60)
        if hours < 24:
            return f"{hours} год {remaining_minutes} хв"
        days, remaining_hours = divmod(hours, 24)
        return f"{days} д {remaining_hours} год"

    def _stop_adb_state_watcher(self) -> None:
        watcher = self._adb_state_watcher
        self._adb_state_watcher = None
        self._active_serials = None
        if watcher is None:
            return
        watcher.stop()
        if watcher.isRunning():
            watcher.wait(3500)

    def closeEvent(self, event) -> None:
        self._stop_adb_state_watcher()
        super().closeEvent(event)

    def _on_transfer_completed(self, result: UsbAdbTransferResult) -> None:
        received = sum(row.items_received for row in result.receipt.categories.values())
        verified = sum(row.items_verified for row in result.receipt.categories.values())
        failed = sum(row.items_failed for row in result.receipt.categories.values())
        status = "Прийом завершено; restore не виконувався." if failed == 0 else "Прийом завершено з помилками окремих items; restore не виконувався."
        details = (
            f"Transfer ID: {result.transfer_id}\n"
            f"Отримано {received}, перевірено {verified}, помилок прийому {failed}; "
            f"підтверджений обсяг {self._format_bytes(result.total_bytes)}. Квитанцію збережено в історії."
        )
        self.progress_view.set_progress(100, status, details)
        self.progress_view.set_transport_reason(None)
        self.history_view.refresh()

    def _on_transfer_failed(self, reason: str) -> None:
        message = reason_message(reason)
        self.progress_view.set_progress(
            self.progress_view.progress.value(),
            "Передача зупинилася до завершення.",
            message,
        )
        self.progress_view.set_transport_reason(message)

    def _on_transfer_finished(self) -> None:
        self._stop_adb_state_watcher()
        self._transfer_worker = None
        self._set_transfer_controls_enabled(True)
        self._update_scan_enabled()

    @staticmethod
    def _format_bytes(value: int) -> str:
        amount = float(value)
        for unit in ("B", "KiB", "MiB", "GiB", "TiB"):
            if amount < 1024 or unit == "TiB":
                return f"{amount:.1f} {unit}"
            amount /= 1024
        return f"{amount:.1f} TiB"

    def _adb_executable(self) -> str | None:
        configured = str(self.settings.value("adb/executable", "")).strip()
        return configured or None

    def _on_report_selected(self, report: dict | None) -> None:
        self.report_view.set_report(report)
        self.navigation.setCurrentRow(2)

    def _on_navigation_changed(self, index: int) -> None:
        self.pages.setCurrentIndex(index)
        if index == 3:
            self.history_view.refresh()
