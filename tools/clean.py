from __future__ import annotations

import shutil
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
TARGETS = [
    ROOT / ".gradle-home",
    ROOT / ".gradle",
    ROOT / "build",
    ROOT / "app" / "build",
]


def main() -> int:
    for target in TARGETS:
        if not target.exists():
            continue
        resolved = target.resolve()
        if ROOT.resolve() not in resolved.parents:
            raise RuntimeError(f"Refusing to delete outside project: {resolved}")
        shutil.rmtree(resolved)
        print(f"deleted {resolved}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
