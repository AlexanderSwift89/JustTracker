# TrekLog — публикация в Google Play (технический писатель + техлид)

## 1. Подготовка сборки

### 1.1. Ключ подписи
Создайте keystore один раз и храните его вне репозитория (потеря ключа = невозможность обновить приложение, если не включён Play App Signing; с Play App Signing — Google хранит ключ приложения, а ваш ключ становится upload-ключом и его можно сбросить через поддержку).

```bash
"D:\Android_studio\jbr\bin\keytool" -genkeypair -v -keystore treklog-upload.jks -alias upload -keyalg RSA -keysize 2048 -validity 10000
```

Создайте `android/keystore.properties` (в `.gitignore`):
```properties
storeFile=../treklog-upload.jks
storePassword=********
keyAlias=upload
keyPassword=********
```

### 1.2. Версия
В `android/app/build.gradle.kts`: `versionCode` увеличивается на 1 для каждой загрузки в консоль, `versionName` — семантическая (`1.0.0`).

### 1.3. Сборка AAB
```bash
cd android && .\gradlew.bat :app:bundleRelease
```
Результат: `android/app/build/outputs/bundle/release/app-release.aab` (~5 МБ). Проверьте release-сборку на устройстве через `bundletool` или установите `assembleRelease` APK.

### 1.4. CI и сборка для тестировщиков

GitHub Actions (`.github/workflows/android.yml`) на каждый push в `main` прогоняет unit-тесты и lint и собирает `TrekLog-<versionName>-debug.apk` (артефакт, 30 дней). Тег `vX.Y.Z` создаёт GitHub Release с этим APK — удобно раздавать тестировщикам без Play Console. Release-AAB для Play в CI **не** собирается: ключ подписи хранится только локально (`keystore.properties`, в `.gitignore`), см. §1.1.

Порядок выпуска: обновить `versionCode`/`versionName` → `CHANGELOG.md` → commit → `git tag v1.1.0 && git push origin main v1.1.0` → дождаться зелёного CI → локально `bundleRelease` → Play Console.

## 2. Google Play Console — пошагово

1. **Аккаунт разработчика**: play.google.com/console, единоразовая плата $25, подтверждение личности (для персонального аккаунта с 2023 г. требуется также закрытое тестирование ≥ 12 тестировщиков в течение 14 дней перед production).
2. **Создать приложение**: имя «TrekLog», язык по умолчанию — русский (или английский), тип — Приложение, бесплатное.
3. **Настройка приложения (Dashboard → Set up your app)**:
   - Политика конфиденциальности: URL (текст — `07_security.md` §6, английская версия — ниже).
   - Доступ к приложению: «Все функции доступны без ограничений» (нет логина).
   - Реклама: нет.
   - Возрастной рейтинг: анкета IARC → Everyone / 3+.
   - Целевая аудитория: 18+ (или 13+); приложение не предназначено для детей.
   - Новости: нет. COVID: нет. Государственное: нет.
   - **Data safety**: см. §3.
   - **Разрешения foreground service**: тип `location` — см. §4.
   - Категория: Health & Fitness. Контакты: email.
4. **Листинг** (§5): название, короткое и полное описания, иконка 512×512, feature graphic 1024×500, ≥ 4 скриншота телефона (рекомендация: 1080×1920 или 1440×3120), при наличии — 7" и 10" планшеты.
5. **Тестирование**: Internal testing → загрузить AAB → добавить тестировщиков по email → проверить установку из Play. Затем Closed testing (обязательно для новых персональных аккаунтов), затем Production.
6. **Релиз**: Production → Create release → выбрать AAB → Release notes (§7) → Review → Start rollout (можно поэтапно: 20 % → 50 % → 100 %).

## 3. Data safety — ответы

| Вопрос | Ответ |
|--------|-------|
| Does your app collect or share any of the required user data types? | Yes |
| Location → Precise location | Collected: Yes. Shared: No. Processed ephemerally: No. Required: Yes. Purpose: App functionality. |
| Location → Approximate location (с 1.1) | Collected: Yes. **Shared: Yes** (with OpenStreetMap Overpass API and Wikimedia for the optional "Places nearby" feature). Processed ephemerally: Yes. Required: No (can be turned off in Settings). Purpose: App functionality. |
| Is all of the user data collected by your app encrypted in transit? | Yes — все запросы (тайлы, Overpass, Wikipedia) только по HTTPS. |
| Do you provide a way for users to request that their data is deleted? | Yes — удаление треков в приложении / удаление приложения. |
| Other data types (Personal info, Financial, Health, Messages, Photos, Files, Contacts, Calendar, Device IDs, App activity, Crash logs) | Not collected. |

Примечание для рецензента (в описании укажите): «Треки хранятся только локально. Приложение обращается в сеть за фрагментами карт OpenStreetMap и, если включена функция „Интересное рядом“, за списком достопримечательностей (Overpass API, передаётся район ≈ 500 м) и описаниями (Wikipedia)».

## 4. Декларация Foreground Service (тип location)

Формулировка для Play Console (EN):
> TrekLog is a GPS track recorder. The user explicitly starts recording by tapping the Start button and stops it with the Stop button. While recording, a foreground service with type "location" collects GPS fixes so that the route continues to be recorded when the screen is off or the app is in the background. A persistent notification with Pause/Stop actions is visible for the whole duration. The service stops immediately when the user taps Stop. No background location permission is requested.

Приложите видео (≤ 30 с): открыть приложение → Старт → свернуть/выключить экран → развернуть → трек продолжен → Стоп.

## 5. Листинг

### 5.1. Название
TrekLog — GPS-трекер маршрутов / TrekLog: GPS Route Tracker

### 5.2. Короткое описание (≤ 80 символов)
- RU: «Записывайте маршруты по GPS. Без аккаунта, без облака, без рекламы.»
- EN: "Record your GPS routes. No account, no cloud, no ads."

### 5.3. Полное описание (RU)
> TrekLog — простой и честный GPS-трекер. Нажмите «Старт» — и ваш маршрут рисуется на карте в реальном времени. Нажмите «Стоп» — и трек сохраняется в истории.
>
> **Что умеет TrekLog**
> • Запись маршрута по GPS с отображением на карте OpenStreetMap
> • Мгновенная, средняя и максимальная скорость, дистанция, время движения, темп
> • Набор и сброс высоты
> • Автоматическое определение типа движения: пешком, бег, велосипед, автомобиль — с возможностью изменить вручную
> • История треков с картой каждого маршрута
> • Общая статистика: за неделю, месяц, за всё время, по типам движения
> • Экспорт в GPX одним нажатием
> • Работа при выключенном экране и в фоне (уведомление с кнопками Пауза/Стоп)
> • Метрические и имперские единицы, тёмная тема
>
> **Приватность**
> Никакой регистрации. Ваши треки хранятся только на вашем телефоне и никуда не отправляются. Приложение обращается в интернет только за фрагментами карты.
>
> Карта: © участники OpenStreetMap.

### 5.4. Полное описание (EN)
> TrekLog is a simple, honest GPS tracker. Tap Start and your route is drawn on the map in real time. Tap Stop and the track is saved to your history.
>
> **Features**
> • GPS route recording on an OpenStreetMap map
> • Current, average and max speed, distance, moving time, pace
> • Elevation gain and loss
> • Automatic activity detection — walking, running, cycling, driving — with manual override
> • Track history with a map for every route
> • Statistics: this week, this month, all time, by activity
> • One-tap GPX export
> • Keeps recording with the screen off (persistent notification with Pause/Stop)
> • Metric and imperial units, dark theme
>
> **Privacy**
> No sign-up. Your tracks stay on your phone and are never uploaded. The only network traffic is map tiles.
>
> Map data © OpenStreetMap contributors.

### 5.5. Ключевые слова для ASO
gps трекер, запись маршрута, gpx, трек, пробег, велосипед, бег, ходьба, без регистрации; gps tracker, route recorder, gpx export, offline, privacy.

### 5.6. Графика
- Иконка 512×512 PNG (из `ic_launcher` — экспортировать через Android Studio Image Asset).
- Feature graphic 1024×500: карта с оранжевой линией трека и крупной цифрой скорости.
- Скриншоты: Запись (живые метрики), Детали трека, История, Статистика, Тёмная тема. Кадры из эмулятора Pixel 7 Pro (1440×3120) подходят.

## 6. Privacy Policy (EN)

> **TrekLog Privacy Policy** — effective October 1, 2026
>
> TrekLog records your device location (GPS) only after you tap Start and until you tap Stop. Recorded routes, speed, altitude and time are stored exclusively on your device. We operate no servers, create no accounts, and do not collect, sell or share your data with third parties. The app contains no advertising or analytics SDKs.
>
> To display the map, the app downloads map tiles from OpenStreetMap servers (https://www.openstreetmap.org). As with any web request, your IP address and the coordinates of the requested tiles are transmitted to those servers; their processing is governed by the OpenStreetMap Foundation privacy policy.
>
> The optional "Places nearby" feature (can be disabled in Settings) asks the Overpass API (https://overpass-api.de, OpenStreetMap data) for points of interest. Only an **approximate area of about 500 m**, not your exact position, is sent; for a saved track you open, a simplified outline of that track is sent. When you open a place card or enable automatic read-aloud, the description of that specific article is requested from Wikipedia (https://www.wikipedia.org, Wikimedia Foundation). None of these requests contain device or account identifiers. Read-aloud uses your device's own text-to-speech engine.
>
> You can delete any route inside the app or uninstall the app to permanently delete all data. GPX files you export are under your control.
>
> The app is not directed at children under 13 and collects no data about them.
>
> Contact: privacy@treklog.app

## 7. Release notes

### 1.1.0
- RU: «Интересное рядом: метки достопримечательностей из OpenStreetMap и Википедии вокруг вас и вдоль трека, карточка с описанием и кнопкой „Прочитать вслух“. Новая настройка: озвучивать объекты при приближении (выключено по умолчанию).»
- EN: "Places nearby: pins for OpenStreetMap/Wikipedia points of interest around you and along your track, a card with the description and a Read-aloud button. New setting: announce places automatically when approaching (off by default)."

### 1.0.0
- RU: «Первый выпуск: запись GPS-треков, карта, история, статистика, автоопределение типа движения, экспорт GPX.»
- EN: "Initial release: GPS track recording, map, history, statistics, automatic activity detection, GPX export."

## 8. После публикации

- Следить за Android Vitals (crash-free ≥ 99.5 %, ANR).
- Отвечать на отзывы, особенно про батарею и «прыгающий» трек (FAQ в руководстве пользователя).
- Планировать 1.1 по `02_market_research.md` §4.
