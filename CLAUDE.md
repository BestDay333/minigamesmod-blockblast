# CLAUDE.md — проект MinigamesMod + Block Blast

Этот файл — рабочая инструкция для Claude Code (и любых агентов) в этом репозитории.

## Структура

- Fabric-мод, клиентский (`ClientModInitializer` в `src/main/java/org/trivait/minigamesmod/MinigamesMod.java`).
- Каждая мини-игра — пакет `minigame/<name>/` с тремя обязательными классами:
  - `<Name>.java` — extends `AbstractMinigame` (id, иконка, `createScreen`, `getLeaderboard`, звуки);
  - `<Name>Config.java` — persistent-конфиг (cloth-config `@Config`), например рекорды;
  - `<Name>Screen.java` — экран игры (`Screen`).
- Регистрация — в `MinigamesMod.onInitializeClient()` через `MinigameRegistry.register(new ...)`.
- Локализация: `src/main/resources/assets/minigamesmod/lang/{en_us,ru_ru}.json` — оба языка добавлять всегда.
- Иконка: `assets/minigamesmod/textures/minigame/<id>_icon.png` (в формате 29×29, как у остальных).

## Веточная специфика (важно!)

- `main` = **Minecraft 26.3, Mojang-маппинги, Java 25**:
  - рендер: `GuiGraphicsExtractor`, метод экрана — `extractRenderState(...)`, текст — `context.centeredText(font, ...)`;
  - мышь: `mouseClicked(MouseButtonEvent, boolean)`, `mouseReleased(MouseButtonEvent)`, координаты — `click.x()/click.y()`;
  - виджеты: `SpriteIconButton.builder(...).sprite(...)`, `setRectangle`, `clearWidgets()`, `addRenderableWidget`;
  - звук: `SoundEvents.VILLAGER_NO`, `PLAYER_LEVELUP` (без префиксов `ENTITY_`), `SoundEvents.UI_BUTTON_CLICK` требует `.value()`;
  - GLFW-константы недоступны — только `InputConstants`.
- `1.21` = yarn-маппинги, Java 21: `DrawContext`, `Text`, `TextIconButtonWidget`, `GLFW.GLFW_KEY_*`.
- Не переносить код между ветками 1:1 — API несовместимы; образцы «правильного» кода в каждой ветке: `minigame/game2048/`.

## Правила сборки

- Готовые JAR не коммитить; `build/`, `.gradle/`, `run/` — в .gitignore.
- Ветки `main` и `1.21` собираются одинаково: `./gradlew build` (или `sh gradlew build` без exec-бита), нужен JDK соответствующей версии.
- Оба файла `LeaderboardLink.java` обязательны для компиляции — не удалять и не игнорировать (в апстриме они ошибочно в .gitignore).

## Block Blast — игровые константы (было подобрано при реализации)

- Сетка 8×8; `GAP=4`; лоток из 3 фигур; 19 форм в `SHAPES`; 8 цветов в `COLORS`.
- Очки: +1 за блок; очистка `(линии×10 + (линии-1)×5) × combo`.
- Комбо: сбрасывается при установке без очистки; плашка показывается 2500 мс от `comboShowUntil`.
- Анимация очистки: белая вспышка 260 мс (`CLEAR_ANIM_MS`, список `clearedCells`).
- Анкор установки: курсор = центр фигуры (`anchorFor`), с клампом к краям поля.

## Дальнейшие задачи (для агента; детали и статус — в issues)

1. **Клик-режим как фолбэк**: выбор фигуры одиночным кликом + установка кликом (для тач-скринов и тех, кому драг неудобен). Тумблер в настройках (`BlockBlastVisibleConfig`).
2. **Анимации установки**: плавное появление фигуры на поле (масштаб 0.8→1 за ~120 мс), аналогично анимациям 2048.
3. **Умная подсветка**: если фигура не влезает под курсором — подсвечивать ближайшую позицию, где влезает.
4. **Комбо-окно настройкой**: сейчас комбо живёт до первой установки без очистки; добавить опцию «мягкое комбо» (сброс по таймеру, а не по ходу).
5. **Рестарт без подтверждения теряет прогресс** — добавить подтверждение, если score > 0.
6. Проверить апстрим на обновления ветки 26.3 и перебазировать при необходимости.
7. Идея на будущее: ночной режим доски как отдельный ресурс-пак, не трогая код.

## Стиль

- Код — в стиле окружающих файлов того же пакета (см. `game2048`): camelCase, `fillRoundedRect` копируется как утилита экрана, комментари в коде — краткие, EN или RU как в файле.
- Коммиты: императив, EN, кратко («Add ...», «Fix ...»).
- Никогда не коммитить: собранные JAR, токены, personal URL-ы сверх уже публичных констант `LeaderboardLink`.
