package org.trivait.minigamesmod.minigame.chess;

import net.minecraft.network.chat.Component;

public enum ChessBotMode {
    EASY(0, Component.translatable("minigame.chess.mode.easy")),
    NORMAL(1, Component.translatable("minigame.chess.mode.normal")),
    HARD(2, Component.translatable("minigame.chess.mode.hard")),
    CRAZY(3, Component.translatable("minigame.chess.mode.crazy")),
    ;

    public int mode;
    public Component name;

    ChessBotMode(int mode, Component name) {
        this.mode = mode;
        this.name = name;
    }

    @Override
    public String toString() {
        return name.getString();
    }
}