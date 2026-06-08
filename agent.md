# BBSFusion Agent Notes

This file records project direction and working rules for future agents.

## Product Direction

BBSFusion is a local-only Android forum reader for S1, NGA, V2EX, and Linux.do.

- Android client only.
- No cloud backend.
- No background refresh, polling, push, scheduled sync, or hidden network work.
- Every network fetch must be triggered by an explicit user action.
- Login, reply, moderation, captcha, and other sensitive flows should use the original site in WebView until there is a clear reason to make them native.
- Main reading flows may use single-site mode or local subscription groups.
- Subscription groups are local-only sets of board definitions; refreshing a group fetches its boards only after the user taps the group/refresh control.
- Do not bypass captcha, login challenges, anti-abuse checks, Cloudflare-like checks, device checks, or signed request mechanisms.
- Do not imply official endorsement by S1, NGA, V2EX, Linux.do, or any other forum operator.
- Respect source-site rules. Linux.do is read-only in this app; do not add AI-generated posting, reply drafting, or auto-posting features.

## Architecture

Current MVP stack:

- Java Android app with native Android views.
- Gradle + Android Gradle Plugin.
- jsoup for HTML parsing.
- One connector per forum under `app/src/main/java/dev/bbsfusion/site`.
- Shared connector contracts under `app/src/main/java/dev/bbsfusion/core`.
- Board catalog and subscription group state live under `core`; persisted subscription data uses local Android `SharedPreferences`.
- `SubscriptionActivity` is the local board/group configuration surface.
- S1 topic lists prefer desktop forum pages because they expose last-reply timestamps.
- NGA topic lists prefer the official app API endpoint `app_api.php?__lib=subject&__act=list`; it should use only local cookies from `CookieManager` and must not log response bodies.
- V2EX topic lists use the older public JSON endpoints for anonymous reading. API 2.0 is PAT-based and should not be enabled without a local-only token design.
- Linux.do uses Discourse JSON endpoints first and crawler HTML as fallback. Do not bypass Cloudflare-like checks or other anti-abuse controls; WebView remains the fallback when native fetches are blocked.

The connector boundary should stay small:

- Fetch topic lists.
- Fetch topic lists for a specific board URL.
- Fetch currently visible board directories when the user asks to refresh the catalog.
- Fetch topic detail.
- Return original URLs for WebView.

Aggregated subscription feeds should deduplicate by site/topic URL and then sort by parsed last activity time descending. If a site cannot expose a timestamp, keep it as a fallback rather than inventing one.

Avoid adding large abstractions until real duplication or a real feature need appears.

## Data And Privacy

- Keep credentials and cookies on device only.
- Do not upload cookies, tokens, post content, private messages, profile data, or browsing history.
- Do not introduce analytics, telemetry, crash upload, or remote config without explicit approval.
- Cache only what the user-facing feature needs, and prefer short-lived/local cache.
- Do not forward WebView console messages to logcat. Login pages may print sensitive callback payloads.

## Script Policy

All project-owned automation scripts must be Python.

- Do not add new `.ps1`, `.sh`, `.cmd`, or custom `.bat` scripts.
- It is acceptable to keep official generated wrapper files such as `gradlew` and `gradlew.bat`.
- Put project scripts under `tools/`.
- Scripts should work from the repository root and avoid changing global machine state.
- Keep Gradle caches in the project-local `.gradle-home` when run through project scripts.

## Testing

Use local unit tests for parser behavior with small HTML fixtures.

Before handing off Android code changes, run:

```text
python tools/test.py
python tools/build_debug.py
```

For device testing, connect a phone with USB debugging enabled and run:

```text
python tools/install_debug.py
python tools/launch.py
```

## Environment Assumptions

Use local environment configuration instead of hard-coded machine paths:

- Prefer `JAVA_HOME` for the JDK.
- Prefer `ANDROID_HOME` or `ANDROID_SDK_ROOT` for the Android SDK.
- Keep `local.properties` local and out of git.
- The Python scripts may use common Android Studio install locations as fallbacks, but the repository should not require a developer-specific path.
