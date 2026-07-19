# Stage 4 Native Android App (MVP + Sync)

> Native Android Kotlin/Compose app replicating the webapp Stage 4 candidate-review feature for offline field use.

**Status**: Active
**Created**: 2026-07-16
**Owner**: Lewis (architect)
**Epic issue**: desertfireballnetwork/dfn-meteorite-drone-android-app#1 ([link](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/1))
**Sibling epic**: desertfireballnetwork/dfn-meteorite-drone-webapp#117 — *Stage 4 JSON API + Claim + EvidencePhoto (Android support)*
**Authoritative design doc**: `dfn-meteorite-drone-android-app/docs/plans/designs/001-stage4-android-app-initial-design.md`

## Goal

Deliver a native Android Stage 4 candidate-review app for offline field use, completing the ADACS 2026-B allocation *Drone_meteorite_2026B* (MVP+Sync = 6 person-weeks). Decisions are recorded per-candidate while offline, with claims coordination + pre-download of offline data at basecamp, and sync to the existing webapp (`POST survey/<id>/stage4/response/`) on reconnect.

## Context

Two live field campaigns (Dec 2025, April 2026) failed because browsers cannot reliably persist large offline tile sets (webapp issues #89, #114). The native app is the agreed delivery mechanism.

The webapp is augmented in parallel via sibling epic `#117` (new JSON endpoints + `CandidateClaim` + `CandidateEvidencePhoto` models + browser parity UX for photos + claims). The Android app reuses the existing form-encoded `stage4_response` + `set_car_location` POSTs unchanged; consumes new JSON endpoints only for candidates-list + claims + evidence-photo upload.

## Constraints

- Stack fixed by brainstorm: Kotlin 2.x, Compose + Hilt + MVVM, OkHttp/Retrofit + Moshi, Room, Mapbox Maps SDK v11+, WorkManager, Coil, CameraX.
- Single signed-in user per tablet; switching user wipes Room + cookies + offline tile cache.
- Reuse Django session auth via OkHttp CookieJar + AuthInterceptor (`X-CSRFToken` header + `Origin: <prod-domain>`).
- No on-device network fallback for the custom per-candidate drone raster tiles in the field — the satellite base + per-candidate tiles are entirely pre-downloaded at basecamp.
- Default offline download buffer = 100 m around each claimed candidate (configurable in-app via Settings).
- Mapbox OfflineManager parallel-region splitting if per-region tile cap exceeded — never narrow zoom range to fit.
- Sync push reuses the existing form-encoded `POST survey/<id>/stage4/response/` endpoint (no new batched endpoint added to the webapp).

## Tasks

Each row is one issue. **Priority** uses the P0/P1/P2/P3 ladder (P0 = critical path blocker-free foundations + strict-order critical; P1 = high; P2 = polish). **Status** is the live GitHub state (TODO → WIP → DONE / BLOCKED). **Dependencies** lists the GitHub-issue numbers that must merge first (inter-repo links via cross-repo hyperlinks); same-repo dependencies are stored as native GitHub `blocked-by` relations on every issue in this table.

| # | Issue | Phase | P | Status | Depends on (must merge first) | Blocked-by on GitHub |
|---|------|-------|---|--------|-------------------------------|----------------------|
| A-1 | [`#2`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/2) — Project scaffold (Gradle + Compose + Hilt + Room + OkHttp + Mapbox) | scaffold | P0 | TODO | — (no blockers; bootstrap) | none |
| A-2 | [`#3`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/3) — Theme + design system | scaffold | P0 | TODO | A-1 | #2 |
| A-3 | [`#4`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/4) — Networking layer (CookieJar + AuthInterceptor + Retrofit + Moshi) | scaffold | P0 | TODO | A-1 | #2 |
| A-8 | [`#9`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/9) — Room schema + DAOs | scaffold | P0 | TODO | A-1 | #2 |
| A-4 | [`#5`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/5) — Login screen | scaffold | P0 | TODO | A-2, A-3 | #3, #4 |
| A-5 | [`#6`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/6) — Survey picker screen | scaffold | P1 | TODO | A-3, A-4 + sibling webapp [W-3 #120](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/120) (deployed) | #4, #5 |
| A-6 | [`#7`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/7) — Mapbox map host Composable | map | P1 | TODO | A-3, A-5 + sibling webapp [W-2 #119](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/119) (deployed) | #4, #6 |
| A-7 | [`#8`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/8) — Custom RasterSource + OfflineManager integration (auto-split) | map | P1 | TODO | A-6 | #7 |
| A-9 | [`#10`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/10) — Candidate markers on map | map | P1 | TODO | A-2, A-6, A-8 | #3, #7, #9 |
| A-10 | [`#11`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/11) — Candidate modal (image/map toggle + Coil pinch-zoom) | map | P1 | TODO | A-9 | #10 |
| A-11 | [`#12`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/12) — Yes/No buttons + detection tag picker + LocalDecision persistence | basecamp | P1 | TODO | A-10, A-8 | #11, #9 |
| A-12 | [`#13`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/13) — Optional camera capture (CameraX) + PendingPhotoUploadEntity | basecamp | P2 | TODO | A-10, A-11 + sibling webapp [W-7 #123](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/123) (deployed) + sibling webapp [W-8 #125](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/125) (deployed) | #11, #12 |
| A-13 | [`#14`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/14) — Basecamp: claim screen (tap + polygon draw) | basecamp | P0 | TODO | A-6, A-8 + sibling webapp [W-5 #122](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/122) (deployed) + sibling webapp [W-6 #124](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/124) (deployed) | #7, #9 |
| A-14 | [`#15`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/15) — Basecamp: "Download for offline" + Settings (buffer radius) | basecamp | P0 | TODO | A-13, A-7 | #14, #8 |
| A-15 | [`#16`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/16) — "Set car to my location" button | basecamp | P2 | TODO | A-3, A-8 | #4, #9 |
| A-16 | [`#17`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/17) — Sync layer (SyncWorker: photo + decision + session recovery) | sync | P0 | TODO | A-11, A-12, A-8 + sibling webapp [W-7 #123](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/123) + sibling webapp [W-8 #125](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/125) + sibling webapp [W-10 #127](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/127) (all deployed) | #12, #13, #9 |
| A-17 | [`#18`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/18) — Sync UI (status, progress, retry, manual trigger) | sync | P0 | TODO | A-16, A-4 | #17, #5 |
| A-18 | [`#19`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/19) — Field UX polish (connection banner, sync indicator, accuracy circle, last-seen markers) | sync | P2 | TODO | A-6, A-15, A-17 | #7, #16, #18 |
| A-19 | [`#20`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/20) — Test coverage (DAOs, repos, Compose UI, MockWebServer, SyncFlowE2eTest) | scaffold | P1 | TODO | Convolves as each A-issue lands; mandatory before epic close | (incremental; no hard blockers set) |

### Critical path

```
A-1 → A-3 → A-4 → A-5 → A-6 → A-9 → A-10 → A-11 → A-16 → A-17   (length 10)
                ⟶ + A-13 → A-14 (basecamp side-branch, parallel to A-9..A-11 once A-6 lands)
                ⟶ + A-12 (camera capture, parallel to A-13+A-14 once A-11 lands; gates A-16)
```

### Cross-repo readiness gate

The Android critical path is blocked FOUR times by the sibling webapp epic's deployment. The webapp epic ([#117](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/117)) MUST ship:

1. `W-3` ([#120](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/120)) before A-5 can be end-to-end tested.
2. `W-2` ([#119](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/119)) before A-6 fetches candidates.
3. `W-5` + `W-6` ([#122](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/122) + [#124](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/124)) before A-13.
4. `W-7` + `W-8` + `W-10` ([#123](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/123) + [#125](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/125) + [#127](https://github.com/desertfireballnetwork/dfn-meteorite-drone-webapp/issues/127)) before A-16's SyncWorker reaches the end-to-end smoke.

Android-side code can be written + unit-tested against MockWebServer ahead of each webapp W-issue's deployment — these are deployment-gates not code-gates. Recommended ordering during shared-sprint weeks: write Android first while webapp W-issues are in PR review, switch to integration testing once webapp merges.

## Done When

See epic issue [`#1`](https://github.com/desertfireballnetwork/dfn-meteorite-drone-android-app/issues/1) Acceptance criteria.

Highlights:
- [ ] All 19 A-issues merged; app tagged v1.0.0.
- [ ] Field-end-to-end smoke test: download @ basecamp → offline field 5 verdicts + 2 photos → reconnect → sync completes within 30 s → browser Stage 4 page shows new verdicts.
- [ ] `./gradlew ktlintCheck detekt connectedCheck` green on final PR.
- [ ] Settings exposes configurable buffer radius (default 100 m).
- [ ] Sync handles session-expired recovery: halt + prompt re-login; zero decisions lost.

## Decision Log

| Date | Decision | Rationale |
|------|----------|-----------|
| 2026-07-16 | Kotlin + Compose + Hilt MVVM stack | Modern standard; ADACS architect time is finite and Hilt+Compose is the cleanest 2026 default. |
| 2026-07-16 | Reuse Django session auth via OkHttp CookieJar | Avoids introducing new webapp-side token infra; preserves the webapp's "no DRF / no token auth for users" boundary. |
| 2026-07-16 | Single-row form-encoded POSTs to existing `stage4_response` | Server upserts keyed on (inference_result, user, stage=300) already idempotent; no need for batched sync endpoint (drop W-...-10 of original design). |
| 2026-07-16 | Candidate-buffered (default 100 m, configurable) satellite + custom tile download | Whole-survey download wastes tens-of-GB; the user's claimed candidate set is the actual offline footprint. |
| 2026-07-16 | Auto-split Mapbox OfflineManager regions on per-region tile cap | Never narrow zoom (team prioritised visual fidelity over storage). |
| 2026-07-16 | Strict epic merge ordering (`webapp #122 → #124 → #128 → #127`) between sibling epics | Without browser claim UX in production, server-side enforcement on `stage4_response` would reject every browser verdict. |

## Progress Notes

- [2026-07-16] Epic + 19 individual issues created in the Android repo with the brainstorm design doc (`docs/plans/designs/001-stage4-android-app-initial-design.md`). Each issue body is a self-contained planning artifact (no design-doc cross-refs).
- [2026-07-16] The 4 parallel Android issue creates worked correctly; one batch of 4 parallel webapp creates raced the GitHub server and caused W-6 (#124) ↔ W-7 (#123) numbering swap — body cross-references patched across 5 live webapp issues + Android issue stubs before Android issue creation. No drift remaining.
- [2026-07-16] Plan tracker created in `docs/plans/work/001-android-stage4-mvp.md`; epic issue `#1` set as the GitHub-side anchor; cross-references between sibling repo issues achieved via markdown hyperlinks (desertfireballnetwork/dfn-meteorite-drone-webapp#NN).