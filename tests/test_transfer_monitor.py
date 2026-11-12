import unittest

from transferkit.monitor import TransferMonitor


class FakeClock:
    def __init__(self) -> None:
        self.value = 100.0

    def __call__(self) -> float:
        return self.value

    def advance(self, seconds: float) -> None:
        self.value += seconds


class TransferMonitorTests(unittest.TestCase):
    def test_rate_is_based_on_target_acknowledgements_and_eta_is_smoothed(self) -> None:
        clock = FakeClock()
        monitor = TransferMonitor(1000, clock=clock)
        monitor.start()

        clock.advance(2)
        first = monitor.acknowledge(100)
        self.assertEqual(first.confirmed_bytes, 100)
        self.assertEqual(first.bytes_per_second, 50)
        self.assertEqual(first.eta_seconds, 18)

        clock.advance(2)
        second = monitor.acknowledge(300)
        self.assertEqual(second.confirmed_bytes, 300)
        self.assertEqual(second.bytes_per_second, 62.5)
        self.assertEqual(second.eta_seconds, 12)
        self.assertEqual(second.active_seconds, 4)

    def test_pause_and_reconnect_time_do_not_affect_active_time_or_rate(self) -> None:
        clock = FakeClock()
        monitor = TransferMonitor(1000, clock=clock)
        monitor.start()

        clock.advance(2)
        monitor.acknowledge(100)
        paused = monitor.pause()
        self.assertFalse(paused.is_running)
        self.assertEqual(paused.active_seconds, 2)

        clock.advance(300)
        monitor.resume()
        clock.advance(2)
        resumed = monitor.acknowledge(200)
        self.assertEqual(resumed.active_seconds, 4)
        self.assertEqual(resumed.bytes_per_second, 50)
        self.assertEqual(resumed.eta_seconds, 16)

    def test_ack_during_pause_is_counted_but_pause_duration_is_not_sampled(self) -> None:
        clock = FakeClock()
        monitor = TransferMonitor(100, clock=clock)
        monitor.start()
        monitor.pause()

        clock.advance(60)
        during_pause = monitor.acknowledge(10)
        self.assertEqual(during_pause.confirmed_bytes, 10)
        self.assertEqual(during_pause.active_seconds, 0)
        self.assertIsNone(during_pause.bytes_per_second)

        monitor.resume()
        clock.advance(1)
        after_resume = monitor.acknowledge(20)
        self.assertEqual(after_resume.active_seconds, 1)
        self.assertEqual(after_resume.bytes_per_second, 10)

    def test_unknown_or_excessive_eta_is_reported_as_unknown(self) -> None:
        clock = FakeClock()
        monitor = TransferMonitor(None, clock=clock, max_eta_seconds=10)
        initial = monitor.start()
        self.assertIsNone(initial.eta_seconds)

        monitor.set_total_bytes(100_000)
        clock.advance(1)
        too_slow = monitor.acknowledge(1)
        self.assertEqual(too_slow.bytes_per_second, 1)
        self.assertIsNone(too_slow.eta_seconds)

        self.assertIsNone(monitor.snapshot().eta_seconds)

    def test_completion_reports_zero_eta_and_finish_freezes_active_time(self) -> None:
        clock = FakeClock()
        monitor = TransferMonitor(10, clock=clock)
        monitor.start()
        clock.advance(1)
        complete = monitor.acknowledge(10)
        self.assertTrue(complete.is_complete)
        self.assertEqual(complete.eta_seconds, 0)

        clock.advance(2)
        finished = monitor.finish()
        self.assertFalse(finished.is_running)
        self.assertEqual(finished.active_seconds, 3)
        clock.advance(100)
        self.assertEqual(monitor.snapshot().active_seconds, 3)

    def test_acknowledged_bytes_must_be_monotonic_and_within_total(self) -> None:
        clock = FakeClock()
        monitor = TransferMonitor(10, clock=clock)
        monitor.start()
        with self.assertRaisesRegex(ValueError, "nonnegative integer"):
            monitor.acknowledge(-1)
        with self.assertRaisesRegex(ValueError, "exceed total_bytes"):
            monitor.acknowledge(11)

        monitor.acknowledge(5)
        with self.assertRaisesRegex(ValueError, "move backwards"):
            monitor.acknowledge(4)
        with self.assertRaisesRegex(ValueError, "cannot change after it is set"):
            monitor.set_total_bytes(4)

        unknown_total = TransferMonitor(None, clock=clock)
        unknown_total.start()
        unknown_total.acknowledge(5)
        with self.assertRaisesRegex(ValueError, "less than confirmed_bytes"):
            unknown_total.set_total_bytes(4)

    def test_manifest_total_is_frozen_once_known(self) -> None:
        clock = FakeClock()
        monitor = TransferMonitor(None, clock=clock)
        monitor.start()
        monitor.set_total_bytes(20)
        self.assertEqual(monitor.snapshot().total_bytes, 20)
        with self.assertRaisesRegex(ValueError, "cannot change after it is set"):
            monitor.set_total_bytes(21)
        with self.assertRaisesRegex(ValueError, "cannot change after it is set"):
            monitor.set_total_bytes(None)

    def test_invalid_timing_configuration_is_rejected(self) -> None:
        with self.assertRaisesRegex(ValueError, "smoothing_factor"):
            TransferMonitor(10, smoothing_factor=0)
        with self.assertRaisesRegex(ValueError, "max_eta_seconds"):
            TransferMonitor(10, max_eta_seconds=0)


if __name__ == "__main__":
    unittest.main()
