# BBSFusion

BBSFusion is a local-only Android forum reader MVP for S1 and NGA.

## Scope

- Android app only.
- No cloud service.
- No account proxy or backend.
- No analytics, telemetry, or crash upload.
- No background refresh, polling, push, scheduled sync, or hidden network work.
- Manual topic-list refresh.
- Native topic-list and topic-reading views.
- Login and original-site actions through WebView.

This project is not affiliated with, endorsed by, or sponsored by S1, NGA, or their operators.

## Current Features

- S1 and NGA topic list aggregation.
- Local subscription groups.
- Original-site login through WebView cookies.
- Native topic detail view with usernames, avatars, and inline images.
- Local-only preferences through Android storage.

## Requirements

- Android Studio or Android SDK command-line tools.
- JDK 17. Android Studio's bundled JBR works.
- Android SDK with the compile SDK configured by `app/build.gradle.kts`.
- Python 3.10 or newer for scripts under `tools/`.

Create `local.properties` locally if Android Studio does not create it for you:

```text
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

You can also set environment variables:

```text
JAVA_HOME=<path-to-jdk-17>
ANDROID_HOME=<path-to-android-sdk>
```

## Build And Test

```text
python tools/test.py
python tools/build_debug.py
```

Install and launch the debug build on a connected device or emulator:

```text
python tools/install_debug.py
python tools/launch.py
```

The debug APK is generated at:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Release APK

Create a local signing key once:

```text
python tools/create_release_keystore.py
```

Then build a signed release APK:

```text
python tools/build_release.py
```

The APK is copied to `dist/`. Keep `keystore/` and `release.properties` private and backed up. See [docs/release.md](docs/release.md).

## Development Rules

- Project-owned automation scripts must be Python.
- Do not add `.ps1`, `.sh`, `.cmd`, or custom `.bat` scripts.
- Official Gradle wrapper files are allowed.
- Do not bypass captcha, login challenges, anti-abuse checks, device checks, or signed request mechanisms.
- Keep credentials and cookies on the device.

See [CONTRIBUTING.md](CONTRIBUTING.md), [PRIVACY.md](PRIVACY.md), and [SECURITY.md](SECURITY.md).
