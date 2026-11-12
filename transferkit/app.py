"""PySide6 shell for the Windows Station."""

from __future__ import annotations

import argparse
import sys

from . import __version__


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="transferkit", description="TransferKit Android migration station")
    parser.add_argument("--version", action="version", version=f"TransferKit {__version__}")
    args = parser.parse_args(argv)
    del args

    from PySide6.QtWidgets import QApplication
    from .ui.station import StationWindow

    application = QApplication(sys.argv[:1] if argv is None else [sys.argv[0], *argv])
    application.setApplicationName("TransferKit")
    window = StationWindow()
    window.show()
    return application.exec()
