# Скриншоты для RuStore

Требования RuStore: JPG/PNG, соотношение строго **16:9 или 9:16**, все одной ориентации, сторона 320–3840 px, ≤ 5 МБ каждый, **от 3 до 10** штук. Рекомендуемый формат — **1080×1920** (9:16), портрет.

## Набор (порядок в карточке)

| # | Файл | Экран | Как получить |
|---|---|---|---|
| 1 | `01_record.png` | Запись: карта с линией по скорости, панель «Скорость · Дистанция · Время записи» | Запись с симуляцией GPS, ~1 мин |
| 2 | `02_detail.png` | Карточка трека: карта, ползунок, плитки статистики | Открыть трек из истории |
| 3 | `03_offline_maps.png` | Настройки → Офлайн-карты, один регион в статусе «Готов» | Скачать «Мальта» |
| 4 | `04_language.png` | Онбординг: выбор языка | Первый запуск (очистить данные приложения) |
| 5 | `05_history.png` | История треков | ≥ 3 трека в истории |
| 6 | `06_dark.png` | Экран записи в тёмной теме | Настройки → Тема → Тёмная |

Русская карточка — интерфейс на русском; для английской карточки повторить с языком English (папка `en/`).

## Захват

Скриншоты Pixel 7 Pro (1440×3120, 19.5:9) не проходят по соотношению. Используйте AVD с экраном 1080×1920 (профиль «Pixel», API 34) — тогда `screencap` даёт готовый 9:16:

```bash
avdmanager create avd -n JT_store -k "system-images;android-34;google_apis;x86_64" -d pixel
emulator -avd JT_store
adb exec-out screencap -p > store/rustore/screenshots/01_record.png
```

Симуляция движения: `adb emu geo fix <lon> <lat>` раз в секунду с шагом ~0.0004°. Для «чистого» статус-бара включите демо-режим: `adb shell settings put global sysui_demo_allowed 1 && adb shell am broadcast -a com.android.systemui.demo -e command enter && adb shell am broadcast -a com.android.systemui.demo -e command clock -e hhmm 1200 && adb shell am broadcast -a com.android.systemui.demo -e command battery -e level 100 -e plugged false`.

Если под рукой только устройство с другим соотношением сторон — приведите к 9:16 скриптом `fit_9x16.py` (масштабирует по ширине до 1080 и обрезает/дополняет фоном по высоте до 1920):

```bash
python store/rustore/screenshots/fit_9x16.py raw/*.png
```
