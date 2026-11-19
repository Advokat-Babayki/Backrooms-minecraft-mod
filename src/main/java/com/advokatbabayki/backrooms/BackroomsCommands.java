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
import net.minecraft.util.Mth;
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
                .then(Commands.literal("leave").executes(ctx -> leave(ctx.getSource())))
                .then(Commands.literal("room").executes(ctx -> whereRoom(ctx.getSource()))));
    }

    private static int enter(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        ServerLevel level0 = source.getServer().getLevel(LEVEL0);
        if (level0 == null) {
            source.sendFailure(Component.literal("Level 0 dimension is missing"));
            return 0;
        }
        RETURN_POINTS.put(player.getUUID(), new ReturnPoint(player.level().dimension(), player.getX(), player.getY(), player.getZ()));
        // Same maze_seed the dimension JSON uses. The entry room is a forced
        // room island pinned to the local web height: the landing spot is
        // always open carved floor.
        Level0Plan plan = new Level0Plan(0L);
        Level0Plan.Room entry = plan.room(0, 0);
        int floorY = entry != null ? entry.floorY() : plan.floorSurface(24, 24);
        player.teleportTo(level0, (entry != null ? entry.centerX() : 24) + 0.5, floorY + 1.0,
                (entry != null ? entry.centerZ() : 24) + 0.5, player.getYRot(), player.getXRot());
        source.sendSuccess(() -> Component.literal("You noclipped into the Backrooms. Mind the hum."), false);
        return Command.SINGLE_SUCCESS;
    }

    /**
     * Dev helper: report which room (type, cell, size, center, floor) the
     * player stands in, so generation feedback can name the exact room.
     */
    private static int whereRoom(CommandSourceStack source) throws CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        Level0Plan plan = new Level0Plan(0L);
        int bx = Mth.floor(player.getX());
        int bz = Mth.floor(player.getZ());
        int cx = Math.floorDiv(bx, Level0Plan.CELL_BLOCKS);
        int cz = Math.floorDiv(bz, Level0Plan.CELL_BLOCKS);
        Level0Plan.Room room = plan.room(cx, cz);
        if (room == null) {
            int floor = plan.floorSurface(bx, bz);
            source.sendSuccess(() -> Component.literal(String.format(
                    "Ты в паутине коридоров, ячейка [%d, %d], пол y=%d. Комнаты в этой ячейке нет.",
                    cx, cz, floor)), false);
            return Command.SINGLE_SUCCESS;
        }
        boolean inside = bx >= Level0Plan.roomMinX(room) && bx <= Level0Plan.roomMaxX(room)
                && bz >= Level0Plan.roomMinZ(room) && bz <= Level0Plan.roomMaxZ(room);
        int type = plan.roomType(cx, cz);
        String typeName = switch (type) {
            case Level0Plan.ROOM_PILLARS -> "PILLARS (колонны)";
            case Level0Plan.ROOM_MEZZANINE -> "MEZZANINE (мезонин)";
            case Level0Plan.ROOM_HOLES -> "HOLES (проломы в полу)";
            case Level0Plan.ROOM_BLACKOUT -> "BLACKOUT (тёмная)";
            case Level0Plan.ROOM_TALL -> "TALL (огромный ангар)";
            default -> Level0Plan.roomTypeName(type) + " (вариант " + type + ")";
        };
        String place = inside ? "Ты в комнате" : "Ты в коридоре; комната ячейки";
        source.sendSuccess(() -> Component.literal(String.format(
                "%s [%d, %d]: %s · размер %d · центр x=%d z=%d · пол y=%d",
                place, cx, cz, typeName, room.size(), room.centerX(), room.centerZ(),
                room.floorY())), false);
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
