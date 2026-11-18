package com.advokatbabayki.backrooms;

import org.slf4j.Logger;

import com.mojang.logging.LogUtils;

import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.event.lifecycle.FMLCommonSetupEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

@Mod(BackroomsMod.MODID)
public class BackroomsMod {
    public static final String MODID = "backrooms";
    public static final Logger LOGGER = LogUtils.getLogger();

    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(MODID);
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredItem<Item> ALMOND_WATER = ITEMS.registerSimpleItem("almond_water");

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BACKROOMS_TAB = CREATIVE_MODE_TABS.register("backrooms_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.backrooms"))
            .icon(() -> ALMOND_WATER.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(ALMOND_WATER.get());
            }).build());

    public BackroomsMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        NeoForge.EVENT_BUS.register(this);
    }

    private void commonSetup(FMLCommonSetupEvent event) {
        LOGGER.info("Backrooms mod loaded. Mind the hum.");
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        LOGGER.info("Backrooms server starting");
    }
}
