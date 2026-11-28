package dev.eyecrasher.jukeboxplaylists.mixin;

import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SoundEvent.class)
public interface SoundEventLocationAccessor {
    @Accessor("location")
    ResourceLocation playlist$getLocation();
}
