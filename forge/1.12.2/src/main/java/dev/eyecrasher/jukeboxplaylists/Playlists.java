package dev.eyecrasher.jukeboxplaylists;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockJukebox;
import net.minecraft.block.BlockJukebox.TileEntityJukebox;
import net.minecraft.block.state.IBlockState;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemRecord;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.SoundEvent;
import java.lang.reflect.Field;
import net.minecraft.world.WorldServer;

final class Playlists {
    private static final String CURSOR = "jukebox_playlists:position";
    private final Map<TileEntityJukebox, Playback> active = new WeakHashMap<>();
    private final List<TileEntityJukebox> ticking = new ArrayList<>();

    void tick(WorldServer world) {
        // Changes to a jukebox can notify neighbors, which may change the world's tile list.
        for (TileEntity tile : world.loadedTileEntityList) {
            if (tile instanceof TileEntityJukebox) ticking.add((TileEntityJukebox) tile);
        }
        try {
            for (TileEntityJukebox box : ticking) {
                if (box.isInvalid() || !world.isBlockLoaded(box.getPos())) continue;
                IBlockState state = world.getBlockState(box.getPos());
                if (state.getBlock() != Blocks.JUKEBOX) continue;
                IInventory chest = chest(world, box.getPos().up());
                if (chest == null) {
                    active.remove(box);
                    continue;
                }
                active.computeIfAbsent(box, ignored -> new Playback(box)).tick(world, box, chest);
            }
        } finally {
            ticking.clear();
        }
    }

    private static IInventory chest(WorldServer world, BlockPos pos) {
        if (pos.getY() >= world.getHeight()) return null;
        Block block = world.getBlockState(pos).getBlock();
        if (block != Blocks.CHEST && block != Blocks.TRAPPED_CHEST) return null;
        for (EnumFacing face : EnumFacing.Plane.HORIZONTAL) {
            BlockPos adjacent = pos.offset(face);
            if (!world.isBlockLoaded(adjacent) || world.getBlockState(adjacent).getBlock() == block) return null;
        }
        TileEntity tile = world.getTileEntity(pos);
        return tile instanceof IInventory ? (IInventory) tile : null;
    }

    private static final class Playback {
        private ItemStack previous;
        private long elapsed;
        private boolean resume;

        Playback(TileEntityJukebox box) {
            resume = box.getTileData().hasKey(CURSOR);
        }

        void tick(WorldServer world, TileEntityJukebox box, IInventory chest) {
            ItemStack current = box.getRecord();
            if (!empty(current) && current != previous) {
                previous = current;
                elapsed = 0;
                if (resume && current.getItem() instanceof ItemRecord) sound(world, box, current);
            }
            resume = false;
            if (empty(current)) {
                advance(world, box, chest, null);
            } else if (current.getItem() instanceof ItemRecord) {
                long length = length((ItemRecord) current.getItem());
                if (++elapsed >= length + 20 && length > 0) advance(world, box, chest, current);
            }
        }

        private void advance(WorldServer world, TileEntityJukebox box, IInventory chest, ItemStack current) {
            if (current != null && current.getCount() != 1) return;
            int size = chest.getSizeInventory();
            int cursor = box.getTileData().hasKey(CURSOR) ? box.getTileData().getInteger(CURSOR) : -1;
            if (cursor < -1 || cursor >= size) cursor = -1;
            for (int offset = 1; offset <= size; offset++) {
                int slot = (cursor + offset) % size;
                ItemStack next = chest.getStackInSlot(slot);
                if (empty(next) || next.getCount() != 1 || !(next.getItem() instanceof ItemRecord)) continue;
                box.getTileData().setInteger(CURSOR, slot);
                chest.setInventorySlotContents(slot, current == null ? ItemStack.EMPTY : current);
                play(world, box, next);
                chest.markDirty();
                return;
            }
            if (cursor >= 0) {
                box.getTileData().removeTag(CURSOR);
                box.markDirty();
            }
            if (current != null) play(world, box, current);
        }

        private void play(WorldServer world, TileEntityJukebox box, ItemStack record) {
            BlockPos pos = box.getPos();
            IBlockState state = world.getBlockState(pos);
            box.setRecord(record);
            world.setBlockState(pos, state.withProperty(BlockJukebox.HAS_RECORD, true), 3);
            world.updateComparatorOutputLevel(pos, state.getBlock());
            sound(world, box, record);
            previous = box.getRecord();
            elapsed = 0;
            box.markDirty();
        }
    }

    private static boolean empty(ItemStack item) { return item == null || item.isEmpty(); }

    private static final Field RECORD_SOUND = recordSound();

    private static Field recordSound() {
        // The getter is stripped on a dedicated server. The stored sound is
        // still present because the ItemRecord constructor initializes it.
        for (Field field : ItemRecord.class.getDeclaredFields()) {
            if (field.getType() == SoundEvent.class) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static long length(ItemRecord record) {
        if (RECORD_SOUND == null) return -1;
        try {
            SoundEvent sound = (SoundEvent) RECORD_SOUND.get(record);
            return LegacyDiscLengths.ticks(sound == null || sound.getRegistryName() == null
                    ? null : sound.getRegistryName().toString(), record.getUnlocalizedName());
        } catch (IllegalAccessException failure) {
            return -1;
        }
    }

    private static void sound(WorldServer world, TileEntityJukebox box, ItemStack record) {
        world.playEvent(1010, box.getPos(), Item.getIdFromItem(record.getItem()));
    }
}
