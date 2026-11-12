"""Read-only ADB discovery for Android devices connected to the station."""

from __future__ import annotations

import os
import re
import shutil
import subprocess
from datetime import datetime, timezone
from enum import StrEnum
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field

from .contracts import DeviceInfo, ReasonCode


class AdbState(StrEnum):
    DEVICE = "device"
    UNAUTHORIZED = "unauthorized"
    OFFLINE = "offline"
    NO_PERMISSIONS = "no permissions"
    UNKNOWN = "unknown"


class AdbError(RuntimeError):
    def __init__(self, reason: ReasonCode, message: str):
        super().__init__(message)
        self.reason = reason


class AdbDevice(BaseModel):
    model_config = ConfigDict(extra="forbid")

    serial: str = Field(min_length=1, max_length=256)
    state: AdbState
    product: str | None = None
    model: str | None = None
    device: str | None = None
    transport_id: int | None = None


class StoragePartition(BaseModel):
    model_config = ConfigDict(extra="forbid")

    mount: str = Field(min_length=1, max_length=256)
    total_kib: int = Field(ge=0)
    used_kib: int = Field(ge=0)
    available_kib: int = Field(ge=0)
    percent_used: int | None = Field(default=None, ge=0, le=100)


class DeviceSnapshot(BaseModel):
    model_config = ConfigDict(extra="forbid")

    serial: str
    info: DeviceInfo
    memory_total_kib: int | None = Field(default=None, ge=0)
    storage: list[StoragePartition] = Field(default_factory=list)
    probe_warnings: list[ReasonCode] = Field(default_factory=list)
    discovered_at: datetime


_PROPERTY_KEYS = {
    "manufacturer": "ro.product.manufacturer",
    "model": "ro.product.model",
    "sdk": "ro.build.version.sdk",
    "fingerprint": "ro.build.fingerprint",
}


def find_adb_executable() -> str:
    """Find ADB without relying on a configured ANDROID_HOME variable."""
    candidates: list[Path] = []
    for env_name in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk = os.environ.get(env_name)
        if sdk:
            candidates.append(Path(sdk) / "platform-tools" / "adb.exe")
            candidates.append(Path(sdk) / "platform-tools" / "adb")
    local_app_data = os.environ.get("LOCALAPPDATA")
    if local_app_data:
        candidates.append(Path(local_app_data) / "Android" / "Sdk" / "platform-tools" / "adb.exe")
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    discovered = shutil.which("adb.exe") or shutil.which("adb")
    if discovered:
        return discovered
    raise AdbError(ReasonCode.ADB_UNAVAILABLE, "ADB executable was not found")


def parse_devices_output(output: str) -> list[AdbDevice]:
    devices: list[AdbDevice] = []
    for raw_line in output.splitlines():
        line = raw_line.strip()
        if not line or line.startswith("List of devices attached"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        serial, state_text = parts[0], parts[1]
        details_start = 2
        if state_text == "no" and len(parts) > 2 and parts[2] == "permissions":
            state_text = "no permissions"
            details_start = 3
        try:
            state = AdbState(state_text)
        except ValueError:
            state = AdbState.UNKNOWN
        details: dict[str, str] = {}
        for field in parts[details_start:]:
            key, separator, value = field.partition(":")
            if separator:
                details[key] = value
        transport_text = details.get("transport_id")
        try:
            transport_id = int(transport_text) if transport_text else None
        except ValueError:
            transport_id = None
        devices.append(
            AdbDevice(
                serial=serial,
                state=state,
                product=details.get("product"),
                model=details.get("model"),
                device=details.get("device"),
                transport_id=transport_id,
            )
        )
    return devices


def parse_mem_total_kib(output: str) -> int | None:
    match = re.search(r"^MemTotal:\s+(\d+)\s+kB\s*$", output, re.MULTILINE)
    return int(match.group(1)) if match else None


def parse_df_output(output: str) -> list[StoragePartition]:
    partitions: list[StoragePartition] = []
    for line in output.splitlines():
        fields = line.split()
        if len(fields) < 6:
            continue
        try:
            total_kib = int(fields[1])
            used_kib = int(fields[2])
            available_kib = int(fields[3])
        except ValueError:
            continue  # header or an OEM-specific line
        percent_text = next((field for field in fields[4:-1] if field.endswith("%")), "")
        try:
            percent_used = int(percent_text[:-1]) if percent_text else None
        except ValueError:
            percent_used = None
        if percent_used is not None and not 0 <= percent_used <= 100:
            percent_used = None
        partitions.append(
            StoragePartition(
                mount=fields[-1],
                total_kib=total_kib,
                used_kib=used_kib,
                available_kib=available_kib,
                percent_used=percent_used,
            )
        )
    return partitions


class AdbClient:
    def __init__(self, executable: str | None = None, command_timeout: float = 4.0):
        if command_timeout <= 0 or command_timeout > 120:
            raise ValueError("command_timeout must be between 0 and 120 seconds")
        self.executable = executable or find_adb_executable()
        self.command_timeout = command_timeout

    def _run(self, args: list[str], timeout: float | None = None) -> str:
        command = [self.executable, *args]
        creationflags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        try:
            completed = subprocess.run(
                command,
                check=False,
                capture_output=True,
                text=True,
                encoding="utf-8",
                errors="replace",
                timeout=timeout or self.command_timeout,
                creationflags=creationflags,
            )
        except FileNotFoundError as exc:
            raise AdbError(ReasonCode.ADB_UNAVAILABLE, "ADB executable could not be started") from exc
        except subprocess.TimeoutExpired as exc:
            raise AdbError(ReasonCode.PROBE_TIMEOUT, "ADB command timed out") from exc
        if completed.returncode != 0:
            diagnostic = f"{completed.stderr}\n{completed.stdout}".lower()
            if any(term in diagnostic for term in ("permission denial", "permission denied", "securityexception")):
                reason = ReasonCode.PERMISSION_DENIED
            elif any(term in diagnostic for term in ("unknown uri", "no such provider", "provider not found")):
                reason = ReasonCode.API_UNAVAILABLE
            elif "daemon" in diagnostic or "server" in diagnostic or "cannot connect" in diagnostic:
                reason = ReasonCode.ADB_UNAVAILABLE
            else:
                reason = ReasonCode.ADB_COMMAND_FAILED
            raise AdbError(reason, "ADB command failed")
        return completed.stdout.strip()

    def list_devices(self) -> list[AdbDevice]:
        return parse_devices_output(self._run(["devices", "-l"], timeout=min(self.command_timeout, 10.0)))

    def run_shell(self, serial: str, *args: str, timeout: float | None = None) -> str:
        """Run a caller-supplied, read-only Android shell command."""
        return self._run(["-s", serial, "shell", *args], timeout=timeout)

    def create_forward(self, serial: str, abstract_socket: str) -> int:
        if not re.fullmatch(r"[A-Za-z0-9._-]{1,128}", abstract_socket):
            raise ValueError("abstract_socket contains unsupported characters")
        output = self._run(
            ["-s", serial, "forward", "tcp:0", f"localabstract:{abstract_socket}"],
            timeout=10.0,
        )
        try:
            port = int(output)
        except ValueError as exc:
            raise AdbError(ReasonCode.ADB_COMMAND_FAILED, "ADB did not return a forwarded TCP port") from exc
        if not 1 <= port <= 65535:
            raise AdbError(ReasonCode.ADB_COMMAND_FAILED, "ADB returned an invalid forwarded TCP port")
        return port

    def remove_forward(self, serial: str, local_port: int) -> None:
        if not 1 <= local_port <= 65535:
            raise ValueError("local_port must be a valid TCP port")
        self._run(["-s", serial, "forward", "--remove", f"tcp:{local_port}"], timeout=10.0)

    def discover(self, serial: str) -> DeviceSnapshot:
        devices = self.list_devices()
        device = next((item for item in devices if item.serial == serial), None)
        if device is None:
            raise AdbError(ReasonCode.DEVICE_NOT_FOUND, "Selected ADB device is not connected")
        if device.state == AdbState.UNAUTHORIZED:
            raise AdbError(ReasonCode.DEVICE_UNAUTHORIZED, "Confirm the ADB authorization prompt on the device")
        if device.state == AdbState.OFFLINE:
            raise AdbError(ReasonCode.DEVICE_OFFLINE, "ADB device is offline")
        if device.state != AdbState.DEVICE:
            raise AdbError(ReasonCode.ADB_COMMAND_FAILED, "ADB device is not ready")

        values = {name: self._shell(serial, "getprop", prop) for name, prop in _PROPERTY_KEYS.items()}
        if not values["manufacturer"]:
            values["manufacturer"] = self._shell(serial, "getprop", "ro.product.brand")
        if not values["model"]:
            values["model"] = self._shell(serial, "getprop", "ro.product.device")
        if not values["manufacturer"] or not values["model"] or not values["fingerprint"]:
            raise AdbError(ReasonCode.API_UNAVAILABLE, "Required Android device properties are unavailable")
        try:
            sdk = int(values["sdk"])
        except ValueError as exc:
            raise AdbError(ReasonCode.API_UNAVAILABLE, "Android SDK version is unavailable") from exc
        if sdk < 26:
            raise AdbError(ReasonCode.UNSUPPORTED, "Android 8.0 or newer is required")
        info = DeviceInfo(
            manufacturer=values["manufacturer"],
            model=values["model"],
            sdk=sdk,
            fingerprint=values["fingerprint"],
        )

        warnings: list[ReasonCode] = []
        memory_output = self._optional_shell(serial, ["cat", "/proc/meminfo"], warnings)
        memory_total = parse_mem_total_kib(memory_output) if memory_output is not None else None
        if memory_total is None:
            warnings.append(ReasonCode.API_UNAVAILABLE)

        df_output = self._optional_shell(serial, ["df", "-k", "/data", "/storage/emulated/0"], warnings)
        storage = parse_df_output(df_output) if df_output is not None else []
        if not storage:
            warnings.append(ReasonCode.API_UNAVAILABLE)

        return DeviceSnapshot(
            serial=serial,
            info=info,
            memory_total_kib=memory_total,
            storage=storage,
            probe_warnings=warnings,
            discovered_at=datetime.now(timezone.utc),
        )

    def _shell(self, serial: str, *args: str) -> str:
        return self.run_shell(serial, *args)

    def _optional_shell(self, serial: str, args: list[str], warnings: list[ReasonCode]) -> str | None:
        try:
            return self._shell(serial, *args)
        except AdbError as exc:
            warnings.append(exc.reason)
            return None
