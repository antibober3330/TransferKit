import io
import unittest
from contextlib import redirect_stdout
from unittest.mock import patch

from transferkit.contracts import ReasonCode
from transferkit.device_detect import AdbError
from transferkit.usb_adb_smoke import run_smoke


class UsbAdbSmokeTests(unittest.TestCase):
    def test_adb_error_is_reported_without_uncaught_traceback(self) -> None:
        output = io.StringIO()
        with patch(
            "transferkit.usb_adb_smoke.AdbClient",
            side_effect=AdbError(ReasonCode.ADB_UNAVAILABLE, "synthetic ADB failure"),
        ):
            with redirect_stdout(output):
                result = run_smoke("synthetic-serial")

        self.assertEqual(result, 1)
        self.assertIn("USB/ADB smoke failed:", output.getvalue())
        self.assertIn("synthetic ADB failure", output.getvalue())


if __name__ == "__main__":
    unittest.main()
