package com.advokatbabayki.backrooms;

import net.minecraft.world.item.BlockItem;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.material.MapColor;
import net.minecraft.world.phys.shapes.Shapes;
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

    /** Unbreakable red wallpaper: the level peeling through (Red Rooms nod). */
    public static final DeferredBlock<Block> RED_WALLPAPER = BLOCKS.registerSimpleBlock("red_wallpaper",
            BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_RED).strength(-1.0f, 3600000.0f).sound(SoundType.WOOL));

    /** Carpet step: every street stair in the levels is built from this. */
    public static final DeferredBlock<StairBlock> CARPET_STAIRS = BLOCKS.register("carpet_stairs",
            () -> new StairBlock(MOIST_CARPET.get().defaultBlockState(),
                    BlockBehaviour.Properties.of().mapColor(MapColor.COLOR_YELLOW).strength(-1.0f, 3600000.0f).sound(SoundType.WOOL)));

    // ---- Furniture: the only breakable blocks of the level ----

    /** Loot container; the generator points it at backrooms:chests/level0_cabinet. */
    public static final DeferredBlock<FilingCabinetBlock> FILING_CABINET = BLOCKS.register("filing_cabinet",
            () -> new FilingCabinetBlock(metal(MapColor.COLOR_GRAY, 3.5f, 6.0f)));

    /** Desk with four legs and a drawer pedestal; variants are oak, spruce, dark oak. */
    public static final DeferredBlock<FurnitureBlock.ThreeVariant> OFFICE_DESK = BLOCKS.register("office_desk",
            () -> new FurnitureBlock.ThreeVariant(wood(MapColor.WOOD, 2.0f, 3.0f), Shapes.block()));

    public static final DeferredBlock<FurnitureBlock.TwoVariant> OFFICE_CHAIR = BLOCKS.register("office_chair",
            () -> new FurnitureBlock.TwoVariant(wood(MapColor.WOOD, 1.5f, 3.0f),
                    Block.box(2, 0, 2, 14, 10, 14)));

    public static final DeferredBlock<FurnitureBlock.TwoVariant> OFFICE_SHELF = BLOCKS.register("office_shelf",
            () -> new FurnitureBlock.TwoVariant(wood(MapColor.WOOD, 2.0f, 3.0f), Shapes.block()));

    public static final DeferredBlock<FurnitureBlock> WATER_COOLER = BLOCKS.register("water_cooler",
            () -> new FurnitureBlock(metal(MapColor.COLOR_LIGHT_GRAY, 2.5f, 5.0f),
                    Block.box(3, 0, 3, 13, 16, 13)));

    public static final DeferredBlock<FurnitureBlock.TwoVariant> POTTED_PLANT = BLOCKS.register("potted_plant",
            () -> new FurnitureBlock.TwoVariant(BlockBehaviour.Properties.of().mapColor(MapColor.PLANT)
                    .strength(0.4f).sound(SoundType.GRASS).noOcclusion(),
                    Block.box(4, 0, 4, 12, 12, 12)));

    public static final DeferredBlock<FurnitureBlock> TRASH_BIN = BLOCKS.register("trash_bin",
            () -> new FurnitureBlock(metal(MapColor.COLOR_GRAY, 2.5f, 5.0f),
                    Block.box(4, 0, 4, 12, 12, 12)));

    public static final DeferredBlock<FurnitureBlock> PRINTER = BLOCKS.register("printer",
            () -> new FurnitureBlock(metal(MapColor.COLOR_LIGHT_GRAY, 3.0f, 6.0f),
                    Block.box(2, 0, 2, 14, 10, 14)));

    public static final DeferredItem<BlockItem> YELLOW_WALLPAPER_ITEM = ITEMS.registerSimpleBlockItem("yellow_wallpaper", YELLOW_WALLPAPER);
    public static final DeferredItem<BlockItem> MOIST_CARPET_ITEM = ITEMS.registerSimpleBlockItem("moist_carpet", MOIST_CARPET);
    public static final DeferredItem<BlockItem> CEILING_TILE_ITEM = ITEMS.registerSimpleBlockItem("ceiling_tile", CEILING_TILE);
    public static final DeferredItem<BlockItem> FLUORESCENT_PANEL_ITEM = ITEMS.registerSimpleBlockItem("fluorescent_panel", FLUORESCENT_PANEL);
    public static final DeferredItem<BlockItem> WALLPAPER_SLAB_ITEM = ITEMS.registerSimpleBlockItem("wallpaper_slab", WALLPAPER_SLAB);
    public static final DeferredItem<BlockItem> RED_WALLPAPER_ITEM = ITEMS.registerSimpleBlockItem("red_wallpaper", RED_WALLPAPER);
    public static final DeferredItem<BlockItem> CARPET_STAIRS_ITEM = ITEMS.registerSimpleBlockItem("carpet_stairs", CARPET_STAIRS);
    public static final DeferredItem<BlockItem> FILING_CABINET_ITEM = ITEMS.registerSimpleBlockItem("filing_cabinet", FILING_CABINET);
    public static final DeferredItem<BlockItem> OFFICE_DESK_ITEM = ITEMS.registerSimpleBlockItem("office_desk", OFFICE_DESK);
    public static final DeferredItem<BlockItem> OFFICE_CHAIR_ITEM = ITEMS.registerSimpleBlockItem("office_chair", OFFICE_CHAIR);
    public static final DeferredItem<BlockItem> OFFICE_SHELF_ITEM = ITEMS.registerSimpleBlockItem("office_shelf", OFFICE_SHELF);
    public static final DeferredItem<BlockItem> WATER_COOLER_ITEM = ITEMS.registerSimpleBlockItem("water_cooler", WATER_COOLER);
    public static final DeferredItem<BlockItem> POTTED_PLANT_ITEM = ITEMS.registerSimpleBlockItem("potted_plant", POTTED_PLANT);
    public static final DeferredItem<BlockItem> TRASH_BIN_ITEM = ITEMS.registerSimpleBlockItem("trash_bin", TRASH_BIN);
    public static final DeferredItem<BlockItem> PRINTER_ITEM = ITEMS.registerSimpleBlockItem("printer", PRINTER);

    private static BlockBehaviour.Properties wood(MapColor color, float hardness, float resistance) {
        return BlockBehaviour.Properties.of().mapColor(color)
                .strength(hardness, resistance).sound(SoundType.WOOD).noOcclusion();
    }

    private static BlockBehaviour.Properties metal(MapColor color, float hardness, float resistance) {
        return BlockBehaviour.Properties.of().mapColor(color)
                .strength(hardness, resistance).sound(SoundType.METAL).noOcclusion();
    }

    private BackroomsBlocks() {}
}
