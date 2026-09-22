# JustTracker — просто трекер (GPS-маршруты для Android, RuStore)

[![Android CI](https://github.com/AlexanderSwift89/JustTracker/actions/workflows/android.yml/badge.svg)](https://github.com/AlexanderSwift89/JustTracker/actions/workflows/android.yml)

Простой и автономный GPS-трекер: запись маршрута одной кнопкой, карта (OpenStreetMap), история, статистика, автоопределение типа движения, экспорт GPX. Без бэкенда, без аккаунтов, **без сервисов Google**, с **офлайн-картами регионов** и **явным выбором языка** (русский / английский). Публикуется в **RuStore**.

JustTracker — ответвление [TrekLog 1.2.0](https://github.com/AlexanderSwift89/treklog-android) (история коммитов сохранена). Что изменилось относительно TrekLog:

- **Офлайн-карты** — Настройки → Офлайн-карты: каталог регионов (федеральные округа РФ, Крым, Калининград, соседние страны) в формате Mapsforge с download.mapsforge.org, загрузка через системный DownloadManager (Wi-Fi only по умолчанию), импорт своего `.map`; явный **режим карты** Онлайн / Офлайн в настройках — при потере или возврате интернета приложение спрашивает во всплывающем окне, переключиться ли, и никогда не переключает само (ADR-13, ADR-16). С 1.0.1 фрагменты рендерятся несколькими потоками, кэшируются на диске и не остаются размытыми после зума (ADR-17).
- **Язык** выбирается явно при первом запуске и в настройках (AppCompat per-app locales, работает с Android 8), варианта «системный» нет (ADR-15).
- **Без Google Play Services**: геолокация через платформенный `LocationManager` (ADR-14) — приложение работает на Huawei/Honor и AOSP-прошивках.
- **Material Design 3**: явные Typography/Shapes, splash screen, predictive back, адаптивная навигация (`NavigationSuiteScaffold`), edge-to-edge, themed icon.
- **RuStore**: подпись собственным ключом, декларации разрешений и данных, возраст 12+ (436-ФЗ), политика конфиденциальности на GitHub Pages, материалы карточки в `store/rustore/`.

Сохранено из TrekLog: «Интересное рядом» (метки Википедии + озвучка, отключается), время записи как основное время, окраска линии по скорости, ползунок по треку.

## Структура репозитория

```
docs/                       документация команды (промпты ролей, план, PRD, UX, архитектура, безопасность, тесты, релиз RuStore, гайд)
store/rustore/              материалы карточки RuStore: листинг ru/en, иконка 512, скриншоты 9:16, декларации, комментарий модератору
site/                       GitHub Pages: лендинг, политика конфиденциальности и лицензии (RU/EN)
android/                    Gradle-проект приложения
  app/src/main/java/com/justtracker/app/
    domain/                 чистые модели и алгоритмы (гео, статистика, SpeedProfile, классификатор, GPX, poi, maps — выбор источника тайла, state machine регионов)
    data/                   Room, DataStore, LocationManager, poi (Overpass/Wikipedia), tts, maps (каталог, DownloadManager, OfflineRegionStore; render — HybridTileProvider, OfflineRenderer, OfflineRegionModule)
    service/                TrackingService (foreground, type=location), TrackingController, PoiAnnouncer
    ui/                     Compose: onboarding (язык → разрешения) / record / history / detail / stats / settings / maps (офлайн-карты) / poi; common/Shimmer — скелетоны загрузки
    util/                   AppLocale, UnitFormatter, TimeFormat, AppLog
  app/src/main/assets/maps/regions.json   каталог регионов
  app/src/test/             unit-тесты (118)
  app/schemas/              экспорт схемы Room
CHANGELOG.md
```

Порядок чтения документации: `docs/00_team_prompts.md` → `01_work_plan.md` → `03_prd.md` → `05_architecture.md` → `09_release_rustore.md` → остальное.

## Требования

- JDK 17+ (используется JBR из Android Studio: `D:\Android_studio\jbr`)
- Android SDK с platform 37 и build-tools 36+ (`android/local.properties` → `sdk.dir`)
- Gradle 9.6 (wrapper скачает сам), AGP 9.4, Kotlin 2.3 (встроенный в AGP)
- Python 3 + Pillow — только для генерации иконки магазина (`store/rustore/make_icon.py`) и подгонки скриншотов

## Скачать APK (без сборки)

- **Релизы:** [github.com/AlexanderSwift89/JustTracker/releases](https://github.com/AlexanderSwift89/JustTracker/releases) — `JustTracker-<версия>-debug.apk` для установки вручную (`adb install -r`). Магазинная сборка — в RuStore.
- **Последний коммит в `main`:** вкладка Actions → нужный запуск → Artifacts (нужен вход в GitHub; хранится 30 дней).

### CI

`.github/workflows/android.yml`: на каждый push/PR в `main` — unit-тесты, lint, `assembleDebug`, APK и отчёты как артефакты. На тег `v*` дополнительно — GitHub Release с debug-APK и job `release-signed` (подписанные AAB + APK + `mapping.txt`, если заданы секреты `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`). `.github/workflows/pages.yml` публикует `site/` на GitHub Pages (Pages включается самим workflow через `configure-pages` + `enablement: true`).

```bash
git tag v1.0.0 && git push origin v1.0.0
```

## Сборка

```bash
cd android
.\gradlew.bat :app:assembleDebug        # debug APK → app/build/outputs/apk/debug/
.\gradlew.bat :app:testDebugUnitTest    # unit-тесты
.\gradlew.bat :app:lintDebug            # lint (abortOnError)
.\gradlew.bat :app:bundleRelease        # AAB для RuStore → app/build/outputs/bundle/release/
.\gradlew.bat :app:assembleRelease      # release APK (~3 МБ)
```

Release подписывается ключом из `android/keystore.properties` (см. `docs/09_release_rustore.md`); без файла — debug-ключом для локальной проверки. Версию можно задать снаружи: `-PversionCode=2 -PversionName=1.0.1`.

## Запуск на эмуляторе и симуляция GPS

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant com.justtracker.app.debug android.permission.ACCESS_FINE_LOCATION
adb emu geo fix 37.6173 55.7558 150      # lon lat alt
```

Скрипт подачи движущихся точек — в `docs/08_test_plan.md` §2 (десятичный разделитель — точка). Проверка офлайн-карт: скачать регион «Мальта» (6,7 МБ), `adb shell cmd connectivity airplane-mode enable`, `adb emu geo fix 14.515 35.899`, начать запись — карта Валлетты рисуется без сети. Проверка без Google-сервисов — образ `system-images;android-34;default;x86_64`.

## Ключевые решения

- osmdroid вместо Google Maps SDK — без ключей и биллинга (ADR-01); офлайн — файлы регионов Mapsforge, а не массовая загрузка тайлов OSM (ADR-13); рендер офлайн-тайлов — собственный многопоточный поверх Mapsforge с дисковым кэшем и тайлами 512 px на плотных экранах (ADR-17).
- Платформенный LocationManager вместо Fused Location Provider — нет зависимости от GMS (ADR-14).
- Per-app locale через AppCompat с DataStore как источником истины (ADR-15).
- Foreground service типа `location`, без `ACCESS_BACKGROUND_LOCATION` (ADR-02).
- База данных — единственный источник истины между сервисом и UI (ADR-04).
- Все величины хранятся в СИ, единицы применяются только в UI (ADR-05).
- Время записи — производная от `startedAt`/`finishedAt` (ADR-11); окраска линии — `PolychromaticPaintList`, одна полилиния на сегмент (ADR-12).

## Лицензии

Карты: © OpenStreetMap contributors (ODbL). Офлайн-карты: файлы Mapsforge (download.mapsforge.org), библиотека Mapsforge (`org.mapsforge:*`, подключена напрямую) — LGPL 3. osmdroid (`osmdroid-android`) — Apache 2.0. Описания мест — Wikipedia, CC BY-SA 4.0. Полный список с обязанностями — [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md), для пользователей — https://alexanderswift89.github.io/JustTracker/licenses/ (Настройки → «Лицензии открытого ПО»); правовая проверка — `docs/07_security.md` §7.

Само приложение — **проприетарное**: [LICENSE](LICENSE) (просмотр и сборка для личного ознакомления; оговорка о совместимости с LGPL для Mapsforge).
