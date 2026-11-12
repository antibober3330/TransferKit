import re
import unittest
from pathlib import Path

from transferkit import __version__


ROOT = Path(__file__).resolve().parents[1]


class VersioningTests(unittest.TestCase):
    def test_version_source_of_truth_matches_python_android_and_changelog(self) -> None:
        version = (ROOT / "VERSION").read_text(encoding="utf-8").strip()
        self.assertRegex(version, r"^(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-(?:alpha|beta|rc)\.[1-9][0-9]*)?$")
        self.assertEqual(__version__, version)

        android_build = (ROOT / "android-client" / "app" / "build.gradle.kts").read_text(encoding="utf-8")
        self.assertIn(f'versionName = "{version}"', android_build)
        changelog = (ROOT / "CHANGELOG.md").read_text(encoding="utf-8")
        self.assertRegex(changelog, rf"(?m)^## \[{re.escape(version)}\] - \d{{4}}-\d{{2}}-\d{{2}}$")


if __name__ == "__main__":
    unittest.main()
