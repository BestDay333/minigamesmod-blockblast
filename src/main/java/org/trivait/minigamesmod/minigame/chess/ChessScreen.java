package org.trivait.minigamesmod.minigame.chess;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.SpriteIconButton;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.trivait.minigamesmod.MinigamesMod;
import org.trivait.minigamesmod.api.MinigameRegistry;
import org.trivait.minigamesmod.api.PlayingSoundManager;
import org.trivait.minigamesmod.gui.widget.ConfigButton;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class ChessScreen extends Screen {

    // ═══════════════════════════════════════════════════════════════
    // Пропорциональная раскладка: ВСЁ считается от размеров экрана.
    // Доска рисуется вручную (context.fill) — клетки гарантированно
    // совпадают с кликами, без погрешностей масштабирования текстуры.
    // Клетка на экране = cell, рамка = 0.4*cell, весь блок = 8.8*cell.
    // ═══════════════════════════════════════════════════════════════
    private static final float TEX_TO_CELL = 8.8f;         // блок доски = 8 клеток + 2 рамки по 0.4
    private static final float BORDER_FRACTION = 0.4f;     // рамка относительно клетки
    private static final int PANEL_GAP = 10;               // зазор доска↔панель

    // --- Анимация хода ---
    private static class MoveAnimation {
        final ChessPiece piece;
        final int fromPixelX, fromPixelY;
        final int toPixelX, toPixelY;
        final int boardToX, boardToY;
        final long startTime, duration;

        MoveAnimation(ChessPiece piece, int fromPx, int fromPy, int toPx, int toPy,
                      int bToX, int bToY, long duration) {
            this.piece = piece;
            this.fromPixelX = fromPx; this.fromPixelY = fromPy;
            this.toPixelX = toPx;    this.toPixelY = toPy;
            this.boardToX = bToX;    this.boardToY = bToY;
            this.startTime = System.currentTimeMillis();
            this.duration = duration;
        }
    }

    private final Screen parent;
    private final Chess minigame;
    private final Minecraft mc;
    private final ChessBoard board;
    private final ChessBot bot;

    private ChessColor actualPlayerColor = ChessColor.WHITE;
    private boolean gameInitialized = false;

    private int selectedX = -1, selectedY = -1;
    private final List<ChessMove> currentLegalMoves = new ArrayList<>();
    private ChessMove lastMove = null;

    private boolean isDragging = false;
    private boolean dragExitedCell = false;
    private ChessPiece draggedPiece = null;
    private int dragStartX = -1, dragStartY = -1;

    private boolean pendingPromotion = false;
    private int promoFromX, promoFromY, promoToX, promoToY;

    private boolean botThinking = false;
    private long botStartedAt = 0;
    private volatile ChessMove pendingBotMove = null;
    private volatile long botMoveReadyTime = 0;
    private static final long BOT_TIMEOUT_MS = 15000;

    private boolean gameOver = false;
    private ChessColor winner = null;
    private Component gameOverReason = null;

    private final List<MoveAnimation> activeAnimations = new ArrayList<>();
    private final List<String> moveNotationHistory = new ArrayList<>();

    public ChessScreen(Screen parent, Chess minigame) {
        super(Component.empty());
        this.parent = parent;
        this.minigame = minigame;
        this.mc = Minecraft.getInstance();
        this.board = new ChessBoard();
        this.bot = new ChessBot();
    }

    // ═══════════════════════════════════════════════════════════════
    // Геометрия — всё в долях экрана
    // ═══════════════════════════════════════════════════════════════

    /** Ширина боковой панели: ~15% ширины экрана, 90…160 px */
    private int panelWidth() {
        return clamp(Math.round(this.width * 0.15f), 90, 160);
    }

    /** Вертикальный резерв: статус сверху + подписи снизу */
    private int verticalChrome() {
        int statusH = 18, labelsH = 12;
        return statusH + labelsH;
    }

    /** Клетка доски: из свободного места по высоте и ширине, 14…44 px */
    private int cellSize() {
        int margin = margin();
        int coordW = 10; // подписи слева (цифры 8…1)
        int availH = this.height - margin * 2 - verticalChrome();
        int availW = this.width - margin * 2 - coordW - PANEL_GAP - panelWidth();
        int c = (int) Math.floor(Math.min(availH, availW) / TEX_TO_CELL);
        return clamp(c, 14, 44);
    }

    private int margin() { return Math.max(6, Math.round(this.height * 0.02f)); }

    /** Весь блок доски (клетки + 2 рамки): 8.8 × клетка */
    private int texSize() { return cellSize() * 8 + boardFrame() * 2; }

    /** Рамка доски: 0.4 × клетка */
    private int boardFrame() { return Math.max(2, Math.round(cellSize() * BORDER_FRACTION)); }

    /** Левый верхний угол доски (вместе с рамкой) */
    private int boardOriginX() {
        int contentW = 10 + texSize() + PANEL_GAP + panelWidth();
        return Math.max(2, (this.width - contentW) / 2 + 10);
    }
    private int boardOriginY() {
        int statusH = 18;
        int labelsH = 12;
        int free = this.height - statusH - labelsH - texSize();
        return Math.max(statusH, statusH + free / 2);
    }

    /** Левый верхний угол сетки клеток (внутри рамки) */
    private int innerX() { return boardOriginX() + boardFrame(); }
    private int innerY() { return boardOriginY() + boardFrame(); }

    /** Размер фигуры: ~84% клетки */
    private int pieceSize() { return Math.max(10, Math.round(cellSize() * 0.84f)); }

    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(max, v)); }

    // ═══════════════════════════════════════════════════════════════
    // Геометрия окна промоушена — единая для отрисовки и кликов
    // ═══════════════════════════════════════════════════════════════
    private int promoW() { return clamp(Math.round(this.width * 0.18f), 120, 170); }
    private int promoH() { return Math.round(promoW() * 0.42f); }
    private int promoX() { return (this.width - promoW()) / 2; }
    private int promoY() { return (this.height - promoH()) / 2; }
    private int promoSlot() { return clamp(Math.round(promoW() * 0.19f), 22, 30); }
    private int promoItemX(int i) { return promoX() + 8 + i * promoSlot(); }
    private int promoItemY() { return promoY() + Math.round(promoH() * 0.40f); }

    // ═══════════════════════════════════════════════════════════════
    // init
    // ═══════════════════════════════════════════════════════════════
    @Override
    protected void init() {
        Button returnButton = SpriteIconButton.builder(Component.empty(), b -> this.onClose(), true)
                .sprite(Identifier.fromNamespaceAndPath(MinigamesMod.MOD_ID, "icon/return"), 15, 15)
                .build();
        returnButton.setTooltip(Tooltip.create(Component.translatable("minigame.return")));
        returnButton.setRectangle(20, 20, 10, 10);

        Button restartButton = SpriteIconButton.builder(Component.empty(), b -> resetGame(), true)
                .sprite(Identifier.fromNamespaceAndPath(MinigamesMod.MOD_ID, "icon/restart"), 15, 15)
                .build();
        restartButton.setTooltip(Tooltip.create(Component.translatable("minigame.restart")));
        restartButton.setRectangle(20, 20, 35, 10);

        ConfigButton configButton = new ConfigButton(60, 10, minigame);

        addRenderableWidget(returnButton);
        addRenderableWidget(restartButton);
        addRenderableWidget(configButton);

        // Новая партия только при первом открытии; при ресайзе окна партию сохраняем
        if (!gameInitialized) {
            gameInitialized = true;
            resetGame();
        }
    }

    // ═══════════════════════════════════════════════════════════════
    // Render
    // ═══════════════════════════════════════════════════════════════
    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        // Применяем ход бота когда готов
        if (botThinking && pendingBotMove != null && System.currentTimeMillis() >= botMoveReadyTime) {
            ChessMove move = pendingBotMove;
            pendingBotMove = null;
            botThinking = false;
            applyMove(move);
        }
        // Страховка: если бот завис (исключение в фоне) — отпускаем игру
        if (botThinking && pendingBotMove == null
                && System.currentTimeMillis() - botStartedAt > BOT_TIMEOUT_MS) {
            botThinking = false;
        }

        long now = System.currentTimeMillis();
        activeAnimations.removeIf(anim -> now - anim.startTime >= anim.duration);

        int bx = boardOriginX();
        int by = boardOriginY();
        int ix = innerX();
        int iy = innerY();
        int cell = cellSize();
        int ps = pieceSize();
        int tex = texSize();

        // Определяем вышел ли курсор за клетку при drag
        if (isDragging && !dragExitedCell) {
            int minX = ix + dragStartX * cell;
            int maxX = minX + cell;
            int minY = iy + dragStartY * cell;
            int maxY = minY + cell;
            if (mouseX < minX || mouseX >= maxX || mouseY < minY || mouseY >= maxY) {
                dragExitedCell = true;
            }
        }

        // --- Доска рисуется вручную — клетки идеально совпадают с кликами ---
        ChessRenderMode boardMode = MinigameRegistry.getConfig(ChessVisibleConfig.class).boardMode;
        boolean wooden = boardMode == ChessRenderMode.WOODEN;
        int frameOuter = wooden ? 0xFF6B4A2A : 0xFF2B2926;
        int frameInner = wooden ? 0xFF8A6234 : 0xFF4A4742;
        int lightSq    = wooden ? 0xFFF0D9B5 : 0xFFEEEED2;
        int darkSq     = wooden ? 0xFFB58863 : 0xFF769656;
        int f = boardFrame();
        // внешняя рамка и внутренняя окантовка
        context.fill(bx, by, bx + tex, by + tex, frameOuter);
        context.fill(bx + f - 1, by + f - 1, bx + tex - f + 1, by + tex - f + 1, frameInner);
        // клетки (a8 — светлая)
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                int sq = ((x + y) % 2 == 0) ? lightSq : darkSq;
                context.fill(ix + x * cell, iy + y * cell, ix + (x + 1) * cell, iy + (y + 1) * cell, sq);
            }
        }

        // --- Координатные метки ---
        drawCoordinateLabels(context, ix, iy, cell);

        boolean tracesEnabled = MinigameRegistry.getConfig(ChessVisibleConfig.class).traces;

        // --- Подсветка последнего хода ---
        if (tracesEnabled && lastMove != null) {
            int fx = ix + lastMove.getFromX() * cell;
            int fy = iy + lastMove.getFromY() * cell;
            context.fill(fx, fy, fx + cell, fy + cell, 0x44FFE082);
            int tx = ix + lastMove.getToX() * cell;
            int ty = iy + lastMove.getToY() * cell;
            context.fill(tx, ty, tx + cell, ty + cell, 0x55FFE082);
        }

        // --- Подсветка шаха ---
        if (!gameOver && board.isInCheck(board.getTurn())) {
            int[] kp = board.findKing(board.getTurn());
            if (kp != null) {
                int kx = ix + kp[0] * cell;
                int ky = iy + kp[1] * cell;
                context.fill(kx, ky, kx + cell, ky + cell, 0x66FF0000);
            }
        }

        // --- Подсветка выбранной фигуры ---
        if (selectedX >= 0 && selectedY >= 0) {
            int sx = ix + selectedX * cell;
            int sy = iy + selectedY * cell;
            context.fill(sx, sy, sx + cell, sy + cell, 0x5500E5FF);
        }

        // --- Подсветка легальных ходов ---
        if (tracesEnabled && !currentLegalMoves.isEmpty()) {
            for (ChessMove move : currentLegalMoves) {
                int mx = ix + move.getToX() * cell;
                int my = iy + move.getToY() * cell;
                boolean isCapture = board.getPiece(move.getToX(), move.getToY()) != null || move.isEnPassant();
                if (isCapture) {
                    // рамка-кольцо вокруг ячейки
                    context.fill(mx, my, mx + cell, my + 2, 0x66FF5252);
                    context.fill(mx, my + cell - 2, mx + cell, my + cell, 0x66FF5252);
                    context.fill(mx, my, mx + 2, my + cell, 0x66FF5252);
                    context.fill(mx + cell - 2, my, mx + cell, my + cell, 0x66FF5252);
                } else {
                    // точка в центре
                    int dotR = Math.max(3, cell / 7);
                    int cx2 = mx + cell / 2;
                    int cy2 = my + cell / 2;
                    context.fill(cx2 - dotR, cy2 - dotR, cx2 + dotR, cy2 + dotR, 0x5500E5FF);
                }
            }
        }

        // --- Фигуры на доске ---
        ChessRenderMode piecesMode = MinigameRegistry.getConfig(ChessVisibleConfig.class).piecesMode;
        for (int y = 0; y < 8; y++) {
            for (int x = 0; x < 8; x++) {
                if (isDragging && dragExitedCell && x == dragStartX && y == dragStartY) continue;
                if (isAnimatingTarget(x, y)) continue;
                ChessPiece piece = board.getPiece(x, y);
                if (piece != null) {
                    int px = ix + x * cell + (cell - ps) / 2;
                    int py = iy + y * cell + (cell - ps) / 2;
                    Identifier texP = ChessTextures.getPieceTexture(piece.getType(), piece.getColor(), piecesMode);
                    context.blit(RenderPipelines.GUI_TEXTURED, texP, px, py, 0, 0, ps, ps, 16, 16);
                }
            }
        }

        // --- Анимации ---
        for (MoveAnimation anim : activeAnimations) {
            float t = Math.max(0f, Math.min(1f, (float)(now - anim.startTime) / (float)anim.duration));
            float smoothT = t * t * (3f - 2f * t);
            int curX = Math.round(anim.fromPixelX + (anim.toPixelX - anim.fromPixelX) * smoothT);
            int curY = Math.round(anim.fromPixelY + (anim.toPixelY - anim.fromPixelY) * smoothT);
            Identifier texA = ChessTextures.getPieceTexture(anim.piece.getType(), anim.piece.getColor(), piecesMode);
            context.blit(RenderPipelines.GUI_TEXTURED, texA, curX, curY, 0, 0, ps, ps, 16, 16);
        }

        // --- Перетаскиваемая фигура ---
        if (isDragging && dragExitedCell && draggedPiece != null) {
            Identifier dragTex = ChessTextures.getPieceTexture(draggedPiece.getType(), draggedPiece.getColor(), piecesMode);
            context.blit(RenderPipelines.GUI_TEXTURED, dragTex,
                    mouseX - ps / 2, mouseY - ps / 2,
                    0, 0, ps, ps, 16, 16);
        }

        // --- Статус-текст над доской ---
        drawStatusText(context, bx, by, tex);

        // --- Боковая панель ---
        drawSidePanel(context, bx, by, piecesMode);

        // --- Окно промоушена ---
        if (pendingPromotion) {
            drawPromotionPanel(context, mouseX, mouseY, piecesMode);
        }
    }

    private void drawCoordinateLabels(GuiGraphicsExtractor context, int ix, int iy, int cell) {
        // Буквы a-h снизу
        char[] files = {'a','b','c','d','e','f','g','h'};
        for (int x = 0; x < 8; x++) {
            String label = String.valueOf(files[x]);
            int lx = ix + x * cell + cell / 2 - font.width(label) / 2;
            int ly = iy + 8 * cell + 3;
            context.text(font, Component.literal(label), lx, ly, 0xFFBBBBBB, false);
        }
        // Цифры 8-1 слева
        for (int y = 0; y < 8; y++) {
            String label = String.valueOf(8 - y);
            int lx = ix - font.width(label) - 3;
            int ly = iy + y * cell + cell / 2 - font.lineHeight / 2;
            context.text(font, Component.literal(label), lx, ly, 0xFFBBBBBB, false);
        }
    }

    private void drawStatusText(GuiGraphicsExtractor context, int bx, int by, int tex) {
        Component statusText;
        int statusColor;
        if (gameOver) {
            if (winner == ChessColor.WHITE) {
                statusText = Component.translatable("minigame.chess.checkmate_white");
                statusColor = 0xFF55FF55;
            } else if (winner == ChessColor.BLACK) {
                statusText = Component.translatable("minigame.chess.checkmate_black");
                statusColor = 0xFFFF5555;
            } else {
                statusText = gameOverReason != null ? gameOverReason : Component.translatable("minigame.chess.stalemate");
                statusColor = 0xFFFFFF55;
            }
        } else if (botThinking) {
            statusText = Component.translatable("minigame.chess.bot_thinking");
            statusColor = 0xFFAAAAAA;
        } else if (board.isInCheck(board.getTurn())) {
            statusText = Component.translatable("minigame.chess.check");
            statusColor = 0xFFFF3333;
        } else if (board.getTurn() == ChessColor.WHITE) {
            statusText = Component.translatable("minigame.chess.white_turn");
            statusColor = 0xFFFFFFFF;
        } else {
            statusText = Component.translatable("minigame.chess.black_turn");
            statusColor = 0xFFCCCCCC;
        }
        int tw = font.width(statusText);
        int textX = bx + tex / 2 - tw / 2;
        int textY = Math.max(1, by - 15);
        context.text(font, statusText, textX, textY, statusColor, true);
    }

    private void drawSidePanel(GuiGraphicsExtractor context, int bx, int by, ChessRenderMode piecesMode) {
        int pw = panelWidth();
        int tex = texSize();
        int cell = cellSize();
        int px = bx + tex + PANEL_GAP;
        int py = by;
        int ph = tex;

        // Фон панели
        context.fill(px, py, px + pw, py + ph, 0xCC111111);
        context.fill(px, py, px + pw, py + 1, 0xFF555555);
        context.fill(px, py + ph - 1, px + pw, py + ph, 0xFF555555);
        context.fill(px, py, px + 1, py + ph, 0xFF555555);
        context.fill(px + pw - 1, py, px + pw, py + ph, 0xFF555555);

        int pad = Math.max(4, pw / 16);
        int cy = py + pad;

        // === Счётчик ходов ===
        int moveNum = board.getFullMoveNumber();
        ChessBotMode botMode = MinigameRegistry.getConfig(ChessVisibleConfig.class).botMode;
        String botLabel = "Bot: " + botMode.name().charAt(0) + botMode.name().substring(1).toLowerCase();
        Component moveComp = Component.literal("Move " + moveNum + "  " + botLabel);
        // Влезает ли строка в панель — при узком экране укорачиваем
        if (font.width(moveComp) > pw - pad * 2) {
            moveComp = Component.literal("M" + moveNum + " " + botMode.name().charAt(0));
        }
        context.text(font, moveComp, px + pad, cy, 0xFFAAAAAA, false);
        cy += font.lineHeight + 4;

        // === Захваченные фигуры ===
        List<ChessPiece> capturedByWhite = board.getCapturedBlackPieces();
        List<ChessPiece> capturedByBlack = board.getCapturedWhitePieces();
        int whiteAdv = computeMaterialAdvantage(capturedByWhite, capturedByBlack);

        int mini = clamp(cell / 3, 9, 14);

        // Захваченные ЧЁРНЫМи (наверху — сторона бота, если игрок белый)
        context.text(font, Component.translatable("minigame.chess.captured"), px + pad, cy, 0xFF888888, false);
        cy += font.lineHeight + 2;
        cy = drawCapturedPieces(context, capturedByBlack, px + pad, cy, pw, mini, piecesMode);
        if (whiteAdv < 0) {
            context.text(font, Component.literal("+" + (-whiteAdv)), px + pad, cy, 0xFF55FF55, false);
        }
        cy += font.lineHeight + 2;

        // Разделитель
        context.fill(px + pad / 2, cy, px + pw - pad / 2, cy + 1, 0xFF444444);
        cy += 4;

        // Захваченные БЕЛЫми
        context.text(font, Component.translatable("minigame.chess.captured"), px + pad, cy, 0xFF888888, false);
        cy += font.lineHeight + 2;
        cy = drawCapturedPieces(context, capturedByWhite, px + pad, cy, pw, mini, piecesMode);
        if (whiteAdv > 0) {
            context.text(font, Component.literal("+" + whiteAdv), px + pad, cy, 0xFF55FF55, false);
        }
        cy += font.lineHeight + 2;

        // Разделитель
        context.fill(px + pad / 2, cy, px + pw - pad / 2, cy + 1, 0xFF444444);
        cy += 6;

        // === История ходов ===
        context.text(font, Component.translatable("minigame.chess.moves"), px + pad, cy, 0xFF888888, false);
        cy += font.lineHeight + 3;

        int maxMoveLines = Math.max(1, (py + ph - cy - pad) / (font.lineHeight + 1));
        int startIdx = Math.max(0, moveNotationHistory.size() - maxMoveLines * 2);
        if (startIdx % 2 == 1) startIdx--;

        for (int i = startIdx; i < moveNotationHistory.size(); i += 2) {
            int pairNum = i / 2 + 1;
            String whiteMove = moveNotationHistory.get(i);
            String blackMove = (i + 1 < moveNotationHistory.size()) ? moveNotationHistory.get(i + 1) : "";
            String line = pairNum + ". " + whiteMove + (blackMove.isEmpty() ? "" : "  " + blackMove);
            int col = (i >= moveNotationHistory.size() - 2) ? 0xFFFFFFFF : 0xFF888888;
            context.text(font, Component.literal(line), px + pad, cy, col, false);
            cy += font.lineHeight + 1;
            if (cy > py + ph - pad) break;
        }
    }

    private int computeMaterialAdvantage(List<ChessPiece> capturedByWhite, List<ChessPiece> capturedByBlack) {
        int white = 0;
        for (ChessPiece p : capturedByWhite) white += p.getType().getValue();
        int black = 0;
        for (ChessPiece p : capturedByBlack) black += p.getType().getValue();
        return white - black;
    }

    /** Рисует взятые фигуры миниатюрами, возвращает новый y */
    private int drawCapturedPieces(GuiGraphicsExtractor context, List<ChessPiece> pieces,
                                   int startX, int startY, int panelW, int mini, ChessRenderMode piecesMode) {
        if (pieces.isEmpty()) return startY;
        int gap = 1;
        int perRow = Math.max(1, (panelW - 12) / (mini + gap));
        int x = startX;
        int y = startY;
        int count = 0;
        for (ChessPiece p : pieces) {
            if (count > 0 && count % perRow == 0) {
                y += mini + gap;
                x = startX;
            }
            Identifier tex = ChessTextures.getPieceTexture(p.getType(), p.getColor(), piecesMode);
            context.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, mini, mini, 16, 16);
            x += mini + gap;
            count++;
        }
        int rows = (count - 1) / perRow + 1;
        return startY + rows * (mini + gap) + 2;
    }

    private void drawPromotionPanel(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                    ChessRenderMode piecesMode) {
        int bx = boardOriginX(), by = boardOriginY(), tex = texSize();
        context.fill(bx, by, bx + tex, by + tex, 0x99000000);
        int pw = promoW(), ph = promoH();
        int ppx = promoX(), ppy = promoY();
        context.fill(ppx, ppy, ppx + pw, ppy + ph, 0xEE2A2A2A);
        context.fill(ppx,          ppy,        ppx + pw,   ppy + 1,   0xFFFFFFFF);
        context.fill(ppx,          ppy + ph-1, ppx + pw,   ppy + ph,  0xFFFFFFFF);
        context.fill(ppx,          ppy,        ppx + 1,    ppy + ph,  0xFFFFFFFF);
        context.fill(ppx + pw - 1, ppy,        ppx + pw,   ppy + ph,  0xFFFFFFFF);

        Component promoTitle = Component.translatable("minigame.chess.promote");
        context.text(font, promoTitle, ppx + pw / 2 - font.width(promoTitle) / 2, ppy + 5, 0xFFFFFFFF, false);

        ChessPieceType[] promoTypes = {ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT};
        int slot = promoSlot();
        int ps = slot - 6;
        for (int i = 0; i < 4; i++) {
            int itemX = promoItemX(i);
            int itemY = promoItemY();
            if (mouseX >= itemX && mouseX < itemX + slot - 2 && mouseY >= itemY && mouseY < itemY + slot - 2) {
                context.fill(itemX, itemY, itemX + slot - 2, itemY + slot - 2, 0x55FFFFFF);
            }
            Identifier pTex = ChessTextures.getPieceTexture(promoTypes[i], actualPlayerColor, piecesMode);
            context.blit(RenderPipelines.GUI_TEXTURED, pTex, itemX + (slot - 2 - ps) / 2, itemY + 2, 0, 0, ps, ps, 16, 16);
        }
    }

    private boolean isAnimatingTarget(int x, int y) {
        for (MoveAnimation anim : activeAnimations) {
            if (anim.boardToX == x && anim.boardToY == y) return true;
        }
        return false;
    }

    // ═══════════════════════════════════════════════════════════════
    // Ввод мыши
    // ═══════════════════════════════════════════════════════════════
    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() != 0) return super.mouseClicked(click, doubled);

        if (pendingPromotion) {
            ChessPieceType[] promoTypes = {ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT};
            int slot = promoSlot();
            for (int i = 0; i < 4; i++) {
                int itemX = promoItemX(i);
                int itemY = promoItemY();
                if (mouseX >= itemX && mouseX < itemX + slot - 2 && mouseY >= itemY && mouseY < itemY + slot - 2) {
                    completePromotion(promoTypes[i]);
                    return true;
                }
            }
            return true;
        }

        if (gameOver || botThinking) return super.mouseClicked(click, doubled);

        int ix = innerX();
        int iy = innerY();
        int cell = cellSize();
        int cellX = (int) Math.floor((mouseX - ix) / (double) cell);
        int cellY = (int) Math.floor((mouseY - iy) / (double) cell);

        if (cellX >= 0 && cellX < 8 && cellY >= 0 && cellY < 8) {
            if (selectedX >= 0 && selectedY >= 0) {
                ChessMove move = findLegalMove(selectedX, selectedY, cellX, cellY);
                if (move != null) {
                    if (move.getPromotion() != null) {
                        pendingPromotion = true;
                        promoFromX = selectedX; promoFromY = selectedY;
                        promoToX = cellX; promoToY = cellY;
                        return true;
                    }
                    executePlayerMove(move);
                    return true;
                }
                ChessPiece clickedPiece = board.getPiece(cellX, cellY);
                if (clickedPiece != null && clickedPiece.getColor() == actualPlayerColor && board.getTurn() == actualPlayerColor) {
                    selectPiece(cellX, cellY);
                    startDrag(cellX, cellY, clickedPiece);
                    return true;
                }
                clearSelection();
                return true;
            } else {
                ChessPiece clickedPiece = board.getPiece(cellX, cellY);
                if (clickedPiece != null && clickedPiece.getColor() == actualPlayerColor && board.getTurn() == actualPlayerColor) {
                    selectPiece(cellX, cellY);
                    startDrag(cellX, cellY, clickedPiece);
                    return true;
                }
            }
        }
        return super.mouseClicked(click, doubled);
    }

    @Override
    public boolean mouseReleased(MouseButtonEvent click) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() == 0 && isDragging) {
            boolean wasExited = dragExitedCell;
            int startX = dragStartX;
            int startY = dragStartY;
            isDragging = false;
            dragExitedCell = false;
            draggedPiece = null;

            if (wasExited) {
                int ix = innerX();
                int iy = innerY();
                int cell = cellSize();
                int cellX = (int) Math.floor((mouseX - ix) / (double) cell);
                int cellY = (int) Math.floor((mouseY - iy) / (double) cell);
                if (cellX >= 0 && cellX < 8 && cellY >= 0 && cellY < 8) {
                    if (cellX != startX || cellY != startY) {
                        ChessMove move = findLegalMove(startX, startY, cellX, cellY);
                        if (move != null) {
                            if (move.getPromotion() != null) {
                                pendingPromotion = true;
                                promoFromX = startX; promoFromY = startY;
                                promoToX = cellX; promoToY = cellY;
                                return true;
                            }
                            int ps = pieceSize();
                            int releasePx = (int) Math.round(mouseX - ps / 2.0);
                            int releasePy = (int) Math.round(mouseY - ps / 2.0);
                            executePlayerMove(move, true, releasePx, releasePy);
                            return true;
                        }
                    }
                }
            }
        }
        return super.mouseReleased(click);
    }

    @Override
    public boolean mouseDragged(MouseButtonEvent click, double offsetX, double offsetY) {
        if (isDragging) return true;
        return super.mouseDragged(click, offsetX, offsetY);
    }

    @Override
    public boolean keyPressed(KeyEvent input) {
        if (input.key() == InputConstants.KEY_R) { resetGame(); return true; }
        if (input.key() == InputConstants.KEY_ESCAPE && pendingPromotion) {
            pendingPromotion = false;
            clearSelection();
            return true;
        }
        return super.keyPressed(input);
    }

    // ═══════════════════════════════════════════════════════════════
    // Логика игры
    // ═══════════════════════════════════════════════════════════════

    private void selectPiece(int x, int y) {
        selectedX = x; selectedY = y;
        currentLegalMoves.clear();
        currentLegalMoves.addAll(board.generateLegalMovesForPiece(x, y));
    }

    private void startDrag(int x, int y, ChessPiece piece) {
        isDragging = true;
        dragExitedCell = false;
        draggedPiece = piece;
        dragStartX = x;
        dragStartY = y;
    }

    private void clearSelection() {
        selectedX = -1; selectedY = -1;
        currentLegalMoves.clear();
        isDragging = false;
        dragExitedCell = false;
        draggedPiece = null;
        dragStartX = -1; dragStartY = -1;
    }

    private ChessMove findLegalMove(int fromX, int fromY, int toX, int toY) {
        for (ChessMove move : currentLegalMoves) {
            if (move.getFromX() == fromX && move.getFromY() == fromY
                    && move.getToX() == toX && move.getToY() == toY) {
                return move;
            }
        }
        return null;
    }

    private ChessMove findLegalMoveWithPromotion(int fromX, int fromY, int toX, int toY, ChessPieceType promoType) {
        List<ChessMove> moves = board.generateLegalMovesForPiece(fromX, fromY);
        for (ChessMove move : moves) {
            if (move.getFromX() == fromX && move.getFromY() == fromY
                    && move.getToX() == toX && move.getToY() == toY
                    && move.getPromotion() == promoType) {
                return move;
            }
        }
        return null;
    }

    private void executePlayerMove(ChessMove move) { executePlayerMove(move, false, 0, 0); }

    private void executePlayerMove(ChessMove move, boolean fromDrag, int dragReleasePx, int dragReleasePy) {
        clearSelection();
        applyMove(move, fromDrag, dragReleasePx, dragReleasePy);
        if (!gameOver && board.getTurn() != actualPlayerColor) {
            triggerBotMove();
        }
    }

    private void completePromotion(ChessPieceType type) {
        pendingPromotion = false;
        ChessMove move = findLegalMoveWithPromotion(promoFromX, promoFromY, promoToX, promoToY, type);
        if (move != null) executePlayerMove(move);
    }

    private void triggerBotMove() {
        botThinking = true;
        botStartedAt = System.currentTimeMillis();
        long startTime = System.currentTimeMillis();
        ChessBoard boardCopy = board.copy();
        ChessBotMode mode = MinigameRegistry.getConfig(ChessVisibleConfig.class).botMode;
        CompletableFuture
                .supplyAsync(() -> bot.findBestMove(boardCopy, mode))
                .thenAccept(bestMove -> {
                    long elapsed = System.currentTimeMillis() - startTime;
                    long delay = Math.max(0, 350 - elapsed);
                    botMoveReadyTime = System.currentTimeMillis() + delay;
                    pendingBotMove = bestMove;
                })
                .exceptionally(ex -> {
                    pendingBotMove = null;
                    return null;
                });
    }

    private void applyMove(ChessMove move) { applyMove(move, false, 0, 0); }

    private void applyMove(ChessMove move, boolean fromDrag, int dragReleasePx, int dragReleasePy) {
        int ix = innerX();
        int iy = innerY();
        int cell = cellSize();
        int ps = pieceSize();

        boolean isCapture = move.isEnPassant() || board.getPiece(move.getToX(), move.getToY()) != null;

        // Записываем нотацию ДО хода
        String notation = buildNotation(move, board);

        board.makeMove(move);
        lastMove = move;
        moveNotationHistory.add(notation);

        // Анимация
        ChessPiece movedPiece = board.getPiece(move.getToX(), move.getToY());
        int toPx = ix + move.getToX() * cell + (cell - ps) / 2;
        int toPy = iy + move.getToY() * cell + (cell - ps) / 2;
        int fromPx = fromDrag ? dragReleasePx : (ix + move.getFromX() * cell + (cell - ps) / 2);
        int fromPy = fromDrag ? dragReleasePy : (iy + move.getFromY() * cell + (cell - ps) / 2);
        long duration = fromDrag ? 80 : 180;
        activeAnimations.add(new MoveAnimation(movedPiece, fromPx, fromPy, toPx, toPy, move.getToX(), move.getToY(), duration));

        if (move.isCastling()) {
            if (move.getToX() == 6) {
                ChessPiece rook = board.getPiece(5, move.getToY());
                if (rook != null) {
                    int rFrom = ix + 7 * cell + (cell - ps) / 2;
                    int rTo   = ix + 5 * cell + (cell - ps) / 2;
                    int rY    = iy + move.getToY() * cell + (cell - ps) / 2;
                    activeAnimations.add(new MoveAnimation(rook, rFrom, rY, rTo, rY, 5, move.getToY(), 180));
                }
            } else if (move.getToX() == 2) {
                ChessPiece rook = board.getPiece(3, move.getToY());
                if (rook != null) {
                    int rFrom = ix + 0 * cell + (cell - ps) / 2;
                    int rTo   = ix + 3 * cell + (cell - ps) / 2;
                    int rY    = iy + move.getToY() * cell + (cell - ps) / 2;
                    activeAnimations.add(new MoveAnimation(rook, rFrom, rY, rTo, rY, 3, move.getToY(), 180));
                }
            }
        }

        // Звуки и конец игры
        if (board.isCheckmate()) {
            gameOver = true;
            winner = board.getTurn().opposite();
            if (winner == actualPlayerColor) playSound(SoundEvents.UI_TOAST_CHALLENGE_COMPLETE, 1.0f);
            else playSound(SoundEvents.VILLAGER_DEATH, 0.9f);
        } else if (board.isStalemate()) {
            gameOver = true; winner = null;
            gameOverReason = Component.translatable("minigame.chess.stalemate");
            playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f);
        } else if (board.isInsufficientMaterial()) {
            gameOver = true; winner = null;
            gameOverReason = Component.translatable("minigame.chess.draw_material");
            playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f);
        } else if (board.isThreefoldRepetition()) {
            gameOver = true; winner = null;
            gameOverReason = Component.translatable("minigame.chess.draw_repetition");
            playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f);
        } else if (board.isFiftyMoveRule()) {
            gameOver = true; winner = null;
            gameOverReason = Component.translatable("minigame.chess.draw_50");
            playSound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.8f);
        } else if (board.isInCheck(board.getTurn())) {
            playSound(SoundEvents.NOTE_BLOCK_BELL.value(), 1.0f);
        } else if (isCapture) {
            playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.6f);
        } else {
            playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.2f);
        }
    }

    /** Упрощённая алгебраическая нотация */
    private String buildNotation(ChessMove move, ChessBoard b) {
        char[] files = {'a','b','c','d','e','f','g','h'};
        if (move.isCastling()) {
            return move.getToX() == 6 ? "O-O" : "O-O-O";
        }
        ChessPiece piece = b.getPiece(move.getFromX(), move.getFromY());
        StringBuilder sb = new StringBuilder();
        if (piece != null && piece.getType() != ChessPieceType.PAWN) {
            switch (piece.getType()) {
                case KNIGHT: sb.append('N'); break;
                case BISHOP: sb.append('B'); break;
                case ROOK:   sb.append('R'); break;
                case QUEEN:  sb.append('Q'); break;
                case KING:   sb.append('K'); break;
                default: break;
            }
        }
        boolean isCapture = b.getPiece(move.getToX(), move.getToY()) != null || move.isEnPassant();
        if (isCapture && piece != null && piece.getType() == ChessPieceType.PAWN) {
            sb.append(files[move.getFromX()]);
        }
        if (isCapture) sb.append('x');
        sb.append(files[move.getToX()]);
        sb.append(8 - move.getToY());
        if (move.getPromotion() != null) {
            sb.append('=');
            switch (move.getPromotion()) {
                case QUEEN: sb.append('Q'); break;
                case ROOK:  sb.append('R'); break;
                case BISHOP:sb.append('B'); break;
                case KNIGHT:sb.append('N'); break;
                default: break;
            }
        }
        return sb.toString();
    }

    private void resetGame() {
        board.initStartingPosition();
        clearSelection();
        lastMove = null;
        pendingPromotion = false;
        pendingBotMove = null;
        botThinking = false;
        gameOver = false;
        winner = null;
        gameOverReason = null;
        activeAnimations.clear();
        moveNotationHistory.clear();

        ChessPlayerColor pref = MinigameRegistry.getConfig(ChessVisibleConfig.class).playerColor;
        if (pref == ChessPlayerColor.WHITE) actualPlayerColor = ChessColor.WHITE;
        else if (pref == ChessPlayerColor.BLACK) actualPlayerColor = ChessColor.BLACK;
        else actualPlayerColor = Math.random() < 0.5 ? ChessColor.WHITE : ChessColor.BLACK;

        playSound(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f);
        if (actualPlayerColor == ChessColor.BLACK) triggerBotMove();
    }

    private void playSound(SoundEvent sound, float pitch) {
        PlayingSoundManager.playSound(sound, pitch, volume());
    }
    private float volume() {
        return PlayingSoundManager.vol(MinigameRegistry.getConfig(ChessVisibleConfig.class).volume);
    }

    @Override
    public void onClose() {
        minigame.onStop();
        this.minecraft.gui.setScreen(parent);
    }
}
