# TrekLog — GPS-трекер маршрутов для Android

[![Android CI](https://github.com/AlexanderSwift89/treklog-android/actions/workflows/android.yml/badge.svg)](https://github.com/AlexanderSwift89/treklog-android/actions/workflows/android.yml)

MVP-приложение: запись GPS-трека по команде пользователя, карта (OpenStreetMap), история, статистика, автоопределение типа движения, экспорт GPX. Без бэкенда, без аккаунтов — все данные на устройстве.

С версии 1.1 — «Интересное рядом»: метки достопримечательностей (Overpass API + Wikipedia) вокруг пользователя и вдоль трека, карточка с описанием и кнопкой «Прочитать вслух» (системный TTS), опциональная авто-озвучка при приближении (выключена по умолчанию). На серверы уходит только примерный район (~500 м), функция отключается в настройках.

С версии 1.2 — основное время — **время записи** (от «Старт» до «Стоп»), время движения второстепенно; линия трека на карте записи и в деталях окрашена по скорости участков, тап по линии показывает скорость на участке; в деталях — слайдер по треку (время от старта · % дистанции, скорость, время суток, высота в точке); макс. скорость и время движения сохраняются в истории.

## Структура репозитория

```
docs/                       документация команды (промпты ролей, план, PRD, архитектура, безопасность, тесты, релиз)
android/                    Gradle-проект приложения
  app/src/main/java/io/treklog/app/
    domain/                 чистые модели и алгоритмы (гео, статистика, SpeedProfile, классификатор, GPX, poi)
    data/                   Room, DataStore, Fused Location Provider, poi (Overpass/Wikipedia, HttpURLConnection), tts
    service/                TrackingService (foreground, type=location), TrackingController, PoiAnnouncer
    ui/                     Compose: record / history / detail / stats / settings / onboarding / poi (карточка объекта)
  app/src/test/             unit-тесты домена и парсеров (81)
  app/schemas/              экспорт схемы Room (для миграций)
CHANGELOG.md
```

Порядок чтения документации: `docs/00_team_prompts.md` → `01_work_plan.md` → `03_prd.md` → `05_architecture.md` → остальное.

## Требования

- JDK 17+ (используется JBR из Android Studio: `D:\Android_studio\jbr`)
- Android SDK с platform 37 и build-tools 36+ (`android/local.properties` → `sdk.dir`)
- Gradle 9.6 (wrapper скачает сам), AGP 9.4, Kotlin 2.3 (встроенный в AGP)

## Скачать APK (без сборки)

- **Релизы:** [github.com/AlexanderSwift89/treklog-android/releases](https://github.com/AlexanderSwift89/treklog-android/releases) — файл `TrekLog-<версия>-debug.apk`. Скопируйте на телефон и откройте (разрешите установку из этого источника) или `adb install -r TrekLog-1.2.0-debug.apk`.
- **Последний коммит в `main`:** вкладка [Actions](https://github.com/AlexanderSwift89/treklog-android/actions) → нужный запуск → раздел Artifacts (нужен вход в GitHub; хранится 30 дней).

APK подписан debug-ключом и предназначен для установки вручную; сборка для Google Play подписывается локально (см. ниже).

### CI

`.github/workflows/android.yml`: на каждый push/PR в `main` — unit-тесты, lint, `assembleDebug`, APK и отчёты как артефакты. На тег `v*` дополнительно создаётся GitHub Release с APK:

```bash
git tag v1.2.0 && git push origin v1.2.0
```

## Сборка

```bash
cd android
.\gradlew.bat :app:assembleDebug        # debug APK → app/build/outputs/apk/debug/
.\gradlew.bat :app:testDebugUnitTest    # unit-тесты
.\gradlew.bat :app:lintDebug            # lint (abortOnError)
.\gradlew.bat :app:bundleRelease        # AAB для Play → app/build/outputs/bundle/release/
```

Release подписывается ключом из `android/keystore.properties` (см. `docs/09_release_google_play.md`); без файла — debug-ключом для локальной проверки.

## Запуск на эмуляторе и симуляция GPS

```bash
adb install -r android/app/build/outputs/apk/debug/app-debug.apk
adb shell pm grant io.treklog.app.debug android.permission.ACCESS_FINE_LOCATION
adb emu geo fix 37.6173 55.7558 150      # lon lat alt
```

Скрипт подачи движущихся точек — в `docs/08_test_plan.md` §2 (важно: десятичный разделитель — точка).

## Ключевые решения

- osmdroid вместо Google Maps SDK — без ключей и биллинга (ADR-01).
- Foreground service типа `location`, без `ACCESS_BACKGROUND_LOCATION` (ADR-02).
- База данных — единственный источник истины между сервисом и UI (ADR-04).
- Все величины хранятся в СИ, единицы применяются только в UI (ADR-05).
- Время записи — производная от `startedAt`/`finishedAt`, без новой колонки и миграции (ADR-11); окраска линии — `PolychromaticPaintList` osmdroid, одна полилиния на сегмент (ADR-12).

## Лицензии

Карты: © OpenStreetMap contributors (ODbL). osmdroid — Apache 2.0.
