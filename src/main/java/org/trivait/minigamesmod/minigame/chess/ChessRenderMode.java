package org.trivait.minigamesmod.minigame.chess;

import net.minecraft.network.chat.Component;

public enum ChessRenderMode {
    CLASSIC(0, Component.translatable("minigame.chess.mode.classic")),
    WOODEN(1, Component.translatable("minigame.chess.mode.wooden"))
    ;

    public int mode;
    public Component name;

    ChessRenderMode(int mode, Component name) {
        this.mode = mode;
        this.name = name;
    }

    @Override
    public String toString() {
        return name.getString();
    }
}