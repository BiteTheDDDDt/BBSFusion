from __future__ import annotations

import subprocess
import sys

from common import ROOT, adb_path

APK = ROOT / "app" / "build" / "outputs" / "apk" / "debug" / "app-debug.apk"


def main() -> int:
    adb = adb_path()
    if adb is None:
        print("adb not found. Set ANDROID_HOME or ANDROID_SDK_ROOT.", file=sys.stderr)
        return 1

    if not APK.exists():
        print("APK not found. Run `python tools/build_debug.py` first.", file=sys.stderr)
        return 1

    subprocess.run([str(adb), "devices"], cwd=ROOT, check=False)
    return subprocess.run([str(adb), "install", "-r", str(APK)], cwd=ROOT).returncode


if __name__ == "__main__":
    sys.exit(main())
