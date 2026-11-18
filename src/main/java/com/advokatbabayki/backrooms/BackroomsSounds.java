package com.advokatbabayki.backrooms;

import java.util.function.Supplier;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.neoforge.registries.DeferredRegister;

/**
 * Sound events referenced by datapack JSONs (biome ambience). The sounds.json
 * file only maps these events to ogg files; the events themselves must exist
 * in the minecraft:sound_event registry, which happens here.
 */
public final class BackroomsSounds {
    public static final DeferredRegister<SoundEvent> SOUND_EVENTS =
            DeferredRegister.create(Registries.SOUND_EVENT, BackroomsMod.MODID);

    public static final Supplier<SoundEvent> HUM = SOUND_EVENTS.register("hum",
            () -> SoundEvent.createVariableRangeEvent(ResourceLocation.fromNamespaceAndPath(BackroomsMod.MODID, "hum")));

    private BackroomsSounds() {}
}
