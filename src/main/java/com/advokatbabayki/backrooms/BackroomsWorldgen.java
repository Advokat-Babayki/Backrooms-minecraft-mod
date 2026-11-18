package com.advokatbabayki.backrooms;

import net.minecraft.core.registries.Registries;
import net.minecraft.world.level.chunk.ChunkGenerator;
import com.mojang.serialization.MapCodec;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Registry for custom worldgen codecs. Dimension JSONs reference these by id.
 */
public final class BackroomsWorldgen {
    public static final DeferredRegister<MapCodec<? extends ChunkGenerator>> GENERATORS =
            DeferredRegister.create(Registries.CHUNK_GENERATOR, BackroomsMod.MODID);

    public static final MapCodec<? extends ChunkGenerator> LEVEL0 =
            GENERATORS.register("level0", () -> Level0Generator.CODEC).get();

    private BackroomsWorldgen() {}
}
