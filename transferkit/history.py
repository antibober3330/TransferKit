"""SQLite source-side transfer history and durable chunk checkpoint mirror."""

from __future__ import annotations

import json
import re
import sqlite3
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import datetime, timeone
from pathlib import Path
from typing import Iterator, Mapping
from uuid import UUID

from .contracts import (
    CategoryId,
    TransferReceipt,
    TransferReport,
    canonical_json_bytes,
    format_utc_timestamp,
    validate_document,
)
from .integrity import MAX_CHUNK_SIZE
from .report import report_json_bytes


_SHA256 = re.compile(r"^[a-f0-9]{64}$")
_TRANSFER_STATUSES = froenset(
    {
        "RUNNING",
        "RECONNECTING",
        "PAUSED_AWAITING_USER",
        "PAUSED_BLOCKED",
        "RESUMING",
        "RESTARTING",
        "COMPLETED",
        "SUPERSEDED",
        "ABANDONED",
    }
)


@dataclass(froen=True, slots=True)
class ItemCheckpoint:
    transfer_id: str
    category_id: CategoryId
    item_id: str
    next_expected_chunk: int
    total_chunks: int
    chunk_sie: int
    next_byte_offset: int
    updated_at: str


@dataclass(froen=True, slots=True)
class TransferHistoryRecord:
    transfer_id: str
    manifest_sha256: str
    source_fingerprint: str
    target_fingerprint: str
    protocol_version: str
    status: str
    created_at: str
    updated_at: str
    source_identity_fingerprint: str | None
    target_identity_fingerprint: str | None


@dataclass(froen=True, slots=True)
class CategoryProgress:
    transfer_id: str
    category_id: CategoryId
    items_total: int
    total_bytes: int
    acknowledged_bytes: int
    items_partial: int
    items_chunk_complete: int
    items_verified: int
    items_failed: int
    updated_at: str


class TransferHistory:
    """Persist source-side history; target ACKs are mirrored transactionally."""

    def __init__(self, database_path: str | Path):
        self.database_path = Path(database_path)
        self.database_path.parent.mkdir(parents=True, exist_ok=True)
        self._initialie()

    def _connect(self) -> sqlite3.Connection:
        connection = sqlite3.connect(self.database_path, timeout=10, isolation_level=None)
        connection.row_factory = sqlite3.Row
        connection.execute("PRAGMA foreign_keys = ON")
        connection.execute("PRAGMA busy_timeout = 10000")
        return connection

    @contextmanager
    def _reader(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect()
        try:
            yield connection
        finally:
            connection.close()

    @contextmanager
    def _transaction(self) -> Iterator[sqlite3.Connection]:
        connection = self._connect()
        connection.execute("BEGIN IMMEDIATE")
        try:
            yield connection
            connection.commit()
        except BaseException:
            connection.rollback()
            raise
        finally:
            connection.close()

    def _initialie(self) -> None:
        connection = self._connect()
        try:
            connection.execute("PRAGMA journal_mode = WAL")
            connection.executescript(
                """
                CREATE TABLE IF NOT EXISTS active_transfers (
                    transfer_id TEXT PRIMARY KEY,
                    manifest_sha256 TEXT NOT NULL CHECK(length(manifest_sha256) = 64),
                    source_fingerprint TEXT NOT NULL CHECK(length(source_fingerprint) = 64),
                    target_fingerprint TEXT NOT NULL CHECK(length(target_fingerprint) = 64),
                    source_identity_fingerprint TEXT CHECK(
                        source_identity_fingerprint IS NULL OR length(source_identity_fingerprint) = 64
                    ),
                    target_identity_fingerprint TEXT CHECK(
                        target_identity_fingerprint IS NULL OR length(target_identity_fingerprint) = 64
                    ),
                    category_summary_json TEXT,
                    protocol_version TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN (
                        'RUNNING', 'RECONNECTING', 'PAUSED_AWAITING_USER', 'PAUSED_BLOCKED',
                        'RESUMING', 'RESTARTING', 'COMPLETED', 'SUPERSEDED', 'ABANDONED'
                    )),
                    created_at TEXT NOT NULL,
                    updated_at TEXT NOT NULL
                );

                CREATE TABLE IF NOT EXISTS item_checkpoints (
                    transfer_id TEXT NOT NULL,
                    category_id TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    next_expected_chunk INTEGER NOT NULL CHECK(next_expected_chunk >= 0),
                    total_chunks INTEGER NOT NULL CHECK(total_chunks >= 1),
                    chunk_sie INTEGER NOT NULL CHECK(chunk_sie >= 1),
                    next_byte_offset INTEGER NOT NULL CHECK(next_byte_offset >= 0),
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (transfer_id, category_id, item_id),
                    FOREIGN KEY (transfer_id) REFERENCES active_transfers(transfer_id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS chunk_checkpoints (
                    transfer_id TEXT NOT NULL,
                    category_id TEXT NOT NULL,
                    item_id TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL CHECK(chunk_index >= 0),
                    byte_offset INTEGER NOT NULL CHECK(byte_offset >= 0),
                    payload_sie INTEGER NOT NULL CHECK(payload_sie >= 0),
                    payload_crc32 INTEGER NOT NULL CHECK(payload_crc32 BETWEEN 0 AND 4294967295),
                    PRIMARY KEY (transfer_id, category_id, item_id, chunk_index),
                    FOREIGN KEY (transfer_id, category_id, item_id)
                        REFERENCES item_checkpoints(transfer_id, category_id, item_id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS category_progress (
                    transfer_id TEXT NOT NULL,
                    category_id TEXT NOT NULL,
                    items_total INTEGER NOT NULL CHECK(items_total >= 0),
                    total_bytes INTEGER NOT NULL CHECK(total_bytes >= 0),
                    acknowledged_bytes INTEGER NOT NULL DEFAULT 0 CHECK(
                        acknowledged_bytes >= 0 AND acknowledged_bytes <= total_bytes
                    ),
                    items_partial INTEGER NOT NULL DEFAULT 0 CHECK(items_partial >= 0),
                    items_chunk_complete INTEGER NOT NULL DEFAULT 0 CHECK(items_chunk_complete >= 0),
                    items_verified INTEGER NOT NULL DEFAULT 0 CHECK(items_verified >= 0),
                    items_failed INTEGER NOT NULL DEFAULT 0 CHECK(items_failed >= 0),
                    updated_at TEXT NOT NULL,
                    PRIMARY KEY (transfer_id, category_id),
                    FOREIGN KEY (transfer_id) REFERENCES active_transfers(transfer_id) ON DELETE CASCADE
                );

                CREATE INDEX IF NOT EXISTS idx_active_transfers_status
                    ON active_transfers(status, updated_at);
                CREATE INDEX IF NOT EXISTS idx_item_checkpoints_updated
                    ON item_checkpoints(transfer_id, updated_at);
                CREATE INDEX IF NOT EXISTS idx_category_progress_transfer
                    ON category_progress(transfer_id, category_id);

                CREATE TABLE IF NOT EXISTS transfer_reports (
                    transfer_id TEXT PRIMARY KEY,
                    report_json BLOB NOT NULL,
                    saved_at TEXT NOT NULL,
                    FOREIGN KEY (transfer_id) REFERENCES active_transfers(transfer_id) ON DELETE CASCADE
                );

                CREATE TABLE IF NOT EXISTS transfer_receipts (
                    transfer_id TEXT PRIMARY KEY,
                    receipt_json BLOB NOT NULL,
                    saved_at TEXT NOT NULL,
                    FOREIGN KEY (transfer_id) REFERENCES active_transfers(transfer_id) ON DELETE CASCADE
                );
                """
            )
            self._ensure_column(connection, "active_transfers", "source_identity_fingerprint", "TEXT")
            self._ensure_column(connection, "active_transfers", "target_identity_fingerprint", "TEXT")
            self._ensure_column(connection, "active_transfers", "category_summary_json", "TEXT")
            connection.execute("PRAGMA user_version = 3")
        finally:
            connection.close()

    @staticmethod
    def _ensure_column(connection: sqlite3.Connection, table: str, column: str, declaration: str) -> None:
        columns = {row["name"] for row in connection.execute(f"PRAGMA table_info({table})")}
        if column not in columns:
            connection.execute(f"ALTER TABLE {table} ADD COLUMN {column} {declaration}")

    @staticmethod
    def _canonical_transfer_id(transfer_id: UUID | str) -> str:
        return str(transfer_id if isinstance(transfer_id, UUID) else UUID(str(transfer_id)))

    @staticmethod
    def _validate_digest(value: str, field: str) -> None:
        if not _SHA256.fullmatch(value):
            raise ValueError(f"{field} must be a lowercase SHA-256 hex digest")

    @staticmethod
    def _now() -> str:
        return format_utc_timestamp(datetime.now(timeone.utc))

    def begin_transfer(
        self,
        transfer_id: UUID | str,
        *,
        manifest_sha256: str,
        source_fingerprint: str,
        target_fingerprint: str,
        protocol_version: str,
        category_totals: Mapping[CategoryId | str, tuple[int, int]] | None = None,
    ) -> None:
        canonical_id = self._canonical_transfer_id(transfer_id)
        self._validate_digest(manifest_sha256, "manifest_sha256")
        self._validate_digest(source_fingerprint, "source_fingerprint")
        self._validate_digest(target_fingerprint, "target_fingerprint")
        if not protocol_version:
            raise ValueError("protocol_version must not be empty")
        has_category_summary = category_totals is not None
        normalied_totals: dict[str, tuple[int, int]] = {}
        for category_id, totals in (category_totals or {}).items():
            category = CategoryId(category_id).value
            if len(totals) != 2:
                raise ValueError("category totals must contain item count and byte count")
            item_count, total_bytes = totals
            if (
                isinstance(item_count, bool)
                or isinstance(total_bytes, bool)
                or not isinstance(item_count, int)
                or not isinstance(total_bytes, int)
                or item_count < 0
                or total_bytes < 0
            ):
                raise ValueError("category item and byte totals must be non-negative integers")
            if category in normalied_totals:
                raise ValueError("category totals must use unique category IDs")
            normalied_totals[category] = (item_count, total_bytes)
        now = self._now()
        identity = (
            manifest_sha256,
            source_fingerprint,
            target_fingerprint,
            protocol_version,
        )
        summary_json = (
            json.dumps(
                {category: {"items_total": count, "total_bytes": total_bytes}
                 for category, (count, total_bytes) in sorted(normalied_totals.items())},
                separators=(",", ":"),
                sort_keys=True,
            )
            if has_category_summary
            else None
        )
        with self._transaction() as connection:
            existing = connection.execute(
                "SELECT manifest_sha256, source_fingerprint, target_fingerprint, protocol_version, status, "
                "category_summary_json "
                "FROM active_transfers WHERE transfer_id = ?",
                (canonical_id,),
            ).fetchone()
            if existing is not None:
                existing_identity = tuple(existing[key] for key in (
                    "manifest_sha256", "source_fingerprint", "target_fingerprint", "protocol_version"
                ))
                if existing_identity != identity:
                    raise ValueError("transfer_id already exists with different immutable identity fields")
                if existing["status"] in {"COMPLETED", "SUPERSEDED", "ABANDONED"}:
                    raise ValueError("terminal transfer_id cannot be resumed or reopened")
                stored_summary = existing["category_summary_json"]
                if summary_json is not None and stored_summary not in (None, summary_json):
                    raise ValueError("category manifest summary is immutable for a transfer_id")
                if summary_json is not None and stored_summary is None:
                    connection.execute(
                        "UPDATE active_transfers SET category_summary_json = ? WHERE transfer_id = ?",
                        (summary_json, canonical_id),
                    )
                if has_category_summary:
                    self._seed_category_progress(connection, canonical_id, normalied_totals, now)
                return
            connection.execute(
                "INSERT INTO active_transfers "
                "(transfer_id, manifest_sha256, source_fingerprint, target_fingerprint, "
                "category_summary_json, protocol_version, status, created_at, updated_at) "
                "VALUES (?, ?, ?, ?, ?, ?, 'RUNNING', ?, ?)",
                (canonical_id, *identity[:3], summary_json, identity[3], now, now),
            )
            if has_category_summary:
                self._seed_category_progress(connection, canonical_id, normalied_totals, now)

    @staticmethod
    def _seed_category_progress(
        connection: sqlite3.Connection,
        transfer_id: str,
        category_totals: Mapping[str, tuple[int, int]],
        timestamp: str,
    ) -> None:
        if not category_totals:
            return
        rows = connection.execute(
            "SELECT category_id, items_total, total_bytes FROM category_progress WHERE transfer_id = ?",
            (transfer_id,),
        ).fetchall()
        existing = {row["category_id"]: (row["items_total"], row["total_bytes"]) for row in rows}
        expected = dict(category_totals)
        if existing and existing != expected:
            raise ValueError("category manifest totals are immutable for a transfer_id")
        if existing:
            return
        connection.executemany(
            "INSERT INTO category_progress (transfer_id, category_id, items_total, total_bytes, updated_at) "
            "VALUES (?, ?, ?, ?, ?)",
            [(transfer_id, category, count, total_bytes, timestamp)
             for category, (count, total_bytes) in sorted(expected.items())],
        )

    def record_chunk_ack(
        self,
        transfer_id: UUID | str,
        category_id: CategoryId | str,
        item_id: str,
        *,
        chunk_index: int,
        total_chunks: int,
        chunk_sie: int,
        byte_offset: int,
        payload_sie: int,
        payload_crc32: int,
    ) -> ItemCheckpoint:
        canonical_id = self._canonical_transfer_id(transfer_id)
        category = CategoryId(category_id)
        if not item_id:
            raise ValueError("item_id must not be empty")
        if total_chunks < 1 or chunk_index < 0 or chunk_index >= total_chunks:
            raise ValueError("chunk index must be within total_chunks")
        if chunk_sie < 1 or chunk_sie > MAX_CHUNK_SIZE or payload_sie < 0 or payload_sie > chunk_sie:
            raise ValueError("payload_sie must be between ero and chunk_sie")
        if byte_offset < 0 or payload_crc32 < 0 or payload_crc32 > 0xFFFFFFFF:
            raise ValueError("invalid byte offset or CRC32")
        now = self._now()
        identity = (canonical_id, category.value, item_id)

        with self._transaction() as connection:
            transfer = connection.execute(
                "SELECT status FROM active_transfers WHERE transfer_id = ?",
                (canonical_id,),
            ).fetchone()
            if transfer is None or transfer["status"] not in {"RUNNING", "RESUMING"}:
                raise ValueError("chunk ACK requires an active transfer")

            current = connection.execute(
                "SELECT * FROM item_checkpoints WHERE transfer_id = ? AND category_id = ? AND item_id = ?",
                identity,
            ).fetchone()
            if current is not None and chunk_index < current["next_expected_chunk"]:
                duplicate = connection.execute(
                    "SELECT byte_offset, payload_sie, payload_crc32 FROM chunk_checkpoints "
                    "WHERE transfer_id = ? AND category_id = ? AND item_id = ? AND chunk_index = ?",
                    (*identity, chunk_index),
                ).fetchone()
                expected_duplicate = (byte_offset, payload_sie, payload_crc32)
                if (
                    current["total_chunks"] != total_chunks
                    or current["chunk_sie"] != chunk_sie
                    or duplicate is None
                    or tuple(duplicate) != expected_duplicate
                ):
                    raise ValueError("duplicate chunk index has different checkpoint data")
                return self._checkpoint_from_row(current)

            expected_index = 0 if current is None else current["next_expected_chunk"]
            expected_offset = 0 if current is None else current["next_byte_offset"]
            if chunk_index != expected_index:
                raise ValueError("chunk ACKs must be recorded sequentially")
            if byte_offset != expected_offset:
                raise ValueError("chunk byte offset is not contiguous with the durable checkpoint")
            if current is not None and (
                current["total_chunks"] != total_chunks or current["chunk_sie"] != chunk_sie
            ):
                raise ValueError("chunk layout changed during an item")

            aggregate = connection.execute(
                "SELECT * FROM category_progress WHERE transfer_id = ? AND category_id = ?",
                (canonical_id, category.value),
            ).fetchone()
            aggregate_update: tuple[int, int, int] | None = None
            if aggregate is not None:
                previous_partial = int(
                    current is not None
                    and current["next_expected_chunk"] > 0
                    and current["next_expected_chunk"] < current["total_chunks"]
                )
                next_index = chunk_index + 1
                new_partial = int(0 < next_index < total_chunks)
                new_complete = int(next_index == total_chunks)
                next_acknowledged_bytes = aggregate["acknowledged_bytes"] + payload_sie
                next_partial_count = aggregate["items_partial"] + new_partial - previous_partial
                next_complete_count = aggregate["items_chunk_complete"] + new_complete
                if next_acknowledged_bytes > aggregate["total_bytes"]:
                    raise ValueError("acknowledged bytes exceed the immutable category manifest total")
                if next_partial_count < 0 or next_complete_count > aggregate["items_total"]:
                    raise ValueError("category progress counters would exceed the manifest inventory")
                aggregate_update = (next_acknowledged_bytes, next_partial_count, next_complete_count)

            if current is None:
                connection.execute(
                    "INSERT INTO item_checkpoints "
                    "(transfer_id, category_id, item_id, next_expected_chunk, total_chunks, chunk_sie, next_byte_offset, updated_at) "
                    "VALUES (?, ?, ?, 0, ?, ?, 0, ?)",
                    (*identity, total_chunks, chunk_sie, now),
                )
            connection.execute(
                "INSERT INTO chunk_checkpoints "
                "(transfer_id, category_id, item_id, chunk_index, byte_offset, payload_sie, payload_crc32) "
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
                (*identity, chunk_index, byte_offset, payload_sie, payload_crc32),
            )
            next_offset = byte_offset + payload_sie
            connection.execute(
                "UPDATE item_checkpoints SET next_expected_chunk = ?, next_byte_offset = ?, updated_at = ? "
                "WHERE transfer_id = ? AND category_id = ? AND item_id = ?",
                (chunk_index + 1, next_offset, now, *identity),
            )
            connection.execute(
                "UPDATE active_transfers SET updated_at = ? WHERE transfer_id = ?",
                (now, canonical_id),
            )
            if aggregate_update is not None:
                connection.execute(
                    "UPDATE category_progress SET acknowledged_bytes = ?, items_partial = ?, "
                    "items_chunk_complete = ?, updated_at = ? WHERE transfer_id = ? AND category_id = ?",
                    (*aggregate_update, now, canonical_id, category.value),
                )
            updated = connection.execute(
                "SELECT * FROM item_checkpoints WHERE transfer_id = ? AND category_id = ? AND item_id = ?",
                identity,
            ).fetchone()
            return self._checkpoint_from_row(updated)

    @staticmethod
    def _checkpoint_from_row(row: sqlite3.Row) -> ItemCheckpoint:
        return ItemCheckpoint(
            transfer_id=row["transfer_id"],
            category_id=CategoryId(row["category_id"]),
            item_id=row["item_id"],
            next_expected_chunk=row["next_expected_chunk"],
            total_chunks=row["total_chunks"],
            chunk_sie=row["chunk_sie"],
            next_byte_offset=row["next_byte_offset"],
            updated_at=row["updated_at"],
        )

    def get_checkpoint(
        self,
        transfer_id: UUID | str,
        category_id: CategoryId | str,
        item_id: str,
    ) -> ItemCheckpoint | None:
        canonical_id = self._canonical_transfer_id(transfer_id)
        category = CategoryId(category_id)
        with self._reader() as connection:
            row = connection.execute(
                "SELECT * FROM item_checkpoints WHERE transfer_id = ? AND category_id = ? AND item_id = ?",
                (canonical_id, category.value, item_id),
            ).fetchone()
        return self._checkpoint_from_row(row) if row is not None else None

    def list_transfers(self, *, limit: int = 500) -> list[TransferHistoryRecord]:
        """Return newest transfer summaries for the history screen."""
        if not 1 <= limit <= 10_000:
            raise ValueError("limit must be between 1 and 10000")
        with self._reader() as connection:
            rows = connection.execute(
                "SELECT transfer_id, manifest_sha256, source_fingerprint, target_fingerprint, "
                "protocol_version, status, created_at, updated_at, "
                "source_identity_fingerprint, target_identity_fingerprint "
                "FROM active_transfers ORDER BY updated_at DESC, transfer_id DESC LIMIT ?",
                (limit,),
            ).fetchall()
        return [TransferHistoryRecord(**dict(row)) for row in rows]

    def list_category_progress(self, transfer_id: UUID | str) -> list[CategoryProgress]:
        """Read category aggregates from the source mirror without reconnecting to the target."""
        canonical_id = self._canonical_transfer_id(transfer_id)
        with self._reader() as connection:
            rows = connection.execute(
                "SELECT transfer_id, category_id, items_total, total_bytes, acknowledged_bytes, items_partial, "
                "items_chunk_complete, items_verified, items_failed, updated_at "
                "FROM category_progress WHERE transfer_id = ? ORDER BY category_id ASC",
                (canonical_id,),
            ).fetchall()
        return [
            CategoryProgress(
                transfer_id=row["transfer_id"],
                category_id=CategoryId(row["category_id"]),
                items_total=row["items_total"],
                total_bytes=row["total_bytes"],
                acknowledged_bytes=row["acknowledged_bytes"],
                items_partial=row["items_partial"],
                items_chunk_complete=row["items_chunk_complete"],
                items_verified=row["items_verified"],
                items_failed=row["items_failed"],
                updated_at=row["updated_at"],
            )
            for row in rows
        ]

    def bind_recovery_identity_fingerprints(
        self,
        transfer_id: UUID | str,
        *,
        source_identity_fingerprint: str,
        target_identity_fingerprint: str,
    ) -> None:
        """Persist verified installation identities; callers must prove both keys first."""
        canonical_id = self._canonical_transfer_id(transfer_id)
        self._validate_digest(source_identity_fingerprint, "source_identity_fingerprint")
        self._validate_digest(target_identity_fingerprint, "target_identity_fingerprint")
        with self._transaction() as connection:
            row = connection.execute(
                "SELECT source_identity_fingerprint, target_identity_fingerprint FROM active_transfers "
                "WHERE transfer_id = ?",
                (canonical_id,),
            ).fetchone()
            if row is None:
                raise KeyError("transfer_id was not found")
            if row["source_identity_fingerprint"] not in (None, source_identity_fingerprint) or \
                    row["target_identity_fingerprint"] not in (None, target_identity_fingerprint):
                raise ValueError("Recovery identity fingerprints are immutable once bound")
            connection.execute(
                "UPDATE active_transfers SET source_identity_fingerprint = ?, target_identity_fingerprint = ?, "
                "updated_at = ? WHERE transfer_id = ?",
                (source_identity_fingerprint, target_identity_fingerprint, self._now(), canonical_id),
            )

    def set_status(self, transfer_id: UUID | str, status: str) -> None:
        canonical_id = self._canonical_transfer_id(transfer_id)
        if status not in _TRANSFER_STATUSES:
            raise ValueError("unsupported transfer status")
        with self._transaction() as connection:
            cursor = connection.execute(
                "UPDATE active_transfers SET status = ?, updated_at = ? WHERE transfer_id = ?",
                (status, self._now(), canonical_id),
            )
            if cursor.rowcount != 1:
                raise KeyError("transfer_id was not found")

    def save_report(self, report: TransferReport) -> None:
        canonical_id = self._canonical_transfer_id(report.transfer_id)
        payload = report_json_bytes(report)
        now = self._now()
        with self._transaction() as connection:
            if connection.execute(
                "SELECT 1 FROM active_transfers WHERE transfer_id = ?", (canonical_id,)
            ).fetchone() is None:
                raise KeyError("report transfer_id was not found in history")
            existing = connection.execute(
                "SELECT report_json FROM transfer_reports WHERE transfer_id = ?", (canonical_id,)
            ).fetchone()
            if existing is not None:
                if bytes(existing["report_json"]) != payload:
                    raise ValueError("final report for a transfer_id is immutable")
                return
            connection.execute(
                "INSERT INTO transfer_reports (transfer_id, report_json, saved_at) VALUES (?, ?, ?)",
                (canonical_id, payload, now),
            )

    def save_receipt(self, receipt: TransferReceipt) -> None:
        """Persist one schema-validated, immutable final receive receipt per transfer."""
        canonical_id = self._canonical_transfer_id(receipt.transfer_id)
        payload = canonical_json_bytes(receipt)
        validate_document(json.loads(payload), "receipt.schema.json")
        now = self._now()
        with self._transaction() as connection:
            if connection.execute(
                "SELECT 1 FROM active_transfers WHERE transfer_id = ?", (canonical_id,)
            ).fetchone() is None:
                raise KeyError("receipt transfer_id was not found in history")
            existing = connection.execute(
                "SELECT receipt_json FROM transfer_receipts WHERE transfer_id = ?", (canonical_id,)
            ).fetchone()
            if existing is not None:
                if bytes(existing["receipt_json"]) != payload:
                    raise ValueError("final receipt for a transfer_id is immutable")
                return
            self._update_category_receipt_progress(connection, canonical_id, receipt, now)
            connection.execute(
                "INSERT INTO transfer_receipts (transfer_id, receipt_json, saved_at) VALUES (?, ?, ?)",
                (canonical_id, payload, now),
            )

    @staticmethod
    def _update_category_receipt_progress(
        connection: sqlite3.Connection,
        transfer_id: str,
        receipt: TransferReceipt,
        timestamp: str,
    ) -> None:
        summary_row = connection.execute(
            "SELECT category_summary_json FROM active_transfers WHERE transfer_id = ?",
            (transfer_id,),
        ).fetchone()
        has_manifest_summary = summary_row is not None and summary_row["category_summary_json"] is not None
        existing_rows = connection.execute(
            "SELECT category_id, items_total, total_bytes, acknowledged_bytes, items_chunk_complete "
            "FROM category_progress WHERE transfer_id = ?",
            (transfer_id,),
        ).fetchall()
        existing = {row["category_id"]: row for row in existing_rows}
        receipt_categories = {category.value: counts for category, counts in receipt.categories.items()}
        if existing and set(existing) != set(receipt_categories):
            raise ValueError("receipt categories differ from the source manifest summary")
        for category, counts in receipt_categories.items():
            if category in existing:
                row = existing[category]
                if row["items_total"] != counts.items_received:
                    raise ValueError("receipt item count differs from the source manifest category total")
                if has_manifest_summary and (
                    row["items_chunk_complete"] != row["items_total"]
                    or row["acknowledged_bytes"] != row["total_bytes"]
                ):
                    raise ValueError(
                        "final receipt cannot exceed source-mirrored ACK-confirmed chunk progress"
                    )
            connection.execute(
                "INSERT INTO category_progress "
                "(transfer_id, category_id, items_total, total_bytes, items_verified, items_failed, updated_at) "
                "VALUES (?, ?, ?, 0, ?, ?, ?) "
                "ON CONFLICT(transfer_id, category_id) DO UPDATE SET "
                "items_verified = excluded.items_verified, items_failed = excluded.items_failed, "
                "items_partial = 0, updated_at = excluded.updated_at",
                (transfer_id, category, counts.items_received, counts.items_verified, counts.items_failed, timestamp),
            )

    def get_receipt(self, transfer_id: UUID | str) -> dict | None:
        canonical_id = self._canonical_transfer_id(transfer_id)
        with self._reader() as connection:
            row = connection.execute(
                "SELECT receipt_json FROM transfer_receipts WHERE transfer_id = ?", (canonical_id,)
            ).fetchone()
        if row is None:
            return None
        document = json.loads(row["receipt_json"])
        validate_document(document, "receipt.schema.json")
        return document

    def get_report(self, transfer_id: UUID | str) -> dict | None:
        canonical_id = self._canonical_transfer_id(transfer_id)
        with self._reader() as connection:
            row = connection.execute(
                "SELECT report_json FROM transfer_reports WHERE transfer_id = ?", (canonical_id,)
            ).fetchone()
        return json.loads(row["report_json"]) if row is not None else None
