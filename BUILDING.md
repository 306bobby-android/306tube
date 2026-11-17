# Building

## Requirements

- JDK 17
- Android SDK, platform 35
- Gradle comes from the wrapper (8.13)

Point Gradle at the SDK with `ANDROID_HOME`, or a `local.properties` in the repo root:

```
sdk.dir=/path/to/Android/Sdk
```

## Debug

```
./gradlew :app:assembleDebug
```

APK lands in `app/build/outputs/apk/debug/`.

## Release

Release builds are signed only if `keystore.properties` exists in the repo root:

```
storeFile=my-release.jks
storePassword=...
keyAlias=...
keyPassword=...
```

`storeFile` is relative to the repo root. Both files are gitignored. Keep it that way.

```
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Without `keystore.properties` you get an unsigned APK. An update only installs over an existing copy if it's signed with the same key.

`lintVitalRelease` prints a wall of `Module was compiled with an incompatible version of Kotlin` errors. It's noise, the build still passes.

## Test builds

Every push to a branch is built by `.github/workflows/build.yml` and signed with the release key, so it installs over a released copy. The APK is attached to the workflow run as an artifact, kept for 30 days. Its version is `git describe`, like `1.0.0-12-gabc1234`.

## Cutting a release

Releases are built by `.github/workflows/release.yml` when a version tag is pushed.

1. In `CHANGELOG.md`, rename `## Unreleased` to the version (`## 1.0.0`) and add a fresh `## Unreleased` above it.
2. Commit, then tag and push:

```
git tag 1.0.0
git push origin main 1.0.0
```

Tags are bare semver, no `v`. A suffix like `1.0.0-rc1` gets published as a pre-release.

The workflow sets `versionName` to the tag and `versionCode` to `major*10000 + minor*100 + patch`, builds the signed APK, and creates the GitHub release from `.github/release-template.md` plus that version's changelog section. No matching changelog section means the run fails before building.

Signing needs four repo secrets: `KEYSTORE_BASE64` (`base64 -w0 my-release.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. Without them the run fails instead of shipping an unsigned APK.

## Layout

| Module | What's in it |
| --- | --- |
| `app` | UI (Compose), settings, Room database, downloads, accounts |
| `innertube` | YouTube's internal API: client, parsers, models. Plain JVM. |
| `player` | Media3 playback, stream resolving, PoToken and JS challenge solving |
| `cast` | DIAL discovery and the YouTube Lounge protocol. Plain JVM. |
| `recommend` | On-device Home feed. Plain JVM. |

Versions live in `gradle/libs.versions.toml`. The app version is in `app/build.gradle.kts`.

## Heads up

This tree was reconstructed from the 1.0-RC1 release APK after the original source was lost. Behaviour matches the shipped build; comments and local structure are best-effort.
