# TrekLog — план работ (техлид)

## 1. Цель и рамки

Выпустить в Google Play версию **1.0 (MVP)** Android-приложения TrekLog: запись GPS-трека по команде пользователя, отображение на карте, локальная история, статистика и автоопределение типа движения. Без бэкенда, без аккаунтов.

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
| 11 | Документация и публикация | Тех. писатель, PO | `09_release_google_play.md`, `10_user_guide.md`, README, листинг | 8 |
| | **Итого 1.0** | | | **108** |
| 12 | 1.1 «Интересное рядом» (ступень 1 + А, минимум) | Все роли | POI-маркеры (Overpass + Wikipedia), карточка + TTS, авто-озвучка opt-in; обновление PRD/UX/архитектуры/безопасности/тестов/гайда | 16 |

Критический путь: 3 → 4 → 5 → 6 → 7 → 9 → 10 → 11. Этапы 1–2 могут идти параллельно с 3; этап 8 параллелен с 7 после готовности 4–5.

## 3. Декомпозиция задач разработки

```
T-01  Gradle-скелет: settings/build KTS, libs.versions.toml, app-модуль, минимальный MainActivity (Compose)
T-02  Room: TrackEntity, TrackPointEntity, TrekLogDatabase, DAO с Flow
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
```

## 4. Definition of Done

**Для задачи:**
- код компилируется, `./gradlew :app:testDebugUnitTest` зелёный;
- нет новых lint-ошибок уровня Error;
- строки вынесены в ресурсы (en + ru);
- изменения покрыты unit-тестом, если это доменная логика;
- ручной smoke на эмуляторе (для UI/сервисных задач).

**Для релиза 1.0:**
- все P0-истории PRD приняты по критериям;
- пройден чек-лист регрессии из `08_test_plan.md`;
- пройден чек-лист безопасности из `07_security.md`;
- собран подписанный AAB, размер ≤ 15 МБ;
- заполнены листинг, Data safety, privacy policy URL;
- внутреннее тестирование в Play Console ≥ 3 дня без крэшей.

## 5. Соглашения по коду

- **Язык:** Kotlin 2.x, Compose BOM, Coroutines/Flow. Java 17 target.
- **Пакеты:** `io.treklog.app.{data,domain,service,ui,util}`; UI по фичам: `ui.record`, `ui.history`, `ui.detail`, `ui.stats`, `ui.settings`, `ui.common`, `ui.theme`.
- **DI:** ручной `AppContainer` в `TrekLogApplication` (объём MVP не оправдывает Hilt; см. ADR-03).
- **Единицы хранения:** СИ — метры, м/с, секунды/миллисекунды epoch, градусы WGS84. Конвертация только в UI-слое через `UnitFormatter`.
- **Ошибки:** доменные функции не бросают исключения на некорректных данных — возвращают пустой/нулевой результат; IO-ошибки логируются через `Timber`-подобный `Log` обёрткой `AppLog`, в release координаты не логируются.
- **Состояние:** ViewModel → `StateFlow<UiState>`; сервис пишет только в БД; UI читает только из БД (single source of truth).
- **Версии:** только через `gradle/libs.versions.toml`.

## 6. Сборка и инструменты

- Gradle 9.6.1 (wrapper), AGP 9.4.1 со встроенным Kotlin (плагин `kotlin-android` не применяется), Kotlin 2.3.21, KSP 2.3.12 для Room.
- `minSdk 26`, `targetSdk 36`, `compileSdk 37` (актуальные AndroidX-библиотеки требуют compileSdk 37).
- Release: `isMinifyEnabled = true`, `isShrinkResources = true`, правила для osmdroid/Room.
- Подпись: `keystore.properties` (в `.gitignore`); если файла нет — release подписывается debug-ключом только для локальной проверки.
- Команды:
  - `.\gradlew.bat :app:assembleDebug`
  - `.\gradlew.bat :app:testDebugUnitTest`
  - `.\gradlew.bat :app:lintDebug`
  - `.\gradlew.bat :app:bundleRelease`

## 7. Проверки перед merge

1. Unit-тесты зелёные.
2. `lintDebug` без Error.
3. Smoke: старт записи → 30 с симулированного движения → стоп → трек в истории → открыть детали → экспорт GPX.
4. Ревью по чек-листу безопасности для изменений в манифесте/сервисе/экспорте **и для любого нового сетевого хоста** (с 1.1).

## 8. Риски и митигация

| Риск | Влияние | Митигация |
|------|---------|-----------|
| Ограничения фоновой геолокации на Android 14+ (FGS type location) | Запись останавливается | Тип сервиса `location`, `FOREGROUND_SERVICE_LOCATION`, запуск сервиса только из foreground-UI |
| Отклонение в Play по политике background location | Задержка релиза | Не запрашивать `ACCESS_BACKGROUND_LOCATION` в MVP: FGS с типом location работает при выключенном экране без него; задокументировать |
| Расход батареи | Плохие отзывы | Интервал 1–2 с только при записи, `PRIORITY_HIGH_ACCURACY`, остановка при STOP, тест на реальном устройстве |
| Шум GPS в городе | Кривые треки, завышенная дистанция | Фильтр по accuracy и правдоподобной скорости, минимальное смещение |
| Google Play требует targetSdk 36 | Блок публикации | targetSdk 36 / compileSdk 37 с самого начала |
