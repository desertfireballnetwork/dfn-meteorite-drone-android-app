# DFN Stage 4 — Android Drone App

Proof-of-concept Android app for the DFN (Desert Fireball Network) meteorite-drone platform. Contains the project scaffold (A-1, issue #2): a Gradle + Compose + Hilt shell that builds a debug-installable APK with no business logic.

## Prerequisites

- **JDK 21** (Temurin recommended) to run Gradle/AGP 9 — verify with `java -version`.
- **JDK 17** (Temurin recommended) as the unit-test toolchain (Gradle resolves it automatically; any JDK 17 installation is picked up, or set `org.gradle.java.installations.paths` in `gradle.properties`).
- **Android SDK** with `platforms;android-37` and matching build-tools installed. Set `ANDROID_HOME` to the SDK root.
- **Two Mapbox tokens**: a secret `sk.` downloads token (scope `downloads:read`) for Maven, and a public `pk.` runtime token. See "Local configuration" below.

## Local configuration

Create a `local.properties` file at the repo root (this file is git-ignored and MUST NOT be committed) with:

```
MAPBOX_DOWNLOADS_TOKEN=sk.…your secret downloads token…
MAPBOX_ACCESS_TOKEN=pk.…your public runtime token…
PRODUCTION_SERVER_URL=
DEV_SERVER_URL=
```

- `MAPBOX_DOWNLOADS_TOKEN` (secret `sk.`) is required to resolve Mapbox artifacts (`com.mapbox.maps:android`, `com.mapbox.extension:maps-compose`) from Mapbox's private Maven registry. It is never packaged into the APK.
- `MAPBOX_ACCESS_TOKEN` (public `pk.`) is embedded in `BuildConfig` as the runtime map token. The build fails fast with a clear message when it is blank.
- As an alternative (or for CI), export `ORG_GRADLE_PROJECT_MAPBOX_DOWNLOADS_TOKEN` and `ORG_GRADLE_PROJECT_MAPBOX_ACCESS_TOKEN` in the environment.

## Build

```
./gradlew assembleDebug
```

The debug APK is written to `app/build/outputs/apk/debug/app-debug.apk`.

## Test, lint, and static analysis

```
./gradlew test                    # JVM unit tests (run on the JDK 17 toolchain)
./gradlew ktlintCheck             # ktlint
./gradlew detekt                  # detekt
./gradlew assembleDebugAndroidTest  # compiles the instrumented (androidTest) suite
./gradlew --no-daemon ktlintCheck detekt test assembleDebug assembleDebugAndroidTest   # everything CI runs
```

## CI

Continuous integration (`.github/workflows/ci.yml`) runs on every push to `main` and on every pull request. It provisions Temurin JDK 21 (Gradle launcher) and Temurin JDK 17 (unit-test toolchain) via `actions/setup-java@v5`, populates `~/.gradle/gradle.properties` with the `MAPBOX_DOWNLOADS_TOKEN` and `MAPBOX_ACCESS_TOKEN` repo secrets, then runs `./gradlew --no-daemon ktlintCheck detekt test assembleDebug assembleDebugAndroidTest`. Instrumented tests are compiled but not executed on CI — running them requires a device or emulator (see the PR smoke-test evidence gates). Test reports and the debug APK are uploaded as artifacts when the job fails.

### Required GitHub repo secrets

Add both secrets under **Settings → Secrets and variables → Actions**:

- `MAPBOX_DOWNLOADS_TOKEN` — the secret `sk.` token with `downloads:read` scope.
- `MAPBOX_ACCESS_TOKEN` — the public `pk.` runtime token.

Without the downloads token, CI cannot resolve Mapbox Maven artifacts; without the runtime token the build fails its blank-token guard.

## Stack

See `gradle/libs.versions.toml` for the single source of truth on every dependency version. Kotlin 2.3.10 + KSP 2.3.10, AGP 9.3.0, Gradle 9.6.1, Compose BOM 2026.06.01, JDK 21 (Gradle launcher) + JDK 17 (test toolchain), `compileSdk`/`targetSdk` 37, `minSdk` 30, package `au.edu.fireballs.stage4`.