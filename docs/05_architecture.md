# JustTracker — архитектура (архитектор)

## 1. Стек и обоснование

| Компонент | Выбор | Обоснование |
|-----------|-------|-------------|
| Язык / UI | Kotlin 2.3 (встроенный в AGP 9), Jetpack Compose (Material 3, BOM 2026.09) | Стандарт Android, декларативный UI, быстрые итерации |
| Хранилище | Room 2.8 + Flow | Реактивные запросы, миграции, SQLite надёжна для 100k точек |
| Настройки | DataStore Preferences | Асинхронно, без SharedPreferences-гонок |
| Геолокация | **Платформенный `LocationManager`** через `LocationManagerCompat` (без GMS) | Работа на устройствах без Google-сервисов (RuStore-аудитория, Huawei/Honor, AOSP); на Android 12+ используется системный fused-провайдер, ниже — GPS. ADR-14 |
| Карта | **osmdroid** (OpenStreetMap) + **Mapsforge 0.21** напрямую (`mapsforge-map-android`, `mapsforge-map`, `mapsforge-themes`; офлайн-регионы) | Без API-ключа и биллинга, свободная лицензия, кэш тайлов из коробки. Офлайн — векторные `.map`-файлы Mapsforge, скачиваемые по регионам (ADR-13): массовая загрузка растровых тайлов OSM запрещена политикой OSMF. Рендер тайлов — собственный многопоточный (`OfflineRenderer`, ADR-17) вместо однопоточного `osmdroid-mapsforge`. Google Maps SDK потребовал бы ключ, Cloud-проект и GMS |
| Локаль | AppCompat per-app locales (`AppCompatActivity`, `AppLocalesMetadataHolderService`) | Явный выбор языка ru/en, сохраняется на API 26–32 силами AppCompat, на 33+ — системным LocaleManager. ADR-15 |
| Фоновая работа | Foreground Service (`foregroundServiceType="location"`) | Единственный поддерживаемый способ непрерывной записи GPS на Android 14+ без background-permission |
| DI | Ручной `AppContainer` | ~10 зависимостей; Hilt добавил бы kapt/ksp-время и сложность без выгоды (ADR-03) |
| Асинхронность | Coroutines + Flow | |
| Тесты | JUnit 4, kotlinx-coroutines-test, Room in-memory (Robolectric не нужен для домена) | |

## 2. Слои

```mermaid
flowchart TB
  subgraph UI
    RecordScreen --> RecordViewModel
    HistoryScreen --> HistoryViewModel
    DetailScreen --> DetailViewModel
    StatsScreen --> StatsViewModel
    SettingsScreen --> SettingsViewModel
  end
  subgraph Domain
    TrackStatsCalculator
    LocationFilter
    ActivityClassifier
    ElevationCalculator
    GpxWriter
    Models[Track, TrackPoint, ActivityType]
  end
  subgraph Data
    TrackRepository --> JustTrackerDatabase[(Room)]
    SettingsRepository --> DataStore[(DataStore)]
    LocationSource --> LM[android.location.LocationManager]
  end
  subgraph Service
    TrackingService --> LocationSource
    TrackingService --> LocationFilter
    TrackingService --> TrackRepository
    TrackingController --> TrackingService
  end
  RecordViewModel --> TrackingController
  RecordViewModel --> TrackRepository
  HistoryViewModel --> TrackRepository
  DetailViewModel --> TrackRepository
  DetailViewModel --> GpxWriter
  DetailViewModel --> TrackStatsCalculator
  StatsViewModel --> TrackRepository
  Domain -.-> |нет зависимостей от Android| Domain
```

Правила:
- `domain` — чистый Kotlin (без `android.*`), тестируется JVM-тестами.
- `data` знает про Room/DataStore/FLP, отдаёт доменные модели.
- `service` зависит от `data` и `domain`, не от `ui`.
- `ui` зависит от `domain`, `data` (через репозитории) и `service` (только `TrackingController`).
- **Single source of truth — БД.** Сервис пишет точки в Room; UI наблюдает `Flow` из Room. Никаких колбэков сервис→UI, никакого Binder-состояния: это делает UI устойчивым к пересозданию Activity и убийству процесса.

## 3. Структура пакетов

```
com.justtracker.app
├── JustTrackerApplication.kt        // создаёт AppContainer
├── di/AppContainer.kt
├── data/
│   ├── db/ (JustTrackerDatabase, TrackDao, TrackEntity, TrackPointEntity, OfflineRegionEntity + Dao)
│   ├── repo/ (TrackRepository, SettingsRepository)
│   ├── location/ (LocationSource, PlatformLocationSource, ProviderPolicy)
│   ├── maps/ (RegionCatalog + Parser, MapsDirectory, MapFileInspector, RegionDownloader + DownloadCompleteReceiver, OfflineRegionStore, MapModeController)
│   │   └── render/ (HybridTileProvider, OfflineRenderTheme + OfflineRenderer, OfflineRegionModule, OfflineTileSource, OfflineTileWriter)
│   ├── network/ (ConnectivityObserver)
│   ├── poi/ (Http, OverpassParser, WikiSummaryParser, PoiRepository)   // 1.1
│   └── tts/ (TtsSpeaker)                                                // 1.1
├── domain/
│   ├── model/ (Track, TrackPoint, ActivityType, TrackStatus, TrackStats, UnitSystem, AppLanguage, AppSettings)
│   ├── maps/ (LatLonBox, RegionCoverage, MapSource + MapSourceResolver, OfflineTiles + MapFileStamp, OfflineRegion, RegionStatus/Error/Source/Event, RegionTransitions)
│   ├── geo/ (Geo.kt — haversine, LocationFilter, ElevationCalculator)
│   ├── stats/ (TrackStatsCalculator)
│   ├── activity/ (ActivityClassifier)
│   ├── gpx/ (GpxWriter)
│   └── poi/ (Poi, WikipediaRef, GeoCell, OverpassQl, PoiFactory, PoiProximity)  // 1.1
├── service/ (TrackingService, TrackingController, TrackingNotification, PoiAnnouncer)
├── ui/
│   ├── MainActivity.kt (AppCompatActivity, splash), JustTrackerApp.kt (NavigationSuiteScaffold + NavHost)
│   ├── theme/ (Theme.kt, Type.kt, Shape.kt)
│   ├── maps/ (OfflineMapsScreen, OfflineMapsViewModel)
│   ├── common/ (StatTile, ActivityIcon, TrackMap, Shimmer — скелетоны загрузки, UnitFormatter, PermissionHelper)
│   ├── poi/ (PoiCard, PoiCardViewModel)                                  // 1.1
│   ├── onboarding/, record/, history/, detail/, stats/, settings/
└── util/ (AppLog, TimeFormat, AppLocale, UnitFormatter)
```

## 4. TrackingService

```mermaid
stateDiagram-v2
  [*] --> Idle
  Idle --> Recording : ACTION_START (создать Track, startForeground)
  Recording --> Paused : ACTION_PAUSE (unsubscribe FLP)
  Paused --> Recording : ACTION_RESUME (segment++, subscribe)
  Recording --> Finished : ACTION_STOP (finalize stats, stopSelf)
  Paused --> Finished : ACTION_STOP
  Finished --> [*]
  Idle --> Recording : onStartCommand(null intent) && БД содержит RECORDING/PAUSED трек (восстановление, segment++)
```

Ключевые решения:
- `startForegroundService()` вызывается только из UI (foreground-контекст), внутри `onStartCommand` — `startForeground(id, notification, FOREGROUND_SERVICE_TYPE_LOCATION)` в первые 5 с.
- `START_STICKY`; при перезапуске с `null`-intent сервис проверяет БД и восстанавливает запись.
- Геолокация: `PlatformLocationSource` — `LocationRequestCompat.Builder(1000).setQuality(QUALITY_HIGH_ACCURACY).setMinUpdateIntervalMillis(500).setMinUpdateDistanceMeters(0)`, провайдер по `ProviderPolicy` (`fused` на API 31+ → `gps` → `network`), колбэк на main executor; `SecurityException` закрывает flow — сервис реагирует как раньше. Запись в БД через `serviceScope`.
- Локаль сервиса: строки (автоимя, уведомление) и `UnitFormatter` берутся из `AppLocale.localized(this, cachedSettings)` — на API < 33 контекст Service следует системной локали, а не выбранной в приложении.
- Уведомление (канал `tracking`, IMPORTANCE_LOW): текст «3.4 km · 12 km/h», время записи — системный хронометр (`setUsesChronometer(true)`, `setWhen(startedAt)`), который тикает без обновлений уведомления; действия Пауза/Продолжить и Стоп (PendingIntent на сервис), тап — открыть MainActivity.
- Первая точка сегмента (1.0.2): хранится в `Session.pendingStart` и записывается, только когда следующая точка её подтверждает (`LocationFilter.confirmStart`, правило 6 §3.1 спецификации); «нереальный скачок» от неё заменяет её следующей. Серия из 5 согласованных между собой точек, каждая из которых — скачок от последней записанной (`JumpStreak`, правило 7), переносит трек (`reanchor`): короткий сегмент-выброс (≤ 3 точек) удаляется из БД (`deleteSegmentIfShort`), иначе начинается новый сегмент. Пауза сбрасывает и ожидающую точку, и счётчик серии. До этого неверная первая точка (грубая/устаревшая позиция) навсегда оставляла запись на 0 м (D-19).
- Инкрементальная статистика (`IncrementalStats`, in-memory) обновляется на каждой принятой точке и записывается в строку `Track` в одной транзакции со вставкой точки (`insertPointAndUpdateTrack`) — при 1 Гц это дёшево, а после смерти процесса сервис восстанавливает счётчики из строки трека без пересчёта всех точек. Полный пересчёт — только при завершении.
- Doze: FGS с типом location освобождён от ограничений на локацию; `WakeLock` не нужен (FLP держит GPS). Опционально пользователь может отключить оптимизацию батареи — в MVP не запрашиваем.

## 5. Модель данных (Room)

```mermaid
erDiagram
  TRACKS ||--o{ TRACK_POINTS : has
  TRACKS {
    long id PK
    string name
    string status
    string activityType
    boolean activityManual
    long startedAt
    long finishedAt
    double distanceM
    long movingTimeMs
    long totalTimeMs
    long pausedTimeMs
    double avgSpeedMps
    double maxSpeedMps
    double elevationGainM
    double elevationLossM
    int pointCount
  }
  TRACK_POINTS {
    long id PK
    long trackId FK
    int segment
    long timestamp
    double lat
    double lon
    double altitudeM
    float accuracyM
    float speedMps
    float bearingDeg
    float verticalAccuracyM
  }
```
Индексы: `track_points(trackId, timestamp)`, `tracks(status)`, `tracks(startedAt)`. `exportSchema = true` (каталог `schemas/`). **Версия схемы 2** (1.0.2): nullable-колонка `track_points.verticalAccuracyM` (вертикальная точность высоты — вход фильтра набора высоты, §6) добавлена `AutoMigration(from = 1, to = 2)`; миграция — один `ALTER TABLE … ADD COLUMN`, существующие точки получают `null`. Следующие изменения схемы — так же через экспортированные JSON и автоматические/ручные миграции, без `fallbackToDestructiveMigration`.

**Время записи** (1.2) — производная величина `Track.recordingTimeMs(now) = (finishedAt ?: now) − startedAt`, не хранится: старые строки корректны без миграции. `totalTimeMs` (время записи без пауз) и `pausedTimeMs` сохраняются как есть. **Скорость по участкам** — `track_points.speedMps` каждой точки (`effectiveSpeed` на момент записи), сглаживается при отображении (`SpeedProfile`).

## 6. Алгоритмы (реализация в domain)

- `Geo.distanceMeters(lat1, lon1, lat2, lon2)` — haversine.
- `LocationFilter.accept(prev, next, maxAccuracy)` — §3.1 спецификации; чистая функция, возвращает `FilterResult(accepted, reason)`.
- `TrackStatsCalculator.calculate(points, pausedTimeMs, finishedAt)` — полный пересчёт при завершении и в деталях; `IncrementalStats` — для сервиса.
- `SpeedProfile.of(points)` — попточечно: сглаженная скорость (та же `smoothedSpeeds`, что и для макс. скорости), накопленная дистанция, timestamp и высота; основа для окраски линии, карточки «Скорость на участке» и курсора по треку в деталях.
- `ElevationCalculator.gainLoss(points)` (1.0.2, ADR-19) — по сегментам: фильтр по вертикальной точности → удаление «залипаний» (скачок ≥ 8 м за ≤ 3 с с возвратом) → среднее в окне ±75 м пути (не меньше ±15 с) → гистерезис 6 м по точкам разворота; формулы — `06_system_analysis.md` §3.4. `TrackDetailViewModel` показывает набор/сброс, пересчитанные по точкам, и один раз записывает их в строку трека, если они отличаются (треки до 1.0.2).
- `ActivityClassifier.classify(speeds)` — перцентили → тип.
- `GpxWriter.write(track, points, out: Appendable)` — GPX 1.1, валидный по схеме (`06_system_analysis.md` §5).

## 7. Карта

`TrackMap` — Compose-обёртка над `osmdroid.MapView` через `AndroidView`. Параметры: список сегментов `PathSegment` (вершины + параллельные массивы скорости/дистанции/времени, собираются `TrackPath.build` на `Dispatchers.Default`), `maxSpeedMps` (верх шкалы цвета), текущее положение, `highlight` (тапнутая вершина), режим follow, колбэки `onUserGesture` и `onTrackTap(segment, index)`, список `pois` + `onPoiClick`. Слои: `TilesOverlay` (Mapnik), `Polyline` на сегмент с `PolychromaticPaintList` (цвет вершины из `SpeedColorScale`; массив скоростей и максимум подменяются на месте, поэтому рост live-трека и максимума не требуют пересоздания оверлея), `Marker` для положения/старт/финиш/выделения, `Marker` с иконкой `ic_poi_marker` на каждый POI (diff по id, маркер положения всегда сверху). Тап по линии → ближайшая вершина (equirectangular) → ViewModel разрешает её в `TrackTapInfo` (экран записи: карточка с авто-скрытием) или переносит курсор `TrackCursor` (детали: слайдер по дистанции, `TrackPath.indexForFraction` / `cursorAt` — глобальный индекс вершины по всем сегментам; скорость, расстояние, время с начала записи, время суток, высота). Онлайн-тайлы кэшируются osmdroid в `cacheDir/osmdroid` (не в external storage — без дополнительных разрешений). `User-Agent` = applicationId (требование политики OSM tile usage).

**Камера и вписывание трека (1.0.2).** `MapView.zoomToBoundingBox` считает зум из размера вида минус два отступа; если вид меньше этого (карта деталей в landscape — 45 % высоты ≈ 50 dp при отступе 48 dp, split screen, вид ещё без размера), зум получается NaN, и `Projection.getCloserPixel` крутится в главном потоке бесконечно — так «зависало» приложение после поворота (D-13). Теперь `fitCamera` вписывает трек только при размере вида ≥ 32 dp по обеим сторонам, уменьшает отступ до помещающегося и проверяет, что зум конечен; вписывание идёт из слушателя изменения размера (первая раскладка, поворот, split screen, сворачивание статистики — серия размеров анимации сводится к одному вписыванию через 150 мс), а не через `post` до раскладки. Пока пользователь не двигал карту (касание в пределах 600 мс до смены камеры), каждый новый размер заново вписывает трек. Камера (центр, зум, «двигал ли пользователь») хранится в `rememberSaveable` и переживает пересоздание activity (тема, язык, смерть процесса).

**Провайдер тайлов** — `HybridTileProvider : MapTileProviderArray` (§12, ADR-16, ADR-17). Собирает ту же цепочку, что штатный `MapTileProviderBasic` osmdroid (assets → SQLite-кэш → архивы → аппроксимация из младших зумов → загрузчик MAPNIK, с защитными вычислителями LRU-кэша и pre-cache для кольца вокруг экрана и зума −1): «голый» массив без этой настройки вытеснял тайлы до отрисовки и скачивал их по кругу — карта при запуске оставалась пустой (D-09). Цепочка собирается вручную, а не наследуется, чтобы в режиме OFFLINE офлайн-модули стояли первыми **и в pre-cache**: иначе закэшированные онлайн-тайлы подмешивались бы к отрендеренным. По режиму карты (`AppSettings.mapMode`): **ONLINE** — цепочка как есть, регионы не используются; **OFFLINE** — впереди стоят SQLite-кэш отрендеренных тайлов → аппроксимация из него → `OfflineRegionModule` (Mapsforge, только тайлы внутри READY-регионов, зум ≥ 8), а `setUseDataConnection(false)` отключает загрузчик, поэтому непокрытые тайлы берутся лишь из кэша/аппроксимации. `isDowngradedMode` переопределён: тайл, который покрывает рендер, никогда не считается «лучше не будет» — иначе osmdroid навсегда оставлял масштабированный плейсхолдер после зума (причина «карты кусками», D-12). `TrackMap` пересобирает провайдер в `DisposableEffect(mapMode, offlineFiles, language)`; `MapView.setTileProvider` создаёт новый `TilesOverlay`, поэтому фильтр `INVERT_COLORS` применяется повторно, а смена темы (`LaunchedEffect(dark)`) меняет только фильтр, не трогая провайдер и его тайлы. Тёмная тема карты — по теме приложения (`colorScheme.background.luminance()`), а не только по системной.

## 8. Экспорт GPX

`TrackDetailViewModel.buildShareIntent()` → `GpxWriter` (потоково, UTF-8) → файл `<имя>_<id>.gpx` в `cacheDir/exports` → `FileProvider` (`${applicationId}.fileprovider`, `cache-path exports/`) → `Intent.ACTION_SEND` (`application/gpx+xml`) с `FLAG_GRANT_READ_URI_PERMISSION`. Документ валиден по схеме GPX 1.1: скорость и курс точки — в пространстве имён Garmin TrackPointExtension v2 (`gpxtpx:`), время — с миллисекундами, если они есть, в метаданных — `<bounds>`; формат — `06_system_analysis.md` §5. Имя файла сохраняет буквы любого алфавита.
## 9. Стратегия тестирования

| Уровень | Что | Инструмент |
|---------|-----|------------|
| Unit (JVM) | Geo, LocationFilter, ElevationCalculator (шум, «залипания», сегменты, фильтр точности, точки разворота), TrackStatsCalculator, SpeedProfile/TrackPath, SpeedColorScale, Track.recordingTimeMs, ActivityClassifier, GpxWriter (валидация по XSD GPX 1.1 + TrackPointExtension v2), `fitPadding` вписывания трека (зум osmdroid `TileSystemWebMercator` конечен), UnitFormatter | JUnit4, `javax.xml.validation` |
| Unit (JVM), JustTracker | ProviderPolicy, AppLanguage, LatLonBox (ofTile), MapSourceResolver, OfflineTiles (размер тайла, потоки, отпечаток набора регионов), RegionTransitions, DownloadManager reason → RegionError, RegionCatalogParser (+ валидность `assets/maps/regions.json`), MapModeAdvisor | JUnit4 |
| Integration | TrackDao + TrackRepository, OfflineRegionDao | Room in-memory (androidTest) |
| UI | Навигация, состояния Record | Compose UI test (androidTest), в MVP — минимально |
| Ручное | Сервис при выключенном экране, kill процесса, отзыв разрешений; загрузка/импорт региона, рендер в авиарежиме (зум-аут: плейсхолдеры заменяются рендером, повторный просмотр — из кэша); смена языка на API 26–32 и 33+; образ без Google APIs; открытие деталей трека без сети / при перегруженном Overpass; повороты на всех экранах, с открытыми диалогами и во время записи (`adb shell settings put system user_rotation 0|1`) | Эмулятор + `adb`, реальное устройство |

## 11. Интересное рядом (POI + TTS, релиз 1.1)

```mermaid
flowchart LR
  LIVE[TrackingController.live<br/>lastLat/lastLon] --> CELL[GeoCell.of<br/>сетка 0.005°]
  CELL -->|distinctUntilChanged| REPO[PoiRepository]
  REPO -->|cache miss, ≥15 с между запросами| OVP[(Overpass API<br/>overpass-api.de)]
  OVP --> PARSE[OverpassParser → PoiFactory]
  PARSE --> REPO
  REPO --> RVM[RecordViewModel.pois]
  REPO --> DVM[TrackDetailViewModel.pois<br/>around:400 вдоль полилинии]
  RVM & DVM --> MAP[TrackMap: Marker на POI]
  MAP -->|tap| CARD[PoiCardViewModel]
  CARD --> REPO2[PoiRepository.summary]
  REPO2 --> WIKI[(Wikipedia REST<br/>page/summary)]
  CARD --> TTS[TtsSpeaker]
  LIVE --> ANN[PoiAnnouncer<br/>appScope, ≤150 м, 1 раз/трек]
  ANN --> REPO
  ANN --> TTS
```

**Источник данных.** Overpass QL: `nwr["wikipedia"]["name"][!"boundary"][!"admin_level"](around:R, …); out center 200;` — только объекты со статьёй (гарантирует, что есть что прочитать) и именем; административные границы исключены. На экране записи запрос строится вокруг **центра ячейки сетки** (`GeoCell`, 0.005° ≈ 550 м), R = 1500 м; в деталях — вокруг упрощённой полилинии трека (≤ 80 вершин, 4 знака после запятой), R = 400 м.

**Описание.** Wikipedia REST `GET https://{lang}.wikipedia.org/api/rest_v1/page/summary/{title}` → поле `extract` (лид-секция, plain text), `lang`, `content_urls.mobile.page`. Язык и заголовок берутся из тега `wikipedia:<язык устройства>`, иначе `wikipedia`; `lang` валидируется регулярным выражением `^[a-z]{2,3}(-[a-z]{2,10})?$` до подстановки в hostname.

**Кэш и вежливость к публичным серверам** (`PoiRepository`): LRU 32 ячейки / 8 треков, TTL 30 мин; описания — LRU 64 на время жизни процесса; запросы к Overpass сериализованы `Mutex`, интервал ≥ 15 с, после любой ошибки (429/504/офлайн) — пауза 60 с. `mapLatest` во ViewModel отменяет запрос при смене ячейки. **POI никогда не задерживают экран**: и в `RecordViewModel`, и в `TrackDetailViewModel` поток меток начинается с пустого списка (`scan(emptyList())`), так что `combine` состояния эмитит, как только прочитаны трек и точки; недоступный результат оставляет прежний список. До этого детали трека ждали ответа Overpass (с его паузами 15/60 с) и «не открывались» (D-11).

**TTS.** `TtsSpeaker` (process-wide, в `AppContainer`): `TextToSpeech` + `UtteranceProgressListener` → `StateFlow<speakingId>`; аудиофокус `AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK` c `USAGE_ASSISTANCE_NAVIGATION_GUIDANCE`. Ручное чтение — `QUEUE_FLUSH`, авто-объявления — `QUEUE_ADD`. Требуется `<queries><intent action=TTS_SERVICE>` (package visibility, Android 11+).

**Авто-озвучка.** `PoiAnnouncer` живёт в `appScope`, а не во ViewModel: подписан на `settingsFlow` × `trackingController.live`, активен только при `poiEnabled && poiAutoSpeak && serviceRunning`. Использует кэш ячейки (при промахе — одиночный запрос), `PoiProximity.nextToAnnounce` (≤ 150 м, ближайший, не объявленный), множество объявленных сбрасывается при старте новой записи. Работает при выключенном экране, пока FGS держит процесс; в Doze (устройство неподвижно) сеть может быть отложена — в этом случае объект будет объявлен при следующем фиксе.

## 12. Офлайн-регионы (JustTracker 1.0.0)

```mermaid
flowchart LR
  UI[OfflineMapsScreen / ViewModel] --> Store[OfflineRegionStore]
  Store --> Catalog[RegionCatalog<br/>assets/maps/regions.json]
  Store --> DM[RegionDownloader<br/>android.app.DownloadManager]
  Store --> Dao[(offline_regions)]
  Store --> Insp[MapFileInspector<br/>MapFile header → bbox]
  DM -. ACTION_DOWNLOAD_COMPLETE .-> Rcv[DownloadCompleteReceiver] --> Store
  Store -- readyCoverage --> Map[TrackMap → HybridTileProvider]
  Map --> MF[MapsForgeTileSource<br/>файлы READY-регионов]
```

**Каталог.** `assets/maps/regions.json` — id, имена ru/en, https-URL на `download.mapsforge.org`, размер, приблизительный bbox. Парсер отбрасывает всё, что не https и не на разрешённом хосте (`RegionCatalogParser.ALLOWED_HOSTS`), и дубли id. Bbox из каталога — только для отображения; после загрузки границы берутся из заголовка файла.

**Хранение.** Файлы — `context.getExternalFilesDir("maps")` (единственное место, куда пишет DownloadManager; без разрешений; удаляется с приложением; исключено из backup). Без внешнего хранилища — `filesDir/maps` и только импорт. Строка в Room-таблице `offline_regions` (id, имена, fileName, size, bbox, source CATALOG|IMPORT, status, downloadId, errorReason, updatedAt).

**State machine** (`RegionTransitions`, чистая функция, тест):
```
(absent) --download--> QUEUED --running--> DOWNLOADING --success--> VERIFYING --header ok--> READY
QUEUED/DOWNLOADING --failed--> ERROR ; --cancel--> (absent)      VERIFYING --corrupt--> ERROR
ERROR --retry--> QUEUED       READY --delete--> (absent)          (absent) --import--> VERIFYING --ok--> READY
```
DownloadManager сам докачивает, ждёт Wi-Fi (`NETWORK_WIFI`, `setAllowedOverMetered(false)`) и показывает уведомление; прогресс опрашивается (`query()`) раз в секунду, пока есть активные строки; завершение приходит через manifest-receiver (работает и при мёртвом процессе). `reconcile()` при старте: READY без файла → удалить; QUEUED/DOWNLOADING без загрузки в DM → ERROR(LOST); VERIFYING → проверить; `.map` без строки → принять как IMPORT; `.part` без строки → удалить. Перед загрузкой — проверка свободного места (размер + 200 МБ).

**Режим карты и связность.** `ConnectivityObserver` (`data/network`) держит `StateFlow<Boolean> online` из `registerDefaultNetworkCallback` по аргументам колбэков (`onCapabilitiesChanged` → INTERNET+VALIDATED, `onLost` → false; `activeNetwork` в момент `onLost` ещё может указывать на исчезающую сеть). `MapModeController` (`data/maps`) объединяет режим из DataStore (`map_mode`), `online` и наличие READY-регионов через чистый `MapModeAdvisor.suggest(mode, online, hasRegions, declinedFor)` → `StateFlow<MapModeSuggestion?>`; `JustTrackerApp` показывает по нему `MapModePromptDialog`. Режим меняется только через `setMode` (диалог настроек `MapModeDialog` или подтверждение окна); `dismissSuggestion()` запоминает состояние сети, для которого пользователь отказался, — окно молчит до следующего изменения связности.

**Рендер (ADR-17).** Собственный конвейер над Mapsforge 0.21 вместо `osmdroid-mapsforge`:
- `OfflineRenderTheme` (один на процесс, `AppContainer.offlineRenderTheme`, создаётся лениво при первом офлайн-провайдере): `DisplayModel` с фиксированным размером тайла и `userScaleFactor = tileSize/256`, `RenderThemeFuture` (тема `InternalRenderTheme.OSMARENDER`) — XML темы и её символы парсятся один раз в фоновом потоке; сразу после парсинга масштабы штрихов/шрифтов «прогреваются» для всех зумов 0–22, чтобы потоки рендера читали per-zoom карты темы без записи. `AndroidGraphicFactory.createInstance(app)` — один раз в `Application`.
- `OfflineRenderer` (один на провайдер, т. е. на экран с картой): общий `DirectRenderer` (размещение подписей через границы тайлов консистентно) и **пул `MultiMapDataStore`** (по одному `MapFile` на регион, `RETURN_ALL`), из которого каждый одновременный `render(index)` берёт свой экземпляр: `MapFile` хранит несинхронизированный кэш индекса, а `MapsForgeTileSource.renderTile` библиотеки был `synchronized` — рендерил один поток. `render` отдаёт `Bitmap` RGB_565; `close()` закрывает простаивающие хранилища сразу, занятые — при возврате в пул.
- `OfflineRegionModule` — `MapTileModuleProviderBase` с `OfflineTiles.renderThreads(cores)` потоками (½ ядер, 2–4): `loadTile` → проверка покрытия (`MapSourceResolver.resolveForTile`, `MIN_OFFLINE_ZOOM = 8`) → `renderer.render` → тайл отдаётся карте и параллельно ставится в очередь единственного низкоприоритетного потока-писателя, который кодирует PNG и кладёт его в общий SQLite-кэш osmdroid (`OfflineTileWriter : SqlTileWriter`, срок 30 дней) под именем источника `OfflineTiles.sourceName(fingerprint)`. Повторный просмотр области и аппроксимация следующего зума идут с диска; лимит кэша (общий с онлайн-тайлами) — 300 МБ, обрезка до 240 МБ.
- **Отпечаток набора регионов** (`OfflineTiles.fingerprint`: путь + размер + mtime каждого READY-файла, язык подписей, размер тайла → SHA-256/16 hex) входит в имя источника: удалённый, перезакачанный регион или другой язык дают новое пространство имён, а строки старых пространств удаляются при создании провайдера (`purgeStale`) — «призрачных» тайлов нет.
- **Размер тайла и масштаб темы** — `OfflineTiles.tileSizeFor(density)`: 512 px при плотности ≥ 1.5 (иначе 256); масштаб темы `tileSize/256`. osmdroid рисует любой тайл в квадрате `256·density` px, так что 512-px тайл на экране 1080×2400 растягивается в 1,4 раза вместо 2,75 — подписи и линии чёткие. Важно: `AndroidGraphicFactory.createInstance()` выставляет статический `DisplayModel.deviceScaleFactor = density`, и старый рендер масштабировал тему по плотности *и* растягивал тайл по DPI — подписи выходили в `density` раз крупнее нужного (OBS-05). Теперь `userScaleFactor = (tileSize/256) / deviceScaleFactor`, итоговый масштаб темы — ровно `tileSize/256`, визуальные размеры как у онлайн-карты. Память: RGB_565, ~0,5 МБ на тайл в LRU-кэше osmdroid, ~60 КБ PNG на диске.
- Известное ограничение: обзорные зумы < 8 в OFFLINE по-прежнему только из кэша/аппроксимации; тёмная тема — инверсия цветов (OBS-06).

## 13. Ориентация экрана (1.0.2, ADR-18)

`MainActivity` объявляет `android:configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`: поворот, split screen и изменение размера окна обрабатывает Compose без пересоздания activity — `MapView` с загруженными фрагментами, провайдер тайлов с потоками рендера, камера, ViewModel-и и диалоги остаются. Тема и язык по-прежнему пересоздают activity (AppCompat per-app locale); на этот путь рассчитаны сохранение камеры карты и открытых диалогов (`rememberSaveable`) и защищённое вписывание трека (§7).

Раскладки: навигация — rail при ширине окна ≥ 600 dp (телефон в landscape, планшет), bar — при компактной ширине; карточка трека в широком невысоком окне (ширина > высоты и ≥ 560 dp) — карта слева на всю высоту, справа прокручиваемая панель шириной 42 % (300–420 dp); карта переносится между раскладками через `movableContentOf` без пересоздания; панель записи и баннер GPS ограничены шириной 640 dp (макс. ширина M3 bottom sheet); онбординг, диалоги и нижние листы прокручиваются, если не помещаются (`BoxWithConstraints` + `verticalScroll` + `heightIn(min = maxHeight)` — веса центрируют содержимое, пока оно помещается).

Проверено при повторном тестировании 1.0.2 на эмуляторе (все экраны, диалоги и листы в обеих ориентациях, пересоздание по теме и языку, смерть процесса, изменение размера окна, шрифт 150–200 %); найденное исправлено:
- **`movableContentOf` отсоединяет `MapView` от окна** при переносе между раскладками. osmdroid по умолчанию уничтожает себя в `onDetachedFromWindow` (провайдер тайлов и оверлеи) — после поворота карта деталей оставалась серой (D-17). `TrackMap` создаёт вид с `setDestroyMode(false)`, а `onDetach()` вызывает `DisposableEffect`, когда карта действительно уходит из композиции.
- **Смерть процесса в фоне** восстанавливает стек навигации, диалоги, камеру и панель статистики (`rememberSaveable`), а позиция курсора карточки трека жила только во ViewModel и сбрасывалась на старт (D-22): теперь она в `SavedStateHandle` (`appViewModelWithState` передаёт его из `CreationExtras` записи навигации).
- **Текст в узких ячейках при крупном шрифте** (D-20, D-21): значения и подписи, которые обязаны уложиться в одну строку (плитки статистики, значения под курсором, подписи навигации, значение с единицей на панели записи), — `FittedText`: одна строка, при нехватке ширины шрифт уменьшается до 60 % (`TextAutoSize.StepBased`), затем «…». `maxLines = 1` без этого молча обрезал «23 сент. 2026 г., 20:36» до «23 сент.», а переносимые подписи ломались посреди слова. Такой текст нельзя класть под `IntrinsicSize`: собственная высота авто-размерного текста считается от текущего (уже уменьшенного) размера, и он сжимается до неё. Плитка «Начало» занимает всю строку сетки (`GridItemSpan(maxLineSpan)`, в landscape — `gridRows`).

## 10. ADR

**ADR-01. osmdroid вместо Google Maps SDK.** Контекст: MVP без бэкенда и биллинга. Решение: osmdroid. Последствия: нет спутникового слоя и Google-стиля; зато нет ключей, квот и Cloud-проекта. Обёртка `TrackMap` изолирует замену.

**ADR-02. Foreground Service вместо WorkManager/`ACCESS_BACKGROUND_LOCATION`.** WorkManager не даёт секундного интервала; background-permission требует отдельной декларации и часто отклоняется. FGS с типом `location` работает при выключенном экране, пока пользователь явно запустил запись.

**ADR-03. Ручной DI.** Один модуль, ~10 объектов. Hilt увеличит время сборки и порог входа. При росте до 3+ модулей — пересмотреть.

**ADR-04. БД как единственный источник истины между сервисом и UI.** Убирает класс ошибок с Binder-жизненным циклом и восстановлением после смерти процесса.

**ADR-05. Хранение точек в СИ, конвертация в UI.** Упрощает алгоритмы и тесты; единицы — чисто презентационное решение.

**ADR-06. Без шифрования БД в MVP.** Данные в приватном каталоге приложения, защищены песочницей ОС; `allowBackup=false` исключает утечку через облачный бэкап. SQLCipher добавляет 5+ МБ и усложняет Room; отложено (см. `07_security.md`).

**ADR-07. Overpass + Wikipedia REST вместо единого Wikipedia GeoSearch.** Контекст: нужны объекты «вокруг трека», а не вокруг точки. Overpass поддерживает `around` вдоль полилинии и даёт категорию (теги OSM) и локализованные имена; Wikipedia REST `page/summary` отдаёт готовый plain-text лид без парсинга wikitext. Последствия: два публичных сервера с лимитами — обязательны кэш, интервалы и backoff; при недоступности функция тихо деградирует.

**ADR-08. HttpURLConnection + org.json вместо OkHttp/Retrofit/Moshi.** Два GET/POST-эндпоинта не оправдывают +1,5 МБ и новые ProGuard-правила (NFR-03). Для JVM-тестов парсеров подключён реальный `org.json:json` (стаб Android SDK бросает «not mocked»).

**ADR-09. Привязка запроса к сетке 0.005°.** Точное положение пользователя не покидает устройство: Overpass получает центр ячейки ≈ 550 м; радиус 1500 м гарантирует покрытие ≥ 1,2 км вокруг реального положения. Побочный эффект — один запрос на ячейку (кэш), что также снижает нагрузку на сервер.

**ADR-11. Время записи — производная, а не новая колонка.** Контекст: основным временем становится интервал «Старт → Стоп» с паузами. Хранить его отдельно — дублировать `startedAt`/`finishedAt` и требовать миграцию с обратным заполнением. Решение: `Track.recordingTimeMs(now)` вычисляется из существующих полей; `totalTimeMs` (без пауз) остаётся в схеме для совместимости и возможного отображения. Последствия: нет миграции, старая история корректна; live-значение тикает по тикеру UI, а не по точкам GPS.

**ADR-12. Окраска линии через `PolychromaticPaintList`, а не набор полилиний.** Контекст: нужна скорость по участкам на live-карте до 100 000 точек. Отдельная `Polyline` на участок — тысячи оверлеев и пересоздание при каждом обновлении. Решение: одна `Polyline` на сегмент с `ColorMapping` по индексу вершины; сглаженные скорости считаются в `SpeedProfile` на `Dispatchers.Default` (O(n) на эмиссию, 1 Гц). Градиент между вершинами выключен (`useGradient=false`) — избегаем аллокации `LinearGradient` на каждый отрезок в каждом кадре.

**ADR-13. Офлайн-карты — файлы регионов Mapsforge, а не кэш растровых тайлов.** Контекст: политика OSMF прямо запрещает «download city/country for offline» с `tile.openstreetmap.org`; osmdroid `CacheManager` бросает `TileSourcePolicyException` для MAPNIK. Альтернативы: платный тайл-провайдер (Thunderforest Small Business+), свой тайл-сервер, MapLibre + собственные PMTiles-выгрузки (нужен хостинг). Решение: `osmdroid-mapsforge` + готовые региональные `.map` с `download.mapsforge.org` (ODbL) — один HTTP-файл на регион, без ключей, серверов и юридических рисков; онлайн-режим остаётся MAPNIK с обычным кэшем. Последствия: файлы 0,03–1,8 ГБ (Wi-Fi only, контроль места), CPU-рендер на устройстве (обзорные зумы < 8 — онлайн), зависимость от одного зеркала (обход — импорт своего файла), Mapsforge 0.21 подключён напрямую; адаптер `osmdroid-mapsforge` заменён собственным рендером (ADR-17).

**ADR-14. Платформенный LocationManager вместо Fused Location Provider (GMS).** Контекст: ценность «автономность», аудитория RuStore включает устройства без Google-сервисов, на которых `FusedLocationProviderClient` не работает. Решение: `PlatformLocationSource` на `LocationManagerCompat` (core-ktx), провайдер `fused` (AOSP, API 31+) → `gps` → `network`; интерфейс `LocationSource` не изменился, сервис не тронут. Последствия: удалены `play-services-location` и `kotlinx-coroutines-play-services` (−GMS, −размер); на старых устройствах без AOSP-fused первый фикс медленнее (только GPS) — приемлемо для трекера, который и так пишет GPS.

**ADR-15. Per-app locale через AppCompat + дублирование выбора в DataStore.** Контекст: язык должен выбираться явно и работать на API 26+. `AppCompatDelegate.setApplicationLocales` — единственный официальный backport (на 33+ делегирует системному LocaleManager), но требует `AppCompatActivity` и AppCompat-тему, а на API 26–32 меняет локаль только activity-контекстов. Решение: выбор хранится в DataStore (`language`) как источник истины; `AppLocale` синхронизирует AppCompat (`sync`), держит `Locale.getDefault()` (`applyDefault`) и даёт локализованный контекст сервису (`localized`). Последствия: `MainActivity : AppCompatActivity`, тема `Theme.AppCompat.DayNight.NoActionBar`, `locales_config.xml`, `AppLocalesMetadataHolderService`; activity пересоздаётся при смене языка; варианта «системный» нет по продуктовому решению.

**ADR-16. Явный режим карты вместо неявной гибридной маршрутизации.** Контекст: первая версия выбирала источник по тайлу автоматически (регион → кэш → сеть), пользователь не знал и не управлял тем, откуда карта; требование продукта — явное переключение онлайн/офлайн в настройках и по подтверждению при потере/возврате сети. Решение: `MapMode {ONLINE, OFFLINE}` в DataStore как единственный источник истины; провайдер строится по режиму (`HybridTileProvider.create(mode)`); связность только *предлагает* смену через окно, никогда не переключает сама; в OFFLINE сеть для карты выключена (`setUseDataConnection(false)`). Побочный результат: провайдер переведён на `MapTileProviderBasic`, что устранило бесконечную перезагрузку тайлов при старте. Последствия: в ONLINE регионы не используются даже без сети до подтверждения; окно при потере сети не показывается, если регионов нет; POI-функция от режима не зависит.

**ADR-17. Собственный многопоточный рендер офлайн-тайлов вместо `osmdroid-mapsforge`.** Контекст: в режиме OFFLINE карта при уменьшении масштаба «собиралась кусками», а после зума оставалась размытой. Причины (D-12): (1) `MapTileProviderBasic.isDowngradedMode` при выключенном соединении отдаёт масштабированный/просроченный тайл из памяти навсегда, не запрашивая рендер; (2) `MapsForgeTileSource.renderTile` — `synchronized`, один поток рендера на любом числе ядер; (3) рендер не кэшировался на диске — каждый возврат в район рендерил заново; (4) тайлы 256 px растягивались по DPI в 2,5–3 раза, а тема Mapsforge к тому же уже была масштабирована по плотности (`AndroidGraphicFactory` задаёт `deviceScaleFactor`) — подписи в `density` раз крупнее нужного. Альтернативы: `Parameters.NUMBER_OF_THREADS` mapsforge — не влияет на путь osmdroid; MapLibre/векторные тайлы — другой формат файлов и хостинг. Решение: `HybridTileProvider` на `MapTileProviderArray` с переопределённым `isDowngradedMode` (покрытый тайл всегда перерисовывается), `OfflineRenderer` с общим `DirectRenderer` и пулом `MapFile` на поток, `OfflineRegionModule` с 2–4 потоками, запись PNG в SQLite-кэш osmdroid под именем с отпечатком набора регионов (`OfflineTiles`), pre-cache кольца и зума −1 из офлайн-модуля, тайлы 512 px на плотных экранах. Последствия: `osmdroid-mapsforge` исключён из зависимостей (Mapsforge подключён напрямую, лицензионные уведомления упрощены до одной LGPL-библиотеки); +~0,5 МБ памяти на тайл в LRU и +CPU на pre-render соседних тайлов (как у онлайн-загрузки); кэш на диске делится с онлайн-тайлами (300/240 МБ). Проверяемая логика (размер тайла, число потоков, отпечаток) вынесена в чистый `OfflineTiles` с unit-тестами.

**ADR-18. Поворот экрана без пересоздания activity (1.0.2).** Контекст: после поворота «портрет → landscape → портрет» приложение зависало (D-13): пересозданная карточка трека вписывала трек в карту высотой ≈ 50 dp с отступами 2 × 48 dp, osmdroid получал зум NaN и зацикливался в главном потоке; кроме того, каждый поворот пересоздавал `MapView` и провайдер тайлов (потоки рендера, повторная загрузка фрагментов) и сбрасывал камеру. Альтернативы: только чинить вписывание и сохранять состояние (пересоздание остаётся дорогим и мигающим); зафиксировать портретную ориентацию (теряется landscape, против требований M3 и доступности). Решение: `configChanges` для ориентации и размера окна — Compose перестраивает раскладку сам; вписывание трека защищено от малых размеров и выполняется по изменению размера; камера и диалоги сохраняются через `rememberSaveable` на случай пересоздания по теме/языку/смерти процесса; landscape-раскладки (rail, карта рядом с панелью деталей, ограничение ширины панели записи, прокрутка онбординга и диалогов). Последствия: код не должен рассчитывать на пересоздание при повороте (ресурсы `-land` не используются); AppCompat сохраняет выбранный язык при обработке `onConfigurationChanged` (проверено на API 34; API 26–32 — TC-51/TC-91 на устройстве).

**ADR-19. Набор высоты: сглаживание вдоль пути, удаление «залипаний», гистерезис по точкам разворота (1.0.2).** Контекст: пользователь получил «+134 / −125 м» за пробежку 11 км по почти ровному городу; разбор его GPX показал, что расчёт в точности воспроизводит эти числа, а завышение дают шум высоты GPS (окно 5 точек ≈ 5 с короче времени корреляции ошибки) и «залипания» приёмника (+12…20 м за 1 с, держатся 20–160 с). Альтернативы: барометр (есть не у всех телефонов, новый датчик в сервисе, калибровка — кандидат на будущее), цифровая модель рельефа (нужна сеть или гигабайты данных, противоречит автономности), только увеличить порог (теряет реальные подъёмы). Решение: `ElevationCalculator` по сегментам — фильтр вертикальной точности (новая колонка, схема v2), удаление экскурсий со скачком ≥ 8 м за ≤ 3 с и возвратом, среднее в окне ±75 м пути (не меньше ±15 с), гистерезис 6 м по точкам разворота; параметры подобраны на синтетике с AR(1)-шумом и на треке пользователя (+55 / −41 м при реальных ≈ +50 / −45). Последствия: ровные треки получают на 70–80 % меньше ложного набора, реальные подъёмы учитываются на 90–100 %, короткие холмы < 6 м и неровности короче ~100 м не считаются; старые треки пересчитываются при открытии карточки.

**ADR-10. PoiAnnouncer в application scope, а не в ViewModel/сервисе.** ViewModel умирает с back stack; встраивание в `TrackingService` смешало бы запись с сетью/TTS. Отдельный объект, подписанный на те же потоки, что и UI, при выключенном экране живёт благодаря FGS. Пересмотреть, если появится требование гарантированной озвучки в Doze (тогда — внутрь сервиса с wakelock на время запроса).
