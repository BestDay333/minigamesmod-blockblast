package org.trivait.minigamesmod.minigame.blockblast;

import me.shedaniel.autoconfig.AutoConfig;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextIconButtonWidget;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.trivait.minigamesmod.MinigamesMod;
import org.trivait.minigamesmod.api.MinigameRegistry;
import org.trivait.minigamesmod.api.PlayingSoundManager;
import org.trivait.minigamesmod.gui.widget.ConfigButton;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class BlockBlastScreen extends Screen {
    private final BlockBlast minigame;
    private final Screen parent;
    private static final int SIZE = 8;
    private static final int GAP = 4;
    private static final int BOARD_RADIUS = 6;
    private static final int PANEL_H = 64;

    private final int[][] grid = new int[SIZE][SIZE];
    private long score = 0;
    private boolean gameOver = false;
    private final List<Piece> tray = new ArrayList<>();
    private Piece selected = null;

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
        int count() {
            int n = 0;
            for (boolean[] row : cells) for (boolean c : row) if (c) n++;
            return n;
        }
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
        super(Text.translatable("minigame.blockblast.title"));
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

    @Override
    protected void init() {
        restart();
        rebuildButtons();
    }

    private void rebuildButtons() {
        clearChildren();
        ButtonWidget returnButton = TextIconButtonWidget.builder(Text.empty(), button -> this.close(), true)
                .texture(Identifier.of(MinigamesMod.MOD_ID, "icon/return"), 15, 15).build();
        returnButton.setTooltip(Tooltip.of(Text.translatable("minigame.blockblast.undo")));
        returnButton.setDimensionsAndPosition(20, 20, 10, 10);

        ButtonWidget restartButton = TextIconButtonWidget.builder(Text.empty(), button -> restart(), true)
                .texture(Identifier.of(MinigamesMod.MOD_ID, "icon/restart"), 15, 15).build();
        restartButton.setTooltip(Tooltip.of(Text.translatable("minigame.restart")));
        restartButton.setDimensionsAndPosition(20, 20, 35, 10);

        this.addDrawableChild(restartButton);
        this.addDrawableChild(returnButton);
        this.addDrawableChild(new ConfigButton(60, 10, minigame));
    }

    private void restart() {
        for (int[] row : grid) java.util.Arrays.fill(row, 0);
        score = 0;
        gameOver = false;
        selected = null;
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

    /** Анкор «по центру»: пробуем, чтобы курсор был серединой фигуры. */
    private int[] anchorFor(Piece p, int mouseR, int mouseC) {
        int r0 = mouseR - p.rows() / 2;
        int c0 = mouseC - p.cols() / 2;
        r0 = Math.max(0, Math.min(SIZE - p.rows(), r0));
        c0 = Math.max(0, Math.min(SIZE - p.cols(), c0));
        return new int[]{r0, c0};
    }

    private void placePiece(Piece p, int r0, int c0) {
        for (int r = 0; r < p.rows(); r++)
            for (int c = 0; c < p.cols(); c++)
                if (p.cells[r][c]) {
                    grid[r0 + r][c0 + c] = p.color;
                    score += 1;
                }
        p.used = true;
        selected = null;
        PlayingSoundManager.playSound(SoundEvents.BLOCK_WOOD_PLACE, new Random().nextFloat(0.9f, 1.2f), vol());

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
            for (int r : fullRows) java.util.Arrays.fill(grid[r], 0);
            for (int c : fullCols) for (int r = 0; r < SIZE; r++) grid[r][c] = 0;
            score += lines * 10L + (lines - 1) * 5L;
            PlayingSoundManager.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP,
                    new Random().nextFloat(0.9f, 1.3f), vol());
        }

        if (tray.stream().allMatch(x -> x.used)) refillTray();
        if (!anyMoveLeft()) {
            gameOver = true;
            saveBestScore();
            minigame.onLose();
            minigame.getLeaderboard().doPost((int) score);
        }
    }

    private void saveBestScore() {
        BlockBlastConfig cfg = MinigameRegistry.getConfig(BlockBlastConfig.class);
        if (score > cfg.bestScore) {
            cfg.bestScore = score;
            AutoConfig.getConfigHolder(BlockBlastConfig.class).save();
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (gameOver) return super.mouseClicked(mouseX, mouseY, button);
        int cell = cellSize();
        int bx = boardX(), by = boardY();

        // Выбор фигуры из лотка
        int slotW = boardW() / 3;
        for (int i = 0; i < tray.size(); i++) {
            Piece p = tray.get(i);
            if (p.used) continue;
            int px = bx + i * slotW + slotW / 2 - p.cols() * trayCell() / 2;
            int py = trayY();
            if (mouseX >= px && mouseX < px + p.cols() * trayCell()
                    && mouseY >= py && mouseY < py + p.rows() * trayCell()) {
                selected = selected == p ? null : p;
                if (selected != null) {
                    PlayingSoundManager.playSound(SoundEvents.UI_BUTTON_CLICK.value(),
                            new Random().nextFloat(0.9f, 1.1f), vol());
                }
                return true;
            }
        }

        // Установка выбранной фигуры на поле
        if (selected != null) {
            int c = (int)((mouseX - bx - GAP) / (cell + GAP));
            int r = (int)((mouseY - by - GAP) / (cell + GAP));
            if (r >= 0 && r < SIZE && c >= 0 && c < SIZE) {
                int[] a = anchorFor(selected, r, c);
                if (fits(selected, a[0], a[1])) {
                    placePiece(selected, a[0], a[1]);
                    return true;
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private void fillRoundedRect(DrawContext context, int x, int y, int w, int h, int r, int color) {
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

    private void drawBlock(DrawContext context, int x, int y, int size, int color) {
        fillRoundedRect(context, x + 1, y + 1, size - 2, size - 2, 3, color);
        fillRoundedRect(context, x + 3, y + 3, size - 6, (size - 6) / 2, 2,
                ((color & 0x00FFFFFF) | 0x66FFFFFF));
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        super.render(context, mouseX, mouseY, delta);
        int cell = cellSize();
        int bw = boardW();
        int bx = boardX(), by = boardY();

        fillRoundedRect(context, bx, by, bw, bw, BOARD_RADIUS, 0xFF2B2B33);
        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                fillRoundedRect(context, bx + GAP + c * (cell + GAP), by + GAP + r * (cell + GAP),
                        cell, cell, 3, 0xFF3C3C46);

        for (int r = 0; r < SIZE; r++)
            for (int c = 0; c < SIZE; c++)
                if (grid[r][c] != 0)
                    drawBlock(context, bx + GAP + c * (cell + GAP), by + GAP + r * (cell + GAP),
                            cell, grid[r][c]);

        // Призрак выбранной фигуры под курсором
        if (selected != null && !gameOver) {
            int c = (int)((mouseX - bx - GAP) / (cell + GAP));
            int r = (int)((mouseY - by - GAP) / (cell + GAP));
            if (r >= 0 && r < SIZE && c >= 0 && c < SIZE) {
                int[] a = anchorFor(selected, r, c);
                boolean ok = fits(selected, a[0], a[1]);
                int ghost = ok ? (selected.color & 0x66FFFFFF) | 0x33000000 : 0x30FF4444;
                for (int pr = 0; pr < selected.rows(); pr++)
                    for (int pc = 0; pc < selected.cols(); pc++)
                        if (selected.cells[pr][pc]) {
                            int gx = bx + GAP + (a[1] + pc) * (cell + GAP);
                            int gy = by + GAP + (a[0] + pr) * (cell + GAP);
                            fillRoundedRect(context, gx + 1, gy + 1, cell - 2, cell - 2, 3, ghost);
                        }
            }
        }

        // Лоток с фигурами
        int slotW = bw / 3;
        for (int i = 0; i < tray.size(); i++) {
            Piece p = tray.get(i);
            if (p.used) continue;
            int px = bx + i * slotW + slotW / 2 - p.cols() * trayCell() / 2;
            int py = trayY();
            for (int pr = 0; pr < p.rows(); pr++)
                for (int pc = 0; pc < p.cols(); pc++)
                    if (p.cells[pr][pc]) {
                        int color = (p == selected)
                                ? ((p.color & 0x00FFFFFF) | 0xFFFFFFFF)
                                : p.color;
                        drawBlock(context, px + pc * trayCell(), py + pr * trayCell(),
                                trayCell(), color);
                    }
        }

        long best = MinigameRegistry.getConfig(BlockBlastConfig.class).bestScore;
        int sbW = bw / 2 - 4, sbH = 32;
        int sx = bx + sbW + 8, sy = by - sbH - 6;

        fillRoundedRect(context, sx, sy, sbW, sbH, 4, 0xFF2B2B33);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("minigame.blockblast.score"),
                sx + sbW / 2, sy + 4, 0xFFB0B0C0);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(String.valueOf(score)),
                sx + sbW / 2, sy + 16, 0xFFFFFFFF);

        fillRoundedRect(context, bx, sy, sbW, sbH, 4, 0xFF2B2B33);
        context.drawCenteredTextWithShadow(textRenderer, Text.translatable("minigame.blockblast.best"),
                bx + sbW / 2, sy + 4, 0xFFB0B0C0);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal(String.valueOf(best)),
                bx + sbW / 2, sy + 16, 0xFFFFFFFF);

        if (gameOver) {
            context.fill(bx, by, bx + bw, by + bw, 0xAA14141A);
            context.drawCenteredTextWithShadow(textRenderer,
                    Text.translatable("minigame.blockblast.game_over"),
                    this.width / 2, by + bw / 2 - 10, 0xFFFF4444);
            context.drawCenteredTextWithShadow(textRenderer, Text.literal(String.valueOf(score)),
                    this.width / 2, by + bw / 2 + 4, 0xFFFFFFFF);
        }
    }

    private float vol() {
        return PlayingSoundManager.vol(MinigameRegistry.getConfig(BlockBlastVisibleConfig.class).volume);
    }

    @Override
    public void close() {
        minigame.onStop();
        this.client.setScreen(parent);
    }

    @Override
    public boolean shouldPause() {
        return false;
    }
}
