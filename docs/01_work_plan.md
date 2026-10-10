# JustTracker — план работ (техлид)

## 1. Цель и рамки

JustTracker — ответвление TrekLog 1.2.0 (история TrekLog сохранена в git; этапы 0–13 ниже относятся к TrekLog и оставлены для контекста). Цель ответвления — выпустить в **RuStore** версию **JustTracker 1.0.0**: «просто трекер» с ключевой ценностью *простота и автономность* — без аккаунтов и бэкенда, без сервисов Google, с офлайн-картами регионов, явным выбором языка, по Material Design 3.

## 2. Этапы

| # | Этап | Роли | Результат | Оценка, ч |
|---|------|------|-----------|-----------|
| 0 | Промпты и план | Техлид, PO | `00_team_prompts.md`, `01_work_plan.md` | 2 |
| 1 | Исследование и требования | БА, PO, Сис. аналитик | `02_market_research.md`, `03_prd.md`, `06_system_analysis.md` | 12 |
| 2 | Проектирование | Дизайнер, Архитектор, Безопасность | `04_ux_design.md`, `05_architecture.md`, `07_security.md` | 14 |
| 3 | Скелет проекта и сборка | Техлид | Gradle-проект, version catalog, CI-скрипт сборки | 4 |
| 4 | Data-слой | Разработчик | Room (Track, TrackPoint), DAO, репозитории | 8 |
| 5 | Domain-слой | Разработчик, Тестировщик | Алгоритмы: дистанция, скорость, высота, фильтр, классификатор, GPX; unit-тесты | 10 |
| 6 | TrackingService | Разработчик | Foreground service, FLP, уведомление, restart-safe | 10 |
| 7 | UI: запись + карта | Разработчик, Дизайнер | Экран записи, карта osmdroid, панель метрик, разрешения | 12 |
| 8 | UI: история, детали, статистика, настройки | Разработчик | Список, карта трека, редактирование, экспорт, сводка | 12 |
| 9 | Тестирование | Тестировщик | `08_test_plan.md`, прогон кейсов, баг-фиксы | 12 |
| 10 | Безопасность и релиз-подготовка | Безопасность, Техлид | Проверка чек-листа, R8, подпись, AAB | 4 |
| 11 | Документация и публикация | Тех. писатель, PO | `09_release_rustore.md`, `10_user_guide.md`, README, листинг | 8 |
| | **Итого 1.0** | | | **108** |
| 12 | 1.1 «Интересное рядом» (ступень 1 + А, минимум; **удалено в 1.1.2**, J11) | Все роли | POI-маркеры (Overpass + Wikipedia), карточка + TTS, авто-озвучка opt-in; обновление PRD/UX/архитектуры/безопасности/тестов/гайда | 16 |
| 13 | 1.2 «Время записи и скорость по участкам» | Все роли | Время записи как основное время (панель, история, детали, хронометр в уведомлении); окраска линии по скорости на live-карте и в деталях, легенда, тап по участку; без миграции БД; обновление PRD/UX/архитектуры/тестов/гайда | 8 |
| **J0** | JustTracker: репозиторий и переименование | Техлид | Клон с историей, `com.justtracker.app`, классы/строки/иконка под бренд, versionCode 1 / 1.0.0, CI | 3 |
| **J1** | LocationManager вместо GMS | Разработчик | `PlatformLocationSource` (LocationManagerCompat), `ProviderPolicy` + тест, `FakeLocationSource`, удаление play-services | 3 |
| **J2** | Явный выбор языка ru/en | Дизайнер, Разработчик | `AppLanguage`, ключ `language`, шаг онбординга, пункт настроек, AppCompat per-app locale, `AppLocale` для сервиса/POI/TTS | 6 |
| **J3** | Material Design 3 | Дизайнер, Разработчик | Typography/Shapes, splash, predictive back, `NavigationSuiteScaffold`, инсеты, pinned top bars, toggleable rows, иконка + monochrome | 9 |
| **J4** | Офлайн-карты регионов | Архитектор, Разработчик | Спайк osmdroid-mapsforge; `domain/maps`, каталог, Room `offline_regions`, DownloadManager, `OfflineRegionStore`, `HybridTileProvider`, экран «Офлайн-карты», 18 unit-тестов | 28 |
| **J5** | Готовность к RuStore | Безопасность, Техлид, Тех. писатель | Версии из `-P`, job `release-signed`, GitHub Pages (`site/`), `store/rustore/` (листинг ru/en, иконка 512, скриншоты 9:16, декларации, возраст, модератор) | 6 |
| **J6** | Документация по ролям | Все роли | Обновление `00`–`10`, README, CHANGELOG под JustTracker 1.0.0 | 12 |
| **J7** | Явный режим карты онлайн/офлайн (US-22) | Все роли | `MapMode` в настройках, диалог, окно при смене связности (`ConnectivityObserver`, `MapModeController`, `MapModeAdvisor` + тесты), провайдер на `MapTileProviderBasic` (фикс пустой карты при старте), бейдж, документация | 6 |
| | **Итого JustTracker 1.0.0** | | | **73** |
| **J8** | 1.0.1: карточка трека, скелетоны, плавная офлайн-карта | Все роли | Фикс открытия деталей (D-11), сворачиваемая панель статистики, `Shimmer` + скелетоны на 5 экранах, `HybridTileProvider` на `MapTileProviderArray` + `OfflineRenderer`/`OfflineRegionModule`/`OfflineTiles` (D-12, ADR-17), Mapsforge напрямую, 6 unit-тестов, документация, версия 1.0.1 (versionCode 2) | 12 |
| | **Итого JustTracker 1.0.1** | | | **85** |
| **J9** | 1.0.2: высота, ориентация экрана, GPX | Все роли | Разбор GPX пользователя; `ElevationCalculator` (сегменты, фильтр точности, удаление «залипаний», окно по пути, точки разворота) + пересчёт старых треков, колонка `verticalAccuracyM` (схема v2, AutoMigration); фикс зависания при повороте (D-13: безопасное вписывание, `configChanges`, камера в `rememberSaveable`), landscape-раскладки (rail, детали в две панели, панель записи ≤ 640 dp, прокрутка онбординга/диалогов/листов, диалоги переживают пересоздание); GPX по схеме (Garmin TrackPointExtension v2, миллисекунды, `bounds`, имя файла на кириллице); повторное тестирование ориентации на эмуляторе по всем сценариям и исправление найденного (D-17…D-23: серая карта после поворота, клавиатура над кнопками переименования, запись на 0 м при далёкой первой точке, обрезанная дата «Начало», крупный шрифт, курсор после смерти процесса, отладочная сетка тайлов); 33 unit-теста (XSD-валидация, зум NaN при вписывании, первая точка сегмента, строки сетки); документация; версия 1.0.2 (versionCode 3) | 16 |
| | **Итого JustTracker 1.0.2** | | | **101** |
| **J10** | 1.1.0: индикатор ускорения, живая скорость | Все роли | Исследование (локально, `research/`, вне git); `AccelerationEstimator` (взвешенная регрессия доплеровской скорости по окну 4 с, гистерезис) + `AccelerationHistory` + `LiveMotion`; скорость по каждому фиксу (OBS-12); `LiveTrackingState` (ускорение, свежесть, минута); блок скорости — кнопка, строка ускорения со стрелкой, значением м/с²/фут/с² и графиком 60 с, раскладка в landscape, однократная подсказка, ключи `show_acceleration`/`acceleration_hint_shown`; 32 unit-теста; проверка на эмуляторе (`geo fix` со скоростью); документация (US-23, UX §2.12, ADR-20, SA §3.10/NFR-20, TC-105…114); версия 1.1.0 (versionCode 4) | 31 |
| | **Итого JustTracker 1.1.0** | | | **132** |
| — | 1.1.1: аудит безопасности (SEC-01…13) | Все роли | Выполнен вне плана этапов: `07_security.md` §8, CHANGELOG 1.1.1 | — |
| **J11** | 1.1.2: приватность, производительность, рефакторинг | Все роли | Три аудита (архитектура, безопасность, производительность) против документации; **удаление «Интересного рядом»** — в онлайн-режиме сеть только для карты (SEC-14, ADR-24); аудит безопасности 1.1.2 (SEC-15…20); измерительная база (`tools/perf`, StrictMode, trace-секции); `TrackLine` и хвостовая загрузка live-линии, детали без пересчёта, упрощение по зуму, простой без кадров (ADR-25, ADR-26, D-26…D-30); `TrackRecorder` с «золотыми» тестами, явные зависимости, `MapTiles`, разбиение экранов и карты (ADR-27, ADR-28); тесты ViewModel/репозитория, androidTest Room, baseline profile + Macrobenchmark, lint без предупреждений, `ArchitectureTest`; захват трафика и замеры на эмуляторе (TC-131…146); документация, политика 1.2; версия 1.1.2 (versionCode 6) | 77 |
| | **Итого JustTracker 1.1.2** (без 1.1.1) | | | **209** |
| **J12** | 1.2.0: ускорение в истории трека | Все роли | План командой из 7 ролей и решения владельца («как скорость + эпизоды», «пересчёт + σv для новых»); общий `AccelerationMath`; `track_points.speedAccuracyMps` (схема 3, AutoMigration 2 → 3); `TrackAcceleration` — доплеровские скорости, центрированное окно, эпизоды с достраиванием до остановки, шкала; `LineColoring` с перекраской на месте, упрощение с границами эпизодов, палитра и легенда; карточка — переключатель «Скорость | Ускорение», ускорение под ползунком, плитки, «Ускорения и замедления» с листом; трек ниже оверлеев; 44 unit- и 3 инструментальных теста, фикстура через настоящий `TrackRecorder`; `make_track_db.py --profile drive`; проверка на эмуляторе (TC-147, 148, 150–155; D-31…37); полевая проверка на телефоне (TC-157): точность скорости — только вес (ADR-31, D-38), «Поиск GPS» по пригодным фиксам (D-39), ускорение среди дополнительных показателей записи и «Макс. ускорение» (D-40), окно живой оценки с запасом на дрожание времени (D-41); документация (US-24, UX §2.13, ADR-30, ADR-31, SA §3.11/NFR-28, безопасность §10); версия 1.2.0 (versionCode 7) | 44 |
| | **Итого JustTracker 1.2.0** | | | **253** |

Критический путь: 3 → 4 → 5 → 6 → 7 → 9 → 10 → 11. Этапы 1–2 могут идти параллельно с 3; этап 8 параллелен с 7 после готовности 4–5.

## 3. Декомпозиция задач разработки

```
T-01  Gradle-скелет: settings/build KTS, libs.versions.toml, app-модуль, минимальный MainActivity (Compose)
T-02  Room: TrackEntity, TrackPointEntity, JustTrackerDatabase, DAO с Flow
T-03  TrackRepository (CRUD, активный трек, точки трека)
T-04  domain/geo: haversine, LocationFilter (accuracy/speed/дистанция), ElevationGain
T-05  domain/stats: TrackStatsCalculator (dist, moving/total time, avg/max speed, pace)
T-06  domain/activity: ActivityClassifier (speed distribution → WALK/RUN/BIKE/CAR/OTHER)
T-07  domain/gpx: GpxWriter (GPX 1.1)
T-08  TrackingService: FGS type=location, FusedLocationProvider, actions START/PAUSE/RESUME/STOP, notification
T-09  TrackingController: единая точка управления сервисом; восстановление активного трека при старте
T-10  Permissions: PermissionFlow (fine/coarse → POST_NOTIFICATIONS → background) с rationale-экранами
T-11  RecordScreen: osmdroid MapView в AndroidView, polyline текущего трека, follow-me, панель метрик, кнопки
T-12  HistoryScreen: список треков (LazyColumn), карточки с мини-статистикой, swipe/долгое нажатие → удалить
T-13  TrackDetailScreen: карта с полным треком (fit bounds), статистика, переименование, смена типа, экспорт GPX (FileProvider + Share), удаление
T-14  StatsScreen: сводка по всем трекам, по типам движения, по неделям
T-15  SettingsScreen: единицы (метрические/имперские), тема, порог точности, ссылка на privacy policy
T-16  Локализация en/ru, иконка, splash
T-17  release: R8/proguard-rules, signingConfig из локального properties, `assembleRelease`/`bundleRelease`
T-18  Unit-тесты domain (T-04..T-07), Room in-memory (T-02)

--- 1.1 (ступень 1 + А) ---
T-19  domain/poi: Poi, WikipediaRef (валидация lang/title), GeoCell, OverpassQl, PoiFactory, PoiProximity + unit-тесты
T-20  data/poi: Http (HttpURLConnection, https-only, лимиты), OverpassParser, WikiSummaryParser (+ org.json в testImplementation), PoiRepository (кэш, 15 с, backoff 60 с)
T-21  data/tts: TtsSpeaker (init, язык, аудиофокус, speakingId), <queries> TTS_SERVICE
T-22  UI: TrackMap.pois + onPoiClick (ic_poi_marker), PoiCard + PoiCardViewModel, интеграция в Record/Detail, настройки (2 switch), строки en/ru
T-23  service/PoiAnnouncer (appScope, opt-in), запуск из Application
T-24  Документация 1.1: PRD US-16/17, UX §2.7, архитектура §11 + ADR-07..10, безопасность (угрозы, privacy policy, Data safety), сис. анализ UC-06/07 §3.7, тест-план TC-31..42, гайд, release notes

--- JustTracker 1.0.0 ---
T-25  Fork: git clone с историей, git mv пакетов, замена io.treklog.app → com.justtracker.app, JustTrackerApplication/App/Theme/Database, бренд в строках/UA/GPX/иконке, CI-артефакт
T-26  data/location: PlatformLocationSource (LocationManagerCompat, fused API 31+ → gps → network), ProviderPolicy + ProviderPolicyTest, testing/FakeLocationSource; удалить play-services-location, coroutines-play-services
T-27  i18n: AppLanguage, AppSettings.language, ключ DataStore, AppLocale (current/localized/applyDefault/sync/apply), потребители (PoiRepository, TtsSpeaker, TrackingService, TimeFormat)
T-28  i18n UI: appcompat, AppCompatActivity, Theme.AppCompat.DayNight, locales_config.xml, AppLocalesMetadataHolderService, шаг LANGUAGE в OnboardingScreen, пункт «Язык» в Settings, bundle.language.enableSplit=false
T-29  M3: ui/theme/Type.kt (Typography + tabular()), Shape.kt (Shapes + topOnly()), core-splashscreen + Theme.JustTracker.Starting, enableOnBackInvokedCallback, SystemBarStyle по теме приложения
T-30  M3 nav/insets: NavigationSuiteScaffold (bar / rail), инсеты оверлеев RecordScreen, pinnedScrollBehavior на History/Stats/Settings/Detail, toggleable switch-rows, ic_launcher_monochrome
T-31  maps domain: LatLonBox (ofTile), RegionCoverage, MapSource, MapSourceResolver (MIN_OFFLINE_ZOOM 8), RegionStatus/Error/Source/Event, RegionTransitions + тесты
T-32  maps data: assets/maps/regions.json, RegionCatalogParser (https + host whitelist) + RegionCatalog, OfflineRegionEntity/Dao, MapsDirectory, MapFileInspector, RegionDownloader (DownloadManager) + DownloadCompleteReceiver, OfflineRegionStore (download/cancel/delete/import/onDownloadFinished/reconcile, поллинг прогресса)
T-33  maps render: HybridTileProvider (OfflineRegionModule → filesystem → approximater → downloader), интеграция в TrackMap (пересборка по readyCoverage/языку/теме, повторное INVERT_COLORS), createInstance в Application
T-34  maps UI: OfflineMapsViewModel/Screen (каталог, импорт через OpenDocument, Wi-Fi only, хранилище, диалоги), маршрут offline_maps, секция в Settings, строки maps_* en/ru, proguard mapsforge, backup exclude external/maps
T-35  release: versionCode/versionName из -P, job release-signed (секреты KEYSTORE_*), pages.yml + site/ (privacy RU/EN), store/rustore/* (листинг, make_icon.py, скриншоты, fit_9x16.py, декларации)
T-37  Режим карты (US-22): domain/maps/MapMode (+ MapModeAdvisor + тест), data/network/ConnectivityObserver, data/maps/MapModeController, ключ map_mode, HybridTileProvider : MapTileProviderBasic (ONLINE / OFFLINE + setUseDataConnection(false)), MapModeDialog + MapModePromptDialog, пункт «Режим карты», MapModeBadge на Record/Detail, строки en/ru, документация (PRD US-22, UX §2.11, arch ADR-16, SA UC-11/NFR-16/17, тесты TC-73..82, гайд)
T-36  Документация JustTracker: 00 контекст, 01 план, 02 позиционирование, 03 US-18..21 + §8 RuStore, 04 §2.1/§2.6/§2.10/§3/§6, 05 §7/§12 + ADR-13..15, 06 UC-08..10 + NFR-13/14, 07 угрозы/чек-лист RuStore/privacy, 08 TC-48+, 09_release_rustore, 10 гайд, README, CHANGELOG

--- JustTracker 1.0.1 ---
T-38  Детали трека: поток POI в `TrackDetailViewModel` стартует с пустого списка (`scan`), состояние не ждёт Overpass (D-11); сворачиваемая сетка плиток (`statsVisible`, анимация веса карты 250 мс, строки `detail_hide_stats`/`detail_show_stats`)
T-39  ui/common/Shimmer.kt: `Modifier.shimmer`, `SkeletonBox/Line/Paragraph/StatTile/TrackCard/ListItem`, `SkeletonGroup` (a11y), `rememberSkeletonVisible` (задержка 100 мс); скелетоны в History, TrackDetail, Stats, OfflineMaps, PoiCard; строка `cd_loading`
T-40  domain/maps/OfflineTiles (+ MapFileStamp): tileSizeFor, scaleFor, renderThreads, fingerprint, sourceName; OfflineTilesTest (6)
T-41  data/maps/render: OfflineRenderTheme (тема + DisplayModel на процесс, prewarm масштабов), OfflineRenderer (общий DirectRenderer, пул MultiMapDataStore, ThreadLocal-делегат), OfflineTileSource (RGB_565), OfflineTileWriter (purgeStale), OfflineRegionModule (N потоков, PNG в SQLite-кэш)
T-42  HybridTileProvider : MapTileProviderArray — цепочка как у MapTileProviderBasic + офлайн-модули впереди (и в pre-cache), `isDowngradedMode` для покрытых тайлов, `setUseDataConnection(mode == ONLINE)`; TrackMap: провайдер без ключа `dark`, фильтр темы отдельным эффектом; AppContainer.offlineRenderTheme; AndroidGraphicFactory.createInstance в Application; кэш 300/240 МБ
T-43  Зависимости: `osmdroid-mapsforge` → `mapsforge-map-android`/`mapsforge-map`/`mapsforge-themes` 0.21.0; THIRD_PARTY_NOTICES, site/licenses; версия 1.0.1 / versionCode 2; CHANGELOG, «Что нового» ru/en
T-44  Документация 1.0.1: PRD US-08/US-19, UX §2.4/§7, архитектура §7/§12/ADR-17, SA UC-03/§3.9/NFR-13/NFR-18, тест-план TC-83..90 + D-11/D-12, гайд, README

--- JustTracker 1.0.2 ---
T-45  domain/geo/ElevationCalculator: gainLoss(points) по сегментам — фильтр verticalAccuracyM ≤ 15 м (с откатом при большинстве неточных), spikeFreeMask, smoothAlongPath (±75 м / ±15 с), turningPointGainLoss (6 м); TrackStatsCalculator → gainLoss(points); 13 + 1 unit-тестов (AR(1)-шум, залипания, сегменты)
T-46  data: TrackPoint/TrackPointEntity.verticalAccuracyM, JustTrackerDatabase v2 + AutoMigration(1→2), schemas/2.json, TrackDao.setElevation; TrackingService пишет Location.verticalAccuracyMeters; TrackDetailViewModel — Geometry(segments, elevation) через shareIn, показ пересчитанного набора и однократная запись в строку трека
T-47  Ориентация: TrackMap — fitCamera/fitPadding (≥ 32 dp, отступ по размеру, проверка NaN; TrackMapFitTest — 4), вписывание по OnLayoutChangeListener (150 мс на серию размеров), MapCamera в rememberSaveable, userMoved по касанию; манифест configChanges; JustTrackerApp — rail при ширине ≥ 600 dp (currentWindowAdaptiveInfoV2); TrackDetailScreen — BoxWithConstraints, двухпанельная раскладка, movableContentOf для карты, прокручиваемый лист типов; RecordScreen — панель/баннер ≤ 640 dp; Onboarding/EmptyState — прокрутка с центрированием; MapModeDialog/ChoiceDialog/PoiCard — прокрутка; флаги диалогов History/Settings/OfflineMaps/Detail/Record — rememberSaveable (по id)
T-48  domain/gpx/GpxWriter: xmlns:gpxtpx + xsi:schemaLocation, bounds, время с миллисекундами, speed/course в TrackPointExtension v2, долгота 180 → −180, фильтр нечисловых точек и недопустимых XML-символов, имя файла \p{L}\p{N}; 11 unit-тестов с валидацией по XSD (test/resources/gpx)
T-49  Документация 1.0.2: PRD US-08/US-11 + границы релиза, UX §1/§2.1/§2.2/§2.4/§2.7/§6/§7, архитектура §5/§6/§7/§8/§13/ADR-18/ADR-19, SA UC-03/UC-04/§2/§3.4/§5/NFR-19, безопасность (имя файла), тест-план TC-91..99 + D-13..D-16 + OBS-10/11, гайд, README, CHANGELOG, «Что нового»; версия 1.0.2 (versionCode 3)
T-50  Повторное тестирование ориентации на эмуляторе API 34 (host GPU): все экраны, диалоги, листы, системные диалоги разрешений, пересоздание по теме/языку, смерть процесса, `wm size`, шрифт 150/200 %, онбординг в landscape (данные — резервная копия через `run-as`). Исправлено: `setDestroyMode(false)` в TrackMap (D-17), `ImeAction.Done` в переименовании (D-18), `LocationFilter.confirmStart` + `JumpStreak` + `reanchor` в TrackingService (D-19), `FittedText` и плитка «Начало» на всю строку (`gridRows`, D-20/D-21), курсор в `SavedStateHandle` (`appViewModelWithState`, D-22), без `isDebugTileProviders` (D-23); +11 unit-тестов (LocationFilterTest, StatGridRowsTest); тест-план TC-91…95/98 ✅, TC-100…103
T-51  Выпуск 1.0.2 в RuStore (D-24): 1.0.0 оказалась подписана debug-ключом (сертификат APK из RuStore) — переход на release-ключ `justtracker-release.jks`, задача Gradle `verifyReleaseKey` (сертификат ключа = `releaseCertSha256`); «Что нового» 1.0.2 включает 1.0.1; процедура проверки перед загрузкой — `09_release_rustore.md` §1.5 (сборка, подпись и версия файла, release-APK на устройстве, обновление поверх опубликованной версии — TC-104, архив APK + `mapping.txt`, правила загрузки), чек-листы `store/rustore/README.md` и `08_test_plan.md` §6 ссылаются на неё. Release-APK 1.0.2 — смоук на эмуляторе API 34

--- JustTracker 1.1.0 ---
T-52  Спайк: исследование вариантов (GNSS / IMU / слияние / точки трека), выбор оценщика; эмулятор — `geo fix … <узлы>` даёт `Location.speed` (NMEA на образе API 34 не доходит до Android); полевые записи скорости на реальных устройствах — TC-110
T-53  domain/stats: AccelerationEstimator (окно 4 с, веса 1/σv², выбросы > 12 м/с², разрыв > 3 с, «на месте» < 0,5 м/с, σ ≤ 0,5, гистерезис 0,25/0,12), AccelerationHistory + AccelerationTrace (60 с, масштаб ≥ 1 м/с²); 19 unit-тестов
T-54  service: Session.motion, onLocation кормит LiveMotion до правил хранения (монотонные часы, speedAccuracy, порог точности — LocationFilter.isAccurate), LiveTrackingState.acceleration/accelerationAt/accelerationTrace, сброс на паузе и при остановке
T-55  OBS-12: LiveMotion — живая скорость по доплеровской скорости каждого фикса, недоверие приёмнику на 10 с при противоречии (D-02), уведомление — та же скорость; 10 unit-тестов (вместе с ускорением)
T-56  AppSettings.showAcceleration / accelerationHintShown, ключи DataStore
T-57  UI: SpeedBlock (кнопка, значок show_chart), AccelerationIndicator + график (Canvas), раскладка по ширине колонки (≥ 380 dp — в строку), AccelerationHint (PlainTooltip, однократно), AccelerationColors, UnitFormatter (знак U+2212, м/с² / фут/с², произносимые единицы), TalkBack-узлы, строки en/ru; 3 unit-теста формата
T-58  Тестирование: TC-105…109, 111, 112 на эмуляторе API 34 (портрет/landscape, пауза, смерть процесса, шрифт 200 %, тёмная тема, приёмник без скорости); TC-110, 113, 114 — реальное устройство перед релизом
T-59  Документация: PRD US-23 + US-06, UX §2.2/§2.12/§3/§4/§5, архитектура §2/§3/§4/§6/§9 + ADR-20, SA UC-01 6f/§2/§3.3/§3.10/NFR-20, безопасность §3, тест-план (TC-105…114, OBS-12/13, §2, §6), гайд, README, CHANGELOG, «Что нового», versionCode 4 / 1.1.0

--- JustTracker 1.1.2 (J11) ---
T-60  Аудиты кода против docs и SEC-01…13: архитектура, безопасность, производительность; решение владельца — удалить «Интересное рядом»
T-61  Удаление POI: экраны, сервис, настройки, старт приложения; затем код (Overpass/Wikipedia, Http, HostPolicy, TTS, PoiAnnouncer), ресурсы, <queries>, 55 тестов; RemovedFeatureKeysMigration + тест (SEC-14, ADR-24)
T-62  Безопасность: R8 вырезает все уровни Log, AppLog через Log.println, логгер Mapsforge OFF (SEC-15, ADR-29); импорт карты — проверка места и copyAtMost (SEC-16); https-only ссылки, ClipData для GPX, без EmojiCompatInitializer (SEC-17, SEC-19); MapFileInspector/reconcile/URL каталога (SEC-18); Actions по SHA (SEC-20)
T-63  Измерительная база: StrictMode в debug, traced(), tools/perf (make_track_db.py, measure.py); замеры «до» на эмуляторе
T-64  Дешёвые исправления: distinctUntilChanged, один поток значка записи, finish вне главного потока; D-26 (уведомление), D-28/D-29 (простой, мигание значка, камера); офлайн-карты — каталог один раз, свободное место на IO
T-65  TrackLine + TrackLineBuilder (ADR-25, тест-свойство); карта рисует куски «сегмент ∩ чанк»; LiveTrackLine + pointsAfterIfIntact; детали читают точки один раз (D-27); LineSimplifier по зуму (ADR-26)
T-66  Рефакторинг: явные зависимости ViewModel, TrackingControl, GpxExporter, AppDispatchers, SettingsRepository(DataStore) (ADR-28); TrackRecorder + «золотые» тесты (ADR-27); SpeedMath; MapTiles, TrackMap → камера/оверлеи; экраны записи и деталей по частям; OfflineRegionStore; TrackSummaries; мёртвый код
T-67  Тесты и инструменты: ViewModel и TrackRepository на фейках; androidTest TrackDaoTest, MigrationTest; :baselineprofile (генератор, StartupBenchmark), профиль в release; lint warningsAsErrors, .editorconfig, удаление неиспользуемых библиотек; ArchitectureTest
T-68  Регрессия на эмуляторе: TC-131…146 (трафик debug и release, release-logcat, восстановление, офлайн-регион, экспорт), замеры «после»
T-69  Документация: PRD, UX, архитектура (ADR-24…29), SA (UC-06/07, §3.6, §3.7, NFR-25…27), безопасность (§1–§3, §5–§7, §9, политика 1.2), тест-план, план работ, гайд, README, сайт, RuStore, CHANGELOG, «Что нового»; versionCode 6 / 1.1.2

--- JustTracker 1.2.0 (J12) ---
T-70  План: анализ кода и документов 7 ролями; решения владельца — объём UI «как скорость + эпизоды», данные «пересчёт + σv для новых»   2 ч
T-71  AccelerationMath — одна формула для живой оценки и истории; AccelerationUi в ui/common   2 ч
T-72  Схема 3: TrackPoint/TrackPointEntity.speedAccuracyMps, AutoMigration(2, 3), 3.json, TrackRecorder (σv только к доплеровской скорости); MigrationTest 2 → 3 и 1 → 3, TrackDaoTest   2.5 ч
T-73  TrackAcceleration: отбор скоростей, серии, центрированное окно, гистерезис, эпизоды (подрезка, достраивание до остановки — D-31, D-33), шкала P95; фикстура Recordings через TrackRecorder; 19 тестов   10 ч
T-74  Карта: LineColoring (BySpeed / ByAcceleration), перекраска кусков на месте, LineSimplifier с границами и средними без NaN, AccelerationColorScale, AccelerationLegend; трек ниже оверлеев (fitTopInsetPx, D-34)   5 ч
T-75  Карточка: detail_line_metric, переключатель, ячейка ускорения курсора, плитки, «Разгоны и замедления» (по полевой проверке — «Ускорения и замедления», T-79) + лист, TalkBack, строки en/ru; тесты ViewModel   8 ч
T-76  tools/perf --profile drive; сборка androidTest — раннер явной зависимостью (D-32)   1.5 ч
T-77  Проверка на эмуляторе: TC-147, 148, 150–155 (старт-стоп, обновление со схемы 2, 200 %, landscape, тёмная тема, TalkBack, atrace 100 000 точек), исправления D-31…D-37   4 ч
T-78  Документация: PRD US-24, UX §2.4/§2.8/§2.9/§2.12/§2.13/§3/§4/§5, архитектура §2/§3/§5/§6/§7/§9 + ADR-30 (ADR-20 — «реализовано»), SA UC-03 3a/§2/§3.11/NFR-20/NFR-28, безопасность §1/§3/§10, тест-план, план работ, гайд, README, сайт, RuStore, CHANGELOG, «Что нового»; versionCode 7 / 1.2.0   5 ч
T-79  Полевая проверка TC-157 (поездка 30 км, GPX пользователя): диагностика на точках поездки; AccelerationMath.weight — σv в 0,05…1 м/с без отсева (LiveMotion, TrackAcceleration, ADR-31, D-38); FixOutcome.usable → lastFixAt (D-39); ускорение в дополнительных показателях панели записи (в широкой панели — рядом со скоростью), удаление show_acceleration / acceleration_hint_shown и подсказки, «Макс. ускорение», «Ускорения и замедления», TalkBack эпизодов (D-40); окно живой оценки + 0,25 с (D-41); 5 unit-тестов; проверка на эмуляторе (release), пересъёмка 01/06/07; документация   4 ч
```

## 4. Definition of Done

**Для задачи:**
- код компилируется, `./gradlew :app:testDebugUnitTest` зелёный;
- lint без предупреждений (`warningsAsErrors`, с 1.1.2);
- строки вынесены в ресурсы (en + ru);
- изменения покрыты unit-тестом, если это доменная логика;
- ручной smoke на эмуляторе (для UI/сервисных задач).

**Для релиза JustTracker 1.0.0 (RuStore):**
- все P0-истории PRD (включая US-18, US-19, US-21) приняты по критериям;
- пройден чек-лист регрессии из `08_test_plan.md`, в том числе на образе **без Google-сервисов**;
- пройден чек-лист безопасности / RuStore из `07_security.md` §5;
- собран подписанный release-ключом AAB (и APK), размер ≤ 15 МБ (NFR-03), `mapping.txt` сохранён;
- заполнена карточка RuStore из `store/rustore/` (листинг ru/en, иконка, ≥ 3 скриншота 9:16, категория, возраст, декларации, комментарий модератору);
- политика конфиденциальности доступна по публичному URL (GitHub Pages);
- модерация RuStore пройдена, первые 3 дня — без крэшей по отзывам.

## 5. Соглашения по коду

- **Язык:** Kotlin 2.x, Compose BOM, Coroutines/Flow. Java 17 target.
- **Пакеты:** `com.justtracker.app.{data,domain,service,ui,util,di}`; UI по фичам: `ui.record`, `ui.history`, `ui.detail`, `ui.stats`, `ui.settings`, `ui.maps`, `ui.onboarding`, `ui.common`, `ui.theme`; офлайн-карты — `data.maps` (+ `data.maps.render`), `domain.maps`; запись — `domain.recording`, линия трека — `domain.track`. Экран и его ViewModel — в разных файлах; файл экрана не разрастается за ~500 строк — части выносятся (`RecordPanels`, `TrackDetailParts`).
- **Слои (1.1.2, ADR-28):** `domain` без Android; `data` без `ui`/`service`; `service` без `ui`; UI не импортирует `AppContainer` (кроме `ViewModelSupport`, `JustTrackerApp`, `MainActivity`) и Room-сущности. Проверяет `ArchitectureTest` — правило не обходить, а обсуждать.
- **Локаль:** язык берётся из DataStore (`AppSettings.language`) через `util.AppLocale`; `Locale.getDefault()` напрямую не использовать; ресурсы в Service/Application — через `AppLocale.localized(context, settings)`.
- **Сеть (1.1.2, ADR-24):** приложение обращается только к `tile.openstreetmap.org` (тайлы в режиме «Онлайн») и `download.mapsforge.org` (регион по явной команде); ничего производного от геолокации на серверы не отправляется. Новый хост, запрос или библиотека с сетью — только решением владельца, через ревью безопасности, захват трафика TC-131 и обновление политики конфиденциальности; каталог регионов принимает только `https://download.mapsforge.org/...`.
- **DI:** ручной `AppContainer` в `JustTrackerApplication` (объём MVP не оправдывает Hilt; см. ADR-03). ViewModel получают только свои зависимости через `appViewModel { c -> … }` (ADR-28); диспетчеры — `AppDispatchers`, чтобы тесты подставляли свой.
- **Единицы хранения:** СИ — метры, м/с, секунды/миллисекунды epoch, градусы WGS84. Конвертация только в UI-слое через `UnitFormatter`.
- **Ошибки:** доменные функции не бросают исключения на некорректных данных — возвращают пустой/нулевой результат; IO-ошибки логируются через `Timber`-подобный `Log` обёрткой `AppLog`, в release координаты не логируются. С 1.1.2 R8 вырезает все вызовы `android.util.Log` в release (ADR-29) — писать только через `AppLog`.
- **Состояние:** ViewModel → `StateFlow<UiState>`; сервис пишет только в БД; UI читает только из БД (single source of truth).
- **Ориентация (1.0.2, ADR-18):** поворот не пересоздаёт activity — раскладка строится от размеров (`BoxWithConstraints`, класс ширины окна), ресурсы `-land` не используются; флаги открытых диалогов и листов — `rememberSaveable` (объекты — по id), чтобы переживать пересоздание по теме/языку; всё, что может не поместиться по высоте (≈ 360 dp в landscape), прокручивается.
- **Схема БД:** только через миграции (`AutoMigration` или ручные) и экспортированные `schemas/*.json`; `fallbackToDestructiveMigration` запрещён.
- **Версии:** только через `gradle/libs.versions.toml`.
- **Производительность (1.1.2):** на пути «фикс → экран» — никакой работы O(n) по треку (ADR-25); горячие участки размечены `traced(...)`; карта инвалидируется только при реальном изменении. Изменения записи, карты и деталей сопровождаются замером `tools/perf` против NFR-25…27.
- **Форматирование:** `.editorconfig` (Kotlin official, строка до 140 символов, хвостовые запятые) для новых и изменённых строк; массово существующий код не переформатируется.

## 6. Сборка и инструменты

- Gradle 9.6.1 (wrapper), AGP 9.4.1 со встроенным Kotlin (плагин `kotlin-android` не применяется), Kotlin 2.3.21, KSP 2.3.12 для Room.
- `minSdk 26`, `targetSdk 36`, `compileSdk 37` (актуальные AndroidX-библиотеки требуют compileSdk 37).
- Release: `isMinifyEnabled = true`, `isShrinkResources = true`, правила для osmdroid/mapsforge/Room; `bundle.language.enableSplit = false` (оба языка в каждой установке).
- Подпись: `keystore.properties` (в `.gitignore`); если файла нет — release не подписывается (с 1.1.1, SEC-03), debug-ключ — только явно `-PallowDebugSignedRelease=true` для локальной проверки (в RuStore не загружать — так ушла 1.0.0, D-24). **Один release-ключ навсегда** — RuStore не переподписывает и не хранит ключ; с 1.0.2 это `justtracker-release.jks` (SHA-256 `BC:B7:0F:…:87:09`), задача `verifyReleaseKey` сверяет сертификат перед каждой release-сборкой. В CI ключ приходит из секретов `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (job `release-signed` на тег `v*`).
- Версия: `-PversionCode=N -PversionName=X.Y.Z` переопределяют значения из `build.gradle.kts`.
- Зависимости без Google: `play-services-*` запрещены (ADR-14); `androidx.appcompat` нужен для per-app locale; `EmojiCompatInitializer` удалён из манифеста (SEC-19).
- Lint (1.1.2): `warningsAsErrors = true` — любое новое предупреждение валит сборку; `GradleDependency` — информационное (обновления версий — осознанным коммитом).
- Baseline Profile (1.1.2): модуль `:baselineprofile` (`com.android.test`, Macrobenchmark, UiAutomator); профиль генерируется вручную на эмуляторе API 34 и коммитится в `app/src/release/generated/baselineProfiles/`, автогенерация при сборке выключена; release-подобные сборки генератора и бенчмарка (`nonMinifiedRelease`, `benchmarkRelease`) получают пакет с суффиксом `.benchmark`, чтобы не задевать установленное приложение.
- Команды:
  - `.\gradlew.bat :app:assembleDebug`
  - `.\gradlew.bat :app:testDebugUnitTest`
  - `.\gradlew.bat :app:lintDebug`
  - `.\gradlew.bat :app:bundleRelease` / `:app:assembleRelease`
  - `python store/rustore/make_icon.py` — иконка 512 для карточки
  - `.\gradlew.bat :app:connectedDebugAndroidTest` — Room на эмуляторе (удаляет установленную debug-сборку с данными)
  - `.\gradlew.bat :app:generateReleaseBaselineProfile` — новый профиль после заметных изменений UI
  - `.\gradlew.bat :baselineprofile:connectedBenchmarkReleaseAndroidTest` — холодный старт с профилем и без
  - `python tools/perf/make_track_db.py --points 10000 --status RECORDING` и `python tools/perf/measure.py` — длинный трек и замер CPU/кадров (`08_test_plan.md` §2)

## 7. Проверки перед merge

1. Unit-тесты зелёные (в том числе `ArchitectureTest`).
2. `lintDebug` без предупреждений (с 1.1.2 предупреждение — ошибка).
3. Smoke: старт записи → 30 с симулированного движения → стоп → трек в истории → открыть детали → экспорт GPX.
4. Ревью по чек-листу безопасности для изменений в манифесте/сервисе/экспорте/receiver **и для любого нового сетевого хоста**.
5. Для изменений в карте/локали — smoke на эмуляторе без Google APIs (AOSP) и проверка офлайн-региона в авиарежиме.
6. Для изменений UI — каждый затронутый экран в портрете и landscape телефона, поворот туда и обратно (в т. ч. с открытым диалогом), без ANR (TC-91..95).
7. (1.1.2) **Любое изменение, касающееся сети** — новая библиотека, обновление osmdroid/Mapsforge, новый хост или запрос: захват трафика TC-131 до merge. «Онлайн» — только тайлы, «Офлайн» — 0 байт, регион — только по команде; release-logcat без строк библиотек (TC-133).
8. (1.1.2) Изменения DAO или схемы — `connectedDebugAndroidTest`; изменения записи, карты и деталей — замер `tools/perf` без регрессии NFR-25…27.

## 8. Риски и митигация

| Риск | Влияние | Митигация |
|------|---------|-----------|
| Ограничения фоновой геолокации на Android 14+ (FGS type location) | Запись останавливается | Тип сервиса `location`, `FOREGROUND_SERVICE_LOCATION`, запуск сервиса только из foreground-UI |
| Отклонение в Play по политике background location | Задержка релиза | Не запрашивать `ACCESS_BACKGROUND_LOCATION` в MVP: FGS с типом location работает при выключенном экране без него; задокументировать |
| Расход батареи | Плохие отзывы | Интервал 1–2 с только при записи, `PRIORITY_HIGH_ACCURACY`, остановка при STOP, тест на реальном устройстве |
| Шум GPS в городе | Кривые треки, завышенная дистанция | Фильтр по accuracy и правдоподобной скорости, минимальное смещение |
| Google Play требует targetSdk 36 | Блок публикации | targetSdk 36 / compileSdk 37 с самого начала |
| Несовместимость Mapsforge 0.21 с новыми V5-файлами | Офлайн-карты не открываются | Спайк подтвердил чтение V5 (malta.map 2026-07); рендер теперь собственный (`data/maps/render`, ADR-17) и зависит только от `org.mapsforge:*` — обновление библиотеки не требует ждать osmdroid-mapsforge |
| Размер файлов регионов (0,1–1,8 ГБ), мобильный трафик | Жалобы, отказ от функции | Wi-Fi only по умолчанию, размер в диалоге, проверка свободного места, DownloadManager с докачкой |
| download.mapsforge.org недоступен / изменил раскладку | Каталог не работает | Каталог в assets с явными URL; импорт своего `.map` как обход; при переезде мирора — обновление каталога релизом |
| Потеря release-ключа | Невозможно обновить приложение в RuStore | Ключ и пароли в менеджере паролей + секреты GitHub; инструкция в `09_release_rustore.md` |
| Устройства без GMS (Huawei/Honor) | Геолокация не работает при зависимости от GMS | ADR-14: только платформенный LocationManager; регресс на образе AOSP |
| Регресс приватности через библиотеку или обновление (новые логи, сетевые вызовы, сервисы Google) | Нарушение политики и декларации RuStore | R8 вырезает `android.util.Log` (ADR-29); захват трафика TC-131 и release-logcat TC-133 перед merge и релизом; ревью итогового манифеста (SEC-19) |
| Деградация на длинных треках (100 000 точек) | Нагрев, расход батареи, рывки | Хвостовая загрузка и куски линии (ADR-25), упрощение по зуму (ADR-26), замеры `tools/perf` против NFR-25…27 |
| Фоновый рендер офлайн-тайлов (pre-cache кольца и зума −1) во время записи | Расход батареи/нагрев на слабых устройствах | Потоки рендера — ½ ядер (2–4), писатель кэша — MIN_PRIORITY, тайлы RGB_565; TC-90 на реальном устройстве; при жалобах — отключать pre-cache офлайн-модуля во время записи |
