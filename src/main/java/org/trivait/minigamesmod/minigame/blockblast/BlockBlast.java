package org.trivait.minigamesmod.minigame.blockblast;

import me.shedaniel.autoconfig.ConfigData;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.trivait.minigamesmod.MinigamesMod;
import org.trivait.minigamesmod.api.AbstractMinigame;
import org.trivait.minigamesmod.api.MinigameRegistry;
import org.trivait.minigamesmod.api.PlayingSoundManager;
import org.trivait.minigamesmod.leaderboard.Leaderboard;
import net.minecraft.client.gui.screen.Screen;

import java.util.List;

public class BlockBlast extends AbstractMinigame {

    public BlockBlast() {
        super("blockblast",
                Text.literal("Block Blast"),
                Identifier.of(MinigamesMod.MOD_ID, "textures/minigame/blockblast_icon.png"));
    }

    @Override
    public Class<? extends ConfigData> getConfigClass() {
        return BlockBlastConfig.class;
    }

    @Override
    public Class<? extends ConfigData> getVisibleConfigClass() {
        return BlockBlastVisibleConfig.class;
    }

    @Override
    public @Nullable Leaderboard getLeaderboard() {
        return new Leaderboard("BlockBlast", List.of(
                Text.translatable("minigame.blockblast.condition")
        ), true);
    }

    @Override
    public Screen createScreen(Screen parent) {
        return new BlockBlastScreen(this, parent);
    }

    @Override
    public void onLose() {
        PlayingSoundManager.playSound(SoundEvents.ENTITY_VILLAGER_NO, 1, vol());
    }

    private float vol() {
        return PlayingSoundManager.vol(MinigameRegistry.getConfig(BlockBlastVisibleConfig.class).volume);
    }
}
