package dev.eyecrasher.jukeboxplaylists.mixin;
import dev.eyecrasher.jukeboxplaylists.LegacyRecordSound;
import net.minecraft.item.MusicDiscItem;
import org.spongepowered.asm.mixin.*;
@Mixin(MusicDiscItem.class)
public abstract class RecordSoundMixin implements LegacyRecordSound {
    @Shadow(remap = false) @Final private java.util.function.Supplier<net.minecraft.util.SoundEvent> soundSupplier;
    @Override public String playlist$soundId() {
        net.minecraft.util.SoundEvent sound = soundSupplier.get();
        return sound == null ? null : ((SoundEventLocationAccessor) sound).playlist$getLocation().toString();
    }
}
