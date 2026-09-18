# DFN Stage 4 — Android Drone App

An offline-first native Android app for Stage 4 meteorite-candidate review in remote field
campaigns. It provides Django session login, a survey picker, basecamp claiming with offline
tile and candidate downloads, and on-map candidate review with a Yes/No decision, detection
tag, and camera evidence photo. Reviews persist offline and sync when connectivity returns.

**Status:** Prototype (`versionName 0.1.0`), pre-release, and in field testing.

## Getting the app

### Field testers

[Download and install the APK](DEPLOYMENT.md#install-the-app). Download the APK and never
build the app from source.

### Developers and maintainers

Follow the [build-from-source instructions](DEPLOYMENT.md#build-from-source).

## Prerequisites

- JDK 21 as the Gradle launcher and a JDK 17 test toolchain.
- Android SDK platform 37 (`platforms;android-37.0`) and build tools
  `build-tools;37.0.0`.
- `ANDROID_HOME` set to the Android SDK location.
- A Mapbox downloads token and a Mapbox public access token.

## Local configuration

Create an untracked `local.properties` in the repository root:

```properties
MAPBOX_DOWNLOADS_TOKEN=your_mapbox_downloads_token
MAPBOX_ACCESS_TOKEN=your_mapbox_public_access_token
PRODUCTION_SERVER_URL=https://find.gfo.rocks/
DEV_SERVER_URL=
```

`DEV_SERVER_URL` is reserved and currently unused. Keep tokens and local configuration out
of version control. The environment alternatives are:

- `ORG_GRADLE_PROJECT_MAPBOX_DOWNLOADS_TOKEN`
- `ORG_GRADLE_PROJECT_MAPBOX_ACCESS_TOKEN`
- `ORG_GRADLE_PROJECT_PRODUCTION_SERVER_URL`

## Build

```bash
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Test, lint, and static analysis

```bash
./gradlew test
./gradlew ktlintCheck
./gradlew detekt
./gradlew assembleDebugAndroidTest
```

Run the complete local CI gate with:

```bash
./gradlew --no-daemon ktlintCheck detekt test assembleDebug assembleDebugAndroidTest
```

## Continuous integration and releases

`.github/workflows/ci.yml` runs on pushes to `main` and pull requests. It runs:

```bash
./gradlew --no-daemon ktlintCheck detekt test assembleDebug assembleDebugAndroidTest
```

On success, CI uploads the debug APK as an artefact. `.github/workflows/release.yml`
publishes the APK to a GitHub Release for tags matching `v*`.

Configure these GitHub repository secrets under **Settings → Secrets and variables →
Actions**:

- `MAPBOX_DOWNLOADS_TOKEN`
- `MAPBOX_ACCESS_TOKEN`
- `PRODUCTION_SERVER_URL`, with value `https://find.gfo.rocks/`

Select **New repository secret** for each entry. Full setup and release instructions are in
[DEPLOYMENT.md](DEPLOYMENT.md).

## Stack

- Kotlin 2.3.10 and KSP 2.3.10
- Android Gradle Plugin 9.3.0 and Gradle wrapper 9.6.1
- Compose BOM 2026.06.01
- Hilt 2.60.1
- Room 2.8.4
- OkHttp 5.4.0, Retrofit 3.0.0, and Moshi 1.15.2
- Mapbox 11.26.0
- `compileSdk` and `targetSdk` 37; `minSdk` 30
- Package: `au.edu.fireballs.stage4`
