# Changelog

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
