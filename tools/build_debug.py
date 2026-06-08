from __future__ import annotations

import sys

from common import run_gradle


def main() -> int:
    return run_gradle(":app:assembleDebug")


if __name__ == "__main__":
    sys.exit(main())
