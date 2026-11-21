package org.trivait.minigamesmod.minigame.blockblast;

import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.trivait.minigamesmod.MinigamesMod;
import org.trivait.minigamesmod.api.MinigameRegistry;
import org.trivait.minigamesmod.api.PlayingSoundManager;
import org.trivait.minigamesmod.gui.widget.ConfigButton;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

public class BlockBlastScreen extends Screen {
    private final BlockBlast minigame;
    private final Screen parent;
    private static final int SIZE = 8;
    private static final int GAP = 4;
    private static final int BOARD_RADIUS = 8;
    private static final int PANEL_H = 72;
    private static final long COMBO_DISPLAY_MS = 2500;
    private static final long CLEAR_ANIM_MS = 320;

    // Hover & drag state
    private int hoverSlot = -1;         // лоток: какой слот подсвечен
    private float dragStartX, dragStartY; // откуда начали тащить
    private long dragStartTime = 0;      // момент подхвата
    private static final long LIFT_MS = 120; // время анимации «поднятия»

    private final int[][] grid = new int[SIZE][SIZE];
    private long score = 0;
    private boolean gameOver = false;
    private final List<Piece> tray = new ArrayList<>();
    private Piece dragging = null;
    private int combo = 0;
    private long comboShowUntil = 0;
    private long clearAnimStart = -CLEAR_ANIM_MS;
    private final List<int[]> clearedCells = new ArrayList<>();

    private static class Piece {
        final boolean[][] cells;
        final int color;
        boolean used = false;
        Piece(boolean[][] cells, int color) {
            this.cells = cells;
            this.color = color;
        }
        int rows() { return cells.length; }
        int cols() { return cells[0].length; }
    }

    private static final boolean[][][] SHAPES = {
            {{true, true, true, true}},
            {{true}, {true}, {true}, {true}},
            {{true, true, true}},
            {{true}, {true}, {true}},
            {{true, true}},
            {{true}, {true}},
            {{true, true}, {true, true}},
            {{true, true}, {true, true}, {true, true}},
            {{true, true, true}, {true, true, true}},
            {{true, true, true}, {true, true, true}, {true, true, true}},
            {{true, false}, {true, false}, {true, true}},
            {{false, true}, {false, true}, {true, true}},
            {{true, true}, {true, false}, {true, false}},
            {{true, true}, {false, true}, {false, true}},
            {{true, true, true}, {false, true, false}},
            {{false, true, false}, {true, true, true}},
            {{true, false, false}, {true, true, true}},
            {{false, false, true}, {true, true, true}},
            {{true, true, true}, {true, true, true}, {false, false, false}}
    };

    private static final int[] COLORS = {
            0xFF4FC3F7, 0xFF81C784, 0xFFFFB74D, 0xFFE57373,
            0xFFBA68C8, 0xFFFFD54F, 0xFF4DB6AC, 0xFFF06292
    };

    public BlockBlastScreen(BlockBlast minigame, Screen parent) {
        super(Component.translatable("minigame.blockblast.title"));
        this.minigame = minigame;
        this.parent = parent;
    }

    // ─── Layout ──────────────────────────────────────────────────────────────

    private int cellSize() {
        int availH = this.height - PANEL_H - 44 - 16;
        int maxBoard = Math.min(this.width - 20, availH);
        int cell = (maxBoard - GAP) / SIZE - GAP;
        return Math.min(48, cell);
    }

    private int boardW() { return SIZE * (cellSize() + GAP) + GAP; }
    private int boardX() { return (this.width - boardW()) / 2; }
    private int boardY() { return 44 + 8 + (this.height - PANEL_H - 44 - 8 - boardW()) / 2; }

    private int trayCell() { return Math.max(10, cellSize() / 3); }
    private int trayY() { return this.height - PANEL_H + 12; }

    private int trayPieceX(Piece p, int slot) {
        int slotW = boardW() / 3;
        // Центрируем фигуру внутри слота по горизонтали
        return boardX() + slot * slotW + (slotW - p.cols() * trayCell()) / 2;
    }

    private int trayPieceY(Piece p) {
        // Вертикально центрируем в области лотка
        int trayH = PANEL_H - 20;
        return trayY() + (trayH - p.rows() * trayCell()) / 2;
    }

    // ─── Init / restart ───────────────────────────────────────────────────────

    @Override
    protected void init() {
        restart();
    }

    private void rebuildButtons() {
        clearWidgets();
        Button returnButton = SpriteIconButton.builder(Component.empty(), button -> this.onClose(), true)
                .sprite(Identifier.fromNamespaceAndPath(MinigamesMod.MOD_ID, "icon/return"), 15, 15).build();
        returnButton.setTooltip(Tooltip.create(Component.translatable("minigame.blockblast.undo")));
        returnButton.setRectangle(20, 20, 10, 10);

        Button restartButton = SpriteIconButton.builder(Component.empty(), button -> restart(), true)
                .sprite(Identifier.fromNamespaceAndPath(MinigamesMod.MOD_ID, "icon/restart"), 15, 15).build();
        restartButton.setTooltip(Tooltip.create(Component.translatable("minigame.restart")));
        restartButton.setRectangle(20, 20, 35, 10);

        this.addRenderableWidget(restartButton);
        this.addRenderableWidget(returnButton);
        this.addRenderableWidget(new ConfigButton(60, 10, minigame));
    }

    private void restart() {
        for (int[] row : grid) Arrays.fill(row, 0);
        score = 0;
        gameOver = false;
        dragging = null;
        combo = 0;
        comboShowUntil = 0;
        clearedCells.clear();
        tray.clear();
        hoverSlot = -1;
        refillTray();
        rebuildButtons();
    }

    private void refillTray() {
        Random random = new Random();
        tray.clear();
        for (int i = 0; i < 3; i++) {
            boolean[][] shape = SHAPES[random.nextInt(SHAPES.length)];
            tray.add(new Piece(shape, COLORS[random.nextInt(COLORS.length)]));
        }
    }

    // ─── Game logic ───────────────────────────────────────────────────────────

    private boolean fits(Piece p, int r0, int c0) {
        for (int r = 0; r < p.rows(); r++)
            for (int c = 0; c < p.cols(); c++)
                if (p.cells[r][c]) {
                    int gr = r0 + r, gc = c0 + c;
                    if (gr < 0 || gr >= SIZE || gc < 0 || gc >= SIZE || grid[gr][gc] != 0) return false;
                }
        return true;
    }

    private boolean anyMoveLeft() {
        for (Piece p : tray) {
            if (p.used) continue;
            for (int r = 0; r <= SIZE - p.rows(); r++)
                for (int c = 0; c <= SIZE - p.cols(); c++)
                    if (fits(p, r, c)) return true;
        }
        return false;
    }

    /**
     * Вычисляем anchor: привязываем ближайшую клетку фигуры к курсору.
     * Snap идёт по ближайшей целой позиции, а не по центру — это значительно
     * удобнее при размещении.
     */
    private int[] anchorFor(Piece p, double mouseX, double mouseY) {
        int cell = cellSize();
        int bx = boardX(), by = boardY();
        // Определяем «центральный» блок фигуры (для snap)
        int halfR = p.rows() / 2;
        int halfC = p.cols() / 2;

        // Ячейка под курсором
        int mc = (int) ((mouseX - bx - GAP) / (cell + GAP));
        int mr = (int) ((mouseY - by - GAP) / (cell + GAP));

        // Anchor = смещаем так чтобы центр фигуры совпал с ячейкой под курсором
        int r0 = mr - halfR;
        int c0 = mc - halfC;
        r0 = Math.max(0, Math.min(SIZE - p.rows(), r0));
        c0 = Math.max(0, Math.min(SIZE - p.cols(), c0));
        return new int[]{r0, c0};
    }

    /** Находит ближайший допустимый anchor если текущий не подходит (snap assist). */
    private int[] snapAnchor(Piece p, int r0, int c0) {
        if (fits(p, r0, c0)) return new int[]{r0, c0};
        // Пробуем соседние позиции с радиусом 1
        for (int dr = -1; dr <= 1; dr++)
            for (int dc = -1; dc <= 1; dc++) {
                if (dr == 0 && dc == 0) continue;
                int nr = r0 + dr, nc = c0 + dc;
                if (nr >= 0 && nr + p.rows() <= SIZE && nc >= 0 && nc + p.cols() <= SIZE)
                    if (fits(p, nr, nc)) return new int[]{nr, nc};
            }
        return new int[]{r0, c0};
    }

    private boolean isOverBoard(double mouseX, double mouseY) {
        int bx = boardX(), by = boardY(), bw = boardW();
        return mouseX >= bx && mouseX < bx + bw && mouseY >= by && mouseY < by + bw;
    }

    private void placePiece(Piece p, int r0, int c0) {
        for (int r = 0; r < p.rows(); r++)
            for (int c = 0; c < p.cols(); c++)
                if (p.cells[r][c]) {
                    grid[r0 + r][c0 + c] = p.color;
                    score += 1;
                }
        p.used = true;

        List<Integer> fullRows = new ArrayList<>();
        List<Integer> fullCols = new ArrayList<>();
        for (int r = 0; r < SIZE; r++) {
            boolean full = true;
            for (int c = 0; c < SIZE; c++) if (grid[r][c] == 0) { full = false; break; }
            if (full) fullRows.add(r);
        }
        for (int c = 0; c < SIZE; c++) {
            boolean full = true;
            for (int r = 0; r < SIZE; r++) if (grid[r][c] == 0) { full = false; break; }
            if (full) fullCols.add(c);
        }
        int lines = fullRows.size() + fullCols.size();
        if (lines > 0) {
            clearedCells.clear();
            for (int r : fullRows)
                for (int c = 0; c < SIZE; c++) { clearedCells.add(new int[]{r, c}); grid[r][c] = 0; }
            for (int c : fullCols)
                for (int r = 0; r < SIZE; r++) { clearedCells.add(new int[]{r, c}); grid[r][c] = 0; }
            clearAnimStart = System.currentTimeMillis();

            combo++;
            long gain = (lines * 10L + (lines - 1) * 5L) * Math.max(1, combo);
            score += gain;
            comboShowUntil = System.currentTimeMillis() + COMBO_DISPLAY_MS;
            saveComboRecord();
            PlayingSoundManager.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP,
                    Math.min(2f, 0.9f + 0.15f * combo), vol());
            if (combo >= 2) {
                PlayingSoundManager.playSound(SoundEvents.PLAYER_LEVELUP,
                        Math.min(2f, 1f + 0.1f * combo), vol());
            }
        } else {
            combo = 0;
            PlayingSoundManager.playSound(SoundEvents.WOOD_PLACE,
                    new Random().nextFloat(0.9f, 1.2f), vol());
        }

        if (tray.stream().allMatch(x -> x.used)) refillTray();
        if (!anyMoveLeft()) {
            gameOver = true;
            saveBestScore();
            minigame.onLose();
            minigame.getLeaderboard().doPost(score);
        }
    }

    private void saveBestScore() {
        BlockBlastConfig cfg = MinigameRegistry.getConfig(BlockBlastConfig.class);
        if (score > cfg.bestScore) {
            cfg.bestScore = score;
            AutoConfig.getConfigHolder(BlockBlastConfig.class).save();
        }
    }

    private void saveComboRecord() {
        BlockBlastConfig cfg = MinigameRegistry.getConfig(BlockBlastConfig.class);
        if (combo > cfg.bestCombo) {
            cfg.bestCombo = combo;
            AutoConfig.getConfigHolder(BlockBlastConfig.class).save();
        }
    }

    // ─── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (gameOver) return super.mouseClicked(click, doubled);
        double mx = click.x(), my = click.y();

        for (int i = 0; i < tray.size(); i++) {
            Piece p = tray.get(i);
            if (p.used) continue;
            int px = trayPieceX(p, i);
            int py = trayPieceY(p);
            if (mx >= px && mx < px + p.cols() * trayCell()
                    && my >= py && my < py + p.rows() * trayCell()) {
                dragging = p;
                dragStartX = (float) mx;
                dragStartY = (float) my;
                dragStartTime = System.currentTimeMillis();
                PlayingSoundManager.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP,
                        new Random().nextFloat(0.9f, 1.1f), vol());
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragging != null && !gameOver) {
            Piece p = dragging;
            dragging = null;
            double mx = click.x(), my = click.y();
            if (isOverBoard(mx, my)) {
                int[] a = anchorFor(p, mx, my);
                a = snapAnchor(p, a[0], a[1]);
                if (fits(p, a[0], a[1])) {
                    placePiece(p, a[0], a[1]);
                    return true;
                }
            }
            // Не поместилось — возвращаем
            PlayingSoundManager.playSound(SoundEvents.WOOD_HIT,
                    new Random().nextFloat(0.8f, 1f), vol());
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseMoved(double mouseX, double mouseY) {
        if (dragging != null) return true;
        // Определяем hover для лотка
        hoverSlot = -1;
        for (int i = 0; i < tray.size(); i++) {
            Piece p = tray.get(i);
            if (p.used) continue;
            int px = trayPieceX(p, i);
            int py = trayPieceY(p);
            int slotW = boardW() / 3;
            // Hover-зона — весь слот, а не только фигура
            int slotX = boardX() + i * slotW;
            if (mouseX >= slotX && mouseX < slotX + slotW
                    && mouseY >= trayY() && mouseY < trayY() + PANEL_H - 20) {
                hoverSlot = i;
                break;
            }
        }
        return super.mouseMoved(mouseX, mouseY);
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    private void fillRoundedRect(GuiGraphicsExtractor context, int x, int y, int w, int h, int r, int color) {
        if (r <= 0) { context.fill(x, y, x + w, y + h, color); return; }
        context.fill(x + r, y, x + w - r, y + h, color);
        context.fill(x, y + r, x + r, y + h - r, color);
        context.fill(x + w - r, y + r, x + w, y + h - r, color);
        for (int i = 0; i < r; i++) {
            for (int j = 0; j < r; j++) {
                if (Math.sqrt((double)(r - 1 - i) * (r - 1 - i) + (double)(r - 1 - j) * (r - 1 - j)) < r) {
                    context.fill(x + i, y + j, x + i + 1, y + j + 1, color);
                    context.fill(x + w - 1 - i, y + j, x + w - i, y + j + 1, color);
                    context.fill(x + i, y + h - 1 - j, x + i + 1, y + h - j, color);
                    context.fill(x + w - 1 - i, y + h - 1 - j, x + w - i, y + h - j, color);
                }
            }
        }
    }

    /** Рисует один блок с бликом и лёгкой тенью снизу */
    private void drawBlock(GuiGraphicsExtractor context, int x, int y, int size, int color) {
        // Основной цвет
        fillRoundedRect(context, x + 1, y + 1, size - 2, size - 2, 3, color);
        // Блик сверху
        int blick = ((color & 0x00FFFFFF) | 0x55FFFFFF);
        fillRoundedRect(context, x + 3, y + 3, size - 6, (size - 6) / 2, 2, blick);
        // Тёмный край снизу
        int shadow = ((color >> 1) & 0x007F7F7F) | (color & 0xFF000000);
        context.fill(x + 2, y + size - 3, x + size - 2, y + size - 1, shadow);
    }

    /** Рисует блок с масштабом (для drag анимации) */
    private void drawBlockF(GuiGraphicsExtractor context, float x, float y, float size, int color) {
        drawBlock(context, (int) x, (int) y, (int) size, color);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        int cell = cellSize();
        int bw = boardW();
        int bx = boardX(), by = boardY();
        long now = System.currentTimeMillis();

        // ── Доска ──
        fillRoundedRect(context, bx, by, bw, bw, BOARD_RADIUS, 0xFF1E1E28);

        // Линии сетки (тонкие) для ориентира
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                fillRoundedRect(context, bx + GAP + c * (cell + GAP), by + GAP + r * (cell + GAP),
                        cell, cell, 3, 0xFF2E2E3A);

        // ── Анимация очистки линий ──
        long clearAge = now - clearAnimStart;
        boolean clearAnim = clearAge >= 0 && clearAge < CLEAR_ANIM_MS && !clearedCells.isEmpty();
        if (!clearAnim && !clearedCells.isEmpty()) clearedCells.clear();

        // ── Заполненные клетки ──
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (grid[r][c] != 0)
                    drawBlock(context,
                            bx + GAP + c * (cell + GAP),
                            by + GAP + r * (cell + GAP),
                            cell, grid[r][c]);

        // ── Вспышка при очистке — радужная волна ──
        if (clearAnim) {
            float t = 1f - (float) clearAge / CLEAR_ANIM_MS;
            // Два кольца: белое + золотое
            int alpha1 = (int)(0xCC * t) << 24;
            int alpha2 = (int)(0x55 * t) << 24;
            for (int[] cellPos : clearedCells) {
                int gx = bx + GAP + cellPos[1] * (cell + GAP);
                int gy = by + GAP + cellPos[0] * (cell + GAP);
                fillRoundedRect(context, gx + 1, gy + 1, cell - 2, cell - 2, 3, alpha1 | 0xFFFFFF);
                // Золотой ореол чуть больше
                if (t > 0.4f)
                    fillRoundedRect(context, gx - 1, gy - 1, cell + 2, cell + 2, 4, alpha2 | 0xFFD700);
            }
        }

        // ── Призрак при перетаскивании на доску ──
        boolean overBoard = dragging != null && !gameOver && isOverBoard(mouseX, mouseY);
        if (overBoard) {
            int[] a = anchorFor(dragging, mouseX, mouseY);
            a = snapAnchor(dragging, a[0], a[1]);
            boolean ok = fits(dragging, a[0], a[1]);
            // Подсветка целевых клеток
            int ghostBase = dragging.color & 0x00FFFFFF;
            int ghost = ok ? (0x55 << 24 | ghostBase) : (0x44 << 24 | 0xFF4444);
            int border = ok ? (0x99 << 24 | ghostBase) : (0x88 << 24 | 0xFF4444);
            for (int pr = 0; pr < dragging.rows(); pr++)
                for (int pc = 0; pc < dragging.cols(); pc++)
                    if (dragging.cells[pr][pc]) {
                        int gx = bx + GAP + (a[1] + pc) * (cell + GAP);
                        int gy = by + GAP + (a[0] + pr) * (cell + GAP);
                        fillRoundedRect(context, gx + 1, gy + 1, cell - 2, cell - 2, 3, ghost);
                        // Рамка вокруг призрака
                        context.fill(gx + 1, gy, gx + cell - 1, gy + 2, border);
                        context.fill(gx + 1, gy + cell - 2, gx + cell - 1, gy + cell, border);
                        context.fill(gx, gy + 1, gx + 2, gy + cell - 1, border);
                        context.fill(gx + cell - 2, gy + 1, gx + cell, gy + cell - 1, border);
                    }
        }

        // ── Лоток: фон ──
        int trayBgY = this.height - PANEL_H + 4;
        fillRoundedRect(context, bx, trayBgY, bw, PANEL_H - 8, 8, 0xFF1E1E28);

        // Слот-разделители (лёгкие)
        int slotW = bw / 3;
        for (int i = 1; i < 3; i++) {
            int lx = bx + i * slotW;
            context.fill(lx, trayBgY + 8, lx + 1, trayBgY + PANEL_H - 16, 0xFF2E2E3A);
        }

        // ── Лоток: фигуры ──
        for (int i = 0; i < tray.size(); i++) {
            Piece p = tray.get(i);
            if (p.used) continue;
            if (p == dragging) continue;

            int px = trayPieceX(p, i);
            int py = trayPieceY(p);
            int tc = trayCell();

            // Hover-подсветка слота
            boolean hovered = (hoverSlot == i);
            if (hovered) {
                int hSlotX = bx + i * slotW + 2;
                fillRoundedRect(context, hSlotX, trayBgY + 2, slotW - 4, PANEL_H - 12, 6, 0x22FFFFFF);
            }

            // Масштаб при hover: фигура чуть крупнее
            float scale = hovered ? 1.15f : 1.0f;
            int scaledTc = (int)(tc * scale);
            int offX = (tc - scaledTc) * p.cols() / 2;
            int offY = (tc - scaledTc) * p.rows() / 2;

            for (int pr = 0; pr < p.rows(); pr++)
                for (int pc = 0; pc < p.cols(); pc++)
                    if (p.cells[pr][pc])
                        drawBlock(context,
                                px + offX + pc * scaledTc,
                                py + offY + pr * scaledTc,
                                scaledTc, p.color);
        }

        // ── Перетаскиваемая фигура — следует за курсором ──
        if (dragging != null && !gameOver) {
            // Плавное увеличение при подъёме (easing)
            long liftAge = now - dragStartTime;
            float liftT = Math.min(1f, (float) liftAge / LIFT_MS);
            // Ease out cubic
            liftT = 1f - (1f - liftT) * (1f - liftT) * (1f - liftT);

            float baseSize = trayCell();
            float targetSize = overBoard ? cellSize() * 0.95f : trayCell() * 1.2f;
            float dragCellF = baseSize + (targetSize - baseSize) * liftT;

            int px = (int)(mouseX - dragging.cols() * dragCellF / 2);
            int py = (int)(mouseY - dragging.rows() * dragCellF * 0.5f - dragging.rows() * dragCellF * 0.1f * liftT);

            // Тень под фигурой (только пока не на доске)
            if (!overBoard && liftT > 0.3f) {
                float shadowAlpha = 0.25f * liftT;
                int sa = (int)(shadowAlpha * 255) << 24;
                int shadowOff = (int)(4 * liftT);
                for (int pr = 0; pr < dragging.rows(); pr++)
                    for (int pc = 0; pc < dragging.cols(); pc++)
                        if (dragging.cells[pr][pc]) {
                            int sx = px + (int)(pc * dragCellF) + shadowOff;
                            int sy2 = py + (int)(pr * dragCellF) + shadowOff;
                            fillRoundedRect(context, sx + 1, sy2 + 1, (int) dragCellF - 2, (int) dragCellF - 2, 3, sa);
                        }
            }

            for (int pr = 0; pr < dragging.rows(); pr++)
                for (int pc = 0; pc < dragging.cols(); pc++)
                    if (dragging.cells[pr][pc]) {
                        int bxi = px + (int)(pc * dragCellF);
                        int byi = py + (int)(pr * dragCellF);
                        drawBlock(context, bxi, byi, (int) dragCellF, dragging.color);
                    }
        }

        // ── Счёт и рекорд ──
        int sbW = bw / 2 - 4, sbH = 32;
        int sx = bx + sbW + 8, sy = by - sbH - 6;
        long best = MinigameRegistry.getConfig(BlockBlastConfig.class).bestScore;
        long bestComboRec = MinigameRegistry.getConfig(BlockBlastConfig.class).bestCombo;

        fillRoundedRect(context, sx, sy, sbW, sbH, 5, 0xFF1E1E28);
        context.centeredText(font, Component.translatable("minigame.blockblast.score"),
                sx + sbW / 2, sy + 4, 0xFFB0B0C0);
        context.centeredText(font, Component.literal(String.valueOf(score)),
                sx + sbW / 2, sy + 16, 0xFFFFFFFF);

        fillRoundedRect(context, bx, sy, sbW, sbH, 5, 0xFF1E1E28);
        context.centeredText(font, Component.translatable("minigame.blockblast.best"),
                bx + sbW / 2, sy + 4, 0xFFB0B0C0);
        context.centeredText(font, Component.literal(String.valueOf(best)),
                bx + sbW / 2, sy + 16, 0xFFFFFFFF);

        // ── Комбо-плашка ──
        if (combo >= 2 && now < comboShowUntil) {
            float fade = Math.min(1f, (float)(comboShowUntil - now) / 600f);
            int alpha = (int)(0xFF * fade);
            String comboText = Component.translatable("minigame.blockblast.combo", combo).getString();
            int textW = font.width(comboText);
            int cx = this.width / 2 - textW / 2 - 8;
            int cy = by + bw + 8;
            fillRoundedRect(context, cx, cy, textW + 16, 16, 5, (alpha / 3) << 24 | 0x00FFD54F);
            context.centeredText(font, Component.literal(comboText),
                    this.width / 2, cy + 4, (alpha << 24) | 0xFFD54F);
        }

        // ── Game over оверлей ──
        if (gameOver) {
            fillRoundedRect(context, bx, by, bw, bw, BOARD_RADIUS, 0xBB0A0A14);
            context.centeredText(font, Component.translatable("minigame.blockblast.game_over"),
                    this.width / 2, by + bw / 2 - 20, 0xFFFF5555);
            context.centeredText(font, Component.literal(String.valueOf(score)),
                    this.width / 2, by + bw / 2 - 6, 0xFFFFFFFF);
            context.centeredText(font, Component.translatable("minigame.blockblast.best_combo",
                    Math.max(bestComboRec, combo)),
                    this.width / 2, by + bw / 2 + 8, 0xFFFFD54F);
            // Подсказка «нажмите restart»
            String hint = "Press Restart to play again";
            context.centeredText(font, Component.literal(hint),
                    this.width / 2, by + bw / 2 + 24, 0xFF808090);
        }
    }

    private float vol() {
        return PlayingSoundManager.vol(MinigameRegistry.getConfig(BlockBlastVisibleConfig.class).volume);
    }

    @Override
    public void onClose() {
        if (!gameOver) saveBestScore();
        minigame.onStop();
        this.minecraft.gui.setScreen(parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
