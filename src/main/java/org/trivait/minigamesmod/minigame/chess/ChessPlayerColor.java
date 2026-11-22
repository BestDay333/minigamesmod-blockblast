package org.trivait.minigamesmod.minigame.chess;

import net.minecraft.network.chat.Component;

public enum ChessPlayerColor {
    WHITE(0, Component.translatable("minigame.chess.color.white")),
    BLACK(1, Component.translatable("minigame.chess.color.black")),
    RANDOM(2, Component.translatable("minigame.chess.color.random"));

    public int mode;
    public Component name;

    ChessPlayerColor(int mode, Component name) {
        this.mode = mode;
        this.name = name;
    }

    @Override
    public String toString() {
        return name.getString();
    }
}
