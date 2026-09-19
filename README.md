# TrekLog — GPS-трекер маршрутов для Android

MVP-приложение: запись GPS-трека по команде пользователя, карта (OpenStreetMap), история, статистика, автоопределение типа движения, экспорт GPX. Без бэкенда, без аккаунтов — все данные на устройстве.

## Структура репозитория

```
docs/                       документация команды (промпты ролей, план, PRD, архитектура, безопасность, тесты, релиз)
android/                    Gradle-проект приложения
  app/src/main/java/io/treklog/app/
    domain/                 чистые модели и алгоритмы (гео, статистика, классификатор, GPX)
    data/                   Room, DataStore, Fused Location Provider
    service/                TrackingService (foreground, type=location), TrackingController
    ui/                     Compose: record / history / detail / stats / settings / onboarding
  app/src/test/             unit-тесты домена (36)
  app/schemas/              экспорт схемы Room (для миграций)
CHANGELOG.md
```

Порядок чтения документации: `docs/00_team_prompts.md` → `01_work_plan.md` → `03_prd.md` → `05_architecture.md` → остальное.

## Требования

- JDK 17+ (используется JBR из Android Studio: `D:\Android_studio\jbr`)
- Android SDK с platform 37 и build-tools 36+ (`android/local.properties` → `sdk.dir`)
- Gradle 9.6 (wrapper скачает сам), AGP 9.4, Kotlin 2.3 (встроенный в AGP)

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

## Лицензии

Карты: © OpenStreetMap contributors (ODbL). osmdroid — Apache 2.0.
