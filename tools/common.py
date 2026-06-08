from __future__ import annotations

import os
import shutil
import subprocess
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MANUAL_GRADLE = ROOT / ".gradle-home" / "manual" / "gradle-9.4.1" / "bin" / "gradle.bat"


def local_properties() -> dict[str, str]:
    path = ROOT / "local.properties"
    values: dict[str, str] = {}
    if not path.exists():
        return values
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        values[key.strip()] = value.strip().replace("\\:", ":")
    return values


def java_home() -> Path | None:
    env = os.environ.get("JAVA_HOME", "").strip()
    if env:
        path = Path(env)
        if path.exists():
            return path

    android_studio_home = os.environ.get("ANDROID_STUDIO_HOME", "").strip()
    candidates = []
    if android_studio_home:
        candidates.append(Path(android_studio_home) / "jbr")
    candidates.extend([
        Path(r"C:\Program Files\Android\Android Studio\jbr"),
    ])
    for candidate in candidates:
        if candidate.exists():
            return candidate
    return None


def android_sdk() -> Path | None:
    for name in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        env = os.environ.get(name, "").strip()
        if env and Path(env).exists():
            return Path(env)

    sdk_dir = local_properties().get("sdk.dir", "")
    if sdk_dir and Path(sdk_dir).exists():
        return Path(sdk_dir)

    default = Path.home() / "AppData" / "Local" / "Android" / "Sdk"
    if default.exists():
        return default
    return None


def adb_path() -> Path | None:
    sdk = android_sdk()
    if sdk is not None:
        candidate = sdk / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb")
        if candidate.exists():
            return candidate

    found = shutil.which("adb")
    return Path(found) if found else None


def gradle_executable() -> Path:
    if os.name == "nt" and MANUAL_GRADLE.exists():
        return MANUAL_GRADLE
    return ROOT / ("gradlew.bat" if os.name == "nt" else "gradlew")


def gradle_command(task: str) -> list[str]:
    gradle = gradle_executable()
    if os.name == "nt":
        return ["cmd.exe", "/c", str(gradle), task]
    return [str(gradle), task]


def gradle_env() -> dict[str, str]:
    env = os.environ.copy()
    detected_java_home = java_home()
    if detected_java_home is not None:
        env["JAVA_HOME"] = str(detected_java_home)
    env["GRADLE_USER_HOME"] = str(ROOT / ".gradle-home")
    return env


def run_gradle(task: str) -> int:
    return subprocess.run(gradle_command(task), cwd=ROOT, env=gradle_env()).returncode
