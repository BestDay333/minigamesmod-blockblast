package org.trivait.minigamesmod.minigame.blockblast;

import me.shedaniel.autoconfig.ConfigData;
import me.shedaniel.autoconfig.annotation.Config;
import me.shedaniel.autoconfig.annotation.ConfigEntry;

@Config(name = "minigames/blockblast_ui")
public class BlockBlastVisibleConfig implements ConfigData {

    @ConfigEntry.BoundedDiscrete(min = 0, max = 100)
    public int volume = 100;
}
