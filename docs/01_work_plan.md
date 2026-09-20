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
| 12 | 1.1 «Интересное рядом» (ступень 1 + А, минимум) | Все роли | POI-маркеры (Overpass + Wikipedia), карточка + TTS, авто-озвучка opt-in; обновление PRD/UX/архитектуры/безопасности/тестов/гайда | 16 |
| 13 | 1.2 «Время записи и скорость по участкам» | Все роли | Время записи как основное время (панель, история, детали, хронометр в уведомлении); окраска линии по скорости на live-карте и в деталях, легенда, тап по участку; без миграции БД; обновление PRD/UX/архитектуры/тестов/гайда | 8 |
| **J0** | JustTracker: репозиторий и переименование | Техлид | Клон с историей, `com.justtracker.app`, классы/строки/иконка под бренд, versionCode 1 / 1.0.0, CI | 3 |
| **J1** | LocationManager вместо GMS | Разработчик | `PlatformLocationSource` (LocationManagerCompat), `ProviderPolicy` + тест, `FakeLocationSource`, удаление play-services | 3 |
| **J2** | Явный выбор языка ru/en | Дизайнер, Разработчик | `AppLanguage`, ключ `language`, шаг онбординга, пункт настроек, AppCompat per-app locale, `AppLocale` для сервиса/POI/TTS | 6 |
| **J3** | Material Design 3 | Дизайнер, Разработчик | Typography/Shapes, splash, predictive back, `NavigationSuiteScaffold`, инсеты, pinned top bars, toggleable rows, иконка + monochrome | 9 |
| **J4** | Офлайн-карты регионов | Архитектор, Разработчик | Спайк osmdroid-mapsforge; `domain/maps`, каталог, Room `offline_regions`, DownloadManager, `OfflineRegionStore`, `HybridTileProvider`, экран «Офлайн-карты», 18 unit-тестов | 28 |
| **J5** | Готовность к RuStore | Безопасность, Техлид, Тех. писатель | Версии из `-P`, job `release-signed`, GitHub Pages (`site/`), `store/rustore/` (листинг ru/en, иконка 512, скриншоты 9:16, декларации, возраст, модератор) | 6 |
| **J6** | Документация по ролям | Все роли | Обновление `00`–`10`, README, CHANGELOG под JustTracker 1.0.0 | 12 |
| | **Итого JustTracker 1.0.0** | | | **67** |

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
T-36  Документация JustTracker: 00 контекст, 01 план, 02 позиционирование, 03 US-18..21 + §8 RuStore, 04 §2.1/§2.6/§2.10/§3/§6, 05 §7/§12 + ADR-13..15, 06 UC-08..10 + NFR-13/14, 07 угрозы/чек-лист RuStore/privacy, 08 TC-48+, 09_release_rustore, 10 гайд, README, CHANGELOG
```

## 4. Definition of Done

**Для задачи:**
- код компилируется, `./gradlew :app:testDebugUnitTest` зелёный;
- нет новых lint-ошибок уровня Error;
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
- **Пакеты:** `com.justtracker.app.{data,domain,service,ui,util}`; UI по фичам: `ui.record`, `ui.history`, `ui.detail`, `ui.stats`, `ui.settings`, `ui.maps`, `ui.onboarding`, `ui.poi`, `ui.common`, `ui.theme`; офлайн-карты — `data.maps` (+ `data.maps.render`), `domain.maps`.
- **Локаль:** язык берётся из DataStore (`AppSettings.language`) через `util.AppLocale`; `Locale.getDefault()` напрямую не использовать; ресурсы в Service/Application — через `AppLocale.localized(context, settings)`.
- **Сеть:** новый хост — только через белый список (`07_security.md` §3) и ревью; каталог регионов принимает только `https://download.mapsforge.org/...`.
- **DI:** ручной `AppContainer` в `JustTrackerApplication` (объём MVP не оправдывает Hilt; см. ADR-03).
- **Единицы хранения:** СИ — метры, м/с, секунды/миллисекунды epoch, градусы WGS84. Конвертация только в UI-слое через `UnitFormatter`.
- **Ошибки:** доменные функции не бросают исключения на некорректных данных — возвращают пустой/нулевой результат; IO-ошибки логируются через `Timber`-подобный `Log` обёрткой `AppLog`, в release координаты не логируются.
- **Состояние:** ViewModel → `StateFlow<UiState>`; сервис пишет только в БД; UI читает только из БД (single source of truth).
- **Версии:** только через `gradle/libs.versions.toml`.

## 6. Сборка и инструменты

- Gradle 9.6.1 (wrapper), AGP 9.4.1 со встроенным Kotlin (плагин `kotlin-android` не применяется), Kotlin 2.3.21, KSP 2.3.12 для Room.
- `minSdk 26`, `targetSdk 36`, `compileSdk 37` (актуальные AndroidX-библиотеки требуют compileSdk 37).
- Release: `isMinifyEnabled = true`, `isShrinkResources = true`, правила для osmdroid/mapsforge/Room; `bundle.language.enableSplit = false` (оба языка в каждой установке).
- Подпись: `keystore.properties` (в `.gitignore`); если файла нет — release подписывается debug-ключом только для локальной проверки. **Один release-ключ навсегда** — RuStore не переподписывает и не хранит ключ. В CI ключ приходит из секретов `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` (job `release-signed` на тег `v*`).
- Версия: `-PversionCode=N -PversionName=X.Y.Z` переопределяют значения из `build.gradle.kts`.
- Зависимости без Google: `play-services-*` запрещены (ADR-14); `androidx.appcompat` нужен для per-app locale.
- Команды:
  - `.\gradlew.bat :app:assembleDebug`
  - `.\gradlew.bat :app:testDebugUnitTest`
  - `.\gradlew.bat :app:lintDebug`
  - `.\gradlew.bat :app:bundleRelease` / `:app:assembleRelease`
  - `python store/rustore/make_icon.py` — иконка 512 для карточки

## 7. Проверки перед merge

1. Unit-тесты зелёные.
2. `lintDebug` без Error.
3. Smoke: старт записи → 30 с симулированного движения → стоп → трек в истории → открыть детали → экспорт GPX.
4. Ревью по чек-листу безопасности для изменений в манифесте/сервисе/экспорте/receiver **и для любого нового сетевого хоста**.
5. Для изменений в карте/локали — smoke на эмуляторе без Google APIs (AOSP) и проверка офлайн-региона в авиарежиме.

## 8. Риски и митигация

| Риск | Влияние | Митигация |
|------|---------|-----------|
| Ограничения фоновой геолокации на Android 14+ (FGS type location) | Запись останавливается | Тип сервиса `location`, `FOREGROUND_SERVICE_LOCATION`, запуск сервиса только из foreground-UI |
| Отклонение в Play по политике background location | Задержка релиза | Не запрашивать `ACCESS_BACKGROUND_LOCATION` в MVP: FGS с типом location работает при выключенном экране без него; задокументировать |
| Расход батареи | Плохие отзывы | Интервал 1–2 с только при записи, `PRIORITY_HIGH_ACCURACY`, остановка при STOP, тест на реальном устройстве |
| Шум GPS в городе | Кривые треки, завышенная дистанция | Фильтр по accuracy и правдоподобной скорости, минимальное смещение |
| Google Play требует targetSdk 36 | Блок публикации | targetSdk 36 / compileSdk 37 с самого начала |
| Несовместимость osmdroid-mapsforge 6.1.20 (mapsforge 0.21) с новыми V5-файлами | Офлайн-карты не открываются | Спайк подтвердил чтение V5 (malta.map 2026-07); при регрессии — Plan B: скопировать 3 класса osmdroid-mapsforge в `data/maps/render` и собрать против актуального mapsforge |
| Размер файлов регионов (0,1–1,8 ГБ), мобильный трафик | Жалобы, отказ от функции | Wi-Fi only по умолчанию, размер в диалоге, проверка свободного места, DownloadManager с докачкой |
| download.mapsforge.org недоступен / изменил раскладку | Каталог не работает | Каталог в assets с явными URL; импорт своего `.map` как обход; при переезде мирора — обновление каталога релизом |
| Потеря release-ключа | Невозможно обновить приложение в RuStore | Ключ и пароли в менеджере паролей + секреты GitHub; инструкция в `09_release_rustore.md` |
| Устройства без GMS (Huawei/Honor) | Геолокация не работает при зависимости от GMS | ADR-14: только платформенный LocationManager; регресс на образе AOSP |
| Крупные подписи на офлайн-карте при высокой плотности экрана (тайлы 256 px × DPI) | Читаемость | Совпадает с поведением онлайн-тайлов; кандидат на улучшение — рендер тайлов размером 256·density (Plan B) |
