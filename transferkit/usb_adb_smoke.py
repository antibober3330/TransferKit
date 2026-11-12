"""Send one deterministic synthetic item through the authenticated USB/ADB receiver."""

from __future__ import annotations

import argparse
import hashlib
import tempfile
from datetime import datetime, timezone
from pathlib import Path
from uuid import uuid4

from .contracts import CategoryId, DeviceInfo, ManifestCategory, ManifestItem, TransferManifest
from .device_detect import AdbClient
from .report import write_receipt_json
from .transport.usb_adb import TransportError, UsbAdbTransport


def _synthetic_manifest(payload: bytes) -> TransferManifest:
    transfer_id = uuid4()
    return TransferManifest(
        version="1.0",
        transfer_id=transfer_id,
        created_at=datetime.now(timezone.utc),
        source_device=DeviceInfo(
            manufacturer="TransferKit Test",
            model="Synthetic Fixture",
            sdk=34,
            fingerprint="transferkit/test-source",
        ),
        categories={
            CategoryId.DOCUMENTS: ManifestCategory(
                provider="SyntheticFixture",
                method="GeneratedBytes",
                items=[
                    ManifestItem(
                        id="usb-adb-smoke",
                        size=len(payload),
                        sha256=hashlib.sha256(payload).hexdigest(),
                        metadata={"path": "usb-adb-smoke.bin"},
                    ),
                ],
            ),
        },
    )


def run_smoke(
    serial: str,
    adb_executable: str | None = None,
    receipt_path: str | Path | None = None,
) -> int:
    """Run an authenticated send, verify the received receipt, and retain target test data."""
    payload = bytes(range(256)) * 256
    manifest = _synthetic_manifest(payload)
    item = manifest.categories[CategoryId.DOCUMENTS].items[0]
    adb = AdbClient(executable=adb_executable, command_timeout=20.0)
    try:
        with tempfile.TemporaryDirectory(prefix="transferkit-usb-adb-smoke-") as folder:
            source = Path(folder) / "fixture.bin"
            source.write_bytes(payload)
            with UsbAdbTransport(adb, serial, connect_timeout=8.0, io_timeout=15.0) as transport:
                transport.send_manifest(manifest)
                chunks = transport.send_file(
                    manifest,
                    CategoryId.DOCUMENTS,
                    item.id,
                    source,
                    item.size,
                    chunk_size=4 * 1024,
                )
                receipt = transport.finish_transfer()
    except (OSError, ValueError, TransportError) as exc:
        print(f"USB/ADB smoke failed: {exc}")
        return 1

    category = receipt.categories.get(CategoryId.DOCUMENTS)
    if category is None or category.items_received != 1 or category.items_verified != 1 or category.items_failed != 0:
        print("USB/ADB smoke failed: final receipt did not verify the synthetic item")
        return 1
    if receipt_path is not None:
        try:
            destination = Path(receipt_path)
            destination.parent.mkdir(parents=True, exist_ok=True)
            write_receipt_json(receipt, destination)
        except OSError:
            print("USB/ADB smoke received a valid receipt but could not save its JSON file")
            return 1
        print(f"Schema-validated receipt atomically saved to {destination}")
    print(
        "USB/ADB receive smoke passed: "
        f"serial={serial}, transfer_id={manifest.transfer_id}, bytes={len(payload)}, chunks={chunks}, "
        f"verified={category.items_verified}/{category.items_received}."
    )
    print("Only generated test bytes were sent. The target retains this app-private inbox item; no restore was performed.")
    return 0


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True, help="Explicit ADB serial for the target Android device")
    parser.add_argument("--adb", help="Optional path to the Android SDK adb executable")
    parser.add_argument("--receipt", help="Optional path for atomically saving the validated receipt JSON")
    arguments = parser.parse_args()
    return run_smoke(arguments.serial, arguments.adb, arguments.receipt)


if __name__ == "__main__":
    raise SystemExit(main())
