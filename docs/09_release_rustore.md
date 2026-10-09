# JustTracker — публикация в RuStore (технический писатель + техлид)

Материалы карточки (тексты, иконка, скриншоты, декларации, комментарий модератору) лежат в `store/rustore/` — этот документ описывает процесс. История TrekLog в Google Play к JustTracker не относится: это новое приложение с пакетом `com.justtracker.app`.

## 1. Подготовка сборки

### 1.1. Ключ подписи — один и навсегда
RuStore **не переподписывает** загруженные сборки и не хранит ключ разработчика: каждая следующая версия должна быть подписана тем же ключом, иначе обновление невозможно. Потеря ключа = новое приложение с нуля.

**Ключ JustTracker** — `D:\keys\justtracker-release.jks`, alias `release`, сертификат `CN=JustTracker, O=JustTracker, C=RU`, SHA-256 `BC:B7:0F:4E:E2:7D:CE:1A:E2:8C:92:46:5A:E1:0D:7C:4C:ED:B3:EA:DD:FF:46:59:6D:E7:1B:F8:06:EF:87:09`. Им подписываются все версии начиная с **1.0.2**.

Версия 1.0.0 ушла в RuStore подписанной **debug-ключом** компьютера разработчика (`CN=Android Debug`, SHA-256 `DA:F3:A5:1D:…:9E:60`, проверено по APK из RuStore): загрузили release-APK, собранный до появления `keystore.properties`, а без него сборка подписывает release debug-ключом (D-24). Пользователей у 1.0.0 не было, поэтому с 1.0.2 приложение перешло на release-ключ: при загрузке 1.0.2 RuStore предупреждает, что ключ не совпадает с 1.0.0, — это ожидаемо, дополнительный APK со старой подписью не загружаем; установившим 1.0.0 — удалить её (сначала экспортировать треки в GPX) и поставить заново.

Защита от повторения: задача Gradle `verifyReleaseKey` (запускается перед `preReleaseBuild`, если есть `keystore.properties`) сверяет сертификат ключа с `releaseCertSha256` в `app/build.gradle.kts` и останавливает сборку при несовпадении. С 1.1.1 без `keystore.properties` release **не подписывается** вовсе — получается `app-release-unsigned.apk`, который нельзя ни установить, ни загрузить (SEC-03). Для локальной проверки R8 на устройстве debug-ключ включается явно: `-PallowDebugSignedRelease=true` (сборка выводит предупреждение) — **такой APK/AAB в RuStore не загружать**. Перед каждой загрузкой проверить файл — SHA-256 должен быть `bcb70f4e…ef8709`:
```powershell
& "D:\Android_sdk\build-tools\37.0.0\apksigner.bat" verify --print-certs android\app\build\outputs\apk\release\app-release.apk
```

Ключ создан один раз (21.09.2026) командой ниже; **повторно не создавать** — новый ключ для RuStore означает смену подписи. PowerShell (путь в кавычках нужно вызывать через оператор `&`, иначе `ParserError: Unexpected token '-genkeypair'`); ключ хранить **вне репозитория**, например в `D:\keys`:

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
`android/app/build.gradle.kts`: `versionCode` +1 на каждую загрузку в консоль (RuStore требует строго возрастающий), `versionName` — семантическая (текущая — `1.2.0`, `versionCode` 7). Оба значения можно переопределить в командной строке: `-PversionCode=8 -PversionName=1.2.1`; CI проверяет, что тег `vX.Y.Z` совпадает с `versionName` по умолчанию.

### 1.3. Сборка
```bash
cd android
.\gradlew.bat :app:bundleRelease      # app/build/outputs/bundle/release/app-release.aab
.\gradlew.bat :app:assembleRelease    # app/build/outputs/apk/release/app-release.apk (~3 МБ)
```
RuStore принимает и AAB, и APK (на версию — 1 AAB + до 8 APK или до 10 APK). **Рекомендуемый формат — APK:** для него достаточно подписи самого файла, приватный ключ не покидает компьютер, а сплиты JustTracker не нужны (native-кода нет, языковые сплиты отключены; release-APK ≈ 3 МБ). Для **AAB** RuStore сам генерирует APK и требует один раз загрузить подпись (блок «Подпись для загрузки AAB» в карточке версии): скачать в диалоге `pepk.jar` и команду с уникальным `--encryptionkey`, выполнить её из папки с ключом (`& "D:\Android_studio\jbr\bin\java.exe" -jar pepk.jar --keystore=D:\keys\justtracker-release.jks --alias=release --output=D:\keys\pepk_out.zip --include-cert --encryptionkey=…`), экспортировать сертификат (`& "D:\Android_studio\jbr\bin\keytool.exe" -export -rfc -keystore D:\keys\justtracker-release.jks -alias release -file D:\keys\upload_certificate.pem`) и загрузить оба файла → «Отправить подпись»; после этого копия ключа хранится у RuStore. `mapping.txt` из `app/build/outputs/mapping/release/` сохранить в архив релиза — по нему расшифровываются стек-трейсы из отзывов. Перед загрузкой — **обязательная проверка по §1.5**: подпись и версия файла, release-APK на устройстве (в том числе офлайн-регион в авиарежиме и образ без Google-сервисов), архив.

### 1.4. CI
`.github/workflows/android.yml`:
- каждый push/PR в `main` — unit-тесты, lint, `JustTracker-<versionName>-debug.apk` (артефакт 30 дней, **только для разработчиков**: сборка debuggable — `run-as` открывает базу треков, в logcat пишутся координаты; отдельный пакет `com.justtracker.app.debug`);
- тег `vX.Y.Z` — GitHub Release **только с заметками** (`gh release create`; файлы не прикладываются, заметки ведут в RuStore — SEC-01) и job **`release-signed`**: если в секретах репозитория заданы `KEYSTORE_BASE64` (base64 файла `.jks`), `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`, собираются подписанные AAB + APK + `mapping.txt` как артефакт `JustTracker-release-signed` (90 дней). Без секретов job — no-op. В секретах должен лежать тот же ключ, что в §1.1: иначе `verifyReleaseKey` остановит job.
- Цепочка поставки (SEC-05): у Gradle wrapper — `distributionSha256Sum`, CI проверяет `gradle-wrapper.jar` (`validate-wrappers`); сторонние Actions (`android-actions/setup-android`, `gradle/actions/setup-gradle`) закреплены по SHA коммита с версией в комментарии — обновлять осознанно: `git ls-remote --tags https://github.com/<владелец>/<репозиторий> '<тег>*'` (для аннотированного тега — строка `^{}`). Рекомендация: GitHub Environment `release` с обязательным ревьюером и перенос туда секретов подписи (`07_security.md` §8).

`.github/workflows/pages.yml` публикует `site/` (лендинг, политика конфиденциальности, лицензии — RU/EN) на GitHub Pages при push в `main`, затрагивающем `site/`. Шаг `actions/configure-pages` с `enablement: true` сам включает Pages с источником *GitHub Actions* при первом запуске (без этого первый деплой падал: «Get Pages site failed … Not Found»); если в организации это запрещено — включить вручную: Settings → Pages → Source: *GitHub Actions*. URL политики: https://alexanderswift89.github.io/JustTracker/privacy/, лицензий: https://alexanderswift89.github.io/JustTracker/licenses/.

Порядок выпуска: `versionCode`/`versionName` → `CHANGELOG.md` → `store/rustore/listing_*.md` («Что нового») → commit → `git tag vX.Y.Z && git push origin main vX.Y.Z` → зелёный CI → собрать локально (или скачать `JustTracker-release-signed`) → **проверка §1.5** → RuStore Console.

### 1.5. Проверка перед загрузкой — каждый релиз
В RuStore должен уйти ровно тот файл, что собран из коммита релиза release-ключом и проверен на устройстве. Без этой проверки 1.0.0 ушла старым APK с debug-подписью, и в 1.0.2 пришлось сменить ключ (D-24).

1. **Исходники и тексты.** `git status` — чисто, `git log -1` — коммит релиза. `versionCode` больше, чем у версии, **опубликованной** в RuStore (RuStore Console, список версий приложения), `versionName` — как в верхнем разделе `CHANGELOG.md`. «Что нового» (`store/rustore/listing_ru.md`, `listing_en.md`) перечисляет всё, что изменилось **с последней опубликованной в RuStore версии**: изменения версий, которые в RuStore не выходили, тоже входят (1.0.2 после 1.0.0). Изменились разрешения, сетевые хосты или обработка данных — обновить `permissions_data_safety.md`, `site/privacy/`, `moderator_notes.md`.
2. **Сборка** — в папке `android`, непосредственно перед загрузкой:
   ```powershell
   .\gradlew.bat :app:testDebugUnitTest :app:lintDebug :app:assembleRelease
   ```
   Тесты и lint — зелёные. Ключ с другим сертификатом остановит сборку на `verifyReleaseKey`; без `keystore.properties` получится только `app-release-unsigned.apk` (§1.1) — загружать нечего. APK из CI (`JustTracker-release-signed`) проходит те же шаги 3–6.
3. **Проверка файла** — там же:
   ```powershell
   $apk = "app\build\outputs\apk\release\app-release.apk"
   $bt = "D:\Android_sdk\build-tools\37.0.0"
   & "$bt\apksigner.bat" verify --print-certs $apk | Select-String "certificate DN|SHA-256 digest"
   & "$bt\aapt2.exe" dump badging $apk | Select-String "^package:|debuggable"
   ```
   Должно быть:
   - `certificate DN: CN=JustTracker, O=JustTracker, C=RU` — **не** `CN=Android Debug`;
   - `certificate SHA-256 digest: bcb70f4ee27dce1ae28c92465ae10d7c4cedb3eaddff46596de71bf806ef8709`;
   - `package: name='com.justtracker.app'` с `versionCode` и `versionName` из шага 1;
   - строки с `debuggable` нет.

   Любое расхождение — не загружать. Дата файла не показатель: если исходники не менялись, Gradle не перезаписывает APK.
4. **Release-APK на устройстве** — R8 и сжатие ресурсов могут сломать то, что работает в debug. Эмулятор или телефон; симуляция GPS и авиарежим — `README.md` («Запуск на эмуляторе и симуляция GPS») и `08_test_plan.md` §2:
   ```powershell
   $adb = "D:\Android_sdk\platform-tools\adb.exe"
   & $adb install -r $apk
   & $adb logcat -c
   & $adb shell am start -W -n com.justtracker.app/.ui.MainActivity
   ```
   Пройти онбординг → запись 30 с → стоп → карточка трека → экспорт GPX → «История», «Статистика», «Настройки» → офлайн-регион в авиарежиме; `& $adb logcat -d -b crash` — пусто. **Обновление поверх опубликованной версии** (начиная с 1.0.3): на устройстве стоит версия из RuStore или APK из архива релизов (шаг 5) с записанными треками → `adb install -r` нового APK → `Success`, треки и настройки на месте (TC-104); `INSTALL_FAILED_UPDATE_INCOMPATIBLE` — другой ключ, не загружать. Исключение — 1.0.2: поверх 1.0.0 она не встанет (другой ключ, D-24), 1.0.0 сначала удалить, экспортировав треки в GPX. Полная регрессия — `08_test_plan.md` §6, в том числе образ без Google-сервисов (TC-65). С 1.1.2 на этом же APK — **захват трафика** TC-131 («Онлайн» — только тайлы, «Офлайн» — 0 байт, регион — только после подтверждения) и **release-logcat** TC-133 (ни строки osmdroid, Mapsforge и путей тайлов).
5. **Архив релиза** вне репозитория: APK с номером версии и `mapping.txt` — по нему расшифровываются стек-трейсы из отзывов, а следующая сборка его перезапишет. Путь — например, `D:\releases\JustTracker\<версия>`:
   ```powershell
   $v = "1.2.0"; $dst = "D:\releases\JustTracker\$v"
   New-Item -ItemType Directory -Force $dst | Out-Null
   Copy-Item $apk "$dst\JustTracker-$v.apk"
   Copy-Item app\build\outputs\mapping\release\mapping.txt $dst
   ```
6. **Загрузка.** В консоль — `JustTracker-<версия>.apk` из архива (или `app-release.apk` из шага 3), не старые файлы из «Загрузок». Консоль должна показать `com.justtracker.app` и новую версию **без** предупреждения «Ключ подписи не совпадает…»; если оно появилось — остановиться и вернуться к шагу 3, «дополнительный APK со старой подписью» не загружать (для 1.0.2 предупреждение ожидаемо — единственный раз, D-24). «Что нового» — из шага 1.
7. **После публикации** — тег `vX.Y.Z` на коммите релиза (если ещё нет), установка или обновление из RuStore на телефоне (§2, п. 8).

## 2. RuStore Console — пошагово

1. **Аккаунт разработчика** (console.rustore.ru): вход по VK ID. Физлицо — мгновенная регистрация с верификацией личности; ИП/юрлицо — ИНН, реквизиты, проверка до нескольких дней. Приложение бесплатное, поэтому статус физлица достаточен (монетизация с 01.02.2026 доступна только ИП/юрлицам).
2. **Создать приложение**: название «JustTracker — GPS-трекер маршрутов», пакет `com.justtracker.app` (совпадает с APK — проверяется автоматически), тип — приложение, бесплатное.
3. **Карточка** (`store/rustore/listing_ru.md`, `listing_en.md`): краткое описание ≤ 80, полное ≤ 4000 символов; категория «Здоровье и спорт» (`category_age.md`); возрастной рейтинг по 436-ФЗ — с 1.1.2 подходит **0+** (внешнего контента нет; до 1.1.1 — 12+ из-за Википедии), смена — решение владельца (`category_age.md`); иконка `icon-512.png`; 3–10 скриншотов 9:16 из `screenshots/` (1080×1920, одной ориентации); сайт и URL политики конфиденциальности (`contact.md`); e-mail поддержки — **обязателен, указывает владелец аккаунта**.
4. **Загрузка версии**: APK, прошедший проверку §1.5 (рекомендуется, см. §1.3), или AAB с загрузкой подписи, «Что нового» из листинга. Автопроверки RuStore: подпись (совпадение ключа с опубликованной версией), targetSdk ≥ 28 (у нас 36), совпадение пакета, 64-bit (native-кода нет — не затрагивает).
5. **Разрешения и безопасность данных**: заполнить по `store/rustore/permissions_data_safety.md` — назначение `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `POST_NOTIFICATIONS`; обоснование foreground service `location`; какие данные обрабатываются и куда уходят (только на устройстве; IP — серверам карт и загрузок; с 1.1.2 приблизительное местоположение **не передаётся** — ответ в форме изменить, если там осталось «Да»).
6. **Комментарий для модератора**: вставить `store/rustore/moderator_notes.md` — аккаунт не нужен, шаги проверки записи с симуляцией GPS, тестовый регион «Мальта» 6,7 МБ для офлайн-карт.
7. **Отправить на модерацию**: обычно до 24 ч, у новых приложений — 1–3 дня. Типичные причины отказа: крэш при проверке, описание не соответствует функциям, не задекларированное разрешение, неверный возраст — всё закрыто чек-листом `store/rustore/README.md`.
8. **После одобрения**: приложение публикуется автоматически; проверить карточку и установку из RuStore на устройстве без Google-сервисов.

## 3. Обновления

1. Поднять `versionCode`/`versionName`, обновить `CHANGELOG.md` и «Что нового» (ru + en) — всё, что изменилось с последней опубликованной в RuStore версии.
2. Собрать тем же ключом (локально или тег → CI) и проверить по §1.5.
3. В консоли: новая версия → загрузить проверенный APK → «Что нового» → при изменении разрешений/данных обновить декларации → на модерацию (обновления проходят быстрее).
4. При изменении политики конфиденциальности — обновить `site/privacy/index.html` (дата и версия), `07_security.md` §6 и, при необходимости, раздел «Безопасность данных» в консоли.

## 4. Privacy Policy
Опубликованный текст — `site/privacy/index.html` (RU + EN на одной странице, якоря `#ru` / `#en`); исходник русской версии — `07_security.md` §6. RuStore требует, чтобы страница открывалась без авторизации — GitHub Pages это обеспечивает.

Структура политики (с версии 1.2 — 8 разделов: общие положения и правовой статус разработчика, данные на устройстве, сетевые запросы, разрешения, безопасность, права пользователя, дети, изменения/контакты; раздел «Озвучивание» удалён вместе с функцией) составлена под 152-ФЗ и GDPR одновременно; правовая проверка и открытые пункты — `07_security.md` §7. Возрастной порог для детей в политике намеренно не задан (см. `category_age.md`). Ссылки на исходный код в материалах карточки и на лендинге не публикуются (проект проприетарный — `LICENSE`); уведомления о стороннем ПО — `THIRD_PARTY_NOTICES.md`, опубликованы на `site/licenses/` и открываются из Настроек → «Лицензии открытого ПО».

## 5. Release notes

### 1.2.0 (versionCode 7)
**RU.** Ускорение в истории: в карточке трека над картой появился переключатель «Скорость | Ускорение». В режиме ускорения линия показывает, где вы разгонялись (зелёный) и тормозили (оранжевый), под ползунком — ускорение в точке, а в статистике — максимальный разгон и замедление и список «Разгоны и замедления»: например, «0 → 72 км/ч за 10 с»; нажмите на строку — карта покажет это место. Работает и для треков, записанных раньше, если GPS-приёмник сообщал скорость. Новых разрешений нет.
**EN.** Acceleration in your history: the track details now have a "Speed | Acceleration" switch over the map. In acceleration mode the line shows where you sped up (green) and slowed down (orange), the scrubber shows the acceleration at that point, and the statistics show the maximum speed-up and slow-down and a "Speeding up and slowing down" list, such as "0 → 72 km/h in 10 s"; tap a row to see the place on the map. Works for earlier tracks too if the GPS receiver reported the speed. No new permissions.

В консоли вместе с версией:
- скриншоты: `02_detail.png` переснят (над картой — переключатель), добавлен `07_acceleration.png` (режим «Ускорение»); загрузить оба (`store/rustore/screenshots/README.md`);
- полное описание — с пунктом об ускорении в карточке трека (`listing_ru.md`, `listing_en.md`);
- политика конфиденциальности (1.2), декларация «Безопасность данных», возрастной рейтинг и комментарий модератору не меняются (`07_security.md` §10).

Обновление поверх 1.1.2: схема БД 2 → 3 автоматически, треки и настройки сохраняются (TC-148); у старых треков ускорение считается из сохранённых точек.

### 1.1.2 (versionCode 6)
**RU.** Приватность: функция «Интересное рядом» удалена. Приложение больше не отправляет на серверы ни координаты, ни район, где вы находитесь: в режиме «Онлайн» оно только подгружает фрагменты карты, показанные на экране, офлайн-регион скачивается только по вашей команде, а в режиме «Офлайн» приложение вообще не выходит в интернет. Длинные треки записываются и открываются плавно и бережнее к батарее; пока на экране ничего не меняется, приложение не тратит батарею на перерисовку. Уведомление о записи сразу переходит на новые единицы и язык. Приложение быстрее запускается. Безопасность: никаких служебных записей в системном журнале, проверка свободного места при импорте карты. Новых разрешений нет.
**EN.** Privacy: Places nearby has been removed. The app no longer sends your coordinates or the area you are in to any server: in Online mode it only loads the map tiles shown on screen, an offline region is downloaded only when you ask for it, and in Offline mode the app does not go online at all. Long tracks record and open smoothly and use less battery; while nothing changes on screen, the app spends no battery on redrawing. The recording notification follows a change of units or language at once. Faster start-up. Security: no service messages in the system log, a free-space check when importing a map. No new permissions.

В консоли вместе с версией (`07_security.md` §9, «Действия владельца»):
- политика конфиденциальности — **версия 1.2** (`site/privacy/index.html`; ссылка прежняя, страница обновляется публикацией GitHub Pages);
- декларация «Безопасность данных» по `store/rustore/permissions_data_safety.md`: приблизительное местоположение — «не собирается»; хосты — только `tile.openstreetmap.org` и `download.mapsforge.org`;
- полное описание без раздела «Интересное рядом» и атрибуции Википедии (`listing_ru.md`, `listing_en.md`);
- комментарий модератору — без пункта о метках (`moderator_notes.md`);
- возрастной рейтинг — решение владельца: 0+ или оставить 12+ (`category_age.md`);
- скриншоты `01_record.png`, `02_detail.png` и `06_dark.png` пересняты на release-сборке 1.1.2: на прежнем `06_dark.png` были метки «Интересного рядом», на всех трёх — отладочная сетка тайлов (D-23). Загрузить их в карточку вместо прежних (`store/rustore/screenshots/README.md`).

Пользователи 1.1.1 после обновления не увидят меток и карточек объектов, а настройки функции удалятся сами; остальные настройки, треки и регионы сохраняются (TC-132).

### 1.1.1 (versionCode 5)
**RU.** Обновление безопасности и конфиденциальности. Для меток «Интересное рядом» вдоль сохранённого трека на сервер теперь уходят только районы ≈ 500 м вдоль маршрута, без начала и конца трека. Копия GPX для «Поделиться» удаляется вместе с треком и не позже чем через сутки. Данные приложения не попадают в облачные копии и не переносятся на новый телефон. Сетевые запросы — только по HTTPS к OpenStreetMap и Википедии, с проверкой ссылок и перенаправлений; при загрузке офлайн-карт приложение больше не сообщает модель телефона. Скачанная офлайн-карта проверяется перед использованием. Новых разрешений нет.
**EN.** Security and privacy update. For "Places nearby" along a saved track the server now receives only ~500 m areas along the route, without the start and the end of the track. The GPX copy made for sharing is deleted together with the track and within a day at the latest. App data is excluded from cloud backups and device-to-device transfer. Network requests go only over HTTPS to OpenStreetMap and Wikipedia, with links and redirects checked; offline map downloads no longer reveal the phone model. A downloaded offline map is checked before use. No new permissions.

Политика конфиденциальности — версия 1.1 (`site/privacy/index.html`); декларация «Безопасность данных» — `store/rustore/permissions_data_safety.md` (уточнены формулировки, категории данных не меняются). Скриншоты не устаревают.

### 1.1.0 (versionCode 4)
**RU.** Индикатор ускорения: нажмите на скорость на экране записи — под ней появятся текущее ускорение (м/с²), стрелка «разгон / замедление» и график за последнюю минуту; повторное нажатие скрывает индикатор, выбор запоминается. Скорость на экране записи обновляется каждую секунду и сразу падает до нуля, когда вы остановились. Новых разрешений нет.
**EN.** Acceleration indicator: tap the speed on the recording screen to see your current acceleration (m/s²), a speeding-up / slowing-down arrow and a chart of the last minute; tap again to hide it, the choice is remembered. The live speed now updates every second and drops to zero as soon as you stop. No new permissions.

Тексты «Что нового» для консоли — `store/rustore/listing_ru.md` / `listing_en.md`. Скриншоты не устаревают: индикатор по умолчанию скрыт.

### 1.0.0 (JustTracker)
**RU.** Первый выпуск JustTracker — простого автономного GPS-трекера: запись маршрута одной кнопкой (работает при выключенном экране), карта с линией по скорости, история и статистика, автоопределение типа движения, экспорт GPX. Новое относительно TrekLog 1.2: офлайн-карты регионов (скачайте округ или страну заранее — карта работает без интернета), явный выбор языка (русский/английский), работа без сервисов Google, интерфейс по Material Design 3 (splash, адаптивная навигация, themed icon), «Интересное рядом» с описаниями из Википедии и озвучкой.
**EN.** First release of JustTracker, a simple self-contained GPS tracker: one-button route recording (keeps working with the screen off), map with a speed-coloured line, history and statistics, automatic activity detection, GPX export. New over TrekLog 1.2: offline map regions (download a district or country in advance and the map works without internet), explicit language choice (Russian/English), no Google services required, Material Design 3 UI (splash, adaptive navigation, themed icon), Places nearby with Wikipedia summaries and read-aloud.

### История TrekLog (для контекста)
- 1.2.0 — время записи как основное время, скорость по участкам, ползунок по треку.
- 1.1.0 — «Интересное рядом»: метки Википедии, карточка, озвучка (в JustTracker удалено в 1.1.2).
- 1.0.0 — MVP: запись, карта, история, статистика, GPX.

## 6. После публикации
- Читать отзывы RuStore ежедневно первую неделю: крэши расшифровывать `mapping.txt` (R8 `retrace`), обращения по батарее/трафику — сверять с NFR-01/NFR-15.
- Метрики: установки, оценка, доля устройств без GMS (по отзывам) — `02_market_research.md` §5.
- Планировать 1.1 по гипотезам `02_market_research.md` §4 (тёмная render-тема карты, рендер тайлов под DPI, графики скорости/высоты).
