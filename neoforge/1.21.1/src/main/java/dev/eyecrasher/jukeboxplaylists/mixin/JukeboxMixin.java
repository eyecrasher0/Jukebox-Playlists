package dev.eyecrasher.jukeboxplaylists.mixin;

import dev.eyecrasher.jukeboxplaylists.PlaylistJukebox;
import dev.eyecrasher.jukeboxplaylists.Playlists;
import dev.eyecrasher.jukeboxplaylists.Platform;
import dev.eyecrasher.jukeboxplaylists.LazoDiscsCompat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.JukeboxBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;


@Mixin(JukeboxBlockEntity.class)
public abstract class JukeboxMixin implements PlaylistJukebox {
    @Unique private int playlist$cursor = -1;
    @Unique private int playlist$advanceDelay = -1;
    @Unique private ItemStack playlist$finishedItem = ItemStack.EMPTY;


    @Override public int playlist$position() { return playlist$cursor; }
    @Override public void playlist$position(int position) { playlist$cursor = position; }
    @Override public ItemStack playlist$current() {
        return ((JukeboxBlockEntity) (Object) this).getTheItem();
    }

    @Override public void playlist$play(ItemStack stack) {
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity) (Object) this;
        playlist$advanceDelay = -1;
        playlist$finishedItem = ItemStack.EMPTY;
        if (LazoDiscsCompat.play(this, stack)) return;
        jukebox.setTheItem(stack);
    }

    @Override public void playlist$tick() {
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity) (Object) this;
        Level level = jukebox.getLevel();
        if (level == null || Platform.isClient(level) || jukebox.isRemoved()) return;
        ItemStack current = playlist$current();
        if (playlist$advanceDelay >= 0 && current != playlist$finishedItem) {
            playlist$advanceDelay = -1;
            playlist$finishedItem = ItemStack.EMPTY;
        }
        boolean custom = LazoDiscsCompat.isCustom(current);
        if (custom) {
            // Keep vanilla's one-second gap, including for unavailable URLs;
            // never start a tight retry loop on the audio loader thread.
            if (playlist$advanceDelay > 0) playlist$advanceDelay--;
        } else {
            boolean wasPlaying = jukebox.getSongPlayer().isPlaying();
            JukeboxBlockEntity.tick(level, jukebox.getBlockPos(), jukebox.getBlockState(), jukebox);
            if (jukebox.getSongPlayer().isPlaying()) {
                if (playlist$advanceDelay >= 0) {
                    playlist$advanceDelay = -1;
                    playlist$finishedItem = ItemStack.EMPTY;
                }
            } else if (wasPlaying && !current.isEmpty() && current == playlist$current()) {
                playlist$advanceDelay = 0;
                playlist$finishedItem = current;
            }
        }
        if (playlist$current().isEmpty()) {
            Playlists.advance(this, false);
        } else if (playlist$advanceDelay == 0 && playlist$current() == playlist$finishedItem
                && Playlists.advance(this, true) && playlist$advanceDelay == 0) {
            playlist$advanceDelay = -1;
            playlist$finishedItem = ItemStack.EMPTY;
        }
    }

    @Override public void playlist$customFinished() {
        playlist$finishedItem = playlist$current();
        playlist$advanceDelay = 20;
    }



    @Inject(method = "loadAdditional", at = @At("TAIL"))
    private void playlist$load(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries, CallbackInfo ci) {
        playlist$cursor = tag.contains("playlist_position") ? tag.getInt("playlist_position") : -1;

    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void playlist$save(net.minecraft.nbt.CompoundTag tag, net.minecraft.core.HolderLookup.Provider registries, CallbackInfo ci) {
        if (playlist$cursor >= 0) tag.putInt("playlist_position", playlist$cursor);

    }
}
