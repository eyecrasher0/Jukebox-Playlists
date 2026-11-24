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
    @Unique private int playlist$finishedCount;
    @Unique private long playlist$elapsed;

    @Override public int playlist$position() { return playlist$cursor; }
    @Override public void playlist$position(int position) { playlist$cursor = position; }
    @Override public ItemStack playlist$current() {
        return ((JukeboxBlockEntity) (Object) this).getRecord();
    }

    @Override public void playlist$play(ItemStack stack) {
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity) (Object) this;
        playlist$markFinished(ItemStack.EMPTY, -1);
        if (LazoDiscsCompat.play(this, stack)) return;
        jukebox.setRecord(stack);
        Level level = jukebox.getLevel();
        level.setBlock(jukebox.getBlockPos(), jukebox.getBlockState().setValue(JukeboxBlock.HAS_RECORD, !stack.isEmpty()), 2);
        if (!stack.isEmpty()) {
            playlist$elapsed = 0;
            level.levelEvent(1010, jukebox.getBlockPos(), Item.getId(stack.getItem()));
        }
        level.updateNeighborsAt(jukebox.getBlockPos(), jukebox.getBlockState().getBlock());
        jukebox.setChanged();
    }

    @Override public void playlist$tick() {
        JukeboxBlockEntity jukebox = (JukeboxBlockEntity) (Object) this;
        Level level = jukebox.getLevel();
        if (level == null || Platform.isClient(level) || jukebox.isRemoved()) return;
        ItemStack current = playlist$current();
        if (playlist$advanceDelay >= 0 && !playlist$hasCompletion(current)) {
            playlist$markFinished(ItemStack.EMPTY, -1);
            playlist$elapsed = 0;
        }
        boolean custom = LazoDiscsCompat.isCustom(current);
        if (custom) {
            // Keep vanilla's one-second gap, including for unavailable URLs;
            // never start a tight retry loop on the audio loader thread.
            if (playlist$advanceDelay > 0) playlist$advanceDelay--;
            if (playlist$advanceDelay == 0 && LazoDiscsCompat.playing(this)) {
                playlist$markFinished(ItemStack.EMPTY, -1);
            }
        } else if (!current.isEmpty() && playlist$advanceDelay < 0 && playlist$elapsed >= 0) {
            long length = dev.eyecrasher.jukeboxplaylists.LegacyDiscLengths.ticks(current);
            // Count playback while the first background resource scan runs.
            if (playlist$elapsed++ >= length + 20 && length > 0) {
                playlist$markFinished(current, 0);
            }
            if (playlist$elapsed > 0 && playlist$elapsed % 200 == 0) jukebox.setChanged();
        }
        if (playlist$current().isEmpty()) {
            Playlists.advance(this, false);
        } else if (playlist$advanceDelay == 0 && playlist$hasCompletion(playlist$current())
                && Playlists.advance(this, true) && playlist$advanceDelay == 0) {
            playlist$markFinished(ItemStack.EMPTY, -1);
            if (!custom) playlist$elapsed = -1;
        }
    }

    @Override public void playlist$customFinished() {
        playlist$markFinished(playlist$current(), 20);
    }

    @Unique
    private boolean playlist$hasCompletion(ItemStack current) {
        return playlist$advanceDelay >= 0 && !current.isEmpty()
                && current == playlist$finishedItem && current.getCount() == playlist$finishedCount;
    }

    @Unique
    private void playlist$markFinished(ItemStack record, int delay) {
        boolean pending = playlist$advanceDelay >= 0;
        playlist$advanceDelay = delay;
        playlist$finishedItem = record;
        playlist$finishedCount = record.getCount();
        if (pending != (delay >= 0)) ((BlockEntity) (Object) this).setChanged();
    }

    @Inject(method = "setRecord", at = @At("TAIL"))
    private void playlist$recordChanged(ItemStack stack, CallbackInfo ci) {
        playlist$elapsed = 0;
        if (playlist$advanceDelay >= 0) playlist$markFinished(ItemStack.EMPTY, -1);
    }

    @Inject(method = "load", at = @At("TAIL"))
    private void playlist$load(net.minecraft.nbt.CompoundTag tag, CallbackInfo ci) {
        playlist$cursor = tag.contains("playlist_position") ? tag.getInt("playlist_position") : -1;
        ItemStack current = playlist$current();
        boolean pending = tag.getInt("jukebox_playlists:advance_pending") != 0 && !current.isEmpty();
        playlist$advanceDelay = pending ? (LazoDiscsCompat.isCustom(current) ? 20 : 0) : -1;
        playlist$finishedItem = pending ? current : ItemStack.EMPTY;
        playlist$finishedCount = pending ? current.getCount() : 0;
        playlist$elapsed = tag.contains("jukebox_playlists:elapsed") ? tag.getLong("jukebox_playlists:elapsed") : 0;
    }

    @Inject(method = "saveAdditional", at = @At("TAIL"))
    private void playlist$save(net.minecraft.nbt.CompoundTag tag, CallbackInfo ci) {
        if (playlist$cursor >= 0) tag.putInt("playlist_position", playlist$cursor);
        if (playlist$hasCompletion(playlist$current())) tag.putInt("jukebox_playlists:advance_pending", 1);
        tag.putLong("jukebox_playlists:elapsed", playlist$elapsed);
    }
}
