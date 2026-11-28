package dev.eyecrasher.jukeboxplaylists.mixin;
import dev.eyecrasher.jukeboxplaylists.LegacyRecordSound;
import net.minecraft.world.item.RecordItem;
import org.spongepowered.asm.mixin.*;
@Mixin(RecordItem.class)
public abstract class RecordSoundMixin implements LegacyRecordSound {
    @Shadow @Final private net.minecraft.sounds.SoundEvent sound;
    @Override public String playlist$soundId() {
        return sound == null ? null : sound.getLocation().toString();
    }
}
