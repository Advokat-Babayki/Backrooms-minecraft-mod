package com.advokatbabayki.backrooms;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.neoforged.neoforge.registries.DeferredBlock;
import net.neoforged.neoforge.registries.DeferredItem;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BackroomsBlocks {
    public static final DeferredRegister.Blocks BLOCKS = DeferredRegister.createBlocks(BackroomsMod.MODID);
    public static final DeferredRegister.Items ITEMS = DeferredRegister.createItems(BackroomsMod.MODID);

    public static final DeferredBlock<Block> YELLOW_WALLPAPER = BLOCKS.registerSimpleBlock("yellow_wallpaper",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(-1.0f, 3600000.0f).sound(SoundType.WOOL));

    public static final DeferredBlock<Block> MOIST_CARPET = BLOCKS.registerSimpleBlock("moist_carpet",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(-1.0f, 3600000.0f).sound(SoundType.WOOL));

    public static final DeferredBlock<Block> CEILING_TILE = BLOCKS.registerSimpleBlock("ceiling_tile",
            BlockBehaviour.Properties.of().mapColor(MapColor.SAND).strength(-1.0f, 3600000.0f).sound(SoundType.WOOL));

    public static final DeferredBlock<Block> FLUORESCENT_PANEL = BLOCKS.registerSimpleBlock("fluorescent_panel",
            BlockBehaviour.Properties.of().mapColor(MapColor.SNOW).strength(-1.0f, 3600000.0f).sound(SoundType.GLASS).lightLevel(state -> 15));

    /** Half-step used for every ramp and staircase in the levels. */
    public static final DeferredBlock<SlabBlock> WALLPAPER_SLAB = BLOCKS.register("wallpaper_slab",
            () -> new SlabBlock(BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(-1.0f, 3600000.0f).sound(SoundType.WOOL)));

    public static final DeferredItem<BlockItem> YELLOW_WALLPAPER_ITEM = ITEMS.registerSimpleBlockItem("yellow_wallpaper", YELLOW_WALLPAPER);
    public static final DeferredItem<BlockItem> MOIST_CARPET_ITEM = ITEMS.registerSimpleBlockItem("moist_carpet", MOIST_CARPET);
    public static final DeferredItem<BlockItem> CEILING_TILE_ITEM = ITEMS.registerSimpleBlockItem("ceiling_tile", CEILING_TILE);
    public static final DeferredItem<BlockItem> FLUORESCENT_PANEL_ITEM = ITEMS.registerSimpleBlockItem("fluorescent_panel", FLUORESCENT_PANEL);
    public static final DeferredItem<BlockItem> WALLPAPER_SLAB_ITEM = ITEMS.registerSimpleBlockItem("wallpaper_slab", WALLPAPER_SLAB);

    private BackroomsBlocks() {}
}
