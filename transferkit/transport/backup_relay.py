"""Streaming, schema-checked ZIP backup relay primitives.

Password mode wraps the complete ZIP in a versioned AES-256-GCM container. The archive hash still
does not identify its creator; password encryption provides confidentiality and tamper detection.
"""

from __future__ import annotations

import hashlib
import json
import os
import tempfile
import zipfile
from contextlib import contextmanager
from dataclasses import dataclass
from pathlib import Path
from typing import Iterator, Mapping
from uuid import UUID

from cryptography.exceptions import InvalidTag
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes
from cryptography.hazmat.primitives.kdf.scrypt import Scrypt

from ..contracts import (
    CategoryId,
    TransferManifest,
    TransferReceipt,
    canonical_json_bytes,
    validate_document,
)


MANIFEST_MEMBER = "manifest.json"
RECEIPT_MEMBER = "receipt.json"
MAX_MANIFEST_BYTES = 64 * 1024 * 1024
MAX_RECEIPT_BYTES = 8 * 1024 * 1024
MAX_ARCHIVE_ENTRIES = 100_002
MAX_ARCHIVE_OVERHEAD_BYTES = 128 * 1024 * 1024
COPY_BUFFER_BYTES = 1024 * 1024
ENCRYPTED_ARCHIVE_MAGIC = b"TKAE"
ENCRYPTED_ARCHIVE_VERSION = 1
_ENCRYPTED_HEADER_PREFIX = ENCRYPTED_ARCHIVE_MAGIC + bytes((ENCRYPTED_ARCHIVE_VERSION,))
_SALT_BYTES = 16
_NONCE_BYTES = 12
_GCM_TAG_BYTES = 16
_ENCRYPTED_HEADER_BYTES = len(_ENCRYPTED_HEADER_PREFIX) + _SALT_BYTES + _NONCE_BYTES
_SCRYPT_N = 1 << 15
_SCRYPT_R = 8
_SCRYPT_P = 1
_MIN_PASSWORD_BYTES = 8
_MAX_PASSWORD_BYTES = 1024
ENCRYPTED_RELAY_ENVELOPE_OVERHEAD_BYTES = _ENCRYPTED_HEADER_BYTES + _GCM_TAG_BYTES


class BackupRelayError(ValueError):
    """The relay archive cannot be safely created or restored."""


@dataclass(frozen=True, slots=True)
class ExtractedBackup:
    transfer_id: UUID
    manifest: TransferManifest
    receipt: TransferReceipt
    directory: Path
    item_files: Mapping[tuple[CategoryId, str], Path]


def write_backup_archive(
    manifest: TransferManifest,
    receipt: TransferReceipt,
    verified_items: Mapping[tuple[CategoryId | str, str], str | Path],
    destination: str | Path,
    *,
    password: str | bytes | bytearray | None = None,
) -> Path:
    """Create an atomic ZIP or optional password-encrypted relay archive.

    `verified_items` must contain exactly the items marked verified by the final receipt. Payloads
    stream directly from source files into ZIP entries; no item is buffered in memory. A password
    selects the TKAE AES-256-GCM container; no password is persisted in the archive or alongside it.
    """
    if manifest.transfer_id != receipt.transfer_id:
        raise BackupRelayError("manifest and receipt transfer_id differ")
    _validate_receipt_categories(manifest, receipt)
    if not verified_items:
        raise BackupRelayError("backup relay has no verified items")

    normalized: dict[tuple[CategoryId, str], Path] = {}
    for (raw_category, item_id), raw_path in verified_items.items():
        try:
            category = CategoryId(raw_category)
        except ValueError as exc:
            raise BackupRelayError("verified item has an unknown category") from exc
        key = (category, item_id)
        if key in normalized:
            raise BackupRelayError("verified item key is duplicated")
        normalized[key] = Path(raw_path)

    manifest_items = {
        (category_id, item.id): item
        for category_id, category in manifest.categories.items()
        for item in category.items
    }
    if not set(normalized).issubset(manifest_items):
        raise BackupRelayError("verified item is not present in the manifest")

    receipt_categories = receipt.categories
    for category_id, result in receipt_categories.items():
        failed_ids = {failure.item_id for failure in result.failures}
        verified_ids = {
            item_id for (item_category, item_id) in normalized if item_category == category_id
        }
        if len(verified_ids) != result.items_verified:
            raise BackupRelayError(f"verified file count does not match receipt for {category_id.value}")
        if verified_ids & failed_ids:
            raise BackupRelayError(f"receipt marks an archived item failed in {category_id.value}")
    for category_id in {category for category, _ in normalized}:
        if category_id not in receipt_categories:
            raise BackupRelayError(f"receipt omits a category with verified files: {category_id.value}")

    manifest_bytes = canonical_json_bytes(manifest)
    receipt_bytes = canonical_json_bytes(receipt)
    validate_document(json.loads(manifest_bytes), "manifest.schema.json")
    validate_document(json.loads(receipt_bytes), "receipt.schema.json")
    if len(manifest_bytes) > MAX_MANIFEST_BYTES or len(receipt_bytes) > MAX_RECEIPT_BYTES:
        raise BackupRelayError("relay metadata exceeds the archive format limit")

    output = Path(destination)
    output.parent.mkdir(parents=True, exist_ok=True)
    if output.exists():
        raise FileExistsError(output)
    temporary: Path | None = None
    try:
        with tempfile.NamedTemporaryFile(
            mode="w+b",
            prefix=f".{output.name}.",
            suffix=".tmp",
            dir=output.parent,
            delete=False,
        ) as handle:
            temporary = Path(handle.name)

        with _open_archive_sink(temporary, password) as sink:
            with zipfile.ZipFile(sink, mode="w", allowZip64=True) as archive:
                archive.writestr(MANIFEST_MEMBER, manifest_bytes, compress_type=zipfile.ZIP_DEFLATED)
                archive.writestr(RECEIPT_MEMBER, receipt_bytes, compress_type=zipfile.ZIP_DEFLATED)
                for key in sorted(normalized, key=lambda row: (row[0].value, row[1])):
                    item = manifest_items[key]
                    source = normalized[key]
                    if not source.is_file():
                        raise BackupRelayError(f"verified source file is unavailable for {key[0].value}")
                    member = _item_member(*key)
                    info = zipfile.ZipInfo(member)
                    info.compress_type = zipfile.ZIP_STORED
                    info.file_size = item.size
                    digest = hashlib.sha256()
                    copied = 0
                    with source.open("rb") as input_stream, archive.open(info, "w", force_zip64=True) as output_stream:
                        while True:
                            block = input_stream.read(COPY_BUFFER_BYTES)
                            if not block:
                                break
                            output_stream.write(block)
                            digest.update(block)
                            copied += len(block)
                    if copied != item.size or digest.hexdigest() != item.sha256:
                        raise BackupRelayError(f"source item changed while creating relay: {key[0].value}")

        with temporary.open("rb+") as handle:
            handle.flush()
            os.fsync(handle.fileno())
        # Windows os.rename is atomic on the same volume and refuses to overwrite a destination.
        os.rename(temporary, output)
        temporary = None
        return output
    except Exception:
        if temporary is not None:
            try:
                temporary.unlink(missing_ok=True)
            except OSError:
                pass
        raise


def extract_backup_archive(
    archive_path: str | Path,
    destination_root: str | Path,
    *,
    max_total_bytes: int,
    password: str | bytes | bytearray | None = None,
) -> ExtractedBackup:
    """Verify and extract a ZIP into a new private staging directory without trusting ZIP paths.

    The caller must obtain user approval and provide a destination budget. Files are written under
    generated names only; archive member names are matched against manifest-derived exact names.
    """
    if max_total_bytes < 0:
        raise ValueError("max_total_bytes must be non-negative")
    archive_file = Path(archive_path)
    if archive_file.stat().st_size > max_total_bytes + MAX_ARCHIVE_OVERHEAD_BYTES + _ENCRYPTED_HEADER_BYTES + _GCM_TAG_BYTES:
        raise BackupRelayError("relay archive exceeds the approved payload and metadata budget")
    root = Path(destination_root)
    root.mkdir(parents=True, exist_ok=True)
    temporary_dir: Path | None = None
    decrypted_archive: Path | None = None
    try:
        with archive_file.open("rb") as probe:
            magic = probe.read(len(ENCRYPTED_ARCHIVE_MAGIC))
        if magic == ENCRYPTED_ARCHIVE_MAGIC:
            if password is None:
                raise BackupRelayError("encrypted relay requires a password")
            decrypted_archive = _decrypt_archive_to_temporary_zip(
                archive_file,
                password,
                maximum_plaintext_bytes=max_total_bytes + MAX_ARCHIVE_OVERHEAD_BYTES,
            )
            zip_path = decrypted_archive
        else:
            if password is not None:
                raise BackupRelayError("a password was supplied for an unencrypted relay")
            zip_path = archive_file

        with zipfile.ZipFile(zip_path, mode="r") as archive:
            infos = archive.infolist()
            if len(infos) > MAX_ARCHIVE_ENTRIES:
                raise BackupRelayError("archive contains too many entries")
            names = [info.filename for info in infos]
            if len(names) != len(set(names)):
                raise BackupRelayError("archive contains duplicate member names")
            by_name = {info.filename: info for info in infos}
            for control_name, maximum in (
                (MANIFEST_MEMBER, MAX_MANIFEST_BYTES),
                (RECEIPT_MEMBER, MAX_RECEIPT_BYTES),
            ):
                info = by_name.get(control_name)
                if info is None or info.file_size > maximum or info.flag_bits & 0x1:
                    raise BackupRelayError(f"missing, oversized, or encrypted {control_name}")

            manifest = _read_model(archive, MANIFEST_MEMBER, "manifest.schema.json", TransferManifest)
            receipt = _read_model(archive, RECEIPT_MEMBER, "receipt.schema.json", TransferReceipt)
            if manifest.transfer_id != receipt.transfer_id:
                raise BackupRelayError("manifest and receipt transfer_id differ")
            _validate_receipt_categories(manifest, receipt)

            manifest_items = {
                (category_id, item.id): item
                for category_id, category in manifest.categories.items()
                for item in category.items
            }
            expected_members = {_item_member(*key): key for key in manifest_items}
            actual_item_names = set(names) - {MANIFEST_MEMBER, RECEIPT_MEMBER}
            if not actual_item_names.issubset(expected_members):
                raise BackupRelayError("archive contains an unknown item member")

            members_by_category: dict[CategoryId, set[str]] = {}
            for member in actual_item_names:
                category_id, item_id = expected_members[member]
                receipt_category = receipt.categories.get(category_id)
                if receipt_category is None:
                    raise BackupRelayError("receipt omits a category with archived items")
                failed_ids = {failure.item_id for failure in receipt_category.failures}
                if item_id in failed_ids:
                    raise BackupRelayError("archive includes an item marked failed by the receipt")
                members_by_category.setdefault(category_id, set()).add(item_id)

            for category_id, result in receipt.categories.items():
                archived_count = len(members_by_category.get(category_id, set()))
                if archived_count != result.items_verified:
                    raise BackupRelayError(f"archive item count does not match receipt for {category_id.value}")

            total_declared = sum(manifest_items[expected_members[name]].size for name in actual_item_names)
            if total_declared > max_total_bytes:
                raise BackupRelayError("verified payload exceeds the approved extraction budget")
            final_dir = root / f"TransferKit-{manifest.transfer_id}"
            if final_dir.exists():
                raise FileExistsError(final_dir)
            temporary_dir = Path(tempfile.mkdtemp(prefix=".transferkit-relay-", dir=root))
            output_files: dict[tuple[CategoryId, str], Path] = {}

            for member in sorted(actual_item_names):
                category_id, item_id = expected_members[member]
                item = manifest_items[(category_id, item_id)]
                info = by_name[member]
                if info.file_size != item.size or info.flag_bits & 0x1:
                    raise BackupRelayError("archive item size/encryption does not match manifest")
                item_dir = temporary_dir / "items" / category_id.value
                item_dir.mkdir(parents=True, exist_ok=True)
                target = item_dir / f"{_item_token(category_id, item_id)}.bin"
                partial = target.with_suffix(".part")
                digest = hashlib.sha256()
                copied = 0
                with archive.open(info, mode="r") as input_stream, partial.open("xb") as output_stream:
                    while True:
                        block = input_stream.read(COPY_BUFFER_BYTES)
                        if not block:
                            break
                        copied += len(block)
                        if copied > item.size or copied > max_total_bytes:
                            raise BackupRelayError("archive item expanded beyond the declared size")
                        output_stream.write(block)
                        digest.update(block)
                    output_stream.flush()
                    os.fsync(output_stream.fileno())
                if copied != item.size or digest.hexdigest() != item.sha256:
                    raise BackupRelayError("archive item failed size/SHA-256 verification")
                os.rename(partial, target)
                output_files[(category_id, item_id)] = target

        if temporary_dir is None:
            raise BackupRelayError("archive extraction did not create staging")
        final_dir = root / f"TransferKit-{manifest.transfer_id}"
        os.rename(temporary_dir, final_dir)
        temporary_dir = None
        # Rebuild paths from generated category/item tokens after moving the staging directory.
        final_files = {
            key: final_dir / "items" / key[0].value / f"{_item_token(*key)}.bin"
            for key in output_files
        }
        return ExtractedBackup(
            transfer_id=manifest.transfer_id,
            manifest=manifest,
            receipt=receipt,
            directory=final_dir,
            item_files=final_files,
        )
    except (zipfile.BadZipFile, json.JSONDecodeError, KeyError, OSError, ValueError) as exc:
        if temporary_dir is not None:
            try:
                for child in sorted(temporary_dir.rglob("*"), reverse=True):
                    if child.is_file() or child.is_symlink():
                        child.unlink(missing_ok=True)
                    elif child.is_dir():
                        child.rmdir()
                temporary_dir.rmdir()
            except OSError:
                pass
        if isinstance(exc, BackupRelayError):
            raise
        raise BackupRelayError("relay archive could not be safely restored") from exc
    finally:
        if decrypted_archive is not None:
            try:
                decrypted_archive.unlink(missing_ok=True)
            except OSError:
                pass


@contextmanager
def _open_archive_sink(path: Path, password: str | bytes | bytearray | None) -> Iterator[object]:
    if password is None:
        with path.open("w+b") as raw:
            yield raw
        return
    writer = _AesGcmZipWriter(path, password)
    try:
        yield writer
    except BaseException:
        writer.abort()
        raise
    else:
        writer.close()


class _AesGcmZipWriter:
    """Write-only ZIP sink encrypting bytes as they are emitted by zipfile."""

    def __init__(self, path: Path, password: str | bytes | bytearray):
        password_bytes = _password_bytes(password)
        salt = os.urandom(_SALT_BYTES)
        nonce = os.urandom(_NONCE_BYTES)
        self._header = _ENCRYPTED_HEADER_PREFIX + salt + nonce
        key = _derive_archive_key(password_bytes, salt)
        self._encryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).encryptor()
        self._encryptor.authenticate_additional_data(self._header)
        self._raw = path.open("wb")
        self._raw.write(self._header)
        self._closed = False

    def write(self, data: bytes) -> int:
        if self._closed:
            raise ValueError("write to closed encrypted archive")
        block = bytes(data)
        encrypted = self._encryptor.update(block)
        if encrypted:
            self._raw.write(encrypted)
        return len(block)

    def flush(self) -> None:
        if not self._closed:
            self._raw.flush()

    def close(self) -> None:
        if self._closed:
            return
        try:
            final = self._encryptor.finalize()
            if final:
                self._raw.write(final)
            self._raw.write(self._encryptor.tag)
            self._raw.flush()
        finally:
            self._closed = True
            self._raw.close()

    def abort(self) -> None:
        if self._closed:
            return
        self._closed = True
        self._raw.close()


def _password_bytes(password: str | bytes | bytearray) -> bytes:
    if isinstance(password, str):
        encoded = password.encode("utf-8")
    else:
        encoded = bytes(password)
    if len(encoded) < _MIN_PASSWORD_BYTES:
        raise BackupRelayError("relay password must contain at least 8 UTF-8 bytes")
    if len(encoded) > _MAX_PASSWORD_BYTES:
        raise BackupRelayError("relay password exceeds the 1024-byte limit")
    return encoded


def _derive_archive_key(password: bytes, salt: bytes) -> bytes:
    return Scrypt(salt=salt, length=32, n=_SCRYPT_N, r=_SCRYPT_R, p=_SCRYPT_P).derive(password)


def _decrypt_archive_to_temporary_zip(
    encrypted_path: Path,
    password: str | bytes | bytearray,
    *,
    maximum_plaintext_bytes: int,
) -> Path:
    password_bytes = _password_bytes(password)
    total_size = encrypted_path.stat().st_size
    if total_size < _ENCRYPTED_HEADER_BYTES + _GCM_TAG_BYTES:
        raise BackupRelayError("encrypted relay header or authentication tag is truncated")

    with encrypted_path.open("rb") as source:
        header = source.read(_ENCRYPTED_HEADER_BYTES)
        if len(header) != _ENCRYPTED_HEADER_BYTES or header[:len(_ENCRYPTED_HEADER_PREFIX)] != _ENCRYPTED_HEADER_PREFIX:
            raise BackupRelayError("unsupported encrypted relay format")
        salt_start = len(_ENCRYPTED_HEADER_PREFIX)
        salt = header[salt_start:salt_start + _SALT_BYTES]
        nonce = header[salt_start + _SALT_BYTES:]
        key = _derive_archive_key(password_bytes, salt)
        decryptor = Cipher(algorithms.AES(key), modes.GCM(nonce)).decryptor()
        decryptor.authenticate_additional_data(header)
        ciphertext_remaining = total_size - _ENCRYPTED_HEADER_BYTES - _GCM_TAG_BYTES
        temporary_path: Path | None = None
        copied = 0
        try:
            with tempfile.NamedTemporaryFile(
                mode="w+b",
                prefix=".transferkit-decrypt-",
                suffix=".zip",
                # Keep transient plaintext under the current user's private OS temp directory,
                # never next to the user-selected relay or final extracted files.
                dir=tempfile.gettempdir(),
                delete=False,
            ) as plaintext:
                temporary_path = Path(plaintext.name)
                while ciphertext_remaining:
                    block = source.read(min(COPY_BUFFER_BYTES, ciphertext_remaining))
                    if not block:
                        raise BackupRelayError("encrypted relay ciphertext is truncated")
                    ciphertext_remaining -= len(block)
                    cleartext = decryptor.update(block)
                    copied += len(cleartext)
                    if copied > maximum_plaintext_bytes:
                        raise BackupRelayError("decrypted relay exceeds the approved extraction budget")
                    plaintext.write(cleartext)
                tag = source.read(_GCM_TAG_BYTES)
                if len(tag) != _GCM_TAG_BYTES or source.read(1):
                    raise BackupRelayError("encrypted relay authentication tag is malformed")
                try:
                    final = decryptor.finalize_with_tag(tag)
                except InvalidTag as exc:
                    raise BackupRelayError("relay password is incorrect or archive integrity failed") from exc
                if final:
                    copied += len(final)
                    if copied > maximum_plaintext_bytes:
                        raise BackupRelayError("decrypted relay exceeds the approved extraction budget")
                    plaintext.write(final)
                plaintext.flush()
                os.fsync(plaintext.fileno())
            return temporary_path
        except Exception:
            if temporary_path is not None:
                try:
                    temporary_path.unlink(missing_ok=True)
                except OSError:
                    pass
            raise


def _read_model(archive: zipfile.ZipFile, member: str, schema_name: str, model_type):
    try:
        document = json.loads(archive.read(member))
        model = validate_document(document, schema_name)
    except Exception as exc:
        raise BackupRelayError(f"invalid relay {member}") from exc
    if not isinstance(model, model_type):
        raise BackupRelayError(f"relay {member} did not produce the expected contract model")
    return model


def _validate_receipt_categories(manifest: TransferManifest, receipt: TransferReceipt) -> None:
    for category_id, result in receipt.categories.items():
        manifest_category = manifest.categories.get(category_id)
        if manifest_category is None:
            raise BackupRelayError(f"receipt category is absent from manifest: {category_id.value}")
        manifest_ids = {item.id for item in manifest_category.items}
        failed_ids = {failure.item_id for failure in result.failures}
        if not failed_ids.issubset(manifest_ids):
            raise BackupRelayError(f"receipt failures do not match manifest items in {category_id.value}")
        if (
            len(failed_ids) != result.items_failed
            or result.items_verified + result.items_failed != result.items_received
            or result.items_received > len(manifest_ids)
        ):
            raise BackupRelayError(f"receipt counts do not match manifest in {category_id.value}")


def _item_token(category_id: CategoryId, item_id: str) -> str:
    return hashlib.sha256(f"{category_id.value}\0{item_id}".encode("utf-8")).hexdigest()


def _item_member(category_id: CategoryId, item_id: str) -> str:
    return f"items/{category_id.value}/{_item_token(category_id, item_id)}.bin"
