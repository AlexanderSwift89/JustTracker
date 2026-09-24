# Changelog

## JustTracker [1.0.2] - 2026-09-23 (versionCode 3)

### Fixed
- **App froze after rotating portrait → landscape → portrait** (D-13): in landscape the Track detail map was
  45 % of ~360 dp, smaller than the 2 × 48 dp fit padding; osmdroid computed a NaN zoom and
  `Projection.getCloserPixel` looped forever on the main thread (ANR reproduced on an API 34 emulator).
  The track is now fitted only into a view of at least 32 dp with a padding that shrinks to fit, the zoom is
  checked to be finite, and fitting follows the view's size changes instead of a `post` before layout.
- **Elevation gain/loss were inflated 2–3×** (D-14): an 11 km run on almost flat ground showed "+134 m / −125 m"
  (the old algorithm reproduces exactly that on the user's GPX). Phone GPS altitude noise and receiver
  "stuck altitude" artefacts (+12…20 m within a second, held 20–160 s) were counted as climbing, and a height
  difference across a pause was counted too. New `ElevationCalculator` (ADR-19): per segment, vertical-accuracy
  gate, removal of excursions that start with an impossible step and return, mean over ±75 m of path (at least
  ±15 s), 6 m turning-point hysteresis. The same run now gives +55 m / −41 m. Tracks recorded earlier are
  recomputed when their detail screen is opened.
- **GPX export did not validate against the GPX 1.1 schema** (D-15): per-point `<speed>` was written into
  `<extensions>` in the GPX namespace, which the schema forbids; timestamps were truncated to whole seconds, so
  fixes less than a second apart shared a time. Speed and course now go into Garmin TrackPointExtension v2
  (`gpxtpx:`), times keep milliseconds when present, metadata gets `<bounds>`, longitude 180° is written as −180°,
  non-finite points and characters XML 1.0 cannot carry are dropped.
- **GPX file name lost the Cyrillic track name** (D-16): "Бег · 23 сент., 14:33" became `23_._14_33_4.gpx`,
  now `Бег_23_сент_14_33_4.gpx` (letters of any script and digits are kept).
- **Track detail map turned grey after a rotation** (D-17): the map moves between the portrait and the landscape
  layout (`movableContentOf`), which detaches the `MapView` from the window, and osmdroid destroyed its tile
  provider and overlays on detach. The view now keeps itself (`setDestroyMode(false)`) and is destroyed when it
  leaves the composition.
- **Rename dialog in landscape**: the keyboard covered Save / Cancel (D-18); the keyboard's ✓ key now saves.
- **A recording could stay at 0 m for good** (D-19): the first fix of a segment was taken unchecked, so a far-off
  first fix (a coarse or stale position) made every real fix an "implausible jump". The first fix now waits for the
  next one to confirm it, and 5 consistent fixes in a row away from the recorded one move the track there, deleting
  a stray start of up to 3 points (`LocationFilter` rules 6–7).
- **"Start" tile showed "23 сент." instead of "23 сент. 2026 г., 20:36"** (D-20, portrait, default font): the tile now
  spans the whole row of the stats grid.
- **Large font sizes (150–200 %)** (D-21): cursor values ran together in the landscape pane ("20:36:36150"), tile
  and navigation labels broke mid-word ("Статистик / а"), the recording-time unit was cut ("ч:мм:с"). Single-line
  values and labels now shrink to fit (down to 60 %, then "…") instead of being clipped or broken.
- **Cursor position in Track detail was lost** when the system killed the app in the background (D-22); it is now
  saved state like the dialogs, the stats panel and the map camera.
- Debug builds drew osmdroid's tile borders and indices over the map (D-23, `isDebugTileProviders`); release builds
  were not affected.

### Changed
- **Rotation no longer recreates the activity** (ADR-18, `configChanges` for orientation and window size): the
  map keeps its tiles and camera, open dialogs stay open. Recreation for other reasons (theme, language, process
  death) keeps the map camera, open dialogs (`rememberSaveable`) and the Track detail cursor (`SavedStateHandle`).
  Re-tested on an API 34 emulator: every screen, dialog and sheet in both orientations, theme and language changes,
  process death, window resizing, font scale 150 % and 200 %, onboarding in landscape.
- **Landscape layouts**: the navigation rail replaces the bottom bar whenever the window is at least 600 dp wide
  (a phone in landscape); Track detail shows the map on the left at full height with the scrubber, values and
  tiles in a scrollable pane on the right; the Record panel and GPS banner are capped at 640 dp and centred;
  onboarding steps, the map-mode and choice dialogs, the activity-type sheet, the place card and empty states
  scroll when they do not fit.
- The Track detail map re-fits the track for every new view size (rotation, split screen, stats panel) until
  the user pans or zooms it.

### Data
- Room schema version 2: nullable `track_points.verticalAccuracyM` (vertical accuracy of the fix), added by an
  automatic migration from version 1; older points keep `null`.

### Tests
- 151 unit tests (+33): the fit padding that produced the NaN zoom (`TrackMapFitTest`); elevation against
  AR(1) GPS noise, stuck-altitude excursions, segments, the accuracy gate and turning points; GPX validated
  against the GPX 1.1 and TrackPointExtension v2 schemas (`src/test/resources/gpx`), milliseconds,
  longitude 180°, invalid XML characters, Cyrillic file names; first-fix confirmation and re-anchoring after a run
  of jumps (`LocationFilterTest`); rows of the stats grid with the full-width "Start" tile (`StatGridRowsTest`).
- Test plan: TC-91…95 and TC-98 passed on the emulator, new TC-100…103 (large font, process death, far-off first
  fix, "Start" tile).
- `versionCode` 3, `versionName` 1.0.2; "What's new" in `store/rustore/listing_*.md`.

## JustTracker [1.0.1] - 2026-09-22 (versionCode 2)

### Fixed
- **Track detail sometimes did not load from History** (D-11): the screen state waited for the first
  emission of the "places nearby" flow, which was held back by the rate-limited Overpass request
  (≥ 15 s between calls, 60 s backoff, 10/25 s timeouts) — the track appeared only after the request
  finished or hit the cache on the 2nd–3rd attempt. The POI flow now starts with an empty list (as the
  Record screen already did); the track, scrubber and tiles appear as soon as Room has answered.
- **Offline map loaded "in chunks" and stayed blurry after zooming** (D-12, ADR-17): osmdroid's
  `MapTileProviderBasic` keeps a rescaled/expired placeholder for good once the data connection is off,
  so covered tiles were never re-rendered; `osmdroid-mapsforge` rendered on a single `synchronized`
  thread. `HybridTileProvider` now builds the same chain on `MapTileProviderArray`, overrides
  `isDowngradedMode` (a tile the renderer covers is always re-requested) and puts the offline modules
  first — also in the pre-cache.

### Added
- **Collapsible statistics panel in Track detail** (US-08): a chevron button next to the activity type
  hides the tile grid so the map takes the whole height; the distance slider and the values at the
  cursor stay in place. Shown by default on every open; the choice survives rotation.
- **Skeleton ("shimmer") loading states** (UX §7) instead of blank screens / spinners: History cards,
  the whole Track detail layout, Statistics tiles, the Offline maps catalogue and the Wikipedia summary
  in the place card. Appear only when a load takes longer than 100 ms, announced as "Loading…" to
  TalkBack.
- **Own multi-threaded offline renderer** (`OfflineRenderer`, `OfflineRegionModule`): one shared
  Mapsforge `DirectRenderer` (consistent labels across tile borders), a pool of `MapFile` stores — one
  per concurrent render, 2–4 threads (half the cores); rendered tiles are written as PNG into the shared
  osmdroid SQLite cache under a name that fingerprints the region set (files + size + mtime, language,
  tile size), stale namespaces are purged; the approximation module upscales cached offline tiles for
  the next zoom; the pre-cache renders the border ring and the zoom-out level ahead of time.
- **Crisp, correctly sized offline labels on dense screens**: 512 px tiles when density ≥ 1.5 (drawn
  at 1.4× instead of 2.75× upscaling), RGB_565 bitmaps; the Mapsforge theme is now scaled by
  `tileSize/256` only — `AndroidGraphicFactory` had also applied the display density, so labels and
  symbols were density× too large on top of the upscaling (closes OBS-05).
- 6 new unit tests (`OfflineTilesTest`, 118 total): tile size per density, render threads, region-set
  fingerprint / cache namespace.

### Changed
- Mapsforge 0.21 is a direct dependency (`mapsforge-map-android`, `mapsforge-map`, `mapsforge-themes`);
  `osmdroid-mapsforge` removed. Third-party notices and the licences page updated accordingly.
- Tile cache limit raised to 300 MB (trim to 240 MB); it now holds rendered offline tiles as well.
- Theme (light/dark) changes only re-apply the map colour filter; the tile provider is no longer rebuilt.
- `versionCode` 2, `versionName` 1.0.1; "What's new" in `store/rustore/listing_*.md`.

## JustTracker [1.0.0] - 2026-09-21 (fork of TrekLog 1.2.0 for RuStore)

JustTracker is a new product line (`com.justtracker.app`, versionCode 1) branched from TrekLog 1.2.0: "just a tracker" —
simple and self-contained. Everything below the TrekLog 1.2.0 entry is inherited history.

### Added
- **Offline map regions** (US-19/US-20): Settings → Offline maps — catalogue of Mapsforge region files
  (Russian federal districts, Crimea, Kaliningrad, neighbouring countries, a small test region) from
  download.mapsforge.org, downloads via the system DownloadManager (Wi-Fi only by default, free-space
  check, resume, completion handled even when the app is dead), header verification, import of a user
  `.map` file. ADR-13.
- **Explicit map mode** (US-22, ADR-16): Settings → Map mode — Online (OSM tiles from the internet +
  cache) or Offline (downloaded regions only, `setUseDataConnection(false)`); a confirmation prompt
  when the internet is lost (and a region is ready) or comes back — the mode never switches by itself;
  "Offline map" badge next to the speed legend. `ConnectivityObserver`, `MapModeController`,
  pure `MapModeAdvisor` (+5 unit tests, 112 total).
- **Explicit language choice** (US-18): first onboarding step and a Settings item — English / Русский,
  no "system" option; AppCompat per-app locales (persists on Android 8–12 too); auto-names, the
  recording notification, Wikipedia language and TTS fallback follow the app language. ADR-15.
- Splash screen (core-splashscreen), predictive back, adaptive navigation (`NavigationSuiteScaffold`:
  bar / rail), explicit M3 Typography (tabular figures) and Shapes, pinned top-bar scroll behaviour,
  themed (monochrome) launcher icon, new brand icon.
- RuStore readiness: `store/rustore/` (RU/EN listing, 512 px icon + generator, 1080×1920 screenshots,
  category & 436-FZ age rating 12+, permissions / data-safety declaration, moderator notes), signed
  `release-signed` CI job, GitHub Pages site with the bilingual privacy policy, version overrides via
  `-PversionCode/-PversionName`.
- 26 new unit tests (107 total): provider policy, language tags, tile bounds, map source resolver,
  region state machine, DownloadManager reason mapping, catalogue parser + bundled catalogue validity.

### Changed
- **No Google Play Services**: `PlatformLocationSource` on `android.location.LocationManager`
  (AOSP fused provider on Android 12+, GPS/network fallback) replaces FusedLocationProvider;
  `play-services-location` and `kotlinx-coroutines-play-services` removed. ADR-14.
- `MainActivity` is an `AppCompatActivity`; theme parent `Theme.AppCompat.DayNight.NoActionBar`.
- Map inversion in dark mode follows the app theme, not only the system one.
- Record screen overlays apply safe-drawing insets themselves; the bottom panel uses the theme's
  extra-large shape (top corners only).
- Switch rows in Settings are single toggleable targets (TalkBack reads one control).
- Tab click pops back to a destination already on the stack (fixes the Record tab after an activity
  recreation).
- Online map was blank at start-up: `HybridTileProvider` now extends `MapTileProviderBasic` (default
  LRU protection / pre-cache); a bare `MapTileProviderArray` evicted tiles before they were drawn and
  re-downloaded them in a loop (D-09).
- Package, brand strings, GPX creator, log tag, POI User-Agent, privacy URL renamed to JustTracker.

### Data
- Room schema version 1 gains the `offline_regions` table (fresh package — no migration).
- New DataStore keys: `language`, `maps_wifi_only`.
- Region files live in `getExternalFilesDir("maps")`, excluded from backup.

### Security / privacy
- Network hosts whitelist extended with `download.mapsforge.org` (only https, only on explicit user
  action); catalogue parser rejects anything else. `DownloadCompleteReceiver` is exported (system
  broadcast) but only looks up the id in DownloadManager. R8 keeps `org.mapsforge.**`.
- Privacy policy restructured for 152-FZ and GDPR at once (developer is neither a PD operator nor a
  controller; purposes, retention, user rights, security sections); the COPPA-style "under 13" clause is
  replaced by "no data collected from anyone, parental consent per applicable law". Store card and
  landing page no longer link the source code. `THIRD_PARTY_NOTICES.md` added; legal review in
  `docs/07_security.md` §7. Settings gains "Open-source licences" (→ `site/licenses/`, LGPL notice for
  Mapsforge); proprietary `LICENSE` with an LGPL §4 compatibility clause; age rating set to 12+ because of
  uncontrolled Wikipedia content in Places nearby.
- `pages.yml`: `configure-pages` with `enablement: true` (first deploy failed — Pages was never enabled),
  `checkout@v5`.

## [1.2.0] - 2026-09-20 (Recording time + speed along the track)

### Changed
- **Recording time is the primary time**: a stopwatch from Start to Stop (pauses included) on the Record panel, in History cards and in Track detail. Moving time stays as a secondary value (small line on the panel, second line on cards, its own tile in detail). It ticks once a second even without GPS fixes.
- Notification shows the recording time as a live chronometer; text is now "distance · speed".
- Record panel shows max speed next to distance and average.
- Track detail: "Total time" tile replaced by "Recording time"; a "Paused" tile appears only when the track had pauses.
- History card: second line "Moving … · max …".

### Added
- Track line coloured by speed (blue → green → yellow → orange → red relative to the track's max) on the live Record map and in Track detail, with a "0 … max" legend (US-15).
- Record screen: tap on the track line shows a card with the speed on that section, distance from start and time since the recording started; the tapped point is ringed on the map.
- Track detail: scrubber under the map — a slider over the track distance captioned "elapsed · %", ◀ ▶ buttons to step one point, and the values at the cursor (speed, distance from start, time of day, altitude); the cursor ring moves along the line and a tap on the line moves the slider.
- `SpeedProfile` (domain) and `TrackPath` (UI) — per-point smoothed speed / cumulative distance / timestamp / altitude shared by both maps, plus cursor addressing; 16 new unit tests (81 total).

### Data
- No schema change: recording time is derived from `startedAt`/`finishedAt`; per-section speed is the `speedMps` already stored with every point, so tracks recorded in 1.0/1.1 get both features.

## [1.1.0] - 2026-09-20 (Places nearby — stage 1 + A, minimal)

### Added
- Places nearby: pins for OpenStreetMap objects with a Wikipedia article around the user (Record screen, ~1.2 km) and within 400 m of a saved track (Detail screen). Source: Overpass API.
- Place card (bottom sheet): name, category, distance, Wikipedia lead summary, "Read aloud" (device TTS, ducks other audio), "Wikipedia" link, CC BY-SA attribution.
- Optional auto read-aloud while recording when within 150 m of a place, once per track, works with the screen off. **Off by default.**
- Settings: "Show places nearby" (on by default) and "Read aloud when approaching" (off by default).
- 29 new unit tests (Wikipedia tag validation, Overpass query/grid, parsers, proximity, spoken intro).

### Infrastructure
- GitHub Actions: unit tests, lint and debug APK on every push/PR; GitHub Release with the APK on `v*` tags.

### Security / privacy
- Overpass requests are built around the center of a ~500 m grid cell, never the exact position; track outlines are simplified to ≤ 80 vertices.
- Wikipedia language codes from OSM tags are validated before being used as a hostname; only `https://*.wikipedia.org` URLs from responses are accepted.
- HTTPS only, 2 MB response cap, timeouts, ≥ 15 s between Overpass calls, 60 s backoff after errors; User-Agent without device identifiers.
- Privacy policy and Data safety answers updated (approximate location shared with OpenStreetMap Overpass / Wikimedia, optional).

## [1.0.0] - 2026-09-19 (MVP, internal)

### Added
- GPS track recording with Start / Pause / Resume / Stop, foreground service (type location), persistent notification with Pause/Stop actions.
- Live map (OpenStreetMap via osmdroid) with follow-me mode, current position marker and growing polyline.
- Live metrics: instantaneous, average and max speed, distance, moving time.
- Track history, track detail (fit-to-track map, start/finish markers, 10 stat tiles).
- Automatic activity classification (walk / run / bike / car) with manual override.
- Elevation gain/loss, pace for walking and running.
- Statistics: totals, this week, this month, longest track, fastest average, by activity.
- GPX 1.1 export via system share sheet (FileProvider).
- Settings: metric/imperial, theme, GPS accuracy threshold, keep screen on.
- Recovery of an interrupted recording after process death.
- English and Russian localisation, dynamic color, dark theme.

### Security
- No background-location permission, no analytics/ads SDKs, backup of location data disabled, cleartext traffic disabled.
