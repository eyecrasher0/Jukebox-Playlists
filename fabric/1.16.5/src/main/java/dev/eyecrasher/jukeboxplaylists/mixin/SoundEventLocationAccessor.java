package dev.eyecrasher.jukeboxplaylists.mixin;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundEvent.class)
public interface SoundEventLocationAccessor {
    @Accessor("location")
    ResourceLocation playlist$getLocation();
}
