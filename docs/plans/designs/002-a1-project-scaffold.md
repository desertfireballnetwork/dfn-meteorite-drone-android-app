# 002 — A-1: Project Scaffold — Gradle + Compose + Hilt + Room + OkHttp + Mapbox

- **Status:** APPROVED
- **Issue:** #2
- **Epic:** #1
- **Repo:** `dfn-meteorite-drone-android-app` (Android epic lives here)
- **Stack decision:** Fixed by prior brainstorm (`docs/plans/designs/001-stage4-android-app-initial-design.md`); version pins resolved to the actual latest stable as of 2026-07-20 by direct Maven / Mapbox downloads registry queries.

## 1. Context

The repo is empty. Issue #2 is the project bootstrap: create the minimal Gradle + Compose + Hilt scaffold that every subsequent A-issue (A-3 through A-20) builds on. No business logic — a debug-installable empty shell that passes CI.

The issue body specifies the file list, package layout, and stack verbatim. This design doc locks the **version pins** and resolves the ambiguities the issue left open:
- AGP / JDK pairing (issue said AGP 8.x + JDK 17; verified AGP 9 is now the latest stable line and requires JDK 21 — user approved the bump).
- KSP / Kotlin pairing (KSP has no release past 2.3.10; pins Kotlin at 2.3.10 to honour KSP-over-KAPT).
- Mapbox token plumbing (Mapbox's private Maven registry requires a `secret sk.` token with `downloads:read` scope; user supplied one to be stored in `local.properties` only).
- Mapbox Compose artifact verification (no official Compose binding exists in v11; we'll use `AndroidView` interop in A-6/A-7).
- `compileSdk 34 → 35` bump (forced by AGP 9).

## 2. Resolved Stack (latest stable, queried 2026-07-20)

| Component | Coordinate | Version | Source |
|-----------|-----------|---------|--------|
| Kotlin | `org.jetbrains.kotlin:kotlin-stdlib` / `kotlin-gradle-plugin` | `2.3.10` | Maven Central metadata |
| Kotlin Compose Plugin | `org.jetbrains.kotlin.plugin.compose` | `2.3.10` | synced with Kotlin |
| KSP | `com.google.devtools.ksp:symbol-processing-api` | `2.3.10` | Maven Central metadata — required to pair with Kotlin 2.3.x (no KSP release for 2.4.x yet) |
| Android Gradle Plugin | `com.android.tools.build:gradle` | `9.3.0` | Google Maven metadata (latest stable) |
| Gradle wrapper | — distribution | `9.6.1` | services.gradle.org/versions/current |
| Compose BOM | `androidx.compose:compose-bom` | `2026.06.01` | Google Maven metadata |
| Hilt | `com.google.dagger:hilt-android` | `2.60.1` | Maven Central metadata |
| Hilt Navigation Compose | `androidx.hilt:hilt-navigation-compose` | `1.4.0` | Google Maven metadata |
| Hilt Gradle Plugin | `com.google.dagger:hilt-android-gradle-plugin` | `2.60.1` | Maven Central metadata |
| Room | `androidx.room:room-runtime` (+ `-ktx`, `room-compiler` via KSP) | `2.8.4` | Google Maven metadata |
| OkHttp | `com.squareup.okhttp3:okhttp` (+ `logging-interceptor`) | `5.4.0` | Maven Central metadata |
| Retrofit | `com.squareup.retrofit2:retrofit` (+ `converter-moshi`) | `3.0.0` | Maven Central metadata |
| Moshi | `com.squareup.moshi:moshi` (+ `moshi-kotlin`, `codegen` via KSP) | `1.15.2` | Maven Central metadata |
| Coil | `io.coil-kt:coil-compose` | `2.7.0` | Maven Central metadata |
| CameraX | `androidx.camera:camera-*` (core, camera2, lifecycle, view) | `1.6.1` | Google Maven (1.7 is alpha) |
| WorkManager | `androidx.work:work-runtime-ktx` | `2.11.2` | Google Maven (2.12 is alpha) |
| Navigation Compose | `androidx.navigation:navigation-compose` | `2.9.8` | Google Maven (2.10 is alpha) |
| Play Services Location | `com.google.android.gms:play-services-location` | `21.4.0` | Google Maven metadata |
| Mapbox Maps Android | `com.mapbox.maps:android` | `11.26.0` | Mapbox downloads registry — **verified present** at `com/mapbox/maps/android/11.26.0/android-11.26.0.pom` (200) |
| Mapbox Compose extension | — | — | **No official Compose artifact in v11.** Compose support later via `AndroidView` interop (A-6/A-7). |
| detekt Gradle Plugin | `io.gitlab.arturbosch.detekt:detekt-gradle-plugin` | `1.23.8` | Maven Central metadata |
| ktlint Gradle Plugin | `org.jlleitschuh.gradle:ktlint-gradle` | `14.2.0` | Gradle Plugin Portal metadata |

## 3. Decisions (locked, with rationale)

1. **KSP over KAPT** for Hilt + Room codegen (per the issue body). Gates Kotlin to 2.3.10 because KSP has not published a release paired with Kotlin 2.4.x as of 2026-07-20 — KSP 2.3.10 + Kotlin 2.3.10 is the newest validated pair. Faster builds than KAPT; modern path. When KSP for newer Kotlin lands, bump Kotlin in a separate issue.
2. **AGP 9.3.0 (not 8.x) with JDK 21 (not 17).** Issue body said "AGP 8.x + JDK 17 build JDK" but the actual latest stable AGP is 9.x and AGP 9 hard-requires JDK 21 to run Gradle. User approved bumping both. CI will use `actions/setup-java@v4` with `java-version: '21'` and `distribution: 'temurin'`.
3. **`compileSdk`/`targetSdk` 35 (issue said 34).** AGP 9 raises the minimum `compileSdk` to 35 (Android 15). Importing the latest platform API surface is required at build time. minSdk stays at 30 per issue (DFN tablets are Android 13/SDK 33).
4. **Version catalog as single source of truth.** `gradle/libs.versions.toml` holds every version. `app/build.gradle.kts` references catalogs only — no hardcoded versions. Verified against acceptance criteria.
5. **`local.properties` holds secrets and is git-ignored.** It carries: `MAPBOX_DOWNLOADS_TOKEN` (the `sk.` secret token), `PRODUCTION_SERVER_URL`, `DEV_SERVER_URL`. Gradle reads it via a small helper (`gradleLocalProperties`-style manual load, no external plugin). For Mapbox Maven authentication, the `settings.gradle.kts` `dependencyResolutionManagement` block reads the token from a gradle property (`MAPBOX_DOWNLOADS_TOKEN`) which `~/.gradle/gradle.properties` *or* the CI secret provides at build time; local devs put it in `local.properties` and we forward it to a gradle property in `app/build.gradle.kts`.
6. **CI secret for Mapbox.** The repo will need a GitHub Actions secret named `MAPBOX_DOWNLOADS_TOKEN` for CI to fetch Mapbox deps. CI step sets it into `~/.gradle/gradle.properties` (or env) before `./gradlew`. The token's lifecycle/rotation is owned by the DFN maintainer (Lewis); it never lives in the repo.
7. **No Mapbox Compose artifact.** The brainstorm design draft listed `mapbox-compose = "11.x"`, but the v11 registry exposes no Compose artifact under `com/mapbox/extension/compose`, `com/mapbox/maps/compose-*`, etc. We'll pull just `com.mapbox.maps:android:11.26.0`; Compose integration is solved in A-6/A-7 via `AndroidView` wrapping `MapView`. Document this so A-6 doesn't go searching for a non-existent artifact.
8. **Single-activity Compose-only host.** `MainActivity : ComponentActivity()` with `@AndroidEntryPoint`; `setContent { Stage4RootScreen() }`. No fragments, no `AppCompatActivity`. `appcompat` dep is pulled only because some libraries (e.g. Mapbox's internal `appcompat` reference) need it on the classpath; `esModule` is fine.
9. **Debug-only build at scaffold stage.** No signing config, no release flavors, no `proguard-rules.pro` writes (a release-prep issue near the end of the epic handles that). `network_security_config.xml` allows cleartext for debug only.
10. **CI single lane.** `.github/workflows/ci.yml` runs `./gradlew ktlintCheck detekt test assembleDebug` on every PR. Upload the test report on failure. Target total runtime < 5 min. Caches Gradle + Konan.
11. **detekt baseline.** Generate `config/detekt/baseline.xml` and a minimal `config/detekt/detekt.yml` that disables rules called out as noisy by the issue (e.g. `MagicNumber`, `LongMethod`, `TooLongClass`) for the scaffold. Hardcode Kt fine-tuning at this stage; revisit when the first feature work adds real code under inspection.
12. **`.editorconfig`.** ktlint + detekt unified config: 4-space indent, max line 120, no wildcard imports, trailing newline, `ktlint_standard_filename` enabled. (Issue said 100 max line; we use 120 as the more common Kotlin community default — note the small deviation. If the team prefers 100 we will align. Actually: align to issue `100`.)

## 4. Files (per Issue body)

```
settings.gradle.kts                              # pluginManagement + dependencyResolutionManagement (Mapbox secret registry wired here) + include(":app")
build.gradle.kts                                 # plugin versions aliases (applies via `alias(libs.plugins.X) version(libs.plugins.X).version`)
gradle/libs.versions.toml                         # version catalog — single source of truth
gradle.properties                                # JVM args, kotlin.code.style=official, android.useAndroidX, non-transitive R classes
gradle/wrapper/gradle-wrapper.properties         # distributionUrl = services.gradle.org 9.6.1-all
gradlew, gradlew.bat                              # generated by `gradle wrapper`
local.properties                                 # NOT COMMITTED — MAPBOX_DOWNLOADS_TOKEN, PRODUCTION_SERVER_URL, DEV_SERVER_URL
.gitignore                                       # android + .gradle/, build/, local.properties, .idea/, *.iml, captures/, .cxx/, .kotlin/
.editorconfig                                    # ktlint + detekt shared
app/build.gradle.kts                             # plugins: android.application, kotlin.android, kotlin.plugin.compose, ksp, hilt, detekt, ktlint
config/detekt/detekt.yml                         # noisy-rule opt-outs
config/detekt/baseline.xml                       # empty baseline
app/src/main/AndroidManifest.xml                 # permissions + application + activity + LAUNCHER intent
app/src/main/java/au/edu/fireballs/stage4/Stage4App.kt         # @HiltAndroidApp class Stage4App : Application()
app/src/main/java/au/edu/fireballs/stage4/MainActivity.kt      # @AndroidEntryPoint ComponentActivity
app/src/main/java/au/edu/fireballs/stage4/ui/Stage4RootScreen.kt   # placeholder "Hello DFN Stage 4"
app/src/main/res/values/strings.xml              # app_name = "DFN Stage 4"
app/src/main/res/values/colors.xml
app/src/main/res/values/themes.xml               # Theme.Stage4App (Material3)
app/src/main/res/xml/network_security_config.xml # cleartext for dev
app/src/main/res/drawable/                       # placeholder app icon (vector)
app/src/main/res/drawable-anydpi-v26/            # adaptive launcher icon
app/src/main/res/mipmap-anydpi-v26/ic_launcher*.xml
app/src/main/res/values/ic_launcher_background.xml
app/src/main/res/raw/                             # empty (Mapbox token runtime holder)
app/src/main/assets/                              # empty
app/src/test/java/au/edu/fireballs/stage4/Stage4AppTest.kt    # smoke JUnit test
.github/workflows/ci.yml                          # JDK 21, Gradle 9, ktlintCheck + detekt + test + assembleDebug
README.md                                         # build + install + dev instructions
```

## 5. Acceptance criteria [unchanged from issue]

- `./gradlew assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk` installable on Android 11+.
- `./gradlew ktlintCheck` passes.
- `./gradlew detekt` passes.
- `./gradlew test` runs and passes the smoke test.
- Launching the app lands on a screen with text "Hello DFN Stage 4".
- CI workflow runs green on a feature-branch PR.
- `libs.versions.toml` is the single source of truth for every dependency version.
- `local.properties` is git-ignored and not committed.
- `@HiltAndroidApp` + `@AndroidEntryPoint` generate cleanly with KSP.

## 6. Out of scope (deferred to later A-issues)

- Real Mapbox UI (A-6/A-7).
- CookieJar + AuthInterceptor networking (A-3).
- Any feature screen beyond the placeholder.
- Build flavors, signing config, release minification.
- Actual Room persistence beyond including the dependency.

## 7. Verification plan

PR review will execute:
1. `./gradlew ktlintCheck detekt test assembleDebug` — all must pass.
2. Manual: `./gradlew installDebug` on a real Android 11+ device and confirm "Hello DFN Stage 4" text renders.
3. Confirm no `local.properties` is committed (`git ls-files | grep local.properties` returns empty).
4. Confirm `libs.versions.toml` is the single source: `rg "[0-9]+\.[0-9]+\.[0-9]+" app/build.gradle.kts` returns nothing (no hardcoded versions).

## 8. Deviation register (from issue body)

| Field | Issue spec | This design | Reason |
|-------|-----------|-------------|--------|
| AGP | 8.x | 9.3.0 | latest stable AGP; user approved bump |
| Build JDK | 17 | 21 | AGP 9 hard-requires JDK 21 |
| Kotlin | 2.x | 2.3.10 | KSP gates to 2.3.x (latest paired) |
| compileSdk/targetSdk | 34 | 35 | AGP 9 minimum compileSdk is 35 |
| max line length | 100 | 120 | ktlint/detekt common Kotlin community default — note: align to issue = **100** in `.editorconfig` (no deviation) |
| Mapbox Compose artifact | listed in draft | (none, defer interop) | no v11 Compose artifact in registry |
| Compose compiler config | `composeOptions { kotlinCompilerExtensionVersion }` | `org.jetbrains.kotlin.plugin.compose` Gradle plugin | Kotlin 2.x integrated Compose compiler |

All deviations are mechanical version upgrades or registry realities; the spirit and file list of the issue are preserved.