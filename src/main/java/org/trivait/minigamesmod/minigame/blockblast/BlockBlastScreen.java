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
    private static final int BOARD_RADIUS = 6;
    private static final int PANEL_H = 64;
    private static final long COMBO_DISPLAY_MS = 2500;
    private static final long CLEAR_ANIM_MS = 260;

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

    private int cellSize() {
        int availH = this.height - PANEL_H - 44 - 16;
        int maxBoard = Math.min(this.width - 20, availH);
        int cell = (maxBoard - GAP) / SIZE - GAP;
        return Math.min(48, cell);
    }

    private int boardW() { return SIZE * (cellSize() + GAP) + GAP; }
    private int boardX() { return (this.width - boardW()) / 2; }
    private int boardY() { return 44 + 8 + (this.height - PANEL_H - 44 - 8 - boardW()) / 2; }

    private int trayCell() { return Math.max(8, cellSize() / 3); }
    private int trayY() { return this.height - PANEL_H + 10; }

    private int trayPieceX(Piece p, int slot) {
        int slotW = boardW() / 3;
        return boardX() + slot * slotW + slotW / 2 - p.cols() * trayCell() / 2;
    }

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

    /** Анкор «по центру»: курсор трактуется как середина фигуры. */
    private int[] anchorFor(Piece p, int mouseR, int mouseC) {
        int r0 = mouseR - p.rows() / 2;
        int c0 = mouseC - p.cols() / 2;
        r0 = Math.max(0, Math.min(SIZE - p.rows(), r0));
        c0 = Math.max(0, Math.min(SIZE - p.cols(), c0));
        return new int[]{r0, c0};
    }

    private int cellAt(double coord, int boardPos) {
        return (int)((coord - boardPos - GAP) / (cellSize() + GAP));
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
            // Очистка: 10 за линию, +5 за каждую дополнительную, всё умножается на комбо
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

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (gameOver) return super.mouseClicked(click, doubled);
        double mouseX = click.x();
        double mouseY = click.y();

        // Начало перетаскивания фигуры из лотка
        for (int i = 0; i < tray.size(); i++) {
            Piece p = tray.get(i);
            if (p.used) continue;
            int px = trayPieceX(p, i);
            int py = trayY();
            if (mouseX >= px && mouseX < px + p.cols() * trayCell()
                    && mouseY >= py && mouseY < py + p.rows() * trayCell()) {
                dragging = p;
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
            int cell = cellSize();
            int bx = boardX(), by = boardY();
            int c = cellAt(click.x(), bx);
            int r = cellAt(click.y(), by);
            if (r >= 0 && r < SIZE && c >= 0 && c < SIZE) {
                int[] a = anchorFor(p, r, c);
                if (fits(p, a[0], a[1])) {
                    placePiece(p, a[0], a[1]);
                    return true;
                }
            }
            // Не влезла — возвращается в лоток
            PlayingSoundManager.playSound(SoundEvents.WOOD_HIT,
                    new Random().nextFloat(0.8f, 1f), vol());
            return true;
        }
        return super.mouseReleased(click);
    }

    private void fillRoundedRect(GuiGraphicsExtractor context, int x, int y, int w, int h, int r, int color) {
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

    private void drawBlock(GuiGraphicsExtractor context, int x, int y, int size, int color) {
        fillRoundedRect(context, x + 1, y + 1, size - 2, size - 2, 3, color);
        fillRoundedRect(context, x + 3, y + 3, size - 6, (size - 6) / 2, 2,
                ((color & 0x00FFFFFF) | 0x66FFFFFF));
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);
        int cell = cellSize();
        int bw = boardW();
        int bx = boardX(), by = boardY();

        fillRoundedRect(context, bx, by, bw, bw, BOARD_RADIUS, 0xFF2B2B33);
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                fillRoundedRect(context, bx + GAP + c * (cell + GAP), by + GAP + r * (cell + GAP),
                        cell, cell, 3, 0xFF3C3C46);

        // Вспышка на месте только что очищенных линий
        long clearAge = System.currentTimeMillis() - clearAnimStart;
        boolean clearAnim = clearAge >= 0 && clearAge < CLEAR_ANIM_MS && !clearedCells.isEmpty();
        if (!clearAnim && !clearedCells.isEmpty()) clearedCells.clear();

        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (grid[r][c] != 0)
                    drawBlock(context, bx + GAP + c * (cell + GAP), by + GAP + r * (cell + GAP),
                            cell, grid[r][c]);

        if (clearAnim) {
            float t = 1f - (float) clearAge / CLEAR_ANIM_MS;
            int alpha = (int)(0xB0 * t) << 24;
            for (int[] cellPos : clearedCells) {
                int gx = bx + GAP + cellPos[1] * (cell + GAP);
                int gy = by + GAP + cellPos[0] * (cell + GAP);
                fillRoundedRect(context, gx + 1, gy + 1, cell - 2, cell - 2, 3, alpha | 0xFFFFFF);
            }
        }

        // Призрачный предпросмотр перетаскиваемой фигуры
        boolean overBoard = false;
        if (dragging != null && !gameOver) {
            int c = cellAt(mouseX, bx);
            int r = cellAt(mouseY, by);
            if (r >= 0 && r < SIZE && c >= 0 && c < SIZE) {
                overBoard = true;
                int[] a = anchorFor(dragging, r, c);
                boolean ok = fits(dragging, a[0], a[1]);
                int ghost = ok ? (dragging.color & 0x66FFFFFF) | 0x33000000 : 0x30FF4444;
                for (int pr = 0; pr < dragging.rows(); pr++)
                    for (int pc = 0; pc < dragging.cols(); pc++)
                        if (dragging.cells[pr][pc]) {
                            int gx = bx + GAP + (a[1] + pc) * (cell + GAP);
                            int gy = by + GAP + (a[0] + pr) * (cell + GAP);
                            fillRoundedRect(context, gx + 1, gy + 1, cell - 2, cell - 2, 3, ghost);
                        }
            }
        }

        // Лоток с фигурами (перетаскиваемая рисуется у курсора)
        int slotW = bw / 3;
        for (int i = 0; i < tray.size(); i++) {
            Piece p = tray.get(i);
            if (p.used) continue;
            if (p == dragging) continue;
            int px = trayPieceX(p, i);
            int py = trayY();
            for (int pr = 0; pr < p.rows(); pr++)
                for (int pc = 0; pc < p.cols(); pc++)
                    if (p.cells[pr][pc])
                        drawBlock(context, px + pc * trayCell(), py + pr * trayCell(),
                                trayCell(), p.color);
        }

        // Фигура в руке: следует за курсором, приподнята и чуть увеличена
        if (dragging != null) {
            float dragCell = trayCell() * (overBoard ? 1.25f : 1.1f);
            int px = (int)(mouseX - dragging.cols() * dragCell / 2);
            int py = (int)(mouseY - dragging.rows() * dragCell / 2);
            for (int pr = 0; pr < dragging.rows(); pr++)
                for (int pc = 0; pc < dragging.cols(); pc++)
                    if (dragging.cells[pr][pc]) {
                        int x = px + (int)(pc * dragCell);
                        int y = py + (int)(pr * dragCell);
                        drawBlock(context, x, y, (int)dragCell, dragging.color);
                        fillRoundedRect(context, x, y + (int)dragCell, (int)dragCell, 2, 0,
                                0x30000000);
                    }
        }

        long best = MinigameRegistry.getConfig(BlockBlastConfig.class).bestScore;
        long bestComboRec = MinigameRegistry.getConfig(BlockBlastConfig.class).bestCombo;
        int sbW = bw / 2 - 4, sbH = 32;
        int sx = bx + sbW + 8, sy = by - sbH - 6;

        fillRoundedRect(context, sx, sy, sbW, sbH, 4, 0xFF2B2B33);
        context.centeredText(font, Component.translatable("minigame.blockblast.score"),
                sx + sbW / 2, sy + 4, 0xFFB0B0C0);
        context.centeredText(font, Component.literal(String.valueOf(score)),
                sx + sbW / 2, sy + 16, 0xFFFFFFFF);

        fillRoundedRect(context, bx, sy, sbW, sbH, 4, 0xFF2B2B33);
        context.centeredText(font, Component.translatable("minigame.blockblast.best"),
                bx + sbW / 2, sy + 4, 0xFFB0B0C0);
        context.centeredText(font, Component.literal(String.valueOf(best)),
                bx + sbW / 2, sy + 16, 0xFFFFFFFF);

        // Комбо-плашка с плавным затуханием
        long now = System.currentTimeMillis();
        if (combo >= 2 && now < comboShowUntil) {
            float fade = Math.min(1f, (float)(comboShowUntil - now) / 600f);
            int alpha = (int)(0xFF * fade);
            String comboText = Component.translatable("minigame.blockblast.combo", combo).getString();
            int textW = font.width(comboText);
            int cx = this.width / 2 - textW / 2 - 6;
            int cy = by + bw + 6;
            fillRoundedRect(context, cx, cy, textW + 12, 14, 4, (alpha / 2) << 24 | 0x00FFD54F);
            context.centeredText(font, Component.literal(comboText),
                    this.width / 2, cy + 3, (alpha << 24) | 0xFFFFD54F);
        }

        if (gameOver) {
            context.fill(bx, by, bx + bw, by + bw, 0xAA14141A);
            context.centeredText(font, Component.translatable("minigame.blockblast.game_over"),
                    this.width / 2, by + bw / 2 - 16, 0xFFFF4444);
            context.centeredText(font, Component.literal(String.valueOf(score)),
                    this.width / 2, by + bw / 2 - 2, 0xFFFFFFFF);
            context.centeredText(font, Component.translatable("minigame.blockblast.best_combo",
                    Math.max(bestComboRec, combo)),
                    this.width / 2, by + bw / 2 + 10, 0xFFFFD54F);
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
