package com.advokatbabayki.backrooms;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.exceptions.CommandSyntaxException;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

/**
 * Dev/teleport commands: /backrooms enter, /backrooms leave.
 * Later: /backrooms level <n> for jumping between levels during development.
 */
@EventBusSubscriber(modid = BackroomsMod.MODID)
public final class BackroomsCommands {
    public static final ResourceKey<net.minecraft.world.level.Level> LEVEL0 =
            ResourceKey.create(Registries.DIMENSION, ResourceLocation.fromNamespaceAndPath(BackroomsMod.MODID, "level0"));

    private record ReturnPoint(ResourceKey<net.minecraft.world.level.Level> dimension, double x, double y, double z) {}
    private static final Map<UUID, ReturnPoint> RETURN_POINTS = new HashMap<>();

    private BackroomsCommands() {}

    @SubscribeEvent
    static void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("backrooms")
                .then(Commands.literal("enter").executes(ctx -> enter(ctx.getSource())))
                .then(Commands.literal("leave").executes(ctx -> leave(ctx.getSource()))));
    }

    private static int enter(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level0 = source.getServer().getLevel(LEVEL0);
        if (level0 == null) {
            source.sendFailure(Component.literal("Level 0 dimension is missing"));
            return 0;
        }
        RETURN_POINTS.put(player.getUUID(), new ReturnPoint(player.level().dimension(), player.getX(), player.getY(), player.getZ()));
        player.teleportTo(level0, 8.5, 65.0, 8.5, player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("You noclipped into the Backrooms. Mind the hum."), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int leave(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ReturnPoint point = RETURN_POINTS.remove(player.getUUID());
        if (point == null) {
            source.sendFailure(Component.literal("You never entered the Backrooms... or you already left."));
            return 0;
        }
        ServerLevel target = source.getServer().getLevel(point.dimension());
        if (target == null) {
            source.sendFailure(Component.literal("Your home dimension is unavailable."));
            return 0;
        }
        player.teleportTo(target, point.x(), point.y(), point.z(), player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("You noclipped back to reality."), false);
        return Command.SINGLE_SUCCESS;
    }
}
