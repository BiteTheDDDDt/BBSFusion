# Release

This project publishes APKs outside app stores. Release APKs must be signed.

## Create a Local Signing Key

Run once on the release machine:

```text
python tools/create_release_keystore.py
```

This creates:

- `keystore/bbsfusion-release.jks`
- `release.properties`

Both files are ignored by git. Back them up securely. Losing the signing key means future APKs cannot update installations signed with the old key.

## Build a Release APK

```text
python tools/build_release.py
```

The signed APK is copied to:

```text
dist/bbsfusion-v<versionName>-<versionCode>.apk
```

## GitHub Release

After pushing tags to GitHub, create a release and upload the APK:

```text
git tag v0.1.0
git push origin main --tags
gh release create v0.1.0 dist/bbsfusion-v0.1.0-1.apk --title "BBSFusion v0.1.0" --notes "Initial MVP release."
```

Use the GitHub web UI if the GitHub CLI is not installed.
