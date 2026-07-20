# DFN Stage 4 — Android Drone App

Proof-of-concept Android app for the DFN (Desert Fireball Network) meteorite-drone platform. Contains the project scaffold (A-1, issue #2): a Gradle + Compose + Hilt shell that builds a debug-installable APK with no business logic.

## Prerequisites

- **JDK 21** (Temurin recommended). AGP 9 hard-requires JDK 21 to run Gradle. Verify with `java -version`.
- **Android SDK** with `platforms;android-35` and `build-tools;35.0.1` (or newer 35.x) installed. Set `ANDROID_HOME` to the SDK root.
- **Mapbox downloads token** (secret `sk.` token with `downloads:read` scope). See "Local configuration" below.

## Local configuration

Create a `local.properties` file at the repo root (this file is git-ignored and MUST NOT be committed) with:

```
MAPBOX_DOWNLOADS_TOKEN=sk.…your secret token…
PRODUCTION_SERVER_URL=
DEV_SERVER_URL=
```

The Mapbox token is required to resolve the `com.mapbox.maps:android` artifact from Mapbox's private Maven registry. As an alternative (or for CI), you can export `ORG_GRADLE_PROJECT_MAPBOX_DOWNLOADS_TOKEN=<token>` in the environment.

## Build

```
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Test, lint, and static analysis

```
./gradlew test                 # unit tests
./gradlew ktlintCheck          # ktlint
./gradlew detekt               # detekt
./gradlew --no-daemon ktlintCheck detekt test assembleDebug   # everything CI runs
```

## CI

Continuous integration (`.github/workflows/ci.yml`) runs on every push to `main` and on every pull request. It uses JDK 21 Temurin via `actions/setup-java@v4`, populates `~/.gradle/gradle.properties` with the `MAPBOX_DOWNLOADS_TOKEN` repo secret, then runs `./gradlew --no-daemon ktlintCheck detekt test assembleDebug`. Test reports and the debug APK are uploaded as artifacts when the job fails.

### Required GitHub repo secret

Lewis (maintainer) must add the following secret to the repo under **Settings → Secrets and variables → Actions**:

- `MAPBOX_DOWNLOADS_TOKEN` — the secret `sk.` token with `downloads:read` scope, obtained from the Mapbox account dashboard.

Without this secret, CI cannot resolve Mapbox Maven artifacts and the build will fail.

## Stack

See `gradle/libs.versions.toml` for the single source of truth on every dependency version. Kotlin 2.3.10 + KSP 2.3.10, AGP 9.3.0, Gradle 9.6.1, Compose BOM 2026.06.01, JDK 21, `compileSdk`/`targetSdk` 35, `minSdk` 30, package `au.edu.fireballs.stage4`.