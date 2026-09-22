# Стороннее ПО и данные в JustTracker / Third-party notices

Список компонентов, лицензии которых требуют уведомления конечного пользователя, и данных, требующих атрибуции. Полные тексты лицензий — по ссылкам. Этот файл — источник для страницы `site/licenses/` (RU/EN), которую открывает пункт Настройки → «Лицензии открытого ПО» (см. `docs/07_security.md` §7). Само приложение — проприетарное, см. `LICENSE`.

## Библиотеки / Libraries

| Компонент | Лицензия | Обязанности |
|---|---|---|
| [Mapsforge](https://github.com/mapsforge/mapsforge) (`org.mapsforge:mapsforge-map-android`, `mapsforge-map`, `mapsforge-map-reader`, `mapsforge-core`, `mapsforge-themes` 0.21.0) — чтение файлов регионов и рендеринг офлайн-карт | [LGPL-3.0](https://www.gnu.org/licenses/lgpl-3.0.html) | Уведомить пользователя, что библиотека используется и под какой лицензией; предоставить текст лицензии; обеспечить возможность замены библиотеки и не запрещать обратную разработку для отладки (LGPL §4) — оговорка в `LICENSE` п. 4 и письменное предложение предоставить Minimal Corresponding Source по запросу. Модификаций библиотеки нет. |
| [osmdroid](https://github.com/osmdroid/osmdroid) (`org.osmdroid:osmdroid-android`) — карта, онлайн-тайлы, кэш | [Apache-2.0](https://www.apache.org/licenses/LICENSE-2.0) | Сохранить уведомление об авторских правах и текст лицензии. |
| [AndroidSVG](https://github.com/BigBadaboom/androidsvg) (`com.caverock:androidsvg`, транзитивно через Mapsforge) — растеризация SVG-символов темы карты | Apache-2.0 | То же. |
| AndroidX (Compose, Material 3, Room, DataStore, Navigation, Lifecycle, AppCompat, Core, SplashScreen) | Apache-2.0 | То же. |
| Kotlin stdlib, kotlinx.coroutines | Apache-2.0 | То же. |

## Данные / Data

| Источник | Лицензия | Атрибуция в приложении |
|---|---|---|
| Данные карты OpenStreetMap (онлайн-тайлы `tile.openstreetmap.org`, файлы регионов Mapsforge, объекты Overpass API) | [ODbL 1.0](https://www.openstreetmap.org/copyright) — «© участники OpenStreetMap» | `CopyrightOverlay` на карте; строки `settings_map_attribution`, `maps_attribution`, `settings_poi_attribution` |
| Краткие описания объектов — Wikipedia (Фонд Викимедиа) | [CC BY-SA 4.0](https://creativecommons.org/licenses/by-sa/4.0/) | Строка `poi_attribution` в карточке объекта + кнопка «Википедия» (ссылка на статью) |

## Правила использования сервисов / Service usage policies

| Сервис | Правила | Как выполняем |
|---|---|---|
| OSMF tile server `tile.openstreetmap.org` | [Tile Usage Policy](https://operations.osmfoundation.org/policies/tiles/) — валидный User-Agent, без массовой выгрузки, атрибуция | User-Agent = `applicationId`; офлайн-карты — файлы Mapsforge, а не выгрузка тайлов (ADR-13); `CopyrightOverlay` |
| Overpass API `overpass-api.de` (FOSSGIS e.V.) | Добросовестное использование, User-Agent | Запрос по ячейке сетки, кэш по ячейке/треку, интервалы повторов; User-Agent `JustTracker/<версия> (<сайт>)` |
| Wikimedia REST API `*.wikipedia.org` | [API etiquette](https://www.mediawiki.org/wiki/API:Etiquette) — User-Agent с контактом | Тот же User-Agent; один запрос на карточку |
| `download.mapsforge.org` | Файлы предоставляются проектом Mapsforge для свободного скачивания (данные ODbL) | Загрузка только по явной команде пользователя |
