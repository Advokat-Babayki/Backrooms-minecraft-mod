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

    public static final DeferredRegister.Items ITEMS = BackroomsBlocks.ITEMS;
    public static final DeferredRegister<CreativeModeTab> CREATIVE_MODE_TABS = DeferredRegister.create(Registries.CREATIVE_MODE_TAB, MODID);

    public static final DeferredItem<AlmondWaterItem> ALMOND_WATER = ITEMS.registerItem("almond_water",
            AlmondWaterItem::new, new Item.Properties().food(AlmondWaterItem.FOOD));

    public static final DeferredHolder<CreativeModeTab, CreativeModeTab> BACKROOMS_TAB = CREATIVE_MODE_TABS.register("backrooms_tab", () -> CreativeModeTab.builder()
            .title(Component.translatable("itemGroup.backrooms"))
            .icon(() -> ALMOND_WATER.get().getDefaultInstance())
            .displayItems((parameters, output) -> {
                output.accept(ALMOND_WATER.get());
                output.accept(BackroomsBlocks.YELLOW_WALLPAPER_ITEM.get());
                output.accept(BackroomsBlocks.MOIST_CARPET_ITEM.get());
                output.accept(BackroomsBlocks.CEILING_TILE_ITEM.get());
                output.accept(BackroomsBlocks.FLUORESCENT_PANEL_ITEM.get());
                output.accept(BackroomsBlocks.WALLPAPER_SLAB_ITEM.get());
                output.accept(BackroomsBlocks.RED_WALLPAPER_ITEM.get());
                output.accept(BackroomsBlocks.CARPET_STAIRS_ITEM.get());
                output.accept(BackroomsBlocks.FILING_CABINET_ITEM.get());
                output.accept(BackroomsBlocks.OFFICE_DESK_ITEM.get());
                output.accept(BackroomsBlocks.OFFICE_CHAIR_ITEM.get());
                output.accept(BackroomsBlocks.OFFICE_SHELF_ITEM.get());
                output.accept(BackroomsBlocks.WATER_COOLER_ITEM.get());
                output.accept(BackroomsBlocks.POTTED_PLANT_ITEM.get());
                output.accept(BackroomsBlocks.TRASH_BIN_ITEM.get());
                output.accept(BackroomsBlocks.PRINTER_ITEM.get());
            }).build());

    public BackroomsMod(IEventBus modEventBus, ModContainer modContainer) {
        modEventBus.addListener(this::commonSetup);
        BackroomsBlocks.BLOCKS.register(modEventBus);
        BackroomsBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
        ITEMS.register(modEventBus);
        CREATIVE_MODE_TABS.register(modEventBus);
        BackroomsWorldgen.GENERATORS.register(modEventBus);
        BackroomsSounds.SOUND_EVENTS.register(modEventBus);

        // Register ourselves for server and other game events we are interested in.
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
