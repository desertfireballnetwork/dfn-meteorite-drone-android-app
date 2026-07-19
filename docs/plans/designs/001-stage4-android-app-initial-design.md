# 001 — Stage 4 Native Android App — Initial Design

- **Status:** DRAFT (awaiting section-by-section approval)
- **Authors:** Lewis (architect), brainstorm session 2026-07-16
- **Repo:** `dfn-meteorite-drone-android-app` (Android epic + issues live here)
- **Sibling repo:** `dfn-meteorite-drone-webapp/webapp` (parallel epic for the webapp-side API surface the app requires)
- **ADACS proposal:** 2026-B *Drone_meteorite_2026B* — sub-allocation "Native Mobile Application Development (MVP)" (4 wks) + "Offline Dataflow and Synchronization" (2 wks) = 6 weeks of Android effort
- **Supersedes:** `webapp/docs/PRODUCT-SENSE.md:70` ("No native mobile app … a project unto itself") — see §12 Rationale.
- **Sibling webapp design doc:** `webapp/docs/design-docs/008-stage4-android-app-and-api.md` (drafted in the same brainstorm session) covers the webapp-side architectural decisions for the JSON API + Claim + EvidencePhoto + browser parity UX. Each repo carries its own design doc and runs its own epic.

---

## 1. Context & Motivation

The Stage 4 candidate review step is the final, in-field pass of the meteorite search pipeline. Hundreds of ML candidates that survived remote screening must be visited on foot. The existing browser-based Stage 4 UI (`webapp/src/dfnweb/templates/stage4.html` + `static/js/survey-map-handler.js`) works well only while online — it streams base satellite imagery, the high-resolution georeferenced drone imagery, the per-candidate crop JPG, and accepts Yes/No decisions over Django session cookies.

Field campaigns at remote fall sites (zero internet) have repeatedly failed:

- Issue **#89** *native app for stage 4* — "relying on an internet connection for stage 4 … the webapp cannot control the browser cache."
- Issue **#114** *Stage 4 caching* — operators were forced to manually scroll the page at camp, screenshot it, and markup the screenshots in the field. From @sed79: "Be careful navigating the webapp while leaving the wifi signal… Losing loaded images is very frustrating and bad for motivation."
- Two real field campaigns (April 2026 + Dec 2025) were materially hampered.

For full context, see the ADACS proposal (2026-B *Drone_meteorite_2026B*). Page 11–12 of the proposal is the authoritative problem statement.

We already exhaustively evaluated alternatives:

- High-gain WiFi on the Starlink terminal: patchy beyond ~600 m, insufficient bandwidth for high-res tiles.
- One Starlink-mini per searcher's backpack: impractical for groups larger than two, kit cost, room for error.

The only credible resolution is a native Android app with explicit pre-caching. The DFN team already owns the Android RTK tablets that the app will run on, sideloads an APK directly (no App Store fees/gating), and Android is the cheapest-desktop-ecosystem option for a small science team taking custody of maintenance.

**Why Android and not iOS:** per ADACS architect advice (proposal p. 12), maintaining a single platform is dramatically simpler for the team long-term; forcing field operators to use Android tablets is an acceptable trade-off.

---

## 2. Goals & Non-Goals

### Goals (MVP)

1. Replicate the **exact feature surface** of the existing web Stage 4 view for a single survey.
2. **Offline-capable Stage 4 candidate review** in the field with no internet — including:
   - Base satellite map (Mapbox-hosted), downloaded as an offline region at basecamp.
   - Per-candidate georeferenced drone imagery, downloaded aggressively at basecamp.
   - Per-candidate cropped JPG (used in the single-candidate modal), downloaded at basecamp.
   - Candidate list, coordinates, box coords, size labels.
3. **Multi-tablet coordination** at basecamp (online) before walking off — see §6 — so two tablets don't cover overlapping candidates.
4. **Sync of decisions back to the server** upon return to basecamp — using the existing upsert semantics of `EliminationProcessing` (keyed `(inference_result, user, stage=STAGE_4=300)`).
5. **Optional photo capture per candidate** (issue #29, decided to include as optional — see §8); the tablet's camera is a capability the browser lacked.
6. **Undo / change-decision on the tablet** (in case of a bad tap), even after the decision is recorded locally — supports the existing server upsert behaviour.
7. **Set car/base GPS** (parity with `stage4_set_car_location`) — uses the tablet's GPS.

### Non-Goals (explicitly deferred to v2 or separate work)

- iOS port.
- A stage-4-specific permission or QC injection flow (Stage 4 does not inject `TrueTrainingCroppedImage` records today — the app preserves that).
- Real-time live location tracking and broadcasting between tablets (the webapp shows "last seen per user" derived from candidate decisions, never from a live GPS stream — the app preserves that).
- The unrelated webapp proposal line items (docs integration 1 wk; data lifecycle + email 1 wk; desktop upload GUI stability 1 wk) — those are separate epics in the webapp repo, not part of this Android epic.
- Mapbox-vs-MapLibre swap, camera-only photo upload, AR overlays, dashboards — out of scope.
- ML training/inference, data ingest, image upload, account/role management UI, Stage 1/1.5/2 review — all out of scope. The app touches only Stage 4.

---

## 3. Architecture Overview

The Android app is a **thin native client** of the existing Django webapp. The webapp remains the only owner of truth (S3, MySQL, the pipeline). The app:

- Authenticates by **reusing the Django session** (username/password POST to `/accounts/login/`), persisting `sessionid` + `csrftoken` cookies via an OkHttp `CookieJar` and sending `X-CSRFToken` on every state-changing request.
- Consumes a **new JSON API surface** (no JSON API exists for Stage 4 today — see §5).
- Operates **offline-first**: candidates, claims, decisions, photos, and tiles are downloaded while online and held in a local Room database / on-disk tile store during the walk; the app's networking layer never blocks on the network while offline.
- **Pushes on reconnect**, batched, idempotent (server upserts per `(inference_result, user, stage=300)`).

Two repos, two epics, one coordinated epic pair:

| Repo | Contains |
|---|---|
| `dfn-meteorite-drone-android-app` (this repo) | Epic + Android-side issues (Kotlin/Compose work) |
| `dfn-meteorite-drone-webapp/webapp` (sibling) | Epic + webapp-side issues (new JSON endpoints, Claim model + endpoints, EvidencePhoto model + endpoint, CORS / `CSRF_TRUSTED_ORIGINS` if needed) |

### Component diagram (Android side)

```
┌─────────────────────────────────────────────┐
│  UI Layer (Jetpack Compose)                 │
│   Survey picker → Stage 4 map screen         │
│   → Candidate modal (image/map toggle)        │
│   → Basecamp: claim / polygon / download      │
└────────────────┬────────────────────────────┘
                 │ StateFlow / events
┌────────────────▼────────────────────────────┐
│  ViewModels (Hilt-injected)                  │
│   Stage4ViewModel, CandidateModalViewModel,  │
│   BasecampViewModel, SyncViewModel            │
└────────────────┬────────────────────────────┘
                 │
┌────────────────▼────────────────────────────┐
│  Repositories                                │
│   SurveyRepo, CandidateRepo, ClaimRepo,      │
│   DecisionRepo, PhotoRepo, TileRepo           │
└────────────────┬────────────────────────────┘
       │         │          │
┌──────▼──┐  ┌────▼────┐  ┌─▼────────────┐
│ Room    │  │ OkHttp/ │  │ Mapbox Maps   │
│ (local │  │ Retrofit │  │ SDK (Compose) │
│ truth  │  │ (server) │  │ + OfflineMgr   │
│ offline)│  └────┬────┘  │ + custom       │
└─────────┘       │       │ RasterSource   │
                  │       │ pointing at    │
┌─────────────────▼─────┐ │ local-file tiles│
│ Remote API (CookieJar│ └─────────────────┘
│ + X-CSRFToken)        │
└──────────────────────-┘
```

### One-time-truth principle

While **online at basecamp**, the app is allowed to mutate the server (claim a candidate, set the car location, push a decision synchronously). While **offline in the field**, decisions are recorded in Room with `synced = false`; they cannot reach the server until the tablet reconnects. The server never trusts unsynced local state during the offline window; the source of truth is the union of (a) server-confirmed decisions and (b) each tablet's committed-but-unsynced local decisions (the latter siloed per `(inference_result, user)`).

---

## 4. Tech Stack & Project Layout

### Pinned stack (decided in brainstorm)

| Concern | Library |
|---|---|
| Language | Kotlin 2.x (target JVM 17) |
| Min SDK / Target SDK | **TBD at scaffold** — recommendation: min 30 (Android 11), target 34 (Android 14). DFN's RTK tablets are recent Samsung Galaxy Tab Active3/4 Pro which run Android 13+; need to confirm with Hadrien. |
| UI | Jetpack Compose (BOM-pinned) |
| DI | Hilt |
| Architecture | MVVM — `ViewModel` + `StateFlow` + repository |
| Networking | OkHttp 4 + Retrofit 2 + Moshi (JSON) + custom `CookieJar` + `AuthInterceptor` (X-CSRFToken header sourced from `csrftoken` cookie per Django convention) |
| Local DB | Room (SQLite) — stores Survey, Candidate, Claim, LocalDecision, PendingPhotoUpload metadata |
| Background work | WorkManager 2.9+ — pre-download job, photo upload job, sync push job |
| Map | Mapbox Maps SDK for Android v11+ (`com.mapbox.maps:android`) + Mapbox Compose extensions (`com.mapbox.maps:plugin-animation` etc.) + Offline Manager for the satellite base |
| Custom raster tiles | An extension of `RasterSource` with a local-file `TileSet` (TileJSON-like) pointing at `file://` URLs in the on-device tile store; a custom offline-only fallback when online-fetch fails |
| GPS | `FusedLocationProviderClient` (Google Play Services Location) — `PRIORITY_HIGH_ACCURACY`, matches webapp's `enableHighAccuracy: true`. Heading via `GeolocationProvider` equivalent in Mapbox SDK |
| Image loading | Coil (Compose-native) for the cropped candidate JPG |
| Testing | JUnit5 unit tests (Room DAOs, repos via fakes); Espresso/Compose UI tests for screen flows; no instrumentation tests against the live server |
| Build | Gradle 8 + version catalog (`libs.versions.toml`) |
| Lint/format | ktlint + detekt |

### App module breakdown (proposed)

```
app/
├── src/main/java/au/edu/fireballs/stage4/
│   ├── Stage4App.kt                 (Application, @HiltAndroidApp)
│   ├── MainActivity.kt              (single-activity host)
│   ├── di/                           (Hilt modules: NetworkModule, DatabaseModule, RepositoryModule)
│   ├── ui/
│   │   ├── theme/
│   │   ├── screen/survey/            (Survey picker)
│   │   ├── screen/stage4/           (Main stage 4 map screen)
│   │   ├── screen/candidate/        (Candidate modal — image/map toggle, Yes/No)
│   │   ├── screen/basecamp/         (Claim candidates, draw polygon, "Download for offline")
│   │   └── screen/sync/             (Pending sync queue, status)
│   ├── viewmodel/
│   ├── data/
│   │   ├── remote/                   (Retrofit services, DTOs)
│   │   ├── local/                    (Room DB, DAOs, entities)
│   │   ├── repository/
│   │   └── tiles/                    (TileStore on-disk, TileFetchService)
│   └── sync/                         (WorkManager Workers: PreDownload, Sync)
├── src/main/res/
└── src/test/ + src/androidTest/
```

Single-module initially. Split into `:app` + `:core-data` + `:core-network` if and when warranted — not on the critical path.

---

## 5. Authentication — Reuse Django Sessions

The webapp's only auth surface is Django's `LoginView` at `/accounts/login/` + session cookies + the standard CSRF middleware (`webapp/docs/SECURITY.md:32`). The only other token/JWT mechanism in the codebase is `MlDaemonSecret` for the ML daemon (`webapp/src/dfnweb/utils/drf.py`) — **not reusable for users**.

### Decision

The Android app reuses Django session auth; we add **no new auth infrastructure** to the webapp. Rationale:

- Satisfies webapp core belief #5 ("Public Stage 1 via UUID is the only public surface") — we don't add a new public-facing token surface.
- Satisfies webapp AGENTS.md rule #7 ("Don't introduce new infrastructure … Match the existing style") — no DRF token auth, no `UserAuthToken` table, no OAuth.
- The session-cookie approach is proven and the webapp `stage4_response` endpoint already protects with the standard `@login_required` + `@user_has_survey_access` chain. The Android app rides all the existing decorator gates without modification.

### Flow

1. The Android login screen GETs `/accounts/login/` to seed a CSRF cookie, then POSTs form-encoded `csrfmiddlewaretoken` + `username` + `password` to `/accounts/login/`.
2. Django sets `sessionid` (Secure, HttpOnly) and `csrftoken` cookies on the response.
3. The OkHttp `CookieJar` persists them (encrypted via `EncryptedSharedPreferences` on Android).
4. The `AuthInterceptor` adds two headers to every state-changing request: (a) `X-CSRFToken: <value>` sourced from the `csrftoken` cookie held in the `CookieJar`; (b) `Origin: <production-domain>` (e.g. `https://find.gfo.rocks`) — this satisfies Django 4.2's CSRF middleware `Origin`-vs-`CSRF_TRUSTED_ORIGINS` check that fires on unsafe HTTP methods when the request is HTTPS. SameSite cookie attribute is browser-only and is **not** enforced against OkHttp.
5. Logout: clear OkHttp CookieJar + EncryptedSharedPreferences credentials block + Room DB + offline tile cache + call `GET /accounts/logout/` (or `POST` if so configured) to destroy the server-side session.

### Cookie + CSRF notes (verified against Django 4.2)

- `Secure`: cookies only sent over HTTPS — fine, production uses HTTPS.
- `HttpOnly`: irrelevant for OkHttp (it parses `Set-Cookie` directly; the JavaScript-only flag is not enforced against native HTTP clients).
- `SameSite=Lax` (Django default): not enforced against OkHttp (browser-only flag).
- CSRF middleware validates the `Origin` header (when present) against `CSRF_TRUSTED_ORIGINS` on unsafe methods over HTTPS. If the production domain is already in `CSRF_TRUSTED_ORIGINS` for the webapp's own browser version, no webapp-side config change is required.

### Webapp-side changes required (webapp epic)

- **Verify `CSRF_TRUSTED_ORIGINS`** in `webapp/src/dfn/settings.py` already contains the production domain (e.g. `https://find.gfo.rocks`). Almost certainly true since the browser webapp uses the same domain. No code change expected — flagged for the webapp team to confirm during W-1.
- Other than that: **no** new auth models, no new tables, no new decorator. The existing surfaces suffice.

### Sessions, expiry, recovery

- Django sessions have a default 2-week expiry (`SESSION_COOKIE_AGE=1209600`). A field campaign typically lasts days. Treat as "may expire mid-campaign".
- A 403 / 302-to-login on any sync push halts the sync queue and surfaces a mandatory re-login screen — see §10. Room rows persist across the re-login so **no decisions are ever lost**.
- We deliberately do NOT store the password on the tablet for transparent re-auth. Re-login requires the operator to re-enter credentials.

---

## 6. Multi-Tablet Coordination Model (Claim)

### Decision (decided in brainstorm)

Per-candidate **Claim** is the source of truth on the server. A polygon-based "claim all candidates inside this region" affordance exists at basecamp as a UX helper; selecting a polygon simply bulk-creates N per-candidate Claim rows.

This unifies the data model (one Claim row per (inference_result, user)) while offering the area-based UX operators want (issue #67 in the webapp repo, closed as wontfix because the *webapp* implementation was deemed too complex; the Android app's basecamp-only polygon affordance is the appropriate home).

### Server model (webapp-side change, webapp epic)

New model in `webapp/src/dfnweb/models.py`:

```python
class CandidateClaim(models.Model):
    inference_result = models.ForeignKey(InferenceResult, on_delete=models.CASCADE)
    user             = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
    survey           = models.ForeignKey(Survey, on_delete=models.CASCADE, related_name="claims")
    claimed_at       = models.DateTimeField(auto_now_add=True)
    released_at      = models.DateTimeField(null=True, blank=True)  # set when released or reassigned
    is_active        = models.BooleanField(default=True)

    class Meta:
        unique_together = ("inference_result", "user")  # idempotent claim by same user
        indexes = [models.Index(fields=["survey", "is_active"])]
```

API contract for Claim endpoints (webapp epic implements these — see §10):

- `POST /api/stage4/surveys/<survey_id>/claims/claim/` — body `{inference_result_ids: [int,…]}` → atomically claims all unclaimed candidates by the requesting user. Returns `{claimed: [...], already_claimed_by_others: [...]}`.
- `POST /api/stage4/surveys/<survey_id>/claims/release/` — body `{inference_result_ids: [int,…]}` → releases the requesting user's claims on those candidates.
- `GET /api/stage4/surveys/<survey_id>/claims/` — returns `{inference_result_id, user_id, username, is_me}` list for live UI feedback while at basecamp.

### Polygon affordance (Android side)

The basecamp UI offers a "Claim candidates in this region" tool. Drawing is done with a Mapbox draw extension or a simple rectangular bounding-box selector (TBD). On commit, the Android client computes which candidates fall inside the polygon (it has all candidate geo_centroids cached locally after the survey fetch) and POSTs the inference_result_ids batch to `claim/`. The polygon itself is **not persisted** server-side — the per-candidate Claims are the truth. The polygon may optionally be cached locally for review ("What did I claim on Tuesday's walk?").

### Browser parity (decided in brainstorm)

The webapp browser Stage 4 page ALSO gets the claim affordance in the SAME MVP epic — individual candidate tap + a polygon draw on the existing `stage4.html` map. This re-homes issue **#67** in the webapp repo ("possibility to assign zones to different people for stage 4 follow up", originally closed wontfix as too complex for the webapp implementation) onto the new `CandidateClaim` model — the work is small once the model and the claim endpoints exist.

This resolves the **R11** risk (see §13): the `W-10` server-side claim enforcement on `stage4_response` therefore affects both Android and browser POST paths equally, because the browser now ships its own claim UX before reaching `stage4_response`.

Without browser parity, the live webapp `stage4_response` would start rejecting every Stage 4 verdict as soon as `W-10` is merged but before the browser UX (new `W-11`) is merged — a strict epic ordering is enforced to prevent regression: **`W-6` (Claim model) → `W-11` (browser claim UX) must merge before `W-10` (enforcement) is enabled.** See §14's "Strict merge order" note.

### Client-side enforcement

- **Offline:** Decisions on candidates not claimed by the local user are blocked at the UI layer (or marked as "forbidden — claimed by @other"). The app's Room schema includes the claim set (cached from basecamp's `GET claims/`); the assertion is local-only.
- **Server-side enforcement:** the sync endpoint rejects decisions on candidates the user did not claim (returns 403 for those rows — those rows remain unsynced). This is a defence in depth, not the primary mechanism.

### Release semantics

- A user can release their own claims via the basecamp UI ("I'm done with these") or implicitly when their sync pushes a decision (a decided candidate auto-releases its claim).
- Claims are not leased/expiring by default — the campaign length is days, not hours. Reserved future work: claim TTL if harassment becomes a problem; out of scope for MVP.

---

## 7. Offline Tile & Image Caching

### Two separate caches

#### 7.1 Mapbox satellite base map (Mapbox-hosted)

- Use the Mapbox Maps SDK's `OfflineManager` to download a **single offline region** per survey per download.
- Region bounds: the survey's `surveyed_areas` polygons plus a buffer (recommend the surveyed-area concave hull + ~200 m padding) — same area Stage 4 covers.
- Zoom range: **18 → 22** at most (far higher is wasteful; the zoom range mapbox supports with the standard-satellite style is enough for on-foot navigation).
- Style URL: `mapbox://styles/mapbox/standard-satellite` — matches the webapp exactly (`survey-map-handler.js:51`).
- A custom access token, same `MAPBOX_TOKEN` value the webapp uses (`webapp/src/dfn/settings.py:196`) — supplied to the app at build time via `local.properties` (kept out of git; the science team's token).
- Limits: Mapbox free tier covers ~50,000 tiles/region; a typical survey offline region at zooms 18–22 fits well within that. **Flag for follow-up** to confirm Mapbox plan tier with Hadrien — production may need a paid plan bit the team already pays.

#### 7.2 Custom webapp-hosted drone imagery tiles (Django/S3-backed)

- Source: `/image_geotiff_candidate_tile/<survey_id>/<inference_result_id>/<z>/<x>/<y>/` for per-candidate tiles, plus optionally `/image_geotiff_survey_tile/.../` for the merged overview.
- These tiles are NOT cacheable by Mapbox's `OfflineManager` — that tool only handles Mapbox-hosted sources.
- Zoom range restricted to `settings.TILE_MIN_ZOOM=20 … TILE_MAX_ZOOM=22` (server returns 204 below zoom 20, per `webapp/src/dfnweb/views/survey.py:471`).

### Decision (decided in brainstorm)

**Aggressive pre-download at basecamp**, no on-device network fallback in the field — i.e., the strategy "Mapbox is fed from a local-file RasterSource; no on-device network fallback."

### Tile-download scope: candidate-buffered, not whole-survey (decided in brainstorm)

**Only download map data around the claimed candidates** (the user's selected set), not the entire surveyed area. This applies to BOTH:

- The satellite base map offline region (Mapbox OfflineManager) — its bounds equal the union bounding box of all claimed candidate buffers + car/base.
- The custom per-candidate drone imagery tiles (zooms 20–22) — fetched per claimed candidate only.

**Buffer radius:** default 100 m from each candidate's `geo_centroid`. Configurable in-app setting (persisted in `SharedPreferences`, edit via a Settings screen). Why 100 m: typical candidate centroid-to-actual-walk-position precision; aligns with how operators navigate to a candidate from its marker in the webapp. Increase if visibility is poor; decrease for tight clusters.

**Rationale:** the surveyed area bounding box can be dramatically larger than the union of candidate buffers (operators claim a subset of candidates per campaign, candidates cluster in the high-confidence sub-regions). Excluding the "middle of nowhere" between candidates saves tens-of-GB of unnecessary satellite tiles at zoom 18–22 — exactly the space savings the team prioritised over-unrestricted zoom.

**Multi-cluster handling:** if candidate buffers are split into 2+ disjoint clusters (e.g. two search sites within one survey) we use **multiple OfflineManager regions**, one per cluster. Cluster detection: union-find on candidate geo_centroids with the buffer radius as the distance threshold. This keeps each offline region tight around its cluster (avoids the union-bbox over-download trap when two clusters are tens of km apart). The on-device Mapbox map composes the regions transparently.

### On-device Web Mercator tile math (specified up front)

To compute the `(z, x, y)` tile triples for a given (lat, lon) + radius buffer at zoom `z`, do this on-device in pure Kotlin — no server help needed:

```kotlin
data class TileCoord(val z: Int, val x: Int, val y: Int)

fun tileX(lon: Double, zoom: Int): Int {
    val n = 1 shl zoom
    return floor((lon + 180.0) / 360.0 * n).toInt().coerceIn(0, n - 1)
}

fun tileY(lat: Double, zoom: Int): Int {
    val rad = Math.toRadians(lat)
    val merc = ln(tan(rad) + 1.0 / cos(rad))       // inverse Mercator
    val n = 1 shl zoom
    return floor((1.0 - merc / PI) / 2.0 * n).toInt().coerceIn(0, n - 1)
}

fun bufferBbox(lat: Double, lon: Double, radiusMeters: Double): BoundingBox {
    val dLat = radiusMeters / 111_000.0
    val dLon = radiusMeters / (111_000.0 * cos(Math.toRadians(lat)))
    return BoundingBox(
        minLat = lat - dLat, maxLat = lat + dLat,
        minLon = lon - dLon, maxLon = lon + dLon,
    )
}

fun tilesForBbox(bbox: BoundingBox, zoom: Int): List<TileCoord> {
    val minX = tileX(bbox.minLon, zoom); val maxX = tileX(bbox.maxLon, zoom)
    val minY = tileY(bbox.maxLat, zoom)             // north = smaller tile y
    val maxY = tileY(bbox.minLat, zoom)             // south = larger tile y
    val out = ArrayList<TileCoord>((maxX - minX + 1) * (maxY - minY + 1))
    for (tx in minX..maxX) for (ty in minY..maxY) out += TileCoord(zoom, tx, ty)
    return out
}
```

Notes:
- `1 shl zoom` is `2^zoom` — works up to zoom ~30 (no overflow on `Int`).
- `ln(tan(rad) + sec(rad))` is the standard Web Mercator formula — note `sec(rad) = 1/cos(rad)`.
- Tile coords clamp to `[0, 2^z − 1]`; for candidate buffers at zoom 18–22, 100 m typically resolves to 1–4 tiles per candidate per zoom.
- Mapbox Maps Android SDK also exposes `Projection`/`LatLngBounds` utilities — used to construct the OfflineManager region bounds. The math above is the fallback for the custom raster tile endpoint URLs where no SDK helper generates URL triples directly.

### WorkManager tile-fetch job

- `TileFetchService` (Retrofit interface) downloads each `(z, x, y)` PNG from `/image_geotiff_candidate_tile/<survey>/<candidate>/<z>/<x>/<y>/` to `context.filesDir/tiles/<survey>/<candidate>/<z>/<x>/<y>.png`. Concurrency throttled to ~6 parallel fetches (respects the small Django/Nectar server). HTTP retry: 3 attempts with exponential backoff.
- The Mapbox `RasterSource` at field time uses a **local-file TileJSON** whose `tiles` array points at `file://…/{z}/{x}/{y}.png`. Cache misses (tile not pre-downloaded) return a transparent 1×1 PNG from the device; the satellite base fills in behind.
- Storage hygiene: a clean-up pass deletes a survey's tile directory when an offline bundle for a different survey is evicted; per-candidate tile directories older than the survey's latest `MLInferenceTask.created` are evicted during the next download prep phase (stale imagery from a re-run ML task).

### Pre-download UX

- A basecamp screen lists surveys → tap → "Stage 4" → the Stage 4 map shows. Before walking off, the operator taps **"Download for offline"** which:
  1. Prompts for which candidates to claim (individual tap selection or polygon draw).
  2. Triggers the Mapbox `OfflineManager` region download for the satellite basemap (progress bar).
  3. Triggers the Android `WorkManager` tile-fetch job for per-candidate drone tiles + crops (progress bar).
  4. Persists an `OfflineBundle` row in Room with `survey_id`, `created`, `total_bytes`, `tile_count`.
- The download completes BEFORE the operator walks off; the UI bars exit from the "you are now offline" mode until the bundle is fully downloaded.

### What we cache per candidate

For each claimed candidate in the campaign, download once and persist locally:

1. Per-candidate `geo_centroid` (lat, lon), `geo_area` (bbox), `box_coords` (x, y, w, h in pixels of the source image), AI confidence, detection tag list.
2. Cropped candidate JPG via `/image_survey_cropped/<inference_result_id>/` (`webapp/src/dfnweb/views/elimination.py:718`) — used in the modal image viewer.
3. Per-candidate raster tile PNG pyramid (z = 20, 21, 22).
4. Optional raw survey image — **NO** by default (large) — only on operator request ("Pin this candidate's full image for offline view").
5. Detection tag picker options (cached at app launch).
6. The survey's `surveyed_areas` polygons + car/base location.

### Storage budget & zoom strategy (decided in brainstorm)

- **No compromise on zoom for the sake of storage.** The DFN tablets typically have ≥ 128 GB internal storage; the team has confirmed willingness to use **tens of GB** per campaign. Android apps writing to app-specific storage have no per-app quota — the only ceiling is device free space.
- Default zoom ranges (use the maximum the server-side feeds support — no narrowing):
  - **Satellite base map (Mapbox-hosted):** zooms 18–22.
  - **Per-candidate drone imagery tiles (Django/S3-hosted, server `settings.TILE_MIN_ZOOM=20 … TILE_MAX_ZOOM=22`):** zooms 20–22.
- **Candidate-buffered download scope** (above) further trims the budget: only tiles around claimed candidates + a default 100 m buffer (configurable) are fetched — not the whole surveyed area.
- Per-campaign budget at a typical 500-claimed-candidate scale (with 100 m buffers):
  - Custom drone tiles: 500 candidates × 12 × ~80 KB ≈ ~480 MB.
  - Satellite offline region(s) covering the union bbox of candidate buffers at zoom 18–22 ≈ low hundreds of MB depending on cluster density + buffer radius setting.
  - Crop JPGs: 500 × ~200 KB ≈ ~100 MB.
  - **Total per campaign well under 1 GB; comfortable within tens-of-GB available.**
- **Mapbox OfflineManager per-region tile cap fallback:** if a single offline region (a candidate cluster's union bbox) exceeds the configured plan's per-region tile/size limit, **split that cluster's region along an axis-aligned sub-divide** (quadrant-recursion) and download each sub-region separately. Never narrow zoom to fit. Each sub-region respects the per-region cap; the on-device map composes them transparently.
- **Confirm with Hadrien before MVP weekend:**
  - Exact RTK tablet model & free storage headroom (so the team knows how many campaigns fit concurrently).
  - Current Mapbox plan tier (free vs paid) and per-region tile cap — the offline download job reads the configured limit and splits dynamically.

---

## 8. Optional Photo Capture (issue #29)

The webapp has no concept of a per-candidate operator photo. The Android tablet's camera enables it. We add it as an **optional** field on a decision:

### Server side (webapp epic)

- New `CandidateEvidencePhoto` model (in `models.py`):
  ```python
  class CandidateEvidencePhoto(models.Model):
      inference_result = models.ForeignKey(InferenceResult, on_delete=models.CASCADE, related_name="evidence_photos")
      user             = models.ForeignKey(settings.AUTH_USER_MODEL, on_delete=models.CASCADE)
      survey           = models.ForeignKey(Survey, on_delete=models.CASCADE, related_name="evidence_photos")
      image_filename   = models.CharField(max_length=300, blank=False)
      captured_at      = models.DateTimeField()
      created          = models.DateTimeField(auto_now_add=True)
      # S3 object key: {S3_EVIDENCE_PHOTOS_PREFIX}/{survey_id}/{inference_result_id}/{user_id}/{uuid}.jpg
      # pre_delete signal: delete_s3_object() — load-bearing per webapp core belief #1
  ```
- New S3 prefix constant `S3_EVIDENCE_PHOTOS = "evidence"` in `settings.py`.
- New endpoint `POST /api/stage4/surveys/<survey_id>/evidence/` (multipart): one file + JSON metadata → creates `CandidateEvidencePhoto` row + uploads bytes to S3. Auth: same decorator chain as `stage4_response`. Returns `{id: int}`.
- Optionally `GET /api/stage4/evidence/<photo_id>/` for clients to download a previously-saved photo (out of MVP scope — photos are write-mostly).

### Client side

- In the candidate modal, an optional "+" button to attach a camera photo.
- Photo is captured via CameraX, downscaled (recommend ~2048×2048 JPEG @ 85% quality — plenty for documentation), written to local storage, recorded as `PendingPhotoUpload` in Room with `synced = false` plus the candidate FK + decision FK.
- During sync, the photo is uploaded via multipart to `POST /api/stage4/surveys/<survey_id>/evidence/` **before** the decision is pushed (so we can attach the photo id to the decision — though currently the decision's `detection_tag` is the only attached metadata; the photo FK is on `InferenceResult`, not on `EliminationProcessing`). If photo upload fails, retry 3× then escalate to user — the decision is still pushed without a photo. **Open question:** should a failed photo upload block the decision sync? — Tentatively **NO**: decisions are more critical than photos. Photos retry later.

---

## 9. Undo / Change-Decision

The webapp's `stage4_response` (`elimination.py:1459`) uses `EliminationProcessing.objects.update_or_create(...)` keyed on `(inference_result, user, stage=STAGE_4=300)` — re-POSTing overwrites the previous verdict. The Android app exploits this:

- A decision recorded in Room stores `LocalDecision.verdict` (yes/no), `detection_tag_id`, `captured_photo_id`, `synced (boolean)`, `synced_at (nullable)`.
- In the candidate modal, the user can re-tap Yes/No to change their verdict. The local row updates immediately (no copy-on-write history, matching the server's upsert semantics).
- On the next sync, the latest local verdict is pushed to the existing `stage4_response` form-encoded endpoint (see §10). Server upserts over the prior.
- No server-side "revert endpoint" is required.

**Soft-delete?** No — the webapp has no concept of deleting a Stage 4 verdict (you can only update it). The Android app matches: a verdict can be flipped, not removed.

---

## 10. Sync Protocol

### Shape (decided in brainstorm)

Reuse the **existing** `POST survey/<int:survey>/stage4/response/` form-encoded endpoint — exactly the same URL & payload the browser uses. The Android app fires one HTTP POST per `LocalDecision` row carrying `inference_result`, `is_meteorite`, optional `detection_tag`, with `X-CSRFToken` header + session cookies (`CookieJar`). No new batched `/decisions/sync` endpoint on the webapp; the upsert semantics of `stage4_response` (keyed on `(inference_result, user, stage=STAGE_4=300)`) already make repeating rows idempotent.

Photos upload first to a new `POST /api/stage4/surveys/<id>/evidence/` multipart endpoint; the resulting `evidence_photo_id` is recorded against the `LocalDecision` for client UI reference (the server-side `CandidateEvidencePhoto` row holds the FK to `InferenceResult`, so re-pushing the decision without the photo id remains a valid operation).

### What syncs from client → server

1. **Photos first:** `PendingPhotoUploadEntity` rows with `synced = false` → multipart POST `/api/stage4/surveys/<id>/evidence/`. On 2xx, row → `synced = true`, captures returned `evidence_photo_id`.
2. **Decisions:** `LocalDecisionEntity` rows with `synced = false` → form-encoded `POST survey/<id>/stage4/response/` (one HTTP request per row, in chronological order). On 200 `"OK"` body → row → `synced = true` + `synced_at = now()`.
3. **Car location:** atomic — pushed immediately while online via existing form-encoded `POST survey/<id>/stage4/set_car_location/`.

### What doesn't sync from server → client during the field

- The server may receive decisions from *other* tablets. The Android tablet does NOT need to know about them offline. On reconnect, before pushing, the app fetches the latest `GET /api/stage4/surveys/<id>/claims/` for the visible viewport — purely informational ("X is now claimed by @Y").

### Sync trigger strategy

Three triggers, in priority order:

1. **On reconnect** (NetworkCallback transitions to WIFI/Cellular) — schedule immediate `SyncWorker`.
2. **On app foreground** while online — schedule deferred `SyncWorker` (6 s debounce).
3. **Manual** — a "Sync now" button on the sync screen triggers `SyncWorker` with no debounce.

WorkManager's exponential backoff handles transient server errors. After 3 backend failures on a single row, the row is marked `failed` and surfaced in the UI; the user can retry manually.

### Session-expired recovery (decided in brainstorm)

- If the first sync POST responds 302 → `/accounts/login/`, or 403 (CSRF fail / login_required), the `SyncWorker` halts.
- All pending rows are marked `failed: session_expired` (NOT deleted — they persist in Room).
- The UI shows a mandatory re-login screen.
- The operator re-enters username + password (we deliberately do NOT store credentials on the tablet for transparent re-auth).
- On successful login, the new `sessionid` + `csrftoken` replace the old in the CookieJar; the queued `SyncWorker` is scheduled to resume (WorkManager exponential backoff starts it).
- **Zero decisions are ever lost** — the only state eaten is the expired cookie, which is regenerated by re-login.

### Conflict resolution

Conflict is minimal because **per-candidate claims prevent overlap** in the field. Two residual edge cases:

1. **Claim released + re-claimed by another user while the original was offline:** The original user's decision push is rejected — `stage4_response` will still accept the upsert (its current decorator chain doesn't enforce claims), so we ALSO add server-side claim enforcement at the `stage4_response` endpoint: if `request.user` does not have an active claim on the posted `inference_result`, respond 403 with body `claim-required`. The local row is marked `failed: claim-released-by-server`. The user sees the candidate marked "claimed by @other" on next pull and can dispute or release.
2. **Re-POST of an already-synced decision:** Idempotent — the upsert semantics make this a no-op.

### Per-row outcome

Because we re-use the form-encoded `stage4_response`, there is no batched per-row outcome array. Each POST's HTTP status is the row-level outcome:

| Response | Row status |
|---|---|
| `200 OK` body `"OK"` | `synced = true`, exit queue |
| `302 → /accounts/login/` | `failed: session_expired` → halt the queue, re-login flow (§10 above) |
| `403` body `claim-required` | `failed: claim-released-by-server` → fetch claims, surface to user |
| `404` (`InferenceResult` deleted meanwhile) | `failed: candidate-gone` → mark `LocalDecision.invalid = true`, exclude from UI |
| Network error / 5xx | retry with WorkManager exponential backoff (max 3 attempts) → `failed: server-error` then manual retry button |

---

## 11. API Contract (TO BE IMPLEMENTED IN THE WEBAPP EPIC)

### Principle (decided in brainstorm)

1. **Existing form-encoded endpoints are reused as-is for state changes.** The Android app sends form-encoded POSTs to the same URLs the browser uses today (`survey/<id>/stage4/response/`, `survey/<id>/stage4/set_car_location/`) with `X-CSRFToken` header + session cookies. **No JSON-variant of these endpoints, no URL change, no DRF plumbing.**
2. **New JSON endpoints are added only where structured data has to leave or enter the server:** the candidate list GET, the claims GET/POST, and the photo upload multipart + photo GET (for parity viewing in the browser too).
3. **DRY shared helpers, NOT a refactor of browser pages.** The new `GET /api/stage4/surveys/<id>/candidates/` JSON endpoint and the existing `stage4` view template-rendered view share a single `_get_stage4_state(survey, user)` Python helper that returns the unpacked querysets / polygons / base coords. The browser view continues to pass that to a `render(...)`; the new JSON view `JsonResponse`s it. Same for the `/api/surveys/` endpoint — shared `_get_user_surveys(user)` helper, both callers use it. The browser templates + JS are untouched. Minimises regression risk to the live webapp; DRY in the Python layer.
4. **No new DRF infrastructure, no serializers** (webapp AGENTS.md rule #7). New endpoints are ordinary FBVs returning `JsonResponse` / `HttpResponse`, using the existing decorator stack.
5. **Platform parity on photo functionality.** The webapp browser Stage 4 modal gets NEW UI (an attach-photo button + a thumbnail gallery) for both uploading and viewing evidence photos against a candidate. THIS is an additive UI change to `stage4.html` (and `survey-map-handler.js` for marker badges), not a behavioural refactor of existing functionality.

### Endpoint catalogue

| Method + Path | Body / Query | Returns | State | Auth / Decorators |
|---|---|---|---|---|
| `POST survey/<int:survey>/stage4/response/` | form: `inference_result`, `is_meteorite=true\|false`, `detection_tag=<id>\|` | `"OK"` / 403 | **EXISTS** — reused | `@login_required` + `@user_has_survey_access` (+ claim enforcement by W-10) |
| `POST survey/<int:survey>/stage4/set_car_location/` | form: `latitude`, `longitude` | `"OK"` / 400 | **EXISTS** — reused | `@login_required` + `@user_has_survey_access` |
| `GET /api/surveys/` | — | `{surveys: [{id, event_id, description, created, has_stage4: bool, …}]}` | **NEW** (W-something) — shared helper with `/` index view | `@login_required` |
| `GET /api/stage4/surveys/<survey_id>/candidates/` | — | `{candidates: [{inference_result_id, image_id, geo_centroid: {lat, lon}, geo_area: {…}, box: {x, y, w, h}, confidence, image_dims: {w, h}, detection_tags: [{id, name, category}]}], surveyed_areas: [...], tileset_id: <str\|null>, base: {lat, lon}\|null}` | **NEW** (W-2) — shared helper with existing `stage4` view | `@login_required` + `@user_has_survey_access` |
| `POST /api/stage4/surveys/<survey_id>/claims/claim/` | JSON: `{inference_result_ids: [int]}` | `{claimed: [int], already_claimed: [int]}` | **NEW** (W-7) | `@login_required` + `@user_has_survey_access` |
| `POST /api/stage4/surveys/<survey_id>/claims/release/` | JSON: `{inference_result_ids: [int]}` | `{released: [int]}` | **NEW** (W-7) | `@login_required` + `@user_has_survey_access` |
| `GET  /api/stage4/surveys/<survey_id>/claims/` | — | `{claims: [{inference_result_id, user_id, username, is_me}]}` | **NEW** (W-7) | `@login_required` + `@user_has_survey_access` |
| `POST /api/stage4/surveys/<survey_id>/evidence/` | multipart: `file` (jpg) + form `inference_result_id`, `captured_at` | `{id: int}` | **NEW** (W-9) | `@login_required` + `@user_has_survey_access` |
| `GET /api/stage4/evidence/<int:photo_id>/` | — | `image/jpeg` bytes (304 on If-Modified-Since) | **NEW** (W-9) — REQUIRED for browser parity viewing | `@login_required` + scope check: photo's survey must be survey the user can access |

### Notes

- The existing `stage4_response` endpoint has the decorator chain `@login_required` + `@user_has_survey_access` only — it does NOT enforce per-candidate claims yet. New webapp epic issue **W-10** adds server-side claim enforcement (403 with body `claim-required` body when no active claim by `request.user`) — see §10.
- The `surveyed_areas` polygons the new candidates endpoint needs are already computed by `elimination.calculate_survey_areas(survey)` (`webapp/src/dfnweb/views/elimination.py` line ~1330s); the shared helper simply exposes them.
- Optional follow-up (out of MVP scope): `GET /api/stage4/surveys/<survey_id>/decisions/` for the post-sync UI refresh. For MVP, the Android client simply re-fetches `/api/stage4/surveys/<id>/candidates/` on reconnect and re-derives the unprocessed layer locally.

---

## 12. Why this Contradicts `docs/PRODUCT-SENSE.md:70` — the Written Rationale

The webapp repo currently contains the documented belief:

> "**No native mobile app.** A mobile-friendly web UI is enough; building a native app would be a project unto itself." — `webapp/docs/PRODUCT-SENSE.md:70`

Per webapp AGENTS.md rule #5 "Don't introduce new infrastructure (FBVs, no pytest, no factory_boy, no DRF serializers … Match the existing style)" and `[adding a new public surface] requires a written design rationale in `docs/design-docs/` before doing so`, this design doc **is** that written rationale when copied/referenced into the webapp repo. The webapp epic should add a `webapp/docs/design-docs/NNN-native-android-stage4-rationale.md` that references this artefact.

### Justification

1. **Field evidence has overturned the assumption.** Two live campaigns (April 2026 + Dec 2025) failed not because the web UI was sub-optimal but because **browsers cannot be coerced to persist large offline tile sets**. This is a toolchain limitation, not a UX choice. A PWA service worker would not have solved it within reasonable effort either — Mapbox GL JS's raster source disk cache is not under our control, and operators reported the page state collapsing mid-walk.
2. **The native app does NOT extend the webapp's public surface.** It re-uses the Django session auth; it does not introduce a public token; it does not extend public Stage 1.
3. **It does introduce one new infra piece** (a small JSON API for Stage 4 + Claim model + EvidencePhoto model). This is justified by paradoxical cost: the existing HTML-scraping approach for a mobile client is unworkable; a JSON surface is the minimal stable interface.
4. **It does NOT break core belief #1** (S3 is canonical). `CandidateEvidencePhoto.bytes` live in S3; the DB row is a pointer.
5. **It does NOT break belief #8** (single Celery queue). The Android app's background workers are **device-side**, not server-side — no new Celery queues, no new server-side concurrency.

The Android epic is scoped strictly to the per-allocation MVP (4 wk app + 2 wk sync). The webapp epic is the necessary-but-tightly-scoped server-side counterpart.

---

## 13. Risks & Remaining Open Questions

| Ref | Risk / Question | Mitigation / Status |
|---|---|---|
| **R1** | Mapbox token: prod plan tier + offline-region per-region tile/size cap. | **Open — confirm with Hadrien.** Design's auto-split sub-region fallback (`A-7`) means we don't pin to a specific plan limit; still need to know the limit for runtime config + budgeting. Token is the same `MAPBOX_TOKEN` the webapp already uses (`webapp/src/dfn/settings.py:196`). |
| **R2** | RTK tablet exact model & free storage headroom. | **Open — confirm with Hadrien.** The team has indicated tens of GB is acceptable, but per-tablet free space must be known for the "Download for offline" UX so the user gets a warning before exceeding capacity. |
| **R3** | Django CSRF middleware accepting native OkHttp requests. | **Resolved in design** (§5): Android `AuthInterceptor` sends `Origin: <production-domain>` + `X-CSRFToken` from cookie; `CSRF_TRUSTED_ORIGINS` already includes the production domain for the browser webapp. Webapp team to verify during W-1 — expected to require no code change. |
| **R4** | Pre-download bandwidth from the small Django/Nectar server. | Cap concurrency at ~6 parallel fetches (`WorkManager` throttling in `A-14`); tested in field before campaign. Standing mitigation. |
| **R5** | Per-campaign on-device storage budget. | **Resolved in design** (§7): tens of GB acceptable; download all available zoom levels (satellite 18–22, candidate 20–22). No zoom narrowing. RTK free space confirmed via R2. |
| **R6** | Mapbox OfflineManager per-region tile cap. | **Resolved in design** (§7, `A-7`): auto-split the offline region into N sub-regions along surveyed-area polygons; never narrow zoom. |
| **R7** | Solar-battery dies mid-walk: Room rows lost? | Write-ahead: persist decision to Room *before* showing the "saved" tick. Tile cache survives reboot (app-specific storage persists across reboots). `LocalDecision` rows survive reboot. |
| **R8** | Multiple users sharing one tablet. | **Resolved in design** (§5): single signed-in user per tablet at a time. Switching user requires explicit sign-out which wipes Room DB + cookies + offline tile cache. UI bars a mid-campaign user switch. |
| **R9** | Sync recovery after 7-day offline (session expired mid-sync). | **Resolved in design** (§10): halt + prompt re-login; resume seamlessly; **zero decisions lost** (Room rows persist); credentials never stored on tablet. |
| **R10** | Camera permission denied by user. | Photos are optional — capture button disabled gracefully with a small inline explanation. The decision is still submittable without a photo. |
| **R11** | Server-side claim enforcement (`W-10`) breaks the existing *browser* `stage4_response` path (browser can't POST a verdict without first claiming). | **Resolved in design** (§6): the browser Stage 4 page ALSO gets the claim affordance in MVP as `W-11` (promoted from deferred). Strict epic merge ordering `W-5 → W-6 → W-11 → W-10` (model → endpoints → browser UX → enforcement) prevents regression. This re-homes webapp issue #67 (formerly closed wontfix) onto the new `CandidateClaim` model — small work once the model exists. |
| **R12 (new)** | Per-candidate tile radius set too low → operator walks into an empty satellite tile just outside the buffer. | Configurable buffer default 100 m (§7); operator can bump radius in Settings if visibility or GPS precision is poor; aggravated-radius warning surfaced at "Download for offline" time if GPS accuracy estimate exceeds half the configured buffer. |

> All other previously-listed risks (operationally) are handled in design. **Remaining open questions for the team to triage before issue creation: R1 (Mapbox plan tier + per-region tile cap), R2 (RTK tablet model & free storage headroom).** R11 (browser claim UX parity) is RESOLVED via the W-11 promotion (see §6 + §13).

---

## 14. Issue Decomposition — High-Level Tree

Two epics, one per repo. **Epic titles:**

- **Android repo:** *Stage 4 Native Android App (MVP+Sync)* — `[epic] android-stage4-mvp`
- **Webapp repo:** *Stage 4 JSON API + Claim + EvidencePhoto (Android support)* — `[epic] webapp-stage4-api-for-android`

The issues below will each carry full implementation detail when created (each issue's body includes: goal, context, references, acceptance criteria, definition-of-done, dependencies, files to touch — TBD at issue-creation time as part of the `/plan` phase that follows this brainstorm).

### 14.1 Webapp repo — `[epic] webapp-stage4-api-for-android`

Issues (predecessors listed in parens):

1. **W-1** *API scaffolding + DRY refactor of query helpers* — add `/api/stage4/...` URL namespace under `webapp/src/dfnweb/urls.py`; nothing else rendered yet. Extract `_get_stage4_state(survey, user)` from the existing `stage4` view (`elimination.py:1330-1457`) into a helper that returns the querysets / surveyed_areas / base / detection_tags; the existing `stage4` view continues to pass them to `render(...)` unchanged. Likewise extract `_get_user_surveys(user)` from the existing `/` index view's queryset. No behavioural change to the existing pages. Confirm `CSRF_TRUSTED_ORIGINS` includes the production domain.
2. **W-2** *(W-1)* `GET /api/stage4/surveys/<survey_id>/candidates/` — new FBV returning `JsonResponse` of `_get_stage4_state(survey, user)` plus candidate box/sizes/etc. No refactor to `stage4.html`; both callers share the helper.
3. **W-3** *(W-1)* `GET /api/surveys/` — new FBV returning `JsonResponse` of `_get_user_surveys(user)`. No refactor to the existing `/` index template; both callers share the helper.
4. **W-4** *(W-1)* Land the webapp repo's design doc `docs/design-docs/008-stage4-android-app-and-api.md` (already drafted in brainstorm — see *Sibling webapp design doc* below) as the architectural-decision record. It supersedes `webapp/docs/PRODUCT-SENSE.md:70`'s "No native mobile app" stance. PR-side review by Hadrien + the science team. Update `webapp/docs/design-docs/index.md` to add row 008 to the index table.
5. **W-5** *(W-1)* `CandidateClaim` model + migration in `webapp/src/dfnweb/models.py`. Per webapp core belief: `pre_delete` signal on `InferenceResult` releases active claims (or `on_delete=CASCADE` — verify with migration author). Includes `unique_together = (inference_result, user)` + indexes.
6. **W-6** *(W-5)* Claim endpoints: `POST /api/stage4/surveys/<id>/claims/claim/` (JSON body `{inference_result_ids: [int]}`), `POST .../claims/release/`, `GET .../claims/`. Same decorator chain as `stage4` views.
7. **W-7** *(W-1)* `CandidateEvidencePhoto` model + `S3_EVIDENCE_PHOTOS` prefix in `settings.py` + `pre_delete` signal calling `delete_s3_object()` (per webapp core belief #1) + migration.
8. **W-8** *(W-7)* Evidence endpoints: `POST /api/stage4/surveys/<id>/evidence/` (multipart upload) + `GET /api/stage4/evidence/<int:photo_id>/` (stream JPG, `@condition` HTTP-cache keyed on `CandidateEvidencePhoto.created`). Required for browser parity viewing.
9. **W-9** *(W-8)* Browser-side parity UI: extend `webapp/src/dfnweb/templates/stage4.html` Stage 4 candidate modal with an "Attach photo" button (multipart form upload) + a thumbnail gallery listing `evidence_photos` for the candidate, fetched from `GET /api/stage4/evidence/<id>/`. Need not touch `survey-map-handler.js` core; additional JS scoped to the modal (or new small static JS file `static/js/evidence-photo-handler.js`). Optional UX: small camera-badge icon on candidates with photos.
10. **W-10** *(W-5, W-2)* Decision-claim enforcement: extend the existing form-encoded `stage4_response` view (`elimination.py:1459`) with a check that `CandidateClaim.objects.filter(inference_result_id=…, user=request.user, is_active=True).exists()` — if missing, return `HttpResponse("claim-required", status=403)`. Preserves existing browser behaviour (browser will similarly be subject to claims — TBD whether browser also implements a claim affordance in this issue or in a separate UI issue; for this issue, scope the enforcement to the endpoint only).
11. **W-11** *(W-5, W-6)* Browser-side claim affordance on `stage4.html` — the same individual-tap + polygon-draw UX as the Android basecamp screen (§6 *Browser parity*). Re-homes webapp issue #67 (closed wontfix as too complex) onto the new `CandidateClaim` model; small once the model + endpoints exist. Includes a "My claims / release" view. **MVP scope** (promoted from deferred in brainstorm — required so the `W-10` enforcement doesn't regress browser verdict posts).

(Items previously numbered W-3 and W-4 in the draft — JSON variants for the two existing form-POST endpoints — are DROPPED. The Android app sends form-encoded POSTs to the existing URLs, see §10.)

### Strict epic merge ordering (W-5 → W-6 → W-11 → W-10)

To avoid regressing the live webapp's browser Stage 4:

1. **W-5** (`CandidateClaim` model + migration) merges first — schema exists, nothing uses it.
2. **W-6** (claim/release/list endpoints) merges second — model has callable Brigade, no enforcement yet.
3. **W-11** (browser claim UX on `stage4.html`) merges third — browsers can now create claims via the new endpoints.
4. **W-10** (server-side `claim-required` enforcement on `stage4_response`) merges LAST — turns on the gate. Browser and Android POST paths both come under the new rule simultaneously once step 3 is in production.

The PRs for W-5 / W-6 / W-11 / W-10 should ship in the SAME release window (or be coordinated as a feature-flagged toggle); never land W-10 before W-11 is deployed to production.

### 14.2 Android repo — `[epic] android-stage4-mvp`

Issues (Android-side work — each depends on the corresponding W- issue):

1. **A-1** *Project scaffold* — repo skeleton, Gradle version catalog, Compose + Hilt + Room + OkHttp + Mapbox deps wired; empty MainActivity; CI workflow (ktlint + detekt + unit tests). No deps.
2. **A-2** *(A-1)* Theme + design system (matches existing Stage 4 webapp visual language — marker SVGs replicate `webapp/src/static/images/marker-{yes,no,unprocessed}.svg`).
3. **A-3** *(A-1)* Networking layer: OkHttp `CookieJar` (encrypted persistent via EncryptedSharedPreferences), `AuthInterceptor` (adds `X-CSRFToken` from cookie + `Origin: <production-domain>` on unsafe methods), Retrofit services (JSON + multipart), Moshi.
4. **A-4** *(A-3)* Login screen — GETs `/accounts/login/` to seed CSRF cookie, POSTs form-encoded username/password, persists `sessionid` + `csrftoken`. (Reuses existing webapp endpoint — no webapp work needed.)
5. **A-5** *(A-3, W-3)* Survey picker screen — `GET /api/surveys/` (W-3), displays list, taps into Stage 4.
6. **A-6** *(A-3, W-2, A-5)* Mapbox map host Composable — satellite style, surveyed_area polygon overlay, base/car marker, satellite + custom raster source scaffold (matches `webapp/src/static/js/survey-map-handler.js`). Fetches candidates via `GET /api/stage4/surveys/<id>/candidates/` (W-2).
7. **A-7** *(A-6)* Custom `RasterSource` with local-file tile provider (offline-only fallback when online tile fetch fails) + Mapbox `OfflineManager` integration for satellite basemap with **automatic region splitting** if the plan's per-region tile cap is exceeded.
8. **A-8** *(A-1)* Room schema — entities: `SurveyEntity, CandidateEntity, ClaimEntity, LocalDecisionEntity, PendingPhotoUploadEntity, OfflineBundleEntity, TileManifestEntity`. DAOs.
9. **A-9** *(A-2, A-6, A-8)* Candidate markers on the map — yes/no/unprocessed layers, layer toggles, marker SVG resources, tap to open modal.
10. **A-10** *(A-9)* Candidate modal screen — image/map toggle, cropped JPG fetch via Coil, pinch-zoom (Compose gestures). Replicates `webapp/src/static/js/candidate-image-handler.js`. Uses existing `GET /image_survey_cropped/<inference_result_id>/` URL for the cropped JPG (`webapp/src/dfnweb/views/elimination.py:718`). Pre-downloads the JPG during the offline prep phase.
11. **A-11** *(A-10)* Yes/No buttons + detection tag picker. Local decision persisted to Room as `LocalDecision(synced=false)`. Submit target: existing `POST survey/<id>/stage4/response/` (form-encoded).
12. **A-12** *(A-10, W-7, W-8)* Optional camera capture via CameraX → `PendingPhotoUploadEntity`. Multipart upload to `POST /api/stage4/surveys/<id>/evidence/` (W-8) at sync time; resulting `evidence_photo_id` recorded locally.
13. **A-13** *(A-6, A-8, W-5, W-6)* Basecamp: claim screen — individual candidate tap + polygon draw (Mapbox draw extension or custom rectangle selector) → POST to `/api/stage4/surveys/<id>/claims/claim/` (W-6). Bulk-releases from the same screen.
14. **A-14** *(A-13, A-7)* Basecamp: "Download for offline" — survey fetch, satellite offline region download (with auto-split on Mapbox quota), custom-tile manifest computation + `WorkManager` tile-fetch job for per-candidate raster tiles at zooms 20–22.
15. **A-15** *(A-3, A-8)* "Set car to my location" button — uses `FusedLocationProviderClient`, POSTs form-encoded to existing `POST survey/<id>/stage4/set_car_location/` URL.
16. **A-16** *(A-3, A-8)* Sync layer — `SyncWorker` (WorkManager) orchestrating: (1) multipart upload of `PendingPhotoUploadEntity` rows to `/evidence/`, (2) one form-encoded POST per `LocalDecisionEntity` row to the existing `stage4_response` URL. Updates Room rows on per-row HTTP status. Handles session-expired halt-and-prompt-relogin flow (§10).
17. **A-17** *(A-16)* Sync UI — status screen, progress, retry, manual "Sync now" button, failed-row escalation with reason codes (`session_expired` / `claim-released-by-server` / `candidate-gone` / `server-error`).
18. **A-18** *(A-2)* Field UX polish — connection-status banner, "offline bundle missing" guards, sync-mode indicator, geolocation accuracy circle (matches `User.show_geolocation_accuracy_circle` user pref — TBD how to fetch per-user pref via the candidates endpoint payload).
19. **A-19** *(A-1)* Test coverage — unit tests for DAOs, repository fakes, sync worker logic; Compose UI tests for login + Stage 4 happy path; integration test using MockWebServer for the JSON API.

### Cross-cutting dependencies (revised)

```
A-4 ─── depends on ──> (webapp /accounts/login — exists, no webapp work)
A-5 ─── depends on ──> W-3 (GET /api/surveys/)
A-6 ─── depends on ──> W-2 (GET /api/stage4/surveys/<id>/candidates/)
A-10 ── depends on ──> (existing /image_survey_cropped/ endpoint — no work)
A-11 ── depends on ──> (existing /stage4/response/ endpoint — no work; W-10 adds claim enforcement)
A-12 ── depends on ──> W-7 (model) + W-8 (upload endpoint)
A-13 ── depends on ──> W-5 (model) + W-6 (claim endpoints)
A-15 ── depends on ──> (existing /stage4/set_car_location/ endpoint — no work)
A-16 ── depends on ──> A-11, A-12, A-8 (sync calls existing /stage4/response/ and new /evidence/)
```

The webapp epic has **no path that blocks A-11 or A-15**. A-1, A-2, A-3, A-4, A-7, A-8, A-10 (image-only part), A-14 (existing tile endpoints), A-18 can all proceed in the first sprint while the webapp epic runs in parallel.

---

## 15. Recommendations for the `/plan` Phase (next step)

Per the brainstorm skill's hand-off protocol, this design should transition to a PM-style decomposition (`/plan`) which produces:

- For each issue above (W-1…W-11 active; A-1…A-19): a populated body containing goal, context with file references (this design doc + the cited webapp `file_path:line_number` ranges), acceptance criteria, definition-of-done, dependencies, owner guidance, sample test cases. **Strict epic merge ordering `W-5 → W-6 → W-11 → W-10`** is documented in §14.1 and must be respected in the issue dependency graph.
- An epic milestone set up in both repos with the epic title; issues tagged with the epic and labelled for repo (e.g. `area:android`, `area:webapp`).
- A gantt-ish ordering / prioritisation guidance for the team (the Android MVP must start with A-1 scaffold work the moment W-1 stub endpoint exists; the dependency tree above ordering is the source).

Suggested next actions for Lewis:

1. Section-by-section review of THIS design doc — flag any disagreement or open questions to resolve (see §13).
2. Save the approved version of this design doc (rename suffix or status field once confirmed).
3. Hand off to `/plan` to generate GitHub issues against both repos with full-issue bodies.

---

## 16. References

### Webapp code

- Stage 4 views: `webapp/src/dfnweb/views/elimination.py:1330` (page render), `:1459` (response POST), `:1539` (set_car_location POST)
- Candidate/tile endpoints: `webapp/src/dfnweb/views/survey.py:457, 550, 571, 596`
- DTO-defining JS: `webapp/src/static/js/survey-map-handler.js` (605 lines), `webapp/src/static/js/candidate-image-handler.js` (542 lines)
- Stage 4 template: `webapp/src/dfnweb/templates/stage4.html`
- Models: `webapp/src/dfnweb/models.py:72` (Survey), `:150` (SurveyImage), `:711` (InferenceResult), `:866` (EliminationProcessing — stage constants 881–895), `:837` (DetectionTag)
- Auth: `webapp/src/accounts/models.py:14, 35, 311, 345`, `webapp/src/accounts/decorators.py:51, 83, 115`, `webapp/src/dfnweb/utils/drf.py` (daemon JWT — not reusable for users)
- Design stances to obsolete: `webapp/docs/PRODUCT-SENSE.md:70`, `webapp/docs/design-docs/005-public-stage1-uuid.md` (public surface scope), `webapp/AGENTS.md` rule #5 + #7
- Tile generation: `webapp/src/utils/tiling.py` (the candidate georeferencing pipeline — server side, not in scope for the app)
- Settings: `webapp/src/dfn/settings.py:196` (MAPBOX_TOKEN), `:215` (TILE_MIN_ZOOM=20, TILE_MAX_ZOOM=22)

### GitHub issues (webapp repo)

- **#89** *native app for stage 4* — origin of this epic
- **#114** *Stage 4 caching* — the field-experience report driving urgency
- **#29** *Uploading phone picture of candidate via webapp during stage 4* — included in MVP as optional photo upload
- **#67** *possibility to assign zones to different people for stage 4 follow up* — closed wontfix in the *webapp*; re-homed into the Android basecamp affordance (this design §6)
- **#96** *using Mapbox standard Tileset IDs* — already handled by replicating `survey.tileset_id` (Android client will pass `survey.tileset_id` through to the Mapbox custom-tileset source)
- **#79** *Ability to change the colour of stage 4 candidate markers* — out of MVP scope; future UX polish issue

### ADACS

- Proposal: *Drone_meteorite_2026B* — pages 11–12 (problem statement), pages 15–17 (resource assessment breakdown)

---

## 17. Approvals

- [x] §1 Context — confirmed by brainstorm
- [x] §2 Goals & non-goals — confirmed via Q on epic scope
- [x] §3 Architecture overview — confirmed via Qs on stack + auth + cache
- [x] §4 Tech stack (Compose + Hilt + MVVM) — confirmed via Q
- [x] §5 Auth (Django session reuse; Origin+CSRF approach) — confirmed via Qs on auth
- [x] §6 Claim model + polygon-as-UX-helper; **browser parity added** (W-11 MVP, not deferred) — confirmed via Qs + R11 resolution
- [x] §7 Offline tile caching (aggressive pre-download; tens of GB acceptable; no zoom narrowing; **candidate-buffered (default 100 m, configurable) download scope with on-device Web Mercator tile math specified**; auto-split Mapbox regions) — confirmed via Qs
- [x] §8 Optional photo capture (camera optional, included as evidence; **browser-side parity** on photo functionality) — confirmed via Qs
- [x] §9 Undo / change decision via existing server upsert — confirmed via Q
- [x] §10 Sync protocol (single-row form-encoded POSTs to existing endpoints; session-expired halt-and-relogin; no decisions lost) — confirmed via Qs on sync shape + recovery
- [x] §11 API contract (reuse existing form-POST endpoints; new JSON endpoints only for GET structured data + photo upload/view; DRY shared helper refactor but NO rewrite of browser templates; **photo + claim parity surfaced on browser too**) — confirmed via Qs on URL strategy + survey picker + parity + R11 resolution
- [x] §12 Rationale (supersedes `webapp/docs/PRODUCT-SENSE.md:70`) — **webapp-side sibling design doc `008-stage4-android-app-and-api.md` drafted in `webapp/docs/design-docs/`**; W-4 lands + indexes it
- [x] §13 Risks — R1 + R2 are open for Hadrien (Mapbox plan tier, RTK tablet storage); R11 RESOLVED via W-11 promotion; R12 (buffer-radius-too-low) added with mitigations; all others resolved in design
- [x] §14 Issue decomposition tree — locked: W-1…W-11 active (W-11 promoted), A-1…A-19; includes **strict epic merge ordering note `W-6 → W-11 → W-10`**
- [x] Sibling webapp design doc — `webapp/docs/design-docs/008-stage4-android-app-and-api.md` drafted alongside this design doc
- [ ] Epic creation in both repos — pending `/plan` execution