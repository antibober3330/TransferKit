"""USB transport that forwards an ADB localabstract socket to the station."""

from __future__ import annotations

import hashlib
import hmac
import json
import secrets
import socket
import struct
import time
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from uuid import UUID

from ..contracts import CategoryId, ReasonCode, TransferManifest, TransferReceipt, validate_document
from ..device_detect import AdbClient, AdbError
from ..integrity import DecodedChunk, decode_chunk, iter_file_chunks
from ..manifest import manifest_json_bytes


MANIFEST_MAGIC = b"TKMF"
ACK_MAGIC = b"TKAK"
CONTROL_VERSION = 1
ACK_KIND_MANIFEST = 0
ACK_KIND_CHUNK = 1
ACK_OK = 0
ACK_REJECTED = 1
ACK_CRC32_MISMATCH = 2
ACK_INVALID_MANIFEST = 3
ACK_TARGET_WRITE_FAILED = 4
ACK_TRANSFER_MISMATCH = 5
MANIFEST_HEADER = struct.Struct(">4sBI")
ACK_STRUCT = struct.Struct(">4sBB16s4s4sIIB")
CONTROL_SOCKET = "transferkit-control-v1"
OFFER_STRUCT = struct.Struct(">4sB16s32s")
OFFER_ACK_STRUCT = struct.Struct(">4sBB16s")
CHALLENGE_STRUCT = struct.Struct(">4sB16s32s")
PROOF_STRUCT = struct.Struct(">4sB32s")
AUTH_RESULT_STRUCT = struct.Struct(">4sBB16s")
FINISH_REQUEST_STRUCT = struct.Struct(">4sB16s")
RECEIPT_RESPONSE_HEADER = struct.Struct(">4sBB16sI")
SOURCE_IDENTITY_STRUCT = struct.Struct(">4sB16s")
SOURCE_DATA_SOCKET = "transferkit-source-v1"
SOURCE_CONTROL_SOCKET = "transferkit-source-control-v1"
MAX_RECEIPT_BYTES = 64 * 1024 * 1024
AUTH_DOMAIN = b"TransferKit USB ADB v1\0"


def usb_adb_auth_proof(transfer_id: UUID, token: bytes, nonce: bytes) -> bytes:
    """Compute the protocol-defined HMAC proof; token and nonce are both 32 bytes."""
    if len(token) != 32 or len(nonce) != 32:
        raise ValueError("token and nonce must be exactly 32 bytes")
    return hmac.new(token, AUTH_DOMAIN + transfer_id.bytes + nonce, hashlib.sha256).digest()


class TransportError(RuntimeError):
    def __init__(self, reason: ReasonCode, message: str):
        super().__init__(message)
        self.reason = reason


@dataclass(frozen=True, slots=True)
class ChunkAcknowledgement:
    transfer_id: UUID
    category_id_hash: bytes
    item_id_hash: bytes
    chunk_index: int
    payload_crc32: int
    status: int


def _read_exact(connection: socket.socket, size: int) -> bytes:
    parts: list[bytes] = []
    remaining = size
    while remaining:
        try:
            piece = connection.recv(remaining)
        except (TimeoutError, OSError) as exc:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "ADB transport was interrupted") from exc
        if not piece:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "ADB transport closed before acknowledgement")
        parts.append(piece)
        remaining -= len(piece)
    return b"".join(parts)


class UsbAdbTransport:
    def __init__(
        self,
        adb: AdbClient,
        serial: str,
        *,
        abstract_socket: str = "transferkit-v1",
        control_abstract_socket: str = CONTROL_SOCKET,
        connect_timeout: float = 5.0,
        io_timeout: float = 30.0,
    ):
        if not serial:
            raise ValueError("serial must not be empty")
        if connect_timeout <= 0 or io_timeout <= 0:
            raise ValueError("timeouts must be positive")
        self.adb = adb
        self.serial = serial
        self.abstract_socket = abstract_socket
        self.control_abstract_socket = control_abstract_socket
        self.connect_timeout = connect_timeout
        self.io_timeout = io_timeout
        self.local_port: int | None = None
        self.control_local_port: int | None = None
        self.connection: socket.socket | None = None
        self.control_connection: socket.socket | None = None
        self._active_transfer_id: UUID | None = None
        self._adb_authenticated_transport = False

    def __enter__(self) -> UsbAdbTransport:
        self.open()
        return self

    def __exit__(self, _exc_type, _exc, _traceback) -> None:
        self.close()

    def open(self) -> None:
        if self.connection is not None:
            return
        try:
            self.local_port = self.adb.create_forward(self.serial, self.abstract_socket)
            self.control_local_port = self.adb.create_forward(self.serial, self.control_abstract_socket)
        except AdbError as exc:
            self._remove_forwards()
            raise TransportError(exc.reason, str(exc)) from exc
        deadline = time.monotonic() + self.connect_timeout
        try:
            self.connection = self._connect_forwarded(self.local_port, deadline)
            self.control_connection = self._connect_forwarded(self.control_local_port, deadline)
            self._adb_authenticated_transport = True
        except OSError as exc:
            self.close()
            raise TransportError(
                ReasonCode.TRANSPORT_INTERRUPTED,
                "Android USB/ADB receiver did not accept both authenticated session sockets",
            ) from exc

    def _connect_forwarded(self, port: int | None, deadline: float) -> socket.socket:
        if port is None:
            raise OSError("ADB forward is not configured")
        last_error: OSError | None = None
        while time.monotonic() < deadline:
            try:
                connection = socket.create_connection(
                    ("127.0.0.1", port),
                    timeout=min(1.0, self.connect_timeout),
                )
                connection.settimeout(self.io_timeout)
                return connection
            except OSError as exc:
                last_error = exc
                time.sleep(0.1)
        raise last_error or OSError("ADB forward connection timed out")

    def close(self) -> None:
        for connection in (self.connection, self.control_connection):
            if connection is not None:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connection.close()
        self.connection = None
        self.control_connection = None
        self._remove_forwards()
        self._active_transfer_id = None
        self._adb_authenticated_transport = False

    def _remove_forwards(self) -> None:
        for port in (self.local_port, self.control_local_port):
            if port is not None:
                try:
                    self.adb.remove_forward(self.serial, port)
                except AdbError:
                    pass
        self.local_port = None
        self.control_local_port = None

    def _authenticate_session(self, transfer_id: UUID) -> None:
        control = self.control_connection
        data = self._require_connection()
        if control is None:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "ADB control channel is not open")
        token = bytearray(secrets.token_bytes(32))
        try:
            control.sendall(OFFER_STRUCT.pack(b"TKSO", CONTROL_VERSION, transfer_id.bytes, bytes(token)))
            offer_magic, offer_version, offer_status, offer_transfer = OFFER_ACK_STRUCT.unpack(
                _read_exact(control, OFFER_ACK_STRUCT.size)
            )
            if offer_magic != b"TKOA" or offer_version != CONTROL_VERSION or offer_transfer != transfer_id.bytes:
                raise TransportError(ReasonCode.IO_ERROR, "Invalid USB/ADB token-offer acknowledgement")
            if offer_status != 0:
                raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Android receiver rejected the one-time session offer")

            challenge_magic, challenge_version, challenge_transfer, nonce = CHALLENGE_STRUCT.unpack(
                _read_exact(data, CHALLENGE_STRUCT.size)
            )
            if challenge_magic != b"TKHC" or challenge_version != CONTROL_VERSION or challenge_transfer != transfer_id.bytes:
                raise TransportError(ReasonCode.INVALID_MANIFEST, "USB/ADB challenge does not match this transfer")
            proof = usb_adb_auth_proof(transfer_id, bytes(token), nonce)
            data.sendall(PROOF_STRUCT.pack(b"TKHA", CONTROL_VERSION, proof))
            result_magic, result_version, result_status, result_transfer = AUTH_RESULT_STRUCT.unpack(
                _read_exact(data, AUTH_RESULT_STRUCT.size)
            )
            if result_magic != b"TKOK" or result_version != CONTROL_VERSION or result_transfer != transfer_id.bytes:
                raise TransportError(ReasonCode.INVALID_MANIFEST, "USB/ADB authentication result is invalid")
            if result_status != 0:
                raise TransportError(ReasonCode.DEVICE_UNAUTHORIZED, "Android receiver rejected USB/ADB authentication")
        except OSError as exc:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "USB/ADB session authentication was interrupted") from exc
        finally:
            token[:] = b"\0" * len(token)

    def _require_connection(self) -> socket.socket:
        if self.connection is None:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "ADB transport is not open")
        return self.connection

    def _receive_ack(self, expected_kind: int) -> ChunkAcknowledgement:
        connection = self._require_connection()
        raw = _read_exact(connection, ACK_STRUCT.size)
        magic, version, kind, transfer_raw, category_hash, item_hash, chunk_index, crc, status = ACK_STRUCT.unpack(raw)
        if magic != ACK_MAGIC or version != CONTROL_VERSION or kind != expected_kind:
            raise TransportError(ReasonCode.IO_ERROR, "Invalid ADB acknowledgement frame")
        return ChunkAcknowledgement(
            transfer_id=UUID(bytes=transfer_raw),
            category_id_hash=category_hash,
            item_id_hash=item_hash,
            chunk_index=chunk_index,
            payload_crc32=crc,
            status=status,
        )

    @staticmethod
    def _raise_for_ack_status(status: int) -> None:
        reason_by_status = {
            ACK_REJECTED: ReasonCode.INVALID_MANIFEST,
            ACK_CRC32_MISMATCH: ReasonCode.CRC32_MISMATCH,
            ACK_INVALID_MANIFEST: ReasonCode.INVALID_MANIFEST,
            ACK_TARGET_WRITE_FAILED: ReasonCode.TARGET_WRITE_FAILED,
            ACK_TRANSFER_MISMATCH: ReasonCode.INVALID_MANIFEST,
        }
        if status != ACK_OK:
            reason = reason_by_status.get(status, ReasonCode.IO_ERROR)
            raise TransportError(reason, f"Target rejected the transfer frame ({reason.value})")

    def send_manifest(self, manifest: TransferManifest) -> None:
        connection = self._require_connection()
        if self._adb_authenticated_transport:
            self._authenticate_session(manifest.transfer_id)
        payload = manifest_json_bytes(manifest)
        frame = MANIFEST_HEADER.pack(MANIFEST_MAGIC, CONTROL_VERSION, len(payload)) + payload
        try:
            connection.sendall(frame)
        except OSError as exc:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Could not send manifest over ADB") from exc
        acknowledgement = self._receive_ack(ACK_KIND_MANIFEST)
        self._raise_for_ack_status(acknowledgement.status)
        if acknowledgement.transfer_id != manifest.transfer_id:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Target acknowledged a different transfer ID")
        self._active_transfer_id = manifest.transfer_id

    def finish_transfer(self) -> TransferReceipt:
        """Request and validate the target's final receipt after all items are classified."""
        connection = self._require_connection()
        transfer_id = self._active_transfer_id
        if transfer_id is None:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "A manifest must be accepted before finalizing")
        try:
            connection.sendall(FINISH_REQUEST_STRUCT.pack(b"TKFN", CONTROL_VERSION, transfer_id.bytes))
            header = _read_exact(connection, RECEIPT_RESPONSE_HEADER.size)
            magic, version, status, response_transfer, payload_size = RECEIPT_RESPONSE_HEADER.unpack(header)
            if magic != b"TKRC" or version != CONTROL_VERSION:
                raise TransportError(ReasonCode.IO_ERROR, "Invalid final receipt response frame")
            if response_transfer != transfer_id.bytes:
                raise TransportError(ReasonCode.INVALID_MANIFEST, "Target returned a receipt for a different transfer")
            if payload_size > MAX_RECEIPT_BYTES or (status != 0 and payload_size != 0):
                raise TransportError(ReasonCode.IO_ERROR, "Invalid final receipt response length")
            if status != 0:
                reason = ReasonCode.TRANSFER_INCOMPLETE if status == 1 else ReasonCode.INVALID_MANIFEST if status == 2 else ReasonCode.IO_ERROR
                raise TransportError(reason, f"Target could not finalize the transfer ({reason.value})")
            payload = _read_exact(connection, payload_size)
            try:
                document = json.loads(payload)
                receipt = validate_document(document, "receipt.schema.json")
            except (ValueError, TypeError, json.JSONDecodeError) as exc:
                raise TransportError(ReasonCode.INVALID_MANIFEST, "Target returned an invalid final receipt") from exc
            if not isinstance(receipt, TransferReceipt) or receipt.transfer_id != transfer_id:
                raise TransportError(ReasonCode.INVALID_MANIFEST, "Final receipt does not match the active transfer")
            return receipt
        except OSError as exc:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Could not retrieve the final receipt over ADB") from exc

    def send_chunk(self, frame: bytes) -> ChunkAcknowledgement:
        connection = self._require_connection()
        if self._active_transfer_id is None:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Send and validate the manifest before sending chunks")
        try:
            decoded: DecodedChunk = decode_chunk(frame)
        except ValueError as exc:
            raise TransportError(ReasonCode.CRC32_MISMATCH, "Local chunk validation failed") from exc
        if decoded.header.transfer_id != self._active_transfer_id:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Chunk transfer ID does not match the active manifest")
        try:
            connection.sendall(frame)
        except OSError as exc:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Could not send chunk over ADB") from exc
        acknowledgement = self._receive_ack(ACK_KIND_CHUNK)
        self._raise_for_ack_status(acknowledgement.status)
        if (
            acknowledgement.transfer_id != decoded.header.transfer_id
            or acknowledgement.category_id_hash != decoded.header.category_id_hash
            or acknowledgement.item_id_hash != decoded.header.item_id_hash
            or acknowledgement.chunk_index != decoded.header.chunk_index
            or acknowledgement.payload_crc32 != decoded.header.payload_crc32
        ):
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Target acknowledgement does not match the sent chunk")
        return acknowledgement

    def send_file(
        self,
        manifest: TransferManifest,
        category_id: CategoryId | str,
        item_id: str,
        source_path: str | Path,
        expected_size: int,
        *,
        chunk_size: int = 4 * 1024 * 1024,
        start_chunk: int = 0,
        on_chunk_ack: Callable[[ChunkAcknowledgement, int], None] | None = None,
    ) -> int:
        if self._active_transfer_id != manifest.transfer_id:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Manifest must be accepted before sending files")
        category = CategoryId(category_id)
        manifest_category = manifest.categories.get(category)
        item = next((candidate for candidate in manifest_category.items if candidate.id == item_id), None) if manifest_category else None
        if item is None or item.size != expected_size:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "File does not match the active manifest item")
        if expected_size < 0 or chunk_size < 1 or start_chunk < 0:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Invalid file size, chunk size, or resume index")
        total_chunks = max(1, (expected_size + chunk_size - 1) // chunk_size)
        if start_chunk > total_chunks:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Resume index is beyond the item's chunk range")
        digest = hashlib.sha256()
        sent = 0
        frames = iter_file_chunks(
            source_path,
            manifest.transfer_id,
            category_id,
            item_id,
            expected_size,
            chunk_size=chunk_size,
            start_chunk=start_chunk,
            on_payload=digest.update,
        )
        while True:
            try:
                frame = next(frames)
            except StopIteration:
                break
            except OSError as exc:
                raise TransportError(ReasonCode.SOURCE_READ_FAILED, "Source file could not be read") from exc
            acknowledgement = self.send_chunk(frame)
            sent += 1
            if on_chunk_ack is not None:
                confirmed_bytes = min(expected_size, (acknowledgement.chunk_index + 1) * chunk_size)
                on_chunk_ack(acknowledgement, confirmed_bytes)
        if digest.hexdigest() != item.sha256:
            raise TransportError(ReasonCode.SHA256_MISMATCH, "Source file changed during ADB transfer")
        return sent


class UsbAdbSourceTransport:
    """Connect to a user-started Android source, learn its transfer ID, and authenticate."""

    def __init__(
        self,
        adb: AdbClient,
        serial: str,
        *,
        data_abstract_socket: str = SOURCE_DATA_SOCKET,
        control_abstract_socket: str = SOURCE_CONTROL_SOCKET,
        connect_timeout: float = 5.0,
        io_timeout: float = 60.0,
    ):
        if not serial:
            raise ValueError("serial must not be empty")
        if connect_timeout <= 0 or io_timeout <= 0:
            raise ValueError("timeouts must be positive")
        self.adb = adb
        self.serial = serial
        self.data_abstract_socket = data_abstract_socket
        self.control_abstract_socket = control_abstract_socket
        self.connect_timeout = connect_timeout
        self.io_timeout = io_timeout
        self.data_local_port: int | None = None
        self.control_local_port: int | None = None
        self.connection: socket.socket | None = None
        self.control_connection: socket.socket | None = None
        self.transfer_id: UUID | None = None

    def __enter__(self) -> UsbAdbSourceTransport:
        self.open()
        return self

    def __exit__(self, _exc_type, _exc, _traceback) -> None:
        self.close()

    def open(self) -> UUID:
        if self.connection is not None and self.transfer_id is not None:
            return self.transfer_id
        try:
            self.data_local_port = self.adb.create_forward(self.serial, self.data_abstract_socket)
            self.control_local_port = self.adb.create_forward(self.serial, self.control_abstract_socket)
        except AdbError as exc:
            self.close()
            raise TransportError(exc.reason, str(exc)) from exc
        deadline = time.monotonic() + self.connect_timeout
        try:
            self.connection = self._connect_forwarded(self.data_local_port, deadline)
            self.control_connection = self._connect_forwarded(self.control_local_port, deadline)
            self.transfer_id = self._read_source_identity()
            self._authenticate(self.transfer_id)
            return self.transfer_id
        except TransportError:
            self.close()
            raise
        except OSError as exc:
            self.close()
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Android source ADB session was interrupted") from exc

    def _connect_forwarded(self, port: int | None, deadline: float) -> socket.socket:
        if port is None:
            raise OSError("ADB source forward is not configured")
        last_error: OSError | None = None
        while time.monotonic() < deadline:
            try:
                connection = socket.create_connection(("127.0.0.1", port), timeout=min(1.0, self.connect_timeout))
                connection.settimeout(self.io_timeout)
                return connection
            except OSError as exc:
                last_error = exc
                time.sleep(0.1)
        raise last_error or OSError("ADB source forward connection timed out")

    def _read_source_identity(self) -> UUID:
        connection = self._require_connection()
        magic, version, transfer_id_raw = SOURCE_IDENTITY_STRUCT.unpack(_read_exact(connection, SOURCE_IDENTITY_STRUCT.size))
        if magic != b"TKSI" or version != CONTROL_VERSION:
            raise TransportError(ReasonCode.INVALID_MANIFEST, "Android source identity frame is invalid")
        return UUID(bytes=transfer_id_raw)

    def _authenticate(self, transfer_id: UUID) -> None:
        control = self.control_connection
        data = self._require_connection()
        if control is None:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Android source control channel is not open")
        token = bytearray(secrets.token_bytes(32))
        try:
            control.sendall(OFFER_STRUCT.pack(b"TKSO", CONTROL_VERSION, transfer_id.bytes, bytes(token)))
            offer_magic, offer_version, offer_status, offer_transfer = OFFER_ACK_STRUCT.unpack(
                _read_exact(control, OFFER_ACK_STRUCT.size)
            )
            if offer_magic != b"TKOA" or offer_version != CONTROL_VERSION or offer_transfer != transfer_id.bytes:
                raise TransportError(ReasonCode.IO_ERROR, "Invalid Android source token-offer acknowledgement")
            if offer_status != 0:
                raise TransportError(ReasonCode.INVALID_MANIFEST, "Android source rejected the transfer identity")

            challenge_magic, challenge_version, challenge_transfer, nonce = CHALLENGE_STRUCT.unpack(
                _read_exact(data, CHALLENGE_STRUCT.size)
            )
            if challenge_magic != b"TKHC" or challenge_version != CONTROL_VERSION or challenge_transfer != transfer_id.bytes:
                raise TransportError(ReasonCode.INVALID_MANIFEST, "Android source challenge does not match its identity")
            proof = bytearray(usb_adb_auth_proof(transfer_id, bytes(token), nonce))
            try:
                data.sendall(PROOF_STRUCT.pack(b"TKHA", CONTROL_VERSION, bytes(proof)))
            finally:
                proof[:] = b"\0" * len(proof)
            result_magic, result_version, result_status, result_transfer = AUTH_RESULT_STRUCT.unpack(
                _read_exact(data, AUTH_RESULT_STRUCT.size)
            )
            if result_magic != b"TKOK" or result_version != CONTROL_VERSION or result_transfer != transfer_id.bytes:
                raise TransportError(ReasonCode.INVALID_MANIFEST, "Android source authentication result is invalid")
            if result_status != 0:
                raise TransportError(ReasonCode.DEVICE_UNAUTHORIZED, "Android source rejected USB/ADB authentication")
        except OSError as exc:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Android source authentication was interrupted") from exc
        finally:
            token[:] = b"\0" * len(token)

    def _require_connection(self) -> socket.socket:
        if self.connection is None:
            raise TransportError(ReasonCode.TRANSPORT_INTERRUPTED, "Android source data socket is not open")
        return self.connection

    def close(self) -> None:
        for connection in (self.connection, self.control_connection):
            if connection is not None:
                try:
                    connection.shutdown(socket.SHUT_RDWR)
                except OSError:
                    pass
                connection.close()
        self.connection = None
        self.control_connection = None
        for port in (self.data_local_port, self.control_local_port):
            if port is not None:
                try:
                    self.adb.remove_forward(self.serial, port)
                except AdbError:
                    pass
        self.data_local_port = None
        self.control_local_port = None
        self.transfer_id = None
