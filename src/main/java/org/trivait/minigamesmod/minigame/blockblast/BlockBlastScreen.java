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
import java.util.StringJoiner;

public class BlockBlastScreen extends Screen {
    private final BlockBlast minigame;
    private final Screen parent;
    private static final int SIZE = 8;
    private static final int GAP = 3;
    private static final int BOARD_RADIUS = 12;
    private static final int PANEL_H = 90;        // лоток снизу
    private static final int HEADER_H = 56;       // шапка
    private static final long COMBO_DISPLAY_MS = 2500;
    private static final long CLEAR_ANIM_MS = 380;

    // Drag state
    private int hoverSlot = -1;
    private long dragStartTime = 0;
    private static final long LIFT_MS = 100;

    private final int[][] grid = new int[SIZE][SIZE];
    private long score = 0;
    private boolean gameOver = false;
    private final List<Piece> tray = new ArrayList<>();
    private Piece dragging = null;
    private int combo = 0;
    private long comboShowUntil = 0;
    private long clearAnimStart = -CLEAR_ANIM_MS;
    private final List<int[]> clearedCells = new ArrayList<>();

    // Плавные анимации: сглаженная позиция/масштаб перетаскивания, масштаб слотов лотка
    private float dragX, dragY;              // сглаженная позиция курсора при драге
    private float dragScale = 0f;            // сглаженный размер клетки фигуры в руке
    private long lastFrameMs = 0;
    private final float[] slotScale = new float[3]; // плавный hover-масштаб лотка
    private long comboShowStart = 0;

    // ═══════════════════════════════════════════════════════════════
    // Цвета — точно как в Block Blast (насыщенные, 3D-блоки)
    // ═══════════════════════════════════════════════════════════════
    // Каждый цвет: [основной, топ-блик, нижняя грань, боковая грань]
    private static final int[][] BLOCK_PALETTES = {
        // Синий
        { 0xFF2979FF, 0xFF82B1FF, 0xFF1565C0, 0xFF1976D2 },
        // Зелёный
        { 0xFF00C853, 0xFF69F0AE, 0xFF1B5E20, 0xFF2E7D32 },
        // Жёлтый
        { 0xFFFFD600, 0xFFFFFF8D, 0xFFF57F17, 0xFFF9A825 },
        // Красный / коралловый
        { 0xFFFF1744, 0xFFFF8A80, 0xFFB71C1C, 0xFFC62828 },
        // Фиолетовый
        { 0xFFD500F9, 0xFFEA80FC, 0xFF6A1B9A, 0xFF7B1FA2 },
        // Голубой / циан
        { 0xFF00E5FF, 0xFFB2EBF2, 0xFF006064, 0xFF00838F },
        // Оранжевый
        { 0xFFFF6D00, 0xFFFFD180, 0xFFE65100, 0xFFF4511E },
        // Розовый
        { 0xFFFF4081, 0xFFFF80AB, 0xFF880E4F, 0xFFAD1457 },
    };
    // Основной цвет для каждого слота (используется в grid)
    private static final int[] COLORS = {
        0xFF2979FF, 0xFF00C853, 0xFFFFD600, 0xFFFF1744,
        0xFFD500F9, 0xFF00E5FF, 0xFFFF6D00, 0xFFFF4081
    };

    private static final boolean[][][] SHAPES = {
        {{true,true,true,true}},
        {{true},{true},{true},{true}},
        {{true,true,true}},
        {{true},{true},{true}},
        {{true,true}},
        {{true},{true}},
        {{true,true},{true,true}},
        {{true,true},{true,true},{true,true}},
        {{true,true,true},{true,true,true}},
        {{true,true,true},{true,true,true},{true,true,true}},
        {{true,false},{true,false},{true,true}},
        {{false,true},{false,true},{true,true}},
        {{true,true},{true,false},{true,false}},
        {{true,true},{false,true},{false,true}},
        {{true,true,true},{false,true,false}},
        {{false,true,false},{true,true,true}},
        {{true,false,false},{true,true,true}},
        {{false,false,true},{true,true,true}},
        {{true,true,true},{true,true,true},{false,false,false}}
    };

    private static class Piece {
        final boolean[][] cells;
        final int paletteIdx;
        boolean used = false;
        Piece(boolean[][] cells, int paletteIdx) {
            this.cells = cells;
            this.paletteIdx = paletteIdx;
        }
        int rows() { return cells.length; }
        int cols() { return cells[0].length; }
        int color() { return BLOCK_PALETTES[paletteIdx][0]; }
    }

    // paletteIdx → palette
    private int[] gridPalette = new int[SIZE * SIZE]; // paletteIdx per cell (flattened)

    public BlockBlastScreen(BlockBlast minigame, Screen parent) {
        super(Component.translatable("minigame.blockblast.title"));
        this.minigame = minigame;
        this.parent = parent;
    }

    // ─── Layout ──────────────────────────────────────────────────────────────

    private int cellSize() {
        int usableH = this.height - HEADER_H - PANEL_H - 12;
        int usableW = this.width - 24;
        int byH = (usableH - GAP) / SIZE - GAP;
        int byW = (usableW - GAP) / SIZE - GAP;
        return Math.min(44, Math.min(byH, byW));
    }

    private int boardW() { return SIZE * (cellSize() + GAP) + GAP; }
    private int boardX() { return (this.width - boardW()) / 2; }
    private int boardY() {
        int usable = this.height - HEADER_H - PANEL_H - 12;
        return HEADER_H + 6 + (usable - boardW()) / 2;
    }

    private int trayCell() { return Math.max(10, cellSize() * 10 / 28); }
    private int trayY()    { return this.height - PANEL_H + 8; }

    private int trayPieceX(Piece p, int slot) {
        int slotW = boardW() / 3;
        return boardX() + slot * slotW + (slotW - p.cols() * trayCell()) / 2;
    }
    private int trayPieceY(Piece p) {
        int trayH = PANEL_H - 16;
        return trayY() + (trayH - p.rows() * trayCell()) / 2;
    }

    // ─── Init / restart ───────────────────────────────────────────────────────

    @Override
    protected void init() {
        if (!loadSavedGame()) restart();
        else rebuildButtons();
    }

    private void rebuildButtons() {
        clearWidgets();
        Button ret = SpriteIconButton.builder(Component.empty(), b -> this.onClose(), true)
            .sprite(Identifier.fromNamespaceAndPath(MinigamesMod.MOD_ID, "icon/return"), 15, 15).build();
        ret.setTooltip(Tooltip.create(Component.translatable("minigame.blockblast.undo")));
        ret.setRectangle(20, 20, 10, 10);

        Button rst = SpriteIconButton.builder(Component.empty(), b -> restart(), true)
            .sprite(Identifier.fromNamespaceAndPath(MinigamesMod.MOD_ID, "icon/restart"), 15, 15).build();
        rst.setTooltip(Tooltip.create(Component.translatable("minigame.restart")));
        rst.setRectangle(20, 20, 35, 10);

        this.addRenderableWidget(rst);
        this.addRenderableWidget(ret);
        this.addRenderableWidget(new ConfigButton(60, 10, minigame));
    }

    private void restart() {
        for (int[] row : grid) Arrays.fill(row, 0);
        Arrays.fill(gridPalette, 0);
        score = 0; gameOver = false; dragging = null;
        combo = 0; comboShowUntil = 0; comboShowStart = 0;
        Arrays.fill(slotScale, 1f);
        dragScale = 0f; lastFrameMs = 0;
        clearedCells.clear(); tray.clear(); hoverSlot = -1;
        refillTray();
        clearSavedGame();
        rebuildButtons();
    }

    // ─── Save / Load ──────────────────────────────────────────────────────────

    /** Сохраняем всё состояние партии в строку в конфиге. */
    private void saveGame() {
        if (gameOver) { clearSavedGame(); return; }
        BlockBlastConfig cfg = MinigameRegistry.getConfig(BlockBlastConfig.class);
        StringBuilder sb = new StringBuilder();
        sb.append(score).append(';');
        // grid (64 ints)
        for (int r = 0; r < SIZE; r++) for (int c = 0; c < SIZE; c++) sb.append(grid[r][c]).append(',');
        sb.append(';');
        // gridPalette (64 ints)
        for (int v : gridPalette) sb.append(v).append(',');
        sb.append(';');
        // combo
        sb.append(combo).append(';');
        // tray: 3 слота, каждый — "used|paletteIdx|rows|cols|cells..."
        for (Piece p : tray) {
            sb.append(p.used ? 1 : 0).append('|');
            sb.append(p.paletteIdx).append('|');
            sb.append(p.rows()).append('|').append(p.cols()).append('|');
            for (boolean[] row : p.cells) for (boolean v : row) sb.append(v ? 1 : 0);
            sb.append(';');
        }
        cfg.savedGame = sb.toString();
        AutoConfig.getConfigHolder(BlockBlastConfig.class).save();
    }

    /** Загружаем сохранённую партию. Возвращает false если нет сохранения или оно повреждено. */
    private boolean loadSavedGame() {
        BlockBlastConfig cfg = MinigameRegistry.getConfig(BlockBlastConfig.class);
        String s = cfg.savedGame;
        if (s == null || s.isEmpty()) return false;
        try {
            String[] parts = s.split(";", -1);
            // parts: [score, grid, palette, combo, tray0, tray1, tray2]
            if (parts.length < 7) return false;
            score = Long.parseLong(parts[0]);
            // grid
            String[] gv = parts[1].split(",");
            if (gv.length < SIZE*SIZE) return false;
            for (int r = 0; r < SIZE; r++) for (int c = 0; c < SIZE; c++)
                grid[r][c] = Integer.parseInt(gv[r*SIZE+c]);
            // palette
            String[] pv = parts[2].split(",");
            if (pv.length < SIZE*SIZE) return false;
            for (int i = 0; i < SIZE*SIZE; i++) gridPalette[i] = Integer.parseInt(pv[i]);
            // combo
            combo = Integer.parseInt(parts[3]);
            // tray
            tray.clear();
            for (int i = 0; i < 3; i++) {
                String[] tp = parts[4 + i].split("\\|");
                boolean used = tp[0].equals("1");
                int paletteIdx = Integer.parseInt(tp[1]);
                int rows = Integer.parseInt(tp[2]);
                int cols = Integer.parseInt(tp[3]);
                String cellStr = tp[4];
                boolean[][] cells = new boolean[rows][cols];
                for (int r = 0; r < rows; r++)
                    for (int c = 0; c < cols; c++)
                        cells[r][c] = cellStr.charAt(r*cols+c) == '1';
                Piece p = new Piece(cells, paletteIdx);
                p.used = used;
                tray.add(p);
            }
            gameOver = false; dragging = null; hoverSlot = -1;
            clearedCells.clear();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void clearSavedGame() {
        BlockBlastConfig cfg = MinigameRegistry.getConfig(BlockBlastConfig.class);
        cfg.savedGame = "";
        AutoConfig.getConfigHolder(BlockBlastConfig.class).save();
    }

    private void refillTray() {
        Random rnd = new Random();
        tray.clear();
        for (int i = 0; i < 3; i++) {
            boolean[][] shape = SHAPES[rnd.nextInt(SHAPES.length)];
            tray.add(new Piece(shape, rnd.nextInt(BLOCK_PALETTES.length)));
        }
    }

    // ─── Game logic ───────────────────────────────────────────────────────────

    private boolean fits(Piece p, int r0, int c0) {
        for (int r = 0; r < p.rows(); r++)
            for (int c = 0; c < p.cols(); c++)
                if (p.cells[r][c]) {
                    int gr = r0+r, gc = c0+c;
                    if (gr<0||gr>=SIZE||gc<0||gc>=SIZE||grid[gr][gc]!=0) return false;
                }
        return true;
    }

    private boolean anyMoveLeft() {
        for (Piece p : tray) {
            if (p.used) continue;
            for (int r=0; r<=SIZE-p.rows(); r++)
                for (int c=0; c<=SIZE-p.cols(); c++)
                    if (fits(p,r,c)) return true;
        }
        return false;
    }

    private int[] anchorFor(Piece p, double mx, double my) {
        int cell = cellSize(), bx = boardX(), by = boardY();
        int mc = (int)((mx - bx - GAP) / (cell+GAP));
        int mr = (int)((my - by - GAP) / (cell+GAP));
        int r0 = Math.max(0, Math.min(SIZE-p.rows(), mr - p.rows()/2));
        int c0 = Math.max(0, Math.min(SIZE-p.cols(), mc - p.cols()/2));
        return new int[]{r0, c0};
    }

    private int[] snapAnchor(Piece p, int r0, int c0) {
        if (fits(p,r0,c0)) return new int[]{r0,c0};
        for (int dr=-1; dr<=1; dr++)
            for (int dc=-1; dc<=1; dc++) {
                if (dr==0&&dc==0) continue;
                int nr=r0+dr, nc=c0+dc;
                if (nr>=0&&nr+p.rows()<=SIZE&&nc>=0&&nc+p.cols()<=SIZE&&fits(p,nr,nc))
                    return new int[]{nr,nc};
            }
        return new int[]{r0,c0};
    }

    private boolean isOverBoard(double mx, double my) {
        int bx=boardX(), by=boardY(), bw=boardW();
        return mx>=bx&&mx<bx+bw&&my>=by&&my<by+bw;
    }

    private void placePiece(Piece p, int r0, int c0) {
        for (int r=0; r<p.rows(); r++)
            for (int c=0; c<p.cols(); c++)
                if (p.cells[r][c]) {
                    grid[r0+r][c0+c] = p.color();
                    gridPalette[(r0+r)*SIZE+(c0+c)] = p.paletteIdx;
                    score++;
                }
        p.used = true;

        List<Integer> fullRows=new ArrayList<>(), fullCols=new ArrayList<>();
        for (int r=0; r<SIZE; r++) {
            boolean full=true;
            for (int c=0; c<SIZE; c++) if (grid[r][c]==0){full=false;break;}
            if (full) fullRows.add(r);
        }
        for (int c=0; c<SIZE; c++) {
            boolean full=true;
            for (int r=0; r<SIZE; r++) if (grid[r][c]==0){full=false;break;}
            if (full) fullCols.add(c);
        }
        int lines = fullRows.size()+fullCols.size();
        if (lines>0) {
            clearedCells.clear();
            for (int r : fullRows) for (int c=0; c<SIZE; c++) { clearedCells.add(new int[]{r,c,gridPalette[r*SIZE+c]}); grid[r][c]=0; }
            for (int c : fullCols) for (int r=0; r<SIZE; r++) { clearedCells.add(new int[]{r,c,gridPalette[r*SIZE+c]}); grid[r][c]=0; }
            clearAnimStart = System.currentTimeMillis();
            combo++;
            long gain = (lines*10L+(lines-1)*5L)*Math.max(1,combo);
            score += gain;
            comboShowStart = System.currentTimeMillis();
            comboShowUntil = comboShowStart+COMBO_DISPLAY_MS;
            saveComboRecord();
            PlayingSoundManager.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, Math.min(2f,0.9f+0.15f*combo), vol());
            if (combo>=2) PlayingSoundManager.playSound(SoundEvents.PLAYER_LEVELUP, Math.min(2f,1f+0.1f*combo), vol());
        } else {
            combo=0;
            PlayingSoundManager.playSound(SoundEvents.WOOD_PLACE, new Random().nextFloat(0.9f,1.2f), vol());
        }
        if (tray.stream().allMatch(x->x.used)) refillTray();
        if (!anyMoveLeft()) {
            gameOver=true; saveBestScore(); minigame.onLose(); minigame.getLeaderboard().doPost(score);
            clearSavedGame();
        } else {
            saveGame();
        }
    }

    private void saveBestScore() {
        BlockBlastConfig cfg = MinigameRegistry.getConfig(BlockBlastConfig.class);
        if (score>cfg.bestScore) { cfg.bestScore=score; AutoConfig.getConfigHolder(BlockBlastConfig.class).save(); }
    }
    private void saveComboRecord() {
        BlockBlastConfig cfg = MinigameRegistry.getConfig(BlockBlastConfig.class);
        if (combo>cfg.bestCombo) { cfg.bestCombo=combo; AutoConfig.getConfigHolder(BlockBlastConfig.class).save(); }
    }

    // ─── Input ────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        if (gameOver) return super.mouseClicked(click, doubled);
        double mx=click.x(), my=click.y();
        for (int i=0; i<tray.size(); i++) {
            Piece p=tray.get(i);
            if (p.used) continue;
            int px=trayPieceX(p,i), py=trayPieceY(p);
            int slotW=boardW()/3, slotX=boardX()+i*slotW;
            if (mx>=slotX&&mx<slotX+slotW&&my>=trayY()&&my<trayY()+PANEL_H-16) {
                dragging=p; dragStartTime=System.currentTimeMillis();
                // старт сглаживания из текущей точки курсора — без прыжка
                dragX=(float)mx; dragY=(float)my; dragScale=trayCell();
                PlayingSoundManager.playSound(SoundEvents.EXPERIENCE_ORB_PICKUP, new Random().nextFloat(0.9f,1.1f), vol());
                return true;
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        if (dragging!=null&&!gameOver) {
            Piece p=dragging; dragging=null;
            double mx=click.x(), my=click.y();
            if (isOverBoard(mx,my)) {
                int[] a=anchorFor(p,mx,my); a=snapAnchor(p,a[0],a[1]);
                if (fits(p,a[0],a[1])) { placePiece(p,a[0],a[1]); return true; }
            }
            PlayingSoundManager.playSound(SoundEvents.WOOD_HIT, new Random().nextFloat(0.8f,1f), vol());
            return true;
        }
        return super.mouseReleased(click);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        if (dragging!=null) return;
        hoverSlot=-1;
        int slotW=boardW()/3;
        for (int i=0; i<tray.size(); i++) {
            Piece p=tray.get(i);
            if (p.used) continue;
            int slotX=boardX()+i*slotW;
            if (mouseX>=slotX&&mouseX<slotX+slotW&&mouseY>=trayY()&&mouseY<trayY()+PANEL_H-16) {
                hoverSlot=i; break;
            }
        }
        super.mouseMoved(mouseX, mouseY);
    }

    // ─── Drawing ──────────────────────────────────────────────────────────────

    /** Скруглённый прямоугольник через pixel-perfect fill */
    private void fillRR(GuiGraphicsExtractor ctx, int x, int y, int w, int h, int r, int color) {
        if (r<=0||w<=0||h<=0) { ctx.fill(x,y,x+w,y+h,color); return; }
        r = Math.min(r, Math.min(w/2, h/2));
        ctx.fill(x+r, y, x+w-r, y+h, color);
        ctx.fill(x, y+r, x+r, y+h-r, color);
        ctx.fill(x+w-r, y+r, x+w, y+h-r, color);
        for (int i=0; i<r; i++) for (int j=0; j<r; j++) {
            double d = Math.sqrt((double)(r-1-i)*(r-1-i)+(double)(r-1-j)*(r-1-j));
            if (d<r) {
                ctx.fill(x+i,y+j,x+i+1,y+j+1,color);
                ctx.fill(x+w-1-i,y+j,x+w-i,y+j+1,color);
                ctx.fill(x+i,y+h-1-j,x+i+1,y+h-j,color);
                ctx.fill(x+w-1-i,y+h-1-j,x+w-i,y+h-j,color);
            }
        }
    }

    /**
     * 3D-блок как в оригинальном Block Blast:
     * — основной цвет
     * — светлая верхняя панель (1/3 высоты)
     * — тёмная нижняя грань (2px)
     * — ещё светлый внутренний блик (маленький квадрат левее-сверху)
     */
    private void drawBlock3D(GuiGraphicsExtractor ctx, int x, int y, int size, int paletteIdx) {
        int[] pal = BLOCK_PALETTES[paletteIdx % BLOCK_PALETTES.length];
        int main   = pal[0];
        int top    = pal[1];
        int bottom = pal[2];
        int side   = pal[3];

        int margin = Math.max(1, size/14);
        int inner  = size - margin*2;
        if (inner<=0) { fillRR(ctx,x,y,size,size,2,main); return; }

        int rad = Math.max(2, size/8);

        // Основа
        fillRR(ctx, x+margin, y+margin, inner, inner, rad, main);

        // Нижняя грань (тень)
        int botH = Math.max(2, size/8);
        fillRR(ctx, x+margin, y+margin+inner-botH, inner, botH, rad/2, bottom);

        // Правая грань (тень)
        int sideW = Math.max(2, size/9);
        ctx.fill(x+margin+inner-sideW, y+margin+rad/2, x+margin+inner, y+margin+inner-botH, side);

        // Верхний блик
        int topH = Math.max(2, inner*2/5);
        fillRR(ctx, x+margin, y+margin, inner, topH, rad, top);

        // Маленький блик-квадрат (имитация глянца)
        int bSize = Math.max(2, size/5);
        int bx2 = x+margin+2, by2 = y+margin+2;
        int bAlpha = 0x55;
        ctx.fill(bx2, by2, bx2+bSize, by2+bSize/2, (bAlpha<<24)|0xFFFFFF);
    }

    /** Упрощённый drawBlock для ghost-preview (полупрозрачный) */
    private void drawGhost(GuiGraphicsExtractor ctx, int x, int y, int size, int paletteIdx, boolean valid) {
        int[] pal = BLOCK_PALETTES[paletteIdx % BLOCK_PALETTES.length];
        int color = valid ? ((0x55<<24)|(pal[0]&0x00FFFFFF)) : 0x44FF3333;
        int border = valid ? ((0xBB<<24)|(pal[0]&0x00FFFFFF)) : 0xAAFF3333;
        int r = Math.max(2, size/8);
        fillRR(ctx, x+1, y+1, size-2, size-2, r, color);
        // Рамка
        ctx.fill(x+1,y,x+size-1,y+2,border);
        ctx.fill(x+1,y+size-2,x+size-1,y+size,border);
        ctx.fill(x,y+1,x+2,y+size-1,border);
        ctx.fill(x+size-2,y+1,x+size,y+size-1,border);
    }

    @Override
    public void extractRenderState(GuiGraphicsExtractor ctx, int mouseX, int mouseY, float delta) {
        super.extractRenderState(ctx, mouseX, mouseY, delta);

        int cell = cellSize(), bw = boardW(), bx = boardX(), by = boardY();
        long now = System.currentTimeMillis();

        // ── Сглаживание (экспоненциальное, кадронезависимое) ──
        float dt = Math.max(16f, now - lastFrameMs);
        lastFrameMs = now;
        float k = 1f - (float)Math.exp(-dt / 55f);   // константа времени ~55 мс

        boolean overBoard = dragging!=null && !gameOver && isOverBoard(mouseX, mouseY);
        if (dragging!=null) {
            // Позиция — ровно за курсором, без задержки
            dragX = mouseX;
            dragY = mouseY;
            // Сглажен только масштаб (быстрый, ~25 мс)
            float targetS = overBoard ? cellSize()*0.92f : trayCell()*1.22f;
            float ks = 1f - (float)Math.exp(-dt / 25f);
            dragScale += (targetS-dragScale)*ks;
        } else {
            dragX = mouseX; dragY = mouseY;
        }
        // Плавный hover-масштаб слотов лотка
        for (int i=0; i<3; i++) {
            float target = (dragging==null && hoverSlot==i) ? 1.16f : 1.0f;
            slotScale[i] += (target-slotScale[i])*k;
        }

        // ═══════════════════════════════════════════════
        // ФОН — полупрозрачный сине-фиолетовый градиент (мир просвечивает)
        // ═══════════════════════════════════════════════
        ctx.fill(0, 0, this.width, this.height/2, 0x8C12122A);
        ctx.fill(0, this.height/2, this.width, this.height, 0x7C0D0D1E);

        // ═══════════════════════════════════════════════
        // ШАПКА: карточки Score / Best
        // ═══════════════════════════════════════════════
        long best = MinigameRegistry.getConfig(BlockBlastConfig.class).bestScore;
        int cardW = bw/2 - 6, cardH = 38, cardY = (HEADER_H - cardH)/2;

        // Best (левая)
        fillRR(ctx, bx, cardY, cardW, cardH, 8, 0xA81C1C38);
        // Accent полоска сверху
        fillRR(ctx, bx+6, cardY, cardW-12, 3, 1, 0xB37C4DFF);
        ctx.centeredText(font, Component.translatable("minigame.blockblast.best"),
            bx+cardW/2, cardY+6, 0xFFB0AECF);
        ctx.centeredText(font, Component.literal(String.valueOf(best)),
            bx+cardW/2, cardY+18, 0xFFFFFFFF);

        // Score (правая)
        int sCardX = bx + cardW + 12;
        fillRR(ctx, sCardX, cardY, cardW, cardH, 8, 0xA81C1C38);
        fillRR(ctx, sCardX+6, cardY, cardW-12, 3, 1, 0xB3FF6D00);
        ctx.centeredText(font, Component.translatable("minigame.blockblast.score"),
            sCardX+cardW/2, cardY+6, 0xFFB0AECF);
        ctx.centeredText(font, Component.literal(String.valueOf(score)),
            sCardX+cardW/2, cardY+18, 0xFFFFFFFF);

        // ═══════════════════════════════════════════════
        // ДОСКА
        // ═══════════════════════════════════════════════
        // Тень под доской
        fillRR(ctx, bx+4, by+6, bw, bw, BOARD_RADIUS, 0x33000000);
        // Фон доски — полупрозрачный
        fillRR(ctx, bx, by, bw, bw, BOARD_RADIUS, 0x99161628);

        // Пустые клетки (тёмные ячейки сетки)
        for (int r=0; r<SIZE; r++)
            for (int c=0; c<SIZE; c++)
                fillRR(ctx,
                    bx+GAP+c*(cell+GAP), by+GAP+r*(cell+GAP), cell, cell, 3, 0x7A1E1E35);

        // ── Анимация очистки ──
        long clearAge = now - clearAnimStart;
        boolean clearAnim = clearAge>=0 && clearAge<CLEAR_ANIM_MS && !clearedCells.isEmpty();
        if (!clearAnim && !clearedCells.isEmpty()) clearedCells.clear();

        // ── Заполненные клетки ──
        for (int r=0; r<SIZE; r++)
            for (int c=0; c<SIZE; c++)
                if (grid[r][c]!=0)
                    drawBlock3D(ctx,
                        bx+GAP+c*(cell+GAP), by+GAP+r*(cell+GAP),
                        cell, gridPalette[r*SIZE+c]);

        // ── Вспышка при очистке — радиальное свечение по цвету блока ──
        if (clearAnim) {
            float t = 1f - (float)clearAge/CLEAR_ANIM_MS;
            for (int[] cp : clearedCells) {
                int gx = bx+GAP+cp[1]*(cell+GAP), gy = by+GAP+cp[0]*(cell+GAP);
                int pidx = cp[2];
                int[] pal = BLOCK_PALETTES[pidx % BLOCK_PALETTES.length];
                // Цветная вспышка основным цветом блока
                int alpha = (int)(0xBB*t)<<24;
                fillRR(ctx, gx, gy, cell, cell, 3, alpha|(pal[0]&0x00FFFFFF));
                // Белый центр
                int wa = (int)(0x99*t)<<24;
                int margin2 = cell/4;
                fillRR(ctx, gx+margin2, gy+margin2, cell-margin2*2, cell-margin2*2, 2, wa|0xFFFFFF);
            }
        }

        // ── Призрак перетаскивания (позиция следует за сглаженным курсором) ──
        if (overBoard) {
            int[] a = anchorFor(dragging, dragX, dragY);
            a = snapAnchor(dragging, a[0], a[1]);
            boolean ok = fits(dragging, a[0], a[1]);
            for (int pr=0; pr<dragging.rows(); pr++)
                for (int pc=0; pc<dragging.cols(); pc++)
                    if (dragging.cells[pr][pc]) {
                        int gx = bx+GAP+(a[1]+pc)*(cell+GAP);
                        int gy = by+GAP+(a[0]+pr)*(cell+GAP);
                        drawGhost(ctx, gx, gy, cell, dragging.paletteIdx, ok);
                    }
        }

        // ═══════════════════════════════════════════════
        // ЛОТОК — снизу, закруглённая карточка
        // ═══════════════════════════════════════════════
        int trayBgY = this.height - PANEL_H + 2;
        // Тень
        fillRR(ctx, bx+3, trayBgY+5, bw, PANEL_H-6, 10, 0x26000000);
        // Карточка лотка — полупрозрачная
        fillRR(ctx, bx, trayBgY, bw, PANEL_H-4, 12, 0xA81C1C38);

        int slotW = bw/3;
        // Разделители
        for (int i=1; i<3; i++) {
            int lx = bx+i*slotW;
            ctx.fill(lx, trayBgY+10, lx+1, trayBgY+PANEL_H-18, 0x592A2A48);
        }

        // Фигуры в лотке — масштаб плавно «дышит» к hover-цели
        for (int i=0; i<tray.size(); i++) {
            Piece p = tray.get(i);
            if (p.used || p==dragging) continue;
            int px = trayPieceX(p, i), py = trayPieceY(p);
            int tc = trayCell();

            boolean hov = (hoverSlot==i);
            if (hov) {
                // Hover-подсветка слота
                int hSlotX = bx+i*slotW+3;
                fillRR(ctx, hSlotX, trayBgY+4, slotW-6, PANEL_H-12, 8, 0x14FFFFFF);
            }

            float scale = slotScale[i];
            int scaledTc = (int)(tc*scale);
            int offX = (tc*p.cols()-scaledTc*p.cols())/2;
            int offY = (tc*p.rows()-scaledTc*p.rows())/2;

            for (int pr=0; pr<p.rows(); pr++)
                for (int pc=0; pc<p.cols(); pc++)
                    if (p.cells[pr][pc])
                        drawBlock3D(ctx,
                            px+offX+pc*scaledTc, py+offY+pr*scaledTc,
                            scaledTc, p.paletteIdx);
        }

        // ═══════════════════════════════════════════════
        // ПЕРЕТАСКИВАЕМАЯ ФИГУРА — плавное следование за курсором
        // ═══════════════════════════════════════════════
        if (dragging!=null && !gameOver) {
            long liftAge = now - dragStartTime;
            float liftT = Math.min(1f, (float)liftAge/LIFT_MS);
            liftT = 1f - (1f-liftT)*(1f-liftT)*(1f-liftT);

            float dragCell = Math.max(1f, dragScale);   // уже сглажен выше

            // Позиция от сглаженного курсора; округляем один раз — клетки не «дрожат»
            int originX = Math.round(dragX - dragging.cols()*dragCell/2);
            int originY = Math.round(dragY - dragging.rows()*dragCell/2
                - dragging.rows()*dragCell*0.12f*liftT);

            // Тень
            if (liftT>0.2f) {
                float sa = 0.3f*liftT;
                int sAlpha = (int)(sa*255)<<24;
                int sOff = (int)(6*liftT);
                for (int pr=0; pr<dragging.rows(); pr++)
                    for (int pc=0; pc<dragging.cols(); pc++)
                        if (dragging.cells[pr][pc])
                            fillRR(ctx,
                                originX+Math.round(pc*dragCell)+sOff,
                                originY+Math.round(pr*dragCell)+sOff,
                                Math.round(dragCell), Math.round(dragCell), 3, sAlpha);
            }

            // Сама фигура
            for (int pr=0; pr<dragging.rows(); pr++)
                for (int pc=0; pc<dragging.cols(); pc++)
                    if (dragging.cells[pr][pc])
                        drawBlock3D(ctx,
                            originX+Math.round(pc*dragCell),
                            originY+Math.round(pr*dragCell),
                            Math.round(dragCell), dragging.paletteIdx);
        }

        // ═══════════════════════════════════════════════
        // КОМБО-ПЛАШКА
        // ═══════════════════════════════════════════════
        if (combo>=2 && now<comboShowUntil) {
            // Плавное появление (150 мс) и такое же затухание (600 мс)
            float fadeIn = Math.min(1f, (float)(now-comboShowStart)/150f);
            float fadeOut = Math.min(1f, (float)(comboShowUntil-now)/600f);
            float fade = Math.min(fadeIn, fadeOut);
            int alpha = (int)(0xFF*fade);
            String txt = Component.translatable("minigame.blockblast.combo", combo).getString();
            int tw = font.width(txt);
            int cx = this.width/2 - tw/2 - 10, cy = by - 24;
            fillRR(ctx, cx, cy, tw+20, 18, 9, (alpha/3)<<24|0x00FF6D00);
            ctx.centeredText(font, Component.literal(txt), this.width/2, cy+5, (alpha<<24)|0xFFD700);
        }

        // ═══════════════════════════════════════════════
        // GAME OVER
        // ═══════════════════════════════════════════════
        if (gameOver) {
            long bestCombo = MinigameRegistry.getConfig(BlockBlastConfig.class).bestCombo;
            // Затемнение доски
            fillRR(ctx, bx, by, bw, bw, BOARD_RADIUS, 0xCC050510);

            // Центральная карточка Game Over
            int goW = bw - 20, goH = 70, goX = bx+10, goY = by+bw/2-goH/2;
            fillRR(ctx, goX, goY, goW, goH, 12, 0xD91C1C38);
            fillRR(ctx, goX+8, goY, goW-16, 3, 1, 0xFFFF1744);

            ctx.centeredText(font, Component.translatable("minigame.blockblast.game_over"),
                this.width/2, goY+10, 0xFFFF5252);
            ctx.centeredText(font, Component.literal(String.valueOf(score)),
                this.width/2, goY+24, 0xFFFFFFFF);
            ctx.centeredText(font, Component.translatable("minigame.blockblast.best_combo",
                Math.max(bestCombo, combo)),
                this.width/2, goY+38, 0xFFFFD700);

            String hint = "▶  Restart to play again";
            ctx.centeredText(font, Component.literal(hint),
                this.width/2, goY+54, 0xFF7C7CA0);
        }
    }

    private float vol() {
        return PlayingSoundManager.vol(MinigameRegistry.getConfig(BlockBlastVisibleConfig.class).volume);
    }

    @Override
    public void onClose() {
        if (!gameOver) {
            saveBestScore();
            saveGame();
        }
        minigame.onStop();
        this.minecraft.gui.setScreen(parent);
    }

    @Override public boolean isPauseScreen() { return false; }
}
