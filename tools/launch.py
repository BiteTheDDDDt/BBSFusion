from __future__ import annotations

import subprocess
import sys

from common import adb_path

COMPONENT = "dev.bbsfusion/.MainActivity"


def main() -> int:
    adb = adb_path()
    if adb is None:
        print("adb not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.", file=sys.stderr)
        return 1
    return subprocess.run([str(adb), "shell", "am", "start", "-n", COMPONENT]).returncode


if __name__ == "__main__":
    sys.exit(main())
