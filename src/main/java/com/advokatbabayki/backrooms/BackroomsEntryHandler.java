package com.advokatbabayki.backrooms;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/**
 * Natural ways into Level 0: suffocating under sand or gravel, or falling out
 * of the world.
 */
@EventBusSubscriber(modid = BackroomsMod.MODID)
public final class BackroomsEntryHandler {
    // About three seconds, the "buried alive" beat of the creepypasta noclip.
    private static final int SUFFOCATION_TICKS = 60;
    private static final String SUFFOCATION_KEY = "backrooms:suffocation_ticks";

    private BackroomsEntryHandler() {}

    @SubscribeEvent
    static void onPlayerTick(PlayerTickEvent.Post event) {
        // The event also fires on the client; only server players can change dimensions.
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        if (player.level().dimension().equals(BackroomsCommands.LEVEL0)) {
            data.remove(SUFFOCATION_KEY);
            return;
        }
        if (player.getY() < player.level().getMinBuildHeight()) {
            data.remove(SUFFOCATION_KEY);
            noclip(player);
            return;
        }
        BlockPos eyes = BlockPos.containing(player.getX(), player.getEyeY(), player.getZ());
        BlockState state = player.level().getBlockState(eyes);
        if (!state.is(Blocks.SAND) && !state.is(Blocks.RED_SAND) && !state.is(Blocks.GRAVEL)) {
            data.remove(SUFFOCATION_KEY);
            return;
        }
        int ticks = data.getInt(SUFFOCATION_KEY) + 1;
        if (ticks < SUFFOCATION_TICKS) {
            data.putInt(SUFFOCATION_KEY, ticks);
            return;
        }
        data.remove(SUFFOCATION_KEY);
        noclip(player);
    }

    private static void noclip(ServerPlayer player) {
        if (BackroomsCommands.noclipIntoLevel0(player)) {
            player.displayClientMessage(Component.literal("Ты провалился в Бэкрумс. Слушай гул."), false);
        }
    }
}
