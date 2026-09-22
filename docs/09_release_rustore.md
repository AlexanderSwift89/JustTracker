# JustTracker — публикация в RuStore (технический писатель + техлид)

Материалы карточки (тексты, иконка, скриншоты, декларации, комментарий модератору) лежат в `store/rustore/` — этот документ описывает процесс. История TrekLog в Google Play к JustTracker не относится: это новое приложение с пакетом `com.justtracker.app`.

## 1. Подготовка сборки

### 1.1. Ключ подписи — один и навсегда
RuStore **не переподписывает** загруженные сборки и не хранит ключ разработчика: каждая следующая версия должна быть подписана тем же ключом, иначе обновление невозможно. Потеря ключа = новое приложение с нуля.

PowerShell (путь в кавычках нужно вызывать через оператор `&`, иначе `ParserError: Unexpected token '-genkeypair'`); ключ хранить **вне репозитория**, например в `D:\keys`:

```powershell
New-Item -ItemType Directory -Force D:\keys | Out-Null
& "D:\Android_studio\jbr\bin\keytool.exe" -genkeypair -v -keystore D:\keys\justtracker-release.jks -alias release -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=JustTracker, O=JustTracker, C=RU"
& "D:\Android_studio\jbr\bin\keytool.exe" -list -v -keystore D:\keys\justtracker-release.jks   # проверка
```

Локально — `android/keystore.properties` (в `.gitignore`; путь абсолютный с прямыми слэшами или относительно `android/`):
```properties
storeFile=D:/keys/justtracker-release.jks
storePassword=********
keyAlias=release
keyPassword=********
```
Резервные копии `.jks` и паролей — в менеджере паролей/офлайн-носителе. Для CI ключ кладётся в секреты репозитория (§1.4). Тот же ключ использовать, если приложение когда-нибудь появится в других магазинах.

### 1.2. Версия
`android/app/build.gradle.kts`: `versionCode` +1 на каждую загрузку в консоль (RuStore требует строго возрастающий), `versionName` — семантическая (текущая — `1.0.1`, `versionCode` 2). Оба значения можно переопределить в командной строке: `-PversionCode=3 -PversionName=1.0.2`.

### 1.3. Сборка
```bash
cd android
.\gradlew.bat :app:bundleRelease      # app/build/outputs/bundle/release/app-release.aab
.\gradlew.bat :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk (~3 МБ)
```
RuStore принимает и AAB, и APK (на версию — 1 AAB + до 8 APK или до 10 APK). **Рекомендуемый формат — APK:** для него достаточно подписи самого файла, приватный ключ не покидает компьютер, а сплиты JustTracker не нужны (native-кода нет, языковые сплиты отключены; release-APK ≈ 3 МБ). Для **AAB** RuStore сам генерирует APK и требует один раз загрузить подпись (блок «Подпись для загрузки AAB» в карточке версии): скачать в диалоге `pepk.jar` и команду с уникальным `--encryptionkey`, выполнить её из папки с ключом (`& "D:\Android_studio\jbr\bin\java.exe" -jar pepk.jar --keystore=D:\keys\justtracker-release.jks --alias=release --output=D:\keys\pepk_out.zip --include-cert --encryptionkey=…`), экспортировать сертификат (`& "D:\Android_studio\jbr\bin\keytool.exe" -export -rfc -keystore D:\keys\justtracker-release.jks -alias release -file D:\keys\upload_certificate.pem`) и загрузить оба файла → «Отправить подпись»; после этого копия ключа хранится у RuStore. `mapping.txt` из `app/build/outputs/mapping/release/` сохранить рядом с версией — по нему расшифровываются стек-трейсы из отзывов. Release-сборку **обязательно** прогнать на устройстве (чек-лист `08_test_plan.md` §6), в том числе офлайн-регион в авиарежиме и образ без Google-сервисов.

### 1.4. CI
`.github/workflows/android.yml`:
- каждый push/PR в `main` — unit-тесты, lint, `JustTracker-<versionName>-debug.apk` (артефакт 30 дней);
- тег `vX.Y.Z` — GitHub Release с debug-APK (для тестировщиков) и job **`release-signed`**: если в секретах репозитория заданы `KEYSTORE_BASE64` (base64 файла `.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, собираются подписанные AAB + APK + `mapping.txt` как артефакт `JustTracker-release-signed` (90 дней). Без секретов job — no-op.

`.github/workflows/pages.yml` публикует `site/` (лендинг, политика конфиденциальности, лицензии — RU/EN) на GitHub Pages при push в `main`, затрагивающем `site/`. Шаг `actions/configure-pages` с `enablement: true` сам включает Pages с источником *GitHub Actions* при первом запуске (без этого первый деплой падал: «Get Pages site failed … Not Found»); если в организации это запрещено — включить вручную: Settings → Pages → Source: *GitHub Actions*. URL политики: https://alexanderswift89.github.io/JustTracker/privacy/, лицензий: https://alexanderswift89.github.io/JustTracker/licenses/.

Порядок выпуска: `versionCode`/`versionName` → `CHANGELOG.md` → `store/rustore/listing_*.md` («Что нового») → commit → `git tag v1.0.0 && git push origin main v1.0.0` → зелёный CI → скачать `JustTracker-release-signed` (или собрать локально) → RuStore Console.

## 2. RuStore Console — пошагово

1. **Аккаунт разработчика** (console.rustore.ru): вход по VK ID. Физлицо — мгновенная регистрация с верификацией личности; ИП/юрлицо — ИНН, реквизиты, проверка до нескольких дней. Приложение бесплатное, поэтому статус физлица достаточен (монетизация с 01.02.2026 доступна только ИП/юрлицам).
2. **Создать приложение**: название «JustTracker — GPS-трекер маршрутов», пакет `com.justtracker.app` (совпадает с APK — проверяется автоматически), тип — приложение, бесплатное.
3. **Карточка** (`store/rustore/listing_ru.md`, `listing_en.md`): краткое описание ≤ 80, полное ≤ 4000 символов; категория «Здоровье и спорт» (`category_age.md`); возрастной рейтинг **12+** по 436-ФЗ (внешний контент Википедии); иконка `icon-512.png`; 3–10 скриншотов 9:16 из `screenshots/` (1080×1920, одной ориентации); сайт и URL политики конфиденциальности (`contact.md`); e-mail поддержки — **обязателен, указывает владелец аккаунта**.
4. **Загрузка версии**: APK (рекомендуется, см. §1.3) или AAB с загрузкой подписи, «Что нового» из листинга. Автопроверки RuStore: подпись, targetSdk ≥ 28 (у нас 36), совпадение пакета, 64-bit (native-кода нет — не затрагивает).
5. **Разрешения и безопасность данных**: заполнить по `store/rustore/permissions_data_safety.md` — назначение `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`; обоснование foreground service `location`; какие данные обрабатываются и куда уходят (только на устройстве; IP — серверам карт; приблизительный район — Overpass/Wikipedia при включённой функции).
6. **Комментарий для модератора**: вставить `store/rustore/moderator_notes.md` — аккаунт не нужен, шаги проверки записи с симуляцией GPS, тестовый регион «Мальта» 6,7 МБ для офлайн-карт.
7. **Отправить на модерацию**: обычно до 24 ч, у новых приложений — 1–3 дня. Типичные причины отказа: крэш при проверке, описание не соответствует функциям, не задекларированное разрешение, неверный возраст — всё закрыто чек-листом `store/rustore/README.md`.
8. **После одобрения**: приложение публикуется автоматически; проверить карточку и установку из RuStore на устройстве без Google-сервисов.

## 3. Обновления

1. Поднять `versionCode`/`versionName`, обновить `CHANGELOG.md` и «Что нового» (ru + en).
2. Тег → CI → подписанная сборка тем же ключом.
3. В консоли: новая версия → загрузить AAB/APK → «Что нового» → при изменении разрешений/данных обновить декларации → на модерацию (обновления проходят быстрее).
4. При изменении политики конфиденциальности — обновить `site/privacy/index.html` (дата и версия), `07_security.md` §6 и, при необходимости, раздел «Безопасность данных» в консоли.

## 4. Privacy Policy
Опубликованный текст — `site/privacy/index.html` (RU + EN на одной странице, якоря `#ru` / `#en`); исходник русской версии — `07_security.md` §6. RuStore требует, чтобы страница открывалась без авторизации — GitHub Pages это обеспечивает.

Структура политики (9 разделов: общие положения и правовой статус разработчика, данные на устройстве, сетевые запросы, разрешения, озвучивание, безопасность, права пользователя, дети, изменения/контакты) составлена под 152-ФЗ и GDPR одновременно; правовая проверка и открытые пункты — `07_security.md` §7. Возрастной порог для детей в политике намеренно не задан (см. `category_age.md`). Ссылки на исходный код в материалах карточки и на лендинге не публикуются (проект проприетарный — `LICENSE`); уведомления о стороннем ПО — `THIRD_PARTY_NOTICES.md`, опубликованы на `site/licenses/` и открываются из Настроек → «Лицензии открытого ПО».

## 5. Release notes

### 1.0.0 (JustTracker)
**RU.** Первый выпуск JustTracker — простого автономного GPS-трекера: запись маршрута одной кнопкой (работает при выключенном экране), карта с линией по скорости, история и статистика, автоопределение типа движения, экспорт GPX. Новое относительно TrekLog 1.2: офлайн-карты регионов (скачайте округ или страну заранее — карта работает без интернета), явный выбор языка (русский/английский), работа без сервисов Google, интерфейс по Material Design 3 (splash, адаптивная навигация, themed icon), «Интересное рядом» с описаниями из Википедии и озвучкой.
**EN.** First release of JustTracker, a simple self-contained GPS tracker: one-button route recording (keeps working with the screen off), map with a speed-coloured line, history and statistics, automatic activity detection, GPX export. New over TrekLog 1.2: offline map regions (download a district or country in advance and the map works without internet), explicit language choice (Russian/English), no Google services required, Material Design 3 UI (splash, adaptive navigation, themed icon), Places nearby with Wikipedia summaries and read-aloud.

### История TrekLog (для контекста)
- 1.2.0 — время записи как основное время, скорость по участкам, ползунок по треку.
- 1.1.0 — «Интересное рядом»: метки Википедии, карточка, озвучка.
- 1.0.0 — MVP: запись, карта, история, статистика, GPX.

## 6. После публикации
- Читать отзывы RuStore ежедневно первую неделю: крэши расшифровывать `mapping.txt` (R8 `retrace`), обращения по батарее/трафику — сверять с NFR-01/NFR-15.
- Метрики: установки, оценка, доля устройств без GMS (по отзывам) — `02_market_research.md` §5.
- Планировать 1.1 по гипотезам `02_market_research.md` §4 (тёмная render-тема карты, рендер тайлов под DPI, графики скорости/высоты).
