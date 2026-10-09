# Changelog

## JustTracker [1.2.0] - 2026-10-09 (versionCode 7)

**Acceleration in the track history:** where and how you sped up and slowed down, shown the way speed is (US-24,
ADR-30). For every track, including those recorded before the update. No new permissions, sensors or network
requests; the database schema is 3.

### Added
- **Speed | Acceleration** switch over the track detail map (only when the track has acceleration: the receiver's
  own Doppler speed): the line is coloured green where you sped up, orange where you slowed down, blue-grey where
  the speed was steady or unknown; the legend shows the scale (the 95th percentile, 1…6 m/s², ft/s² in imperial
  units). The choice is remembered (`detail_line_metric`).
- The **acceleration at the scrubber** — signed value with a direction arrow, "—" without an estimate — as the first
  value in acceleration mode; TalkBack reads it in words.
- **Max speed-up / Max slow-down** tiles and **Speeding up and slowing down**: the strongest episodes of each kind
  ("0 → 72 km/h in 10 s, +2.0 m/s² · 9.4 km from start"); a tap moves the scrubber and the ring to where it started;
  "Show all (N)" lists every episode in order.

### Changed
- Acceleration is computed when a track is opened from its stored points with the live indicator's formula (one
  shared `AccelerationMath`), but over a window centred on each point: the colour sits where the speed changed,
  without the live value's 2 s delay. Only Doppler speeds are used; runs are trimmed to the speed change and
  completed to a standstill — also after a stop too short for a standing point (a traffic light).
- The line is recoloured in place when switching (no rebuild of the drawn pieces); the simplified line at overview
  zoom keeps the episodes' ends; the track is fitted below the overlays of the map.
- The dark-theme speeding-up green is `#4CAF50` (was `#81C784`) for the live indicator too: it now differs from the
  orange in lightness as well, which is what tells them apart with deuteranopia.

### Data
- Schema 3: `track_points.speedAccuracyMps`, the 68 % accuracy of the receiver's Doppler speed, written only with
  that speed (automatic migration, existing points keep null). It stays on the device and is not exported to GPX.

### Fixed
- Instrumented tests ran 0 tests and passed since espresso-core was removed in 1.1.2: the test runner is a
  dependency of its own again (D-32).

### Tests
- 35 new JVM tests (255 in total): `AccelerationMath`, `TrackAcceleration` on a fixture that records through the real
  `TrackRecorder` (history equals the live value shifted by half a window; the emulator's stop-and-go, also from the
  recorded points; noise, walking, receivers without Doppler speed, contradiction, tunnel, traffic light, spikes,
  accuracies, thresholds, 100 000 points), colours, simplification breaks, the fit, settings, view model. 7
  instrumented tests: migrations 1 → 2, 2 → 3, 1 → 3, DAO.

### Docs
- PRD US-24, UX §2.13, ADR-30, system analysis §3.11 / NFR-28, security review §10 (no findings, privacy policy
  unchanged), test plan TC-147…157, user guide; store screenshot `02_detail.png` retaken, `07_acceleration.png` added.

## JustTracker [1.1.2] - 2026-10-06 (versionCode 6)

Privacy, performance and refactoring release. **Places nearby is removed: in the Online map mode the app only
loads map tiles.** Second security audit (`docs/07_security.md` §9: SEC-14…SEC-20, OWASP MASVS v2). No new
permissions, database schema unchanged.

### Removed
- **Places nearby** (SEC-14, ADR-24): Overpass/Wikipedia pins on both maps, the place card, read-aloud and the
  automatic announcements, together with the HTTP client, host policy, parsers, text-to-speech, the TTS
  `<queries>` block, strings, the pin icon and 55 tests. On every cold start the Record screen sent the ~500 m grid
  cell of the last known location — usually home — to Overpass before any recording, contrary to the privacy policy
  and the RuStore declaration. The app now talks to `tile.openstreetmap.org` (Online mode) and
  `download.mapsforge.org` (a region, on the user's command) only; apart from the tiles of the map shown on screen
  (around the position on the Record screen, also before a recording, and around an opened track), nothing derived
  from the location leaves the device. The `poi_enabled` and `poi_auto_speak` settings are dropped from DataStore on the first read; the other
  settings, tracks and regions are kept.

### Security / privacy
- **No library logs in release** (SEC-15, ADR-29): R8 now strips every `android.util.Log` level, not only `d/v` —
  osmdroid wrote `Log.w/i` lines with tile paths `/z/x/y`, i.e. the area around the user while following.
  `AppLog.w/e` write through `Log.println` (SEC-12 format kept); Mapsforge `java.util.logging` is off in release.
- **Map import needs free space** (SEC-16): size + 200 MB, like a download; the copy stops at that budget whatever
  the provider reports, so an endless document cannot fill the disk and end a running recording.
- **Links and sharing** (SEC-17): links open only as `https://` with a host; the GPX share grant is an explicit
  `ClipData`.
- **Malformed map files** (SEC-18): a header that runs out of memory or stack is rejected as corrupt; the start-up
  reconcile handles each region on its own and an uncaught app-scope failure is logged instead of crashing — no
  crash loop; catalogue URLs with credentials or an explicit port are rejected.
- **No Google font provider** (SEC-19): `EmojiCompatInitializer` (from appcompat) looked up the Google Play
  services font provider at start on API 26–29; removed, emoji use the system font.
- **CI** (SEC-20): `actions/*` pinned by commit SHA too; the Pages checkout keeps no token.
- **Offline mode cannot reach the network through the pre-cache**: the tile downloader is not built at all in
  Offline mode (osmdroid's pre-cache ignores `useDataConnection()`; before, only MAPNIK's no-preventive policy kept
  it off the network).
- Privacy policy 1.2 (RU/EN): two hosts only; which map tiles are requested (around the position, also before a
  recording, and around an opened track) and why the last known position is read before a recording; no "Read
  aloud" section; the age rating is left to the store page.
  RuStore texts, declarations and moderator notes updated; rating 0+ now applies (owner's decision in the console).
- Verified by traffic capture on the release APK (TC-131): Online — only the OSM tile CDN; Offline — 0 bytes; a region
  — only after the confirmation dialog.

### Performance (emulator API 34, debug, Offline mode without regions)
- **Live track line read from the database tail** (ADR-25): the line is an immutable chunked `TrackLine` that a
  builder extends point by point; the map rebuilds only the pieces from the last drawn vertex. Recording on a
  10 000-point track: process CPU 141–149 % → 21–22 %, main thread 31 % → 5–9 %, 60 → ~3 frames per second.
- **Track detail** reads the points once (D-27): with a recording in the background a 50 000-point track no longer
  rebuilds every second — 17 % CPU and a 65–85 ms frame per second → 0.8 % and no frames.
- **Idle Record screen** draws nothing (D-28): 8 % CPU and a frame per second → 0 %. The recording dot on the tab
  blinks once a second and the followed map moves without a one-second animation per fix (D-29).
- **Line simplified for the zoom** (ADR-26): Douglas–Peucker per piece below 0.4 px, mean speed colour, taps refined
  to the full line. Scrubbing a 50 000-point track: janky 90 % → 55 %, p90 101 → 85 ms.
- Stop computes the statistics off the main thread; History and Statistics skip equal database results; the
  Offline maps screen reads the catalogue once and the free space on the IO pool.
- **Baseline profile** shipped in the APK: cold start median 660 → 633 ms (`StartupBenchmark`).
- The unused Mapsforge default render theme is no longer packaged.

### Fixed
- The recording notification kept the old units or language until the next recording (D-26); it now follows a
  change at once, and its builder and pending intents are made once per settings.
- A tapped section of the line came back on the next recording with the previous track's values, and stayed on a
  vertex that a dropped stray start had removed (D-30, since 1.0); a tap now belongs to the line it was made on.

### Changed (no behaviour change)
- Recording rules moved from `TrackingService` into a pure `domain/recording/TrackRecorder` with 13 golden tests
  written before the move (ADR-27); one live-state update per fix.
- View models get the dependencies they use instead of the whole `AppContainer`: `TrackingControl`, `GpxExporter`,
  injectable DataStore and `AppDispatchers`; `ArchitectureTest` checks the layer rules on the sources (ADR-28).
- `SpeedMath` shared by the statistics pass, the running totals and the live speed; `MapTiles` gives the map its
  tile configuration; `TrackMap` split into camera / overlays / composable; Record and Detail screens split by
  part; `OfflineRegionStore` cleanup; statistics summaries in the domain (`TrackSummaries`); dead code removed.
- Build: lint fails on any new warning; unused libraries removed; core-ktx 1.19.1, navigation 2.10.2;
  kotlinx-serialization aligned with Room 2.8; `.editorconfig`.

### Tests
- 220 JVM tests: the 55 Places nearby tests are gone, `SpeedProfile` tests moved to `TrackLineTest`; new —
  `TrackLineTest` 11, `TrackRecorderTest` 13, `LiveTrackLineTest` 6, `LineSimplifierTest` 4, `TrackSummariesTest` 3,
  `ArchitectureTest` 5, `TrackRepositoryTest` 4, view models 8, `CopyAtMostTest` 3, settings migration 2, region row 2,
  catalogue URLs, pace colour scale.
- 4 instrumented Room tests (`TrackDaoTest`, `MigrationTest` 1 → 2); `:baselineprofile` module (profile generator,
  startup benchmark); `tools/perf` (synthetic long tracks, CPU and frame measurements).

### Docs
- `07_security.md` §9 (audit 1.1.2, MASVS, accepted risks), STRIDE, checklist, policy 1.2; ADR-24…29 (ADR-07…10 and
  ADR-21 cancelled, ADR-22 narrowed); UC-06/07 and NFR-11/21 retired, NFR-12/22 narrowed, NFR-25…27; TC-131…146,
  D-26…30, OBS-16…19; stage J11; PRD, UX, user guide, README, site, RuStore materials; store screenshots 1, 2 and 6
  retaken on the release build (no debug tile grid, no place pins).

## JustTracker [1.1.1] - 2026-10-05 (versionCode 5)

Security audit of the app, build and CI (`docs/07_security.md` §8: SEC-01…SEC-13, OWASP MASVS v2). No critical
issues; one high, four medium and eight low findings fixed. No new permissions.

### Security / privacy
- **Places along a saved track no longer reveal its start and end** (SEC-02, ADR-21): Overpass used to get the
  track as up to 80 vertices rounded to ~11 m, always including the exact start and finish (usually home or
  work). It now gets only the grid-cell centers (0.005°, as on the recording screen) of the track without its
  first and last 300 m and without any point within 300 m of the start or finish; the query radius grows to
  800 m and the answer is filtered on the device to places within 400 m of the real line.
- **No APK on GitHub releases** (SEC-01, ADR-23): the tag release attached the debug build — debuggable
  (`run-as` exposes the track database), logging coordinates, signed with a throw-away CI key. Releases now
  carry notes only; users install from RuStore; the debug APK stays a CI artifact for developers.
- **Release is never silently debug-signed** (SEC-03): without `keystore.properties` the release build is
  unsigned; the debug key needs `-PallowDebugSignedRelease=true` and prints a warning (the D-24 cause).
- **Network perimeter** (SEC-07, ADR-22): HTTPS to `overpass-api.de` and `(*.)wikipedia.org` only, default port,
  no credentials in URLs, re-checked on every redirect; GET follows at most 3 redirects by hand (Wikipedia
  redirects renamed titles), POST follows none.
- **Strict Wikipedia links** (SEC-06): the page URL from a Wikipedia answer is accepted only if it parses as
  `https://(*.)wikipedia.org` without userinfo or port — the substring check accepted
  `https://evil.example/.wikipedia.org/`; links open with `CATEGORY_BROWSABLE`.
- **One User-Agent without device details** (SEC-04): `JustTracker/<version> (<site>)` for Overpass, Wikipedia,
  OSM tiles and region downloads — DownloadManager used to send the Android release and the device model.
- **Download-complete broadcasts only from the system** (SEC-09): the receiver requires
  `SEND_DOWNLOAD_COMPLETED_INTENTS`.
- **Nothing in backups or device transfer** (SEC-10): every data domain is excluded from cloud backup and
  device-to-device transfer (with targetSdk 31+ `allowBackup=false` does not stop the latter everywhere).
- **GPX copies are removed** (SEC-11) together with their track and at the latest 24 h after export (also on app
  start), not only on the next export.
- **Release logs without exception text** (SEC-12): warnings and errors name the exception class only — no file
  path with the track name, document URI or server answer; no region file names.
- **Downloaded offline maps are checked against the catalogue** (SEC-13): a header box that does not overlap the
  catalogue box or differs in area by more than 20× marks the file as corrupt.

### Fixed
- A deeply nested Overpass/Wikipedia answer could crash the app (`StackOverflowError` of Android's recursive
  `org.json` on the main thread, SEC-08); JSON is now parsed off the main thread and such input is a parse error.
- The Track detail places pipeline (up to 100 000 points mapped and compared) ran on the main thread.
- CI named the debug artifact `JustTracker-versionName-debug.apk` (D-25); the step now reads the real version,
  checks its format and that a tag matches it.

### Changed
- Gradle wrapper regenerated for 9.6.1 with `distributionSha256Sum`; CI validates wrappers; third-party actions
  (`android-actions/setup-android`, `gradle/actions/setup-gradle`) pinned by commit SHA; the release job uses
  the preinstalled `gh` instead of `softprops/action-gh-release` (SEC-05).
- Privacy policy 1.1 (RU/EN): track cells without the ends, Wikipedia requests by automatic announcements,
  Offline map mode for a map without network, device transfer disabled, GPX copy retention. RuStore data-safety
  texts updated accordingly.

### Docs
- `07_security.md` §8 (findings, MASVS v2 coverage, accepted risks, owner actions), STRIDE and checklist
  verified against the code; ADR-21…23; NFR-21…24; TC-115…130, D-25, OBS-14/15; release process and README:
  install from RuStore only.

### Tests
- 36 new unit tests (219 in total): track query cells and trimming, segment distance and the along-line filter,
  host policy and redirects, Wikipedia URL checks, parser guard, User-Agent, release log line, GPX copy cleanup,
  region header plausibility against the real headers of all bundled regions.

## JustTracker [1.1.0] - 2026-10-04 (versionCode 4)

### Added
- **Acceleration indicator on the recording screen** (US-23, ADR-20): tap the speed to show or hide a row
  with the current along-track (horizontal) acceleration — an arrow (speeding up / slowing down / steady /
  standing still), the signed value in m/s² (ft/s² for imperial units) and the last 60 seconds as bars,
  up while speeding up and down while slowing down. Hidden by default; the choice is remembered. In
  landscape the row sits beside the speed, so the panel does not grow. A one-time tooltip on the first
  recording points to it. TalkBack reads the value in words.
- Acceleration is the slope of a weighted least-squares fit of the receiver's Doppler ground speed over the
  last 4 s (weights from the reported speed accuracy), with outlier, gap and standstill rules and direction
  hysteresis — no accelerometer, no new permissions, nothing stored. Without a usable Doppler speed
  (network fixes, a receiver reporting 0 while moving, pause) it shows "—".

### Fixed
- **Live speed stuck after a stop** (OBS-12): the speed on the panel and in the notification came only
  from points stored in the track; the "moved ≥ 2 m or 30 s passed" storage rule skipped every second or
  third fix at walking pace and kept the last moving value for up to 30 s after stopping. It now follows
  the Doppler speed of every fix and drops to ~0 within 3–4 s; track statistics are unchanged.

### Tests
- 32 new unit tests (183 in total): acceleration estimator, last-minute history, live speed and
  acceleration, sign and units.

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

### Release / signing
- **New signing key for RuStore** (D-24): 1.0.2 is signed with the release key `justtracker-release.jks`
  (`CN=JustTracker`, SHA-256 `BC:B7:0F:…:87:09`). 1.0.0 had reached RuStore signed with a developer machine's
  debug key (`CN=Android Debug`, SHA-256 `DA:F3:A5:1D:…:9E:60`): the uploaded release APK was built before
  `keystore.properties` existed, and without it the release build falls back to the debug key. RuStore therefore
  reported that the key does not match 1.0.0. 1.0.0 had no users, so the app moves to the release key instead of
  keeping the debug one; anyone who installed 1.0.0 has to uninstall it (export tracks to GPX first) and install 1.0.2.
- Gradle task `verifyReleaseKey` (runs before `preReleaseBuild` when `keystore.properties` exists) fails the build
  when the key's certificate differs from the published one (`releaseCertSha256`); release procedure in
  `docs/09_release_rustore.md` §1.1 adds an `apksigner verify --print-certs` check before every upload.
- "What's new" for the RuStore upload of 1.0.2 includes the 1.0.1 changes: 1.0.1 never reached RuStore.
- Pre-upload check `docs/09_release_rustore.md` §1.5, referenced by the store and test-plan checklists: build,
  signature/package/version of the APK, the release APK on a device including an update over the published
  version (new TC-104), a release archive with the APK and `mapping.txt`, upload rules (stop on a key warning).

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
