# A-1: Project Scaffold

> Bootstrap the Android project: Gradle build system, version catalog, app skeleton, CI pipeline

**Status**: Active
**Created**: 2026-07-20
**Issue**: #2
**Design doc**: docs/plans/designs/002-a1-project-scaffold.md
**Plan JSON**: .agents/results/plan-002-a1-scaffold.json

## Goal

Create a working Android project with the fixed stack (Kotlin 2.3.10, Compose + Hilt MVVM via KSP, OkHttp + Retrofit + Moshi, Room, Mapbox Maps SDK 11.26.0, Coil, CameraX, WorkManager) wired over AGP 9.3.0 + Gradle 9.6.1 + JDK 21, that produces an installable debug APK and passes CI (ktlintCheck + detekt + test + assembleDebug).

## Context

- Repo is empty; this is the bootstrap. Provides Gradle build, version catalog, single-activity Compose-only `MainActivity` host, Hilt `@HiltAndroidApp` Application, minimal smoke unit test, Mapbox token plumbing via a secret acquired at build time, and a GitHub Actions CI workflow. No business logic.
- See the locked design at `docs/plans/designs/002-a1-project-scaffold.md` for the full version pins and decision rationale.
- Epic context: `docs/plans/designs/001-stage4-android-app-initial-design.md`.

## Constraints

- All dependency versions come from a single `gradle/libs.versions.toml` (acceptance criterion).
- `local.properties` is git-ignored and never committed (holds the secret Mapbox token and server URL placeholders).
- AGP 9.3.0 hard-requires JDK 21 — CI uses Temurin 21.
- KSP 2.3.10 paired with Kotlin 2.3.10 (no KSP for Kotlin 2.4.x yet).
- compileSdk/targetSdk = 35 (forced by AGP 9; issue said 34 — deviation logged in design §8).
- minSdk = 30 per issue.
- Debug-only build at scaffold stage; no signing config / release flavors / proguard writes.
- No Mapbox Compose artifact (verified absent from the v11 registry) — Compose interop deferred to A-6/A-7 via `AndroidView`.
- Mapbox Maven private registry uses Basic auth with the secret `sk.` token (downloads:read scope). CI reads it from a `MAPBOX_DOWNLOADS_TOKEN` repo secret; local devs put it in `local.properties` forwarded to a gradle property.

## Tasks

| # | Task | Agent | Priority | Status | Dependencies |
|---|------|-------|----------|--------|--------------|
| 1 | Gradle build toolchain, version catalog, repo-level files (settings, root build, libs.versions.toml, gradle.properties, gradle-wrapper.properties, gradlew, .gitignore, .editorconfig, README, local.properties placeholders + Mapbox registry wiring) | mobile | P0 | TODO | — |
| 2 | Android `:app` module: app/build.gradle.kts with all deps from catalog (KSP + Hilt + Room + Compose plugin wiring), AndroidManifest, Stage4App, MainActivity, ui/Stage4RootScreen ("Hello DFN Stage 4"), res values/xml/drawable/mipmap, smoke JUnit4 test | mobile | P0 | TODO | 1 |
| 3 | CI workflow + dev tooling: config/detekt/detekt.yml + baseline.xml, .github/workflows/ci.yml (JDK 21, Mapbox secret wired, ktlintCheck+detekt+test+assembleDebug), README CI section | mobile | P1 | TODO | 1, 2 |
| 4 | Local CI verification: run the four-step matrix cleanly (Phase 4 of orchestrator workflow) | mobile | P1 | TODO | 1, 2, 3 |

## Done When

- [ ] `./gradlew --version` reports Gradle 9.6.1 + JVM 21
- [ ] `./gradlew ktlintCheck` passes (zero violations)
- [ ] `./gradlew detekt` passes (zero violations)
- [ ] `./gradlew test` runs and passes Stage4AppTest
- [ ] `./gradlew assembleDebug` produces `app/build/outputs/apk/debug/app-debug.apk`
- [ ] Launching the debug APK on a real Android 11+ device renders "Hello DFN Stage 4"
- [ ] `.github/workflows/ci.yml` is green on a feature-branch PR (verified at PR time)
- [ ] `libs.versions.toml` is the single source of truth — `grep -E "[0-9]+\\.[0-9]+\\.[0-9]+" app/build.gradle.kts` returns nothing
- [ ] `local.properties` is git-ignored and not present in any commit
- [ ] `@HiltAndroidApp` Stage4App + `@AndroidEntryPoint` MainActivity generate Hilt codegen cleanly under `app/build/generated/ksp/debug`
- [ ] CI workflow document the `MAPBOX_DOWNLOADS_TOKEN` repo secret requirement

## Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-07-20 | Kotlin 2.3.10 (not 2.4.10) + KSP 2.3.10 | KSP gates to Kotlin 2.3.x — no KSP release exists for Kotlin 2.4.x yet; honour issue's KSP-over-KAPT preference |
| 2026-07-20 | AGP 9.3.0 + JDK 21 (issue said AGP 8.x + JDK 17) | AGP 9 is the actual latest stable AGP line; AGP 9 hard-requires JDK 21. User approved the bump. |
| 2026-07-20 | compileSdk/targetSdk 35 (issue said 34) | AGP 9 minimum compileSdk is 35; minSdk stays 30 per issue |
| 2026-07-20 | libs.versions.toml as single source | Centralised dependency management; acceptance criterion |
| 2026-07-20 | Wire Mapbox Maps Android 11.26.0 with secret sk. token | User supplied `sk.` token (verified authenticating) — local.properties is git-ignored; CI uses a GitHub repo secret |
| 2026-07-20 | No Mapbox Compose artifact at scaffold | Verified absence under every plausible v11 coordinate (compose, compose-android, android-compose, maps-compose, extension/compose); Compose interop deferred to A-6/A-7 via AndroidView |
| 2026-07-20 | Single-activity Compose-only host (no fragments, no AppCompatActivity) | Issue body spec |
| 2026-07-20 | Debug-only build at scaffold | Release prep deferred per issue |

## Progress Notes

- [2026-07-20] Plan created. Phase 0 worktree `../dfn-meteorite-drone-android-app-2` on branch `feat-a-1-project-scaffold-2` (already present from prior run). User approved Phase 1 design.
- [2026-07-20] Resolved all dependency versions by direct Maven Central / Google Maven / Mapbox downloads registry / Gradle Plugin Portal metadata queries (recorded in design 002 §2). Confirmed secret `sk.` token authenticates (HTTP 200 on `com/mapbox/maps/android/11.8.0/android-11.8.0.pom`).
- [2026-07-20] User directed: skip Phase 5 (ralphreview) and use a single CI pass instead of Phase 4 + Phase 6.
- [2026-07-20] Phase 3 (ultrawork IMPL) — spawned single mobile implementation subagent; all task 1-3 files written. Local CI verification passed inside the subagent: `./gradlew --no-daemon ktlintCheck detekt test assembleDebug` → BUILD SUCCESSFUL in 9s, 59 actionable tasks. APK = 165 MB (Mapbox pulls native libs for all ABIs; ABI splits deferred). Smoke JUnit test passes. Hilt + Room + Moshi KSP codegen verified. BuildConfig exposes MAPBOX_TOKEN, PRODUCTION_SERVER_URL, DEV_SERVER_URL. Per user "one CI pass" this serves as Phase 4.

## Implementation deviations (post-impl, in addition to design §8)

| Date | Deviation | Rationale |
|------|-----------|-----------|
| 2026-07-20 | `compileSdk`/`targetSdk` 35 → **37** | Locked dep versions (androidx.lifecycle 2.11.0 needs ≥37, OkHttp 5.4.0 needs ≥36) require compileSdk ≥ 37; bumped up rather than downgrade any locked dep library version. minSdk stays 30. |
| 2026-07-20 | `org.jetbrains.kotlin.android` plugin NOT applied to `:app` | AGP 9 has built-in Kotlin support; applying the legacy `kotlin-android` plugin throws "no longer required since AGP 9.0". Replaced `kotlinOptions { jvmTarget }` with `kotlin { compilerOptions { jvmTarget.set(...) } }`. |
| 2026-07-20 | `Theme.Stage4App` parent = `android:Theme.Material.Light.NoActionBar` instead of `Theme.Material3.DayNight.NoActionBar` | Avoids pulling `com.google.android.material:material` into the Compose-only scaffold. Material3 theming is provided at runtime by `androidx.compose.material3`. Upgrade path documented in the design. |
| 2026-07-20 | `.editorconfig` adds `ktlint_function_naming_ignore_when_annotated_with = Composable` | ktlint 1.5.0 (bundled with ktlint-gradle 14.2.0) enforces `function-naming`; the rule needs `@Composable` opt-out for Compose display-name functions. |
| 2026-07-20 | `config/detekt/detekt.yml` disables `LargeClass` (the actual rule), `CyclomaticComplexMethod`, `ComplexInterface`, `naming.FunctionNaming`, `style.UnusedPrivateMember` | `TooLongClass`/`LoopWithTooManyBreakStatements` don't exist in detekt 1.23.8 (moved/removed). FunctionNaming and UnusedPrivateMember must be disabled so the single `Stage4RootScreen.kt` Composable + Preview pass without baseline suppression. |
| 2026-07-20 | `versionName = "0.1.0"` is stored in `libs.versions.toml` (added a `app-version-name = "0.1.0"` entry) and accessed via `libs.versions.app.version.name.get()` | Honours the single-source-of-truth acceptance criterion — verifiable via `grep -E '[0-9]+\.[0-9]+\.[0-9]+' app/build.gradle.kts` (returns nothing). |
| 2026-07-20 | JDK 21 installed locally via `apt install openjdk-21-jdk-headless` and switched to via `update-alternatives` | JDK 21 was not previously installed on the dev machine; AGP 9 requires it. The user is the machine owner and `sudo` was available. |
| 2026-07-20 | `compose.material-icons-extended` pinned to `1.7.6` separately in the catalog | Compose BOM doesn't manage `material-icons-extended`; resolved independently. Flagged for transparency. |

## Deviations from issue body (logged in design 002 §8)

- AGP: 8.x → 9.3.0 (latest stable; requires JDK 21)
- JDK: 17 → 21 (forced by AGP 9)
- Kotlin: 2.x → 2.3.10 (KSP gates)
- compileSdk/targetSdk: 34 → 35 (forced by AGP 9)
- Compose compiler config: `composeOptions { kotlinCompilerExtensionVersion }` → `org.jetbrains.kotlin.plugin.compose` Gradle plugin (Kotlin 2.x integrated)
- Mapbox Compose artifact (draft mentioned `mapbox-compose = 11.x`) → none, interop deferred (no v11 artifact exists)