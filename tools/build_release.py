from __future__ import annotations

import re
import shutil
import sys
from pathlib import Path

from common import ROOT, run_gradle


RELEASE_PROPERTIES = ROOT / "release.properties"
APP_BUILD = ROOT / "app" / "build.gradle.kts"
RELEASE_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
UNSIGNED_APK = ROOT / "app" / "build" / "outputs" / "apk" / "release" / "app-release-unsigned.apk"
DIST = ROOT / "dist"


def app_version() -> tuple[str, str]:
    text = APP_BUILD.read_text(encoding="utf-8")
    version_name = re.search(r'versionName\s*=\s*"([^"]+)"', text)
    version_code = re.search(r"versionCode\s*=\s*(\d+)", text)
    return (
        version_name.group(1) if version_name else "0.0.0",
        version_code.group(1) if version_code else "0",
    )


def main() -> int:
    if not RELEASE_PROPERTIES.exists():
        print(
            "release.properties not found. Run `python tools/create_release_keystore.py` first.",
            file=sys.stderr,
        )
        return 1

    result = run_gradle(":app:assembleRelease")
    if result != 0:
        return result

    if not RELEASE_APK.exists():
        if UNSIGNED_APK.exists():
            print("release APK is unsigned; check release.properties signing values", file=sys.stderr)
        else:
            print("release APK not found", file=sys.stderr)
        return 1

    version_name, version_code = app_version()
    DIST.mkdir(parents=True, exist_ok=True)
    output = DIST / f"bbsfusion-v{version_name}-{version_code}.apk"
    shutil.copy2(RELEASE_APK, output)
    print(f"release APK: {output}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
