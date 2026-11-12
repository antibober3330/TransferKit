"""Station orchestration for an authenticated Android-to-Android ADB relay."""

from __future__ import annotations

import hashlib
from dataclasses import dataclass
from uuid import UUID

from ..contracts import ReasonCode, TransferManifest, TransferReceipt
from ..device_detect import AdbClient
from ..history import TransferHistory
from ..integrity import RECOMMENDED_CHUNK_SIZE
from ..manifest import manifest_json_bytes
from .relay import ProgressCallback, relay_authenticated_source_stream
from .usb_adb import TransportError, UsbAdbSourceTransport, UsbAdbTransport


@dataclass(frozen=True, slots=True)
class UsbAdbTransferResult:
    transfer_id: UUID
    manifest_sha256: str
    total_bytes: int
    receipt: TransferReceipt


def run_authenticated_usb_adb_transfer(
    adb: AdbClient,
    history: TransferHistory,
    source_serial: str,
    target_serial: str,
    *,
    on_progress: ProgressCallback | None = None,
) -> UsbAdbTransferResult:
    """Relay a user-prepared source bundle to a user-started target receiver.

    The final receipt records target receive verification only. This function does not
    run restore or destination post-verification and therefore does not create a report.
    """
    if not source_serial or not target_serial or source_serial == target_serial:
        raise ValueError("source and target must be distinct, explicitly selected ADB serials")

    source_device = adb.discover(source_serial)
    target_device = adb.discover(target_serial)
    source = UsbAdbSourceTransport(adb, source_serial)
    target = UsbAdbTransport(adb, target_serial)
    transfer_id: UUID | None = None
    manifest_sha256: str | None = None
    total_bytes = 0
    history_started = False
    chunk_size = RECOMMENDED_CHUNK_SIZE

    try:
        with source, target:
            transfer_id = source.open()
            if source.connection is None:
                raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Android source socket did not open")
            target.open()

            def register_manifest(manifest: TransferManifest) -> None:
                nonlocal manifest_sha256, total_bytes, history_started
                if manifest.transfer_id != transfer_id:
                    raise TransportError(ReasonCode.INVALID_MANIFEST, "Source manifest ID differs from its authenticated session")
                if manifest.source_device.fingerprint != source_device.info.fingerprint:
                    raise TransportError(ReasonCode.INVALID_MANIFEST, "Source manifest fingerprint differs from the selected ADB device")
                manifest_bytes = manifest_json_bytes(manifest)
                manifest_sha256 = hashlib.sha256(manifest_bytes).hexdigest()
                source_fingerprint = hashlib.sha256(manifest.source_device.fingerprint.encode("utf-8")).hexdigest()
                target_fingerprint = hashlib.sha256(target_device.info.fingerprint.encode("utf-8")).hexdigest()
                history.begin_transfer(
                    manifest.transfer_id,
                    manifest_sha256=manifest_sha256,
                    source_fingerprint=source_fingerprint,
                    target_fingerprint=target_fingerprint,
                    protocol_version="1",
                    category_totals={
                        category_id: (len(category.items), sum(item.size for item in category.items))
                        for category_id, category in manifest.categories.items()
                    },
                )
                total_bytes = sum(
                    item.size
                    for category in manifest.categories.values()
                    for item in category.items
                )
                history_started = True

            def record_chunk_ack(category_id, item_id, decoded, _acknowledgement) -> None:
                if not history_started or transfer_id is None:
                    raise RuntimeError("manifest identity must be persisted before chunk acknowledgements")
                header = decoded.header
                history.record_chunk_ack(
                    transfer_id,
                    category_id,
                    item_id,
                    chunk_index=header.chunk_index,
                    total_chunks=header.total_chunks,
                    chunk_size=chunk_size,
                    byte_offset=header.chunk_index * chunk_size,
                    payload_size=len(decoded.payload),
                    payload_crc32=header.payload_crc32,
                )

            receipt = relay_authenticated_source_stream(
                source.connection,
                target,
                chunk_size=chunk_size,
                on_progress=on_progress,
                on_manifest=register_manifest,
                on_chunk_ack=record_chunk_ack,
            )
            if transfer_id is None or manifest_sha256 is None or not history_started:
                raise RuntimeError("source relay completed without registering its manifest")
            if receipt.transfer_id != transfer_id:
                raise TransportError(ReasonCode.INVALID_MANIFEST, "Target receipt belongs to a different transfer")
            history.save_receipt(receipt)
            history.set_status(transfer_id, "COMPLETED")
            return UsbAdbTransferResult(
                transfer_id=transfer_id,
                manifest_sha256=manifest_sha256,
                total_bytes=total_bytes,
                receipt=receipt,
            )
    except Exception as exc:
        if history_started and transfer_id is not None:
            status = (
                "PAUSED_AWAITING_USER"
                if isinstance(exc, TransportError) and exc.reason == ReasonCode.TRANSPORT_INTERRUPTED
                else "PAUSED_BLOCKED"
            )
            try:
                history.set_status(transfer_id, status)
            except (KeyError, ValueError, OSError):
                pass
        raise
