package com.advokatbabayki.backrooms;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.event.lifecycle.FMLClientSetupEvent;

// This class will not load on dedicated servers. Accessing client side code from here is safe.
@Mod(value = BackroomsMod.MODID, dist = Dist.CLIENT)
@EventBusSubscriber(modid = BackroomsMod.MODID, value = Dist.CLIENT)
public class BackroomsModClient {
    public BackroomsModClient(ModContainer container) {
    }

    @SubscribeEvent
    static void onClientSetup(FMLClientSetupEvent event) {
        BackroomsMod.LOGGER.info("Backrooms client setup, player: {}", Minecraft.getInstance().getUser().getName());
    }
}
