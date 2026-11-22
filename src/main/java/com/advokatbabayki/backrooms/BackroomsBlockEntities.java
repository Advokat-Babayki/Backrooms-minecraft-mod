package com.advokatbabayki.backrooms;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;

public final class BackroomsBlockEntities {
    public static final DeferredRegister<BlockEntityType<?>> BLOCK_ENTITY_TYPES =
            DeferredRegister.create(Registries.BLOCK_ENTITY_TYPE, BackroomsMod.MODID);

    public static final DeferredHolder<BlockEntityType<?>, BlockEntityType<FilingCabinetBlockEntity>> FILING_CABINET =
            BLOCK_ENTITY_TYPES.register("filing_cabinet",
                    () -> BlockEntityType.Builder.of(FilingCabinetBlockEntity::new, BackroomsBlocks.FILING_CABINET.get()).build(null));

    private BackroomsBlockEntities() {}
}
