import unittest

from transferkit.ui.reason_text import reason_message


class ReasonTextTests(unittest.TestCase):
    def test_known_error_is_localized_without_machine_code(self) -> None:
        message = reason_message("DEVICE_UNAUTHORIZED")
        self.assertIn("підтвердіть", message)
        self.assertNotIn("DEVICE_UNAUTHORIZED", message)

    def test_unknown_error_uses_safe_fallback_without_machine_code(self) -> None:
        message = reason_message("NEW_INTERNAL_ERROR")
        self.assertIn("Перевірте підключення", message)
        self.assertNotIn("NEW_INTERNAL_ERROR", message)

    def test_incomplete_transfer_has_a_user_facing_explanation(self) -> None:
        message = reason_message("TRANSFER_INCOMPLETE")
        self.assertIn("фінальну квитанцію", message)
        self.assertNotIn("TRANSFER_INCOMPLETE", message)


if __name__ == "__main__":
    unittest.main()
