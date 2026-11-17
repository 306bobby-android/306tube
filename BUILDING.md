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
