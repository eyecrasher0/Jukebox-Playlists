package dev.eyecrasher.jukeboxplaylists;

import dev.eyecrasher.jukeboxplaylists.mixin.ContainerLockAccessor;
import net.minecraft.core.BlockPos;
import net.minecraft.world.Container;
import net.minecraft.world.LockCode;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.JukeboxBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

public final class Playlists {
    private Playlists() {}

    public static boolean advance(PlaylistJukebox jukebox, boolean repeat) {
        BlockEntity entity = (BlockEntity) jukebox;
        Level level = entity.getLevel();
        BlockPos position = entity.getBlockPos();
        // A removal can also stop a song. Never refill a destroyed jukebox.
        if (level == null || Platform.isClient(level) || entity.isRemoved()
                || level.getBlockEntity(position) != entity
                || !(level.getBlockState(position).getBlock() instanceof JukeboxBlock)) {
            return true;
        }
        BlockState above = level.getBlockState(position.above());
        if (!Platform.isPlaylistContainer(above)
                || (above.getBlock() instanceof ChestBlock
                    && above.getValue(ChestBlock.TYPE) != ChestType.SINGLE)) {
            return true;
        }
        BlockEntity containerEntity = level.getBlockEntity(position.above());
        if (containerEntity instanceof ContainerLockAccessor
                && !LockCode.NO_LOCK.equals(((ContainerLockAccessor) containerEntity).jukeboxPlaylists$getLock())) {
            return false;
        }
        if (!(containerEntity instanceof Container)) {
            return true;
        }
        Container chest = (Container) containerEntity;
        int size = chest.getContainerSize();
        ItemStack current = jukebox.playlist$current().copy();
        boolean canReturn = current.isEmpty()
                || current.getCount() <= Math.min(current.getMaxStackSize(), chest.getMaxStackSize(current));
        int cursor = jukebox.playlist$position();
        if (cursor < -1 || cursor >= size) {
            cursor = -1;
        }
        for (int offset = 1; canReturn && offset <= size; offset++) {
            int slot = (cursor + offset) % size;
            ItemStack candidate = chest.getItem(slot);
            if (!Platform.isDisc(candidate) || candidate.getCount() > candidate.getMaxStackSize()) continue;
            if (!tryPlay(jukebox, candidate, current)) continue;
            chest.setItem(slot, current);
            jukebox.playlist$position(slot);
            chest.setChanged();
            entity.setChanged();
            return true;
        }
        if (canReturn && jukebox.playlist$position() != -1) {
            jukebox.playlist$position(-1);
            entity.setChanged();
        }
        if (repeat && Platform.isDisc(current)) {
            tryPlay(jukebox, current, current);
        }
        return true;
    }

    private static boolean tryPlay(PlaylistJukebox jukebox, ItemStack record, ItemStack previous) {
        ItemStack installed = playStack(jukebox, record, false);
        if (ItemStack.matches(record, installed)) return true;
        if (!ItemStack.matches(previous, installed)) playStack(jukebox, previous, true);
        return false;
    }

    private static ItemStack playStack(PlaylistJukebox jukebox, ItemStack record, boolean restore) {
        jukebox.playlist$play(record.copy());
        ItemStack installed = jukebox.playlist$current();
        if (!installed.isEmpty() && installed.getCount() < record.getCount()
                && (restore || LazoDiscsCompat.isCustom(record))) {
            ItemStack partial = record.copy();
            partial.setCount(installed.getCount());
            if (ItemStack.matches(partial, installed)) installed.setCount(record.getCount());
        }
        return installed;
    }
}
