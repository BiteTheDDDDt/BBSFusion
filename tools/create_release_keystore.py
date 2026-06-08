from __future__ import annotations

import argparse
import secrets
import string
import subprocess
import sys
from pathlib import Path

from common import ROOT, java_home


KEYSTORE = ROOT / "keystore" / "bbsfusion-release.jks"
PROPERTIES = ROOT / "release.properties"
ALIAS = "bbsfusion"


def random_password(length: int = 32) -> str:
    alphabet = string.ascii_letters + string.digits
    return "".join(secrets.choice(alphabet) for _ in range(length))


def keytool_path() -> Path | str:
    home = java_home()
    if home is not None:
        candidate = home / "bin" / ("keytool.exe" if sys.platform.startswith("win") else "keytool")
        if candidate.exists():
            return candidate
    return "keytool"


def main() -> int:
    parser = argparse.ArgumentParser(description="Create a local Android release signing key.")
    parser.add_argument("--force", action="store_true", help="overwrite existing local signing files")
    args = parser.parse_args()

    if (KEYSTORE.exists() or PROPERTIES.exists()) and not args.force:
        print("release signing files already exist; use --force to replace them", file=sys.stderr)
        return 1

    KEYSTORE.parent.mkdir(parents=True, exist_ok=True)
    store_password = random_password()
    key_password = random_password()

    command = [
        str(keytool_path()),
        "-genkeypair",
        "-v",
        "-keystore",
        str(KEYSTORE),
        "-storetype",
        "JKS",
        "-alias",
        ALIAS,
        "-keyalg",
        "RSA",
        "-keysize",
        "4096",
        "-validity",
        "10000",
        "-storepass",
        store_password,
        "-keypass",
        key_password,
        "-dname",
        "CN=BBSFusion, OU=Local Release, O=BBSFusion, L=Local, ST=Local, C=US",
    ]
    result = subprocess.run(command, cwd=ROOT)
    if result.returncode != 0:
        return result.returncode

    PROPERTIES.write_text(
        "\n".join([
            "STORE_FILE=keystore/bbsfusion-release.jks",
            f"STORE_PASSWORD={store_password}",
            f"KEY_ALIAS={ALIAS}",
            f"KEY_PASSWORD={key_password}",
            "",
        ]),
        encoding="utf-8",
    )
    print(f"created {KEYSTORE}")
    print(f"created {PROPERTIES}")
    print("Back up both files. Losing this key prevents APK update compatibility.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
