package org.trivait.minigamesmod.minigame.blockblast;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;

@Config(name = "minigames/blockblast")
public class BlockBlastConfig implements ConfigData {
    public long bestScore = 0;
    public int bestCombo = 0;
}
