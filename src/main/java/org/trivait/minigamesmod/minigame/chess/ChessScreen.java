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

    // --- Размеры ---
    private static final int CELL_SIZE = 36;
    private static final int PIECE_SIZE = 30;
    private static final int BOARD_PX = CELL_SIZE * 8;         // 288
    // GUI-текстуры сделаны под 160×160 (8*20), масштабируем при рендере
    private static final int GUI_TEX_SIZE = 176;               // размер текстуры с рамкой
    private static final int BOARD_TEX = 160;                  // размер доски в текстуре
    private static final int BORDER = (GUI_TEX_SIZE - BOARD_TEX) / 2; // 8 пикс рамка в текстуре
    // Реальная рамка на экране (масштабируем)
    private static final int BORDER_PX = Math.round(BORDER * (float) BOARD_PX / BOARD_TEX); // ~14px
    private static final int TOTAL_W = BOARD_PX + BORDER_PX * 2;
    // Координатные метки: слева и снизу, 10px
    private static final int COORD_SIZE = 10;
    // Боковая панель
    private static final int PANEL_W = 110;
    private static final int PANEL_GAP = 10;

    private static final Identifier CLASSIC_GUI = Identifier.fromNamespaceAndPath(MinigamesMod.MOD_ID, "textures/minigame/chess/gui_classic.png");
    private static final Identifier WOODEN_GUI  = Identifier.fromNamespaceAndPath(MinigamesMod.MOD_ID, "textures/minigame/chess/gui_wooden.png");

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
    private volatile ChessMove pendingBotMove = null;
    private volatile long botMoveReadyTime = 0;

    private boolean gameOver = false;
    private ChessColor winner = null;
    private Component gameOverReason = null;

    private final List<MoveAnimation> activeAnimations = new ArrayList<>();

    // История ходов в формате алгебраической нотации (упрощённой)
    private final List<String> moveNotationHistory = new ArrayList<>();

    public ChessScreen(Screen parent, Chess minigame) {
        super(Component.empty());
        this.parent = parent;
        this.minigame = minigame;
        this.mc = Minecraft.getInstance();
        this.board = new ChessBoard();
        this.bot = new ChessBot();
    }

    // ======= Вычисление позиции доски =======

    private int boardOriginX() {
        // Доска смещена влево от центра, чтобы дать место панели справа
        int centerX = width / 2;
        return centerX - TOTAL_W / 2 - (PANEL_W + PANEL_GAP) / 2;
    }
    private int boardOriginY() { return (height - TOTAL_W) / 2; }

    // Верхний левый угол сетки ячеек внутри рамки
    private int innerX() { return boardOriginX() + BORDER_PX; }
    private int innerY() { return boardOriginY() + BORDER_PX; }

    // ======= init =======
    @Override
    protected void init() {
        int bx = boardOriginX();
        int by = boardOriginY();

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

        resetGame();
    }

    // ======= Render =======
    @Override
    public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
        super.extractRenderState(context, mouseX, mouseY, delta);

        // Применяем ход бота если готов
        if (botThinking && pendingBotMove != null && System.currentTimeMillis() >= botMoveReadyTime) {
            ChessMove move = pendingBotMove;
            pendingBotMove = null;
            botThinking = false;
            applyMove(move);
        }

        long now = System.currentTimeMillis();
        activeAnimations.removeIf(anim -> now - anim.startTime >= anim.duration);

        int bx = boardOriginX();
        int by = boardOriginY();
        int ix = innerX();
        int iy = innerY();

        // Определяем вышел ли курсор при drag
        if (isDragging && !dragExitedCell) {
            int minX = ix + dragStartX * CELL_SIZE;
            int maxX = minX + CELL_SIZE;
            int minY = iy + dragStartY * CELL_SIZE;
            int maxY = minY + CELL_SIZE;
            if (mouseX < minX || mouseX >= maxX || mouseY < minY || mouseY >= maxY) {
                dragExitedCell = true;
            }
        }

        // --- Рисуем GUI-текстуру (доска + рамка) ---
        ChessRenderMode boardMode = MinigameRegistry.getConfig(ChessVisibleConfig.class).boardMode;
        Identifier guiTex = boardMode == ChessRenderMode.CLASSIC ? CLASSIC_GUI : WOODEN_GUI;
        // Текстура 176×176 → рисуем в TOTAL_W×TOTAL_W
        context.blit(RenderPipelines.GUI_TEXTURED, guiTex, bx, by, 0, 0, TOTAL_W, TOTAL_W, GUI_TEX_SIZE, GUI_TEX_SIZE);

        // --- Координатные метки ---
        drawCoordinateLabels(context, ix, iy);

        boolean tracesEnabled = MinigameRegistry.getConfig(ChessVisibleConfig.class).traces;

        // --- Подсветка последнего хода ---
        if (tracesEnabled && lastMove != null) {
            int fx = ix + lastMove.getFromX() * CELL_SIZE;
            int fy = iy + lastMove.getFromY() * CELL_SIZE;
            context.fill(fx, fy, fx + CELL_SIZE, fy + CELL_SIZE, 0x44FFE082);
            int tx = ix + lastMove.getToX() * CELL_SIZE;
            int ty = iy + lastMove.getToY() * CELL_SIZE;
            context.fill(tx, ty, tx + CELL_SIZE, ty + CELL_SIZE, 0x55FFE082);
        }

        // --- Подсветка шаха ---
        if (!gameOver && board.isInCheck(board.getTurn())) {
            int[] kp = board.findKing(board.getTurn());
            if (kp != null) {
                int kx = ix + kp[0] * CELL_SIZE;
                int ky = iy + kp[1] * CELL_SIZE;
                context.fill(kx, ky, kx + CELL_SIZE, ky + CELL_SIZE, 0x66FF0000);
            }
        }

        // --- Подсветка выбранной фигуры ---
        if (selectedX >= 0 && selectedY >= 0) {
            int sx = ix + selectedX * CELL_SIZE;
            int sy = iy + selectedY * CELL_SIZE;
            context.fill(sx, sy, sx + CELL_SIZE, sy + CELL_SIZE, 0x5500E5FF);
        }

        // --- Подсветка легальных ходов ---
        if (tracesEnabled && !currentLegalMoves.isEmpty()) {
            for (ChessMove move : currentLegalMoves) {
                int mx = ix + move.getToX() * CELL_SIZE;
                int my = iy + move.getToY() * CELL_SIZE;
                boolean isCapture = board.getPiece(move.getToX(), move.getToY()) != null || move.isEnPassant();
                if (isCapture) {
                    // красное кольцо по углам ячейки
                    context.fill(mx, my, mx + CELL_SIZE, my + CELL_SIZE, 0x44FF5252);
                    context.fill(mx + 2, my + 2, mx + CELL_SIZE - 2, my + CELL_SIZE - 2, 0x00000000);
                    // заполним снова только углы
                    context.fill(mx, my, mx + CELL_SIZE, my + CELL_SIZE, 0x44FF5252);
                } else {
                    // точка в центре
                    int dotR = 5;
                    int cx2 = mx + CELL_SIZE / 2;
                    int cy2 = my + CELL_SIZE / 2;
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
                    int px = ix + x * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2;
                    int py = iy + y * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2;
                    Identifier tex = ChessTextures.getPieceTexture(piece.getType(), piece.getColor(), piecesMode);
                    context.blit(RenderPipelines.GUI_TEXTURED, tex, px, py, 0, 0, PIECE_SIZE, PIECE_SIZE, 16, 16);
                }
            }
        }

        // --- Анимации ---
        for (MoveAnimation anim : activeAnimations) {
            float t = Math.max(0f, Math.min(1f, (float)(now - anim.startTime) / (float)anim.duration));
            float smoothT = t * t * (3f - 2f * t);
            int curX = Math.round(anim.fromPixelX + (anim.toPixelX - anim.fromPixelX) * smoothT);
            int curY = Math.round(anim.fromPixelY + (anim.toPixelY - anim.fromPixelY) * smoothT);
            Identifier tex = ChessTextures.getPieceTexture(anim.piece.getType(), anim.piece.getColor(), piecesMode);
            context.blit(RenderPipelines.GUI_TEXTURED, tex, curX, curY, 0, 0, PIECE_SIZE, PIECE_SIZE, 16, 16);
        }

        // --- Перетаскиваемая фигура ---
        if (isDragging && dragExitedCell && draggedPiece != null) {
            Identifier dragTex = ChessTextures.getPieceTexture(draggedPiece.getType(), draggedPiece.getColor(), piecesMode);
            context.blit(RenderPipelines.GUI_TEXTURED, dragTex,
                    mouseX - PIECE_SIZE / 2, mouseY - PIECE_SIZE / 2,
                    0, 0, PIECE_SIZE, PIECE_SIZE, 16, 16);
        }

        // --- Статус-текст над доской ---
        drawStatusText(context, bx, by);

        // --- Боковая панель ---
        drawSidePanel(context, bx, by, piecesMode, mouseX, mouseY);

        // --- Окно промоушена ---
        if (pendingPromotion) {
            drawPromotionPanel(context, mouseX, mouseY, piecesMode, bx, by);
        }
    }

    private void drawCoordinateLabels(GuiGraphicsExtractor context, int ix, int iy) {
        // Буквы a-h снизу
        char[] files = {'a','b','c','d','e','f','g','h'};
        for (int x = 0; x < 8; x++) {
            String label = String.valueOf(files[x]);
            int lx = ix + x * CELL_SIZE + CELL_SIZE / 2 - font.width(label) / 2;
            int ly = iy + BOARD_PX + 3;
            context.text(font, Component.literal(label), lx, ly, 0xFFBBBBBB, false);
        }
        // Цифры 8-1 слева (8 вверху, 1 внизу)
        for (int y = 0; y < 8; y++) {
            String label = String.valueOf(8 - y);
            int lx = ix - font.width(label) - 3;
            int ly = iy + y * CELL_SIZE + CELL_SIZE / 2 - font.lineHeight / 2;
            context.text(font, Component.literal(label), lx, ly, 0xFFBBBBBB, false);
        }
    }

    private void drawStatusText(GuiGraphicsExtractor context, int bx, int by) {
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
        int textX = bx + TOTAL_W / 2 - tw / 2;
        int textY = by - 14;
        context.text(font, statusText, textX, textY, statusColor, true);
    }

    private void drawSidePanel(GuiGraphicsExtractor context, int bx, int by,
                               ChessRenderMode piecesMode, int mouseX, int mouseY) {
        int px = bx + TOTAL_W + PANEL_GAP;
        int py = by;
        int ph = TOTAL_W;

        // Фон панели
        context.fill(px, py, px + PANEL_W, py + ph, 0xCC111111);
        context.fill(px, py, px + PANEL_W, py + 1, 0xFF555555);
        context.fill(px, py + ph - 1, px + PANEL_W, py + ph, 0xFF555555);
        context.fill(px, py, px + 1, py + ph, 0xFF555555);
        context.fill(px + PANEL_W - 1, py, px + PANEL_W, py + ph, 0xFF555555);

        int cy = py + 8;

        // === Счётчик ходов ===
        int moveNum = board.getFullMoveNumber();
        ChessBotMode botMode = MinigameRegistry.getConfig(ChessVisibleConfig.class).botMode;
        String botLabel = "Bot: " + botMode.name().charAt(0) + botMode.name().substring(1).toLowerCase();
        Component moveComp = Component.literal("Move " + moveNum + "   " + botLabel);
        context.text(font, moveComp, px + 6, cy, 0xFFAAAAAA, false);
        cy += font.lineHeight + 6;

        // === Взятые фигуры игрока (белые взяли что-то = чёрные фигуры захвачены) ===
        // Показываем взятые белыми (capturedBlack) и взятые чёрными (capturedWhite)
        List<ChessPiece> capturedByWhite = board.getCapturedBlackPieces();
        List<ChessPiece> capturedByBlack = board.getCapturedWhitePieces();

        // Преимущество в материале
        int whiteAdv = computeMaterialAdvantage(capturedByWhite, capturedByBlack);

        // --- Захваченные ЧЁРНЫМи (наверху — сторона бота если игрок белый) ---
        context.text(font, Component.literal("Captured:"), px + 6, cy, 0xFF888888, false);
        cy += font.lineHeight + 2;
        cy = drawCapturedPieces(context, capturedByBlack, px + 6, cy, piecesMode);
        if (whiteAdv < 0) {
            context.text(font, Component.literal("+" + (-whiteAdv)), px + 6, cy, 0xFF55FF55, false);
        }
        cy += font.lineHeight + 4;

        // Разделитель
        context.fill(px + 4, cy, px + PANEL_W - 4, cy + 1, 0xFF444444);
        cy += 5;

        // --- Захваченные БЕЛЫМи ---
        context.text(font, Component.literal("Captured:"), px + 6, cy, 0xFF888888, false);
        cy += font.lineHeight + 2;
        cy = drawCapturedPieces(context, capturedByWhite, px + 6, cy, piecesMode);
        if (whiteAdv > 0) {
            context.text(font, Component.literal("+" + whiteAdv), px + 6, cy, 0xFF55FF55, false);
        }
        cy += font.lineHeight + 4;

        // Разделитель
        context.fill(px + 4, cy, px + PANEL_W - 4, cy + 1, 0xFF444444);
        cy += 8;

        // === История ходов ===
        context.text(font, Component.literal("Moves:"), px + 6, cy, 0xFF888888, false);
        cy += font.lineHeight + 3;

        // Показываем последние ходы (пары: белый/чёрный)
        int maxMoveLines = (py + ph - cy - 10) / (font.lineHeight + 1);
        int startIdx = Math.max(0, moveNotationHistory.size() - maxMoveLines * 2);
        // Выравниваем до начала пары
        if (startIdx % 2 == 1) startIdx--;

        for (int i = startIdx; i < moveNotationHistory.size(); i += 2) {
            int pairNum = i / 2 + 1;
            String whiteMove = moveNotationHistory.get(i);
            String blackMove = (i + 1 < moveNotationHistory.size()) ? moveNotationHistory.get(i + 1) : "";
            String line = pairNum + ". " + whiteMove + (blackMove.isEmpty() ? "" : "  " + blackMove);
            // Последняя пара — ярче
            int col = (i >= moveNotationHistory.size() - 2) ? 0xFFFFFFFF : 0xFF888888;
            context.text(font, Component.literal(line), px + 6, cy, col, false);
            cy += font.lineHeight + 1;
            if (cy > py + ph - 10) break;
        }
    }

    private int computeMaterialAdvantage(List<ChessPiece> capturedByWhite, List<ChessPiece> capturedByBlack) {
        int white = 0;
        for (ChessPiece p : capturedByWhite) white += p.getType().getValue();
        int black = 0;
        for (ChessPiece p : capturedByBlack) black += p.getType().getValue();
        return white - black;
    }

    /** Рисует взятые фигуры миниатюрами 12×12, возвращает новый y */
    private int drawCapturedPieces(GuiGraphicsExtractor context, List<ChessPiece> pieces,
                                   int startX, int startY, ChessRenderMode piecesMode) {
        if (pieces.isEmpty()) return startY;
        int miniSize = 12;
        int gap = 1;
        int perRow = (PANEL_W - 12) / (miniSize + gap);
        int x = startX;
        int y = startY;
        int count = 0;
        for (ChessPiece p : pieces) {
            if (count > 0 && count % perRow == 0) {
                y += miniSize + gap;
                x = startX;
            }
            Identifier tex = ChessTextures.getPieceTexture(p.getType(), p.getColor(), piecesMode);
            context.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0, 0, miniSize, miniSize, 16, 16);
            x += miniSize + gap;
            count++;
        }
        int rows = (count == 0) ? 0 : (count - 1) / perRow + 1;
        return startY + rows * (miniSize + gap) + 2;
    }

    private void drawPromotionPanel(GuiGraphicsExtractor context, int mouseX, int mouseY,
                                    ChessRenderMode piecesMode, int bx, int by) {
        context.fill(bx, by, bx + TOTAL_W, by + TOTAL_W, 0x99000000);
        int pw = 130;
        int ph = 52;
        int ppx = (width - pw) / 2;
        int ppy = (height - ph) / 2;
        context.fill(ppx, ppy, ppx + pw, ppy + ph, 0xEE2A2A2A);
        context.fill(ppx,          ppy,      ppx + pw,     ppy + 1,  0xFFFFFFFF);
        context.fill(ppx,          ppy + ph - 1, ppx + pw, ppy + ph, 0xFFFFFFFF);
        context.fill(ppx,          ppy,      ppx + 1,      ppy + ph, 0xFFFFFFFF);
        context.fill(ppx + pw - 1, ppy,      ppx + pw,     ppy + ph, 0xFFFFFFFF);

        Component promoTitle = Component.translatable("minigame.chess.promote");
        context.text(font, promoTitle, ppx + pw / 2 - font.width(promoTitle) / 2, ppy + 5, 0xFFFFFFFF, false);

        ChessPieceType[] promoTypes = {ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT};
        int pieceSlot = 26;
        for (int i = 0; i < 4; i++) {
            int itemX = ppx + 7 + i * pieceSlot;
            int itemY = ppy + 20;
            if (mouseX >= itemX && mouseX < itemX + pieceSlot - 2 && mouseY >= itemY && mouseY < itemY + pieceSlot - 2) {
                context.fill(itemX, itemY, itemX + pieceSlot - 2, itemY + pieceSlot - 2, 0x55FFFFFF);
            }
            Identifier pTex = ChessTextures.getPieceTexture(promoTypes[i], actualPlayerColor, piecesMode);
            int ps = 20;
            context.blit(RenderPipelines.GUI_TEXTURED, pTex, itemX + (pieceSlot - 2 - ps) / 2, itemY + 2, 0, 0, ps, ps, 16, 16);
        }
    }

    private boolean isAnimatingTarget(int x, int y) {
        for (MoveAnimation anim : activeAnimations) {
            if (anim.boardToX == x && anim.boardToY == y) return true;
        }
        return false;
    }

    // ======= Ввод мыши =======
    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubled) {
        double mouseX = click.x();
        double mouseY = click.y();
        if (click.button() != 0) return super.mouseClicked(click, doubled);

        if (pendingPromotion) {
            int pw = 130;
            int ph = 52;
            int ppx = (width - pw) / 2;
            int ppy = (height - ph) / 2;
            ChessPieceType[] promoTypes = {ChessPieceType.QUEEN, ChessPieceType.ROOK, ChessPieceType.BISHOP, ChessPieceType.KNIGHT};
            int pieceSlot = 26;
            for (int i = 0; i < 4; i++) {
                int itemX = ppx + 7 + i * pieceSlot;
                int itemY = ppy + 20;
                if (mouseX >= itemX && mouseX < itemX + pieceSlot - 2 && mouseY >= itemY && mouseY < itemY + pieceSlot - 2) {
                    completePromotion(promoTypes[i]);
                    return true;
                }
            }
            return true;
        }

        if (gameOver || botThinking) return super.mouseClicked(click, doubled);

        int ix = innerX();
        int iy = innerY();
        int cellX = (int) Math.floor((mouseX - ix) / (double) CELL_SIZE);
        int cellY = (int) Math.floor((mouseY - iy) / (double) CELL_SIZE);

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
                int cellX = (int) Math.floor((mouseX - ix) / (double) CELL_SIZE);
                int cellY = (int) Math.floor((mouseY - iy) / (double) CELL_SIZE);
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
                            int releasePx = (int) Math.round(mouseX - PIECE_SIZE / 2.0);
                            int releasePy = (int) Math.round(mouseY - PIECE_SIZE / 2.0);
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

    // ======= Логика игры =======

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
        long startTime = System.currentTimeMillis();
        ChessBoard boardCopy = board.copy();
        ChessBotMode mode = MinigameRegistry.getConfig(ChessVisibleConfig.class).botMode;
        CompletableFuture.supplyAsync(() -> bot.findBestMove(boardCopy, mode)).thenAccept(bestMove -> {
            long elapsed = System.currentTimeMillis() - startTime;
            long delay = Math.max(0, 350 - elapsed);
            botMoveReadyTime = System.currentTimeMillis() + delay;
            pendingBotMove = bestMove;
        });
    }

    private void applyMove(ChessMove move) { applyMove(move, false, 0, 0); }

    private void applyMove(ChessMove move, boolean fromDrag, int dragReleasePx, int dragReleasePy) {
        int ix = innerX();
        int iy = innerY();

        boolean isCapture = move.isEnPassant() || board.getPiece(move.getToX(), move.getToY()) != null;

        // Записываем нотацию ДО хода
        String notation = buildNotation(move, board);

        board.makeMove(move);
        lastMove = move;
        moveNotationHistory.add(notation);

        // Анимация
        ChessPiece movedPiece = board.getPiece(move.getToX(), move.getToY());
        int toPx = ix + move.getToX() * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2;
        int toPy = iy + move.getToY() * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2;
        int fromPx = fromDrag ? dragReleasePx : (ix + move.getFromX() * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2);
        int fromPy = fromDrag ? dragReleasePy : (iy + move.getFromY() * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2);
        long duration = fromDrag ? 80 : 180;
        activeAnimations.add(new MoveAnimation(movedPiece, fromPx, fromPy, toPx, toPy, move.getToX(), move.getToY(), duration));

        if (move.isCastling()) {
            if (move.getToX() == 6) {
                ChessPiece rook = board.getPiece(5, move.getToY());
                if (rook != null) {
                    int rFrom = ix + 7 * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2;
                    int rTo   = ix + 5 * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2;
                    int rY    = iy + move.getToY() * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2;
                    activeAnimations.add(new MoveAnimation(rook, rFrom, rY, rTo, rY, 5, move.getToY(), 180));
                }
            } else if (move.getToX() == 2) {
                ChessPiece rook = board.getPiece(3, move.getToY());
                if (rook != null) {
                    int rFrom = ix + 0 * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2;
                    int rTo   = ix + 3 * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2;
                    int rY    = iy + move.getToY() * CELL_SIZE + (CELL_SIZE - PIECE_SIZE) / 2;
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
