# Contributing

BBSFusion is a local-only Android forum reader. Keep changes aligned with that scope.

## Rules

- Project automation scripts must be Python and live under `tools/`.
- Do not add project-owned `.ps1`, `.sh`, `.cmd`, or custom `.bat` scripts.
- Official Gradle wrapper files are allowed.
- Do not add analytics, telemetry, crash upload, remote config, background refresh, scheduled sync, push, or hidden network work without explicit discussion.
- Do not log credentials, cookies, tokens, private messages, or WebView console messages from login pages.
- Do not imply endorsement by S1, NGA, or any other forum.

## Before Submitting

Run:

```text
python tools/test.py
python tools/build_debug.py
```

Use a device or emulator for WebView, login, image-loading, and network behavior.
