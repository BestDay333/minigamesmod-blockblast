package org.trivait.minigamesmod.minigame.blockblast;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

@Config(name = "minigames/blockblast")
public class BlockBlastConfig implements ConfigData {
    // Records
    public long bestScore = 0;
    public int bestCombo = 0;

    // Saved game state (empty string = no saved game)
    // Format: "score;grid(64 ints);gridPalette(64 ints);tray(3 slots: paletteIdx,rows,cols,cells...);combo"
    public String savedGame = "";
}
