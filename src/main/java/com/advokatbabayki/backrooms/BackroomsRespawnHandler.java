package com.advokatbabayki.backrooms;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.portal.DimensionTransition;
import net.minecraft.world.phys.Vec3;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.player.PlayerRespawnPositionEvent;

/**
 * Death inside a Backrooms level keeps the player on that level: at their bed
 * if they slept there, otherwise at the entry point. The stored respawn data
 * is left untouched, so the player's own bed outside keeps working.
 */
@EventBusSubscriber(modid = BackroomsMod.MODID)
public final class BackroomsRespawnHandler {
    private BackroomsRespawnHandler() {}

    @SubscribeEvent
    static void onRespawnPosition(PlayerRespawnPositionEvent event) {
        // The End return travels through the same respawn path but is not a death.
        if (event.isFromEndFight() || !(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        ResourceKey<Level> diedIn = player.level().dimension();
        // A bed in a Backrooms level already wins: vanilla picked it.
        if (!isBackrooms(diedIn) || isBackrooms(event.getOriginalDimensionTransition().newLevel().dimension())) {
            return;
        }
        ServerLevel level = player.server.getLevel(diedIn);
        if (level == null) {
            return;
        }
        // The entry point /backrooms enter uses, so respawn and entry stay one place.
        // The only Backrooms level so far; a new level needs its own spawn point.
        int[] spawn = new Level0Plan(0L).spawnPoint();
        event.setDimensionTransition(new DimensionTransition(level,
                new Vec3(spawn[0] + 0.5, spawn[2] + 1.0, spawn[1] + 0.5),
                Vec3.ZERO, 0.0F, 0.0F, DimensionTransition.DO_NOTHING));
    }

    static boolean isBackrooms(ResourceKey<Level> dimension) {
        return dimension.location().getNamespace().equals(BackroomsMod.MODID);
    }
}
