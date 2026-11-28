package dev.eyecrasher.jukeboxplaylists.mixin;
import dev.eyecrasher.jukeboxplaylists.LegacyRecordSound;
import net.minecraft.world.item.RecordItem;
import org.spongepowered.asm.mixin.*;
@Mixin(RecordItem.class)
public abstract class RecordSoundMixin implements LegacyRecordSound {
    @Shadow(remap = false) @Final private java.util.function.Supplier<net.minecraft.sounds.SoundEvent> soundSupplier;
    @Override public String playlist$soundId() {
        net.minecraft.sounds.SoundEvent sound = soundSupplier.get();
        return sound == null ? null : sound.getLocation().toString();
    }
}
