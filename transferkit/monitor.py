"""Transport-neutral progress metrics based on durable target acknowledgements."""

from __future__ import annotations

import math
import time
from collections.abc import Callable
from dataclasses import dataclass


Clock = Callable[[], float]
_MIN_RATE_SAMPLE_SECONDS = 0.5
_DEFAULT_MAX_ETA_SECONDS = 7 * 24 * 60 * 60


@dataclass(frozen=True, slots=True)
class TransferProgressSnapshot:
    """Immutable metrics for UI, logging, or another transport-neutral consumer."""

    confirmed_bytes: int
    total_bytes: int | None
    active_seconds: float
    bytes_per_second: float | None
    eta_seconds: int | None
    is_running: bool
    is_complete: bool


class TransferMonitor:
    """Track cumulative bytes confirmed by the target and active transfer time.

    Call :meth:`acknowledge` only after validating the target ACK. Paused and
    reconnecting intervals are excluded from active time and rate samples.
    ``eta_seconds`` is unknown until a rate can be estimated, and is also
    reported as unknown when it exceeds the configured bound.
    """

    def __init__(
        self,
        total_bytes: int | None = None,
        *,
        clock: Clock = time.monotonic,
        smoothing_factor: float = 0.25,
        max_eta_seconds: int = _DEFAULT_MAX_ETA_SECONDS,
    ) -> None:
        self._validate_total(total_bytes)
        try:
            smoothing = float(smoothing_factor)
        except (TypeError, ValueError) as exc:
            raise ValueError("smoothing_factor must be between 0 and 1") from exc
        if not math.isfinite(smoothing) or not 0 < smoothing <= 1:
            raise ValueError("smoothing_factor must be between 0 and 1")
        if isinstance(max_eta_seconds, bool) or not isinstance(max_eta_seconds, int) or max_eta_seconds <= 0:
            raise ValueError("max_eta_seconds must be a positive integer")
        if not callable(clock):
            raise ValueError("clock must be callable")

        self._total_bytes = total_bytes
        self._clock = clock
        self._smoothing_factor = smoothing
        self._max_eta_seconds = max_eta_seconds
        self._confirmed_bytes = 0
        self._active_seconds = 0.0
        self._active_started_at: float | None = None
        self._rate_window_started_at: float | None = None
        self._pending_rate_bytes = 0
        self._bytes_per_second: float | None = None
        self._last_clock_value: float | None = None
        self._started = False
        self._finished = False

    @staticmethod
    def _validate_total(total_bytes: int | None) -> None:
        if total_bytes is not None and (
            isinstance(total_bytes, bool) or not isinstance(total_bytes, int) or total_bytes < 0
        ):
            raise ValueError("total_bytes must be a nonnegative integer or None")

    def _now(self) -> float:
        try:
            now = float(self._clock())
        except (TypeError, ValueError) as exc:
            raise ValueError("clock must return a finite monotonic value") from exc
        if not math.isfinite(now):
            raise ValueError("clock must return a finite monotonic value")
        if self._last_clock_value is not None and now < self._last_clock_value:
            raise ValueError("clock moved backwards")
        self._last_clock_value = now
        return now

    def start(self) -> TransferProgressSnapshot:
        if self._started:
            raise RuntimeError("transfer monitor has already started")
        now = self._now()
        self._started = True
        self._active_started_at = now
        self._rate_window_started_at = now
        return self._snapshot_at(now)

    def set_total_bytes(self, total_bytes: int | None) -> TransferProgressSnapshot:
        """Set the manifest total once it becomes available; then keep it fixed."""
        self._validate_total(total_bytes)
        if self._total_bytes is not None and total_bytes != self._total_bytes:
            raise ValueError("total_bytes cannot change after it is set")
        if total_bytes is not None and total_bytes < self._confirmed_bytes:
            raise ValueError("total_bytes cannot be less than confirmed_bytes")
        if self._finished:
            raise RuntimeError("transfer monitor is finished")
        self._total_bytes = total_bytes
        return self.snapshot()

    def acknowledge(self, confirmed_bytes: int) -> TransferProgressSnapshot:
        """Record a cumulative byte count confirmed by valid target ACKs."""
        if isinstance(confirmed_bytes, bool) or not isinstance(confirmed_bytes, int) or confirmed_bytes < 0:
            raise ValueError("confirmed_bytes must be a nonnegative integer")
        if confirmed_bytes < self._confirmed_bytes:
            raise ValueError("confirmed_bytes cannot move backwards")
        if self._total_bytes is not None and confirmed_bytes > self._total_bytes:
            raise ValueError("confirmed_bytes cannot exceed total_bytes")
        if not self._started:
            raise RuntimeError("transfer monitor has not started")
        if self._finished:
            raise RuntimeError("transfer monitor is finished")

        now = self._now()
        newly_confirmed = confirmed_bytes - self._confirmed_bytes
        self._confirmed_bytes = confirmed_bytes
        if newly_confirmed and self._active_started_at is not None:
            self._pending_rate_bytes += newly_confirmed
            if self._rate_window_started_at is None:
                self._rate_window_started_at = now
            elapsed = now - self._rate_window_started_at
            if elapsed >= _MIN_RATE_SAMPLE_SECONDS:
                sample = self._pending_rate_bytes / elapsed
                if math.isfinite(sample) and sample > 0:
                    if self._bytes_per_second is None:
                        self._bytes_per_second = sample
                    else:
                        alpha = self._smoothing_factor
                        self._bytes_per_second = alpha * sample + (1 - alpha) * self._bytes_per_second
                self._pending_rate_bytes = 0
                self._rate_window_started_at = now
        return self._snapshot_at(now)

    def pause(self) -> TransferProgressSnapshot:
        """Pause metrics while reconnecting or waiting for user action."""
        if not self._started:
            raise RuntimeError("transfer monitor has not started")
        if self._finished:
            return self.snapshot()
        now = self._now()
        if self._active_started_at is not None:
            self._active_seconds += now - self._active_started_at
            self._active_started_at = None
        self._rate_window_started_at = None
        self._pending_rate_bytes = 0
        return self._snapshot_at(now)

    def resume(self) -> TransferProgressSnapshot:
        """Resume metrics with a fresh rate window, excluding the paused gap."""
        if not self._started:
            raise RuntimeError("transfer monitor has not started")
        if self._finished:
            raise RuntimeError("transfer monitor is finished")
        if self._active_started_at is not None:
            return self.snapshot()
        now = self._now()
        self._active_started_at = now
        self._rate_window_started_at = now
        self._pending_rate_bytes = 0
        return self._snapshot_at(now)

    def finish(self) -> TransferProgressSnapshot:
        """Stop the active clock while retaining the last acknowledgement state."""
        if not self._started:
            raise RuntimeError("transfer monitor has not started")
        if self._finished:
            return self.snapshot()
        now = self._now()
        if self._active_started_at is not None:
            self._active_seconds += now - self._active_started_at
        self._active_started_at = None
        self._rate_window_started_at = None
        self._pending_rate_bytes = 0
        self._finished = True
        return self._snapshot_at(now)

    def snapshot(self) -> TransferProgressSnapshot:
        if not self._started:
            return self._snapshot_at(None)
        return self._snapshot_at(self._now())

    def _snapshot_at(self, now: float | None) -> TransferProgressSnapshot:
        active_seconds = self._active_seconds
        if now is not None and self._active_started_at is not None:
            active_seconds += now - self._active_started_at

        is_complete = self._total_bytes is not None and self._confirmed_bytes >= self._total_bytes
        eta_seconds: int | None
        if is_complete:
            eta_seconds = 0
        elif self._total_bytes is None or self._bytes_per_second is None or self._bytes_per_second <= 0:
            eta_seconds = None
        else:
            estimate = math.ceil((self._total_bytes - self._confirmed_bytes) / self._bytes_per_second)
            eta_seconds = estimate if estimate <= self._max_eta_seconds else None

        return TransferProgressSnapshot(
            confirmed_bytes=self._confirmed_bytes,
            total_bytes=self._total_bytes,
            active_seconds=active_seconds,
            bytes_per_second=self._bytes_per_second,
            eta_seconds=eta_seconds,
            is_running=self._active_started_at is not None and not self._finished,
            is_complete=is_complete,
        )
