# Changelog

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
