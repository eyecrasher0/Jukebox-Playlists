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
import net.minecraft.tileentity.TileEntityLockable;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundEvent;
import java.lang.reflect.Field;
import net.minecraft.world.WorldServer;

final class Playlists {
    private static final String CURSOR = "jukebox_playlists:position";
    private static final String ADVANCE_PENDING = "jukebox_playlists:advance_pending";
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
                if (chest == null && !active.containsKey(box) && !box.getTileData().hasKey(ADVANCE_PENDING)) continue;
                boolean canAdvance = chest != null
                        && (!(chest instanceof TileEntityLockable) || !((TileEntityLockable) chest).isLocked());
                active.computeIfAbsent(box, ignored -> new Playback(box)).tick(world, box, chest, canAdvance);
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
        private boolean pending;

        Playback(TileEntityJukebox box) {
            previous = box.getRecord();
            pending = box.getTileData().getBoolean(ADVANCE_PENDING);
            resume = !pending && box.getTileData().hasKey(CURSOR);
        }

        void tick(WorldServer world, TileEntityJukebox box, IInventory chest, boolean canAdvance) {
            ItemStack current = box.getRecord();
            if (current != previous) {
                previous = current;
                elapsed = 0;
                resume = false;
                setPending(box, false);
            }
            if (empty(current)) {
                setPending(box, false);
                if (canAdvance) advance(world, box, chest, null);
            } else if (current.getItem() instanceof ItemRecord) {
                if (resume) sound(world, box, current);
                long length = length((ItemRecord) current.getItem());
                if (pending || (++elapsed >= length + 20 && length > 0)) {
                    setPending(box, !canAdvance);
                    if (canAdvance) advance(world, box, chest, current);
                }
            } else {
                setPending(box, false);
            }
            resume = false;
        }

        private void setPending(TileEntityJukebox box, boolean value) {
            if (pending == value) return;
            pending = value;
            if (value) box.getTileData().setBoolean(ADVANCE_PENDING, true);
            else box.getTileData().removeTag(ADVANCE_PENDING);
            box.markDirty();
        }

        private void advance(WorldServer world, TileEntityJukebox box, IInventory chest, ItemStack current) {
            boolean canReturn = current == null
                    || current.getCount() <= Math.min(current.getMaxStackSize(), chest.getInventoryStackLimit());
            int size = chest.getSizeInventory();
            int cursor = box.getTileData().hasKey(CURSOR) ? box.getTileData().getInteger(CURSOR) : -1;
            if (cursor < -1 || cursor >= size) cursor = -1;
            for (int offset = 1; canReturn && offset <= size; offset++) {
                int slot = (cursor + offset) % size;
                ItemStack next = chest.getStackInSlot(slot);
                if (empty(next) || !(next.getItem() instanceof ItemRecord)
                        || next.getCount() > next.getMaxStackSize()) continue;
                play(world, box, next.copy());
                chest.setInventorySlotContents(slot, current == null ? ItemStack.EMPTY : current);
                box.getTileData().setInteger(CURSOR, slot);
                box.markDirty();
                chest.markDirty();
                return;
            }
            if (current != null) {
                if (canReturn) box.getTileData().setInteger(CURSOR, -1);
                play(world, box, current);
            } else if (box.getTileData().hasKey(CURSOR)) {
                box.getTileData().removeTag(CURSOR);
                box.markDirty();
            }
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

    private static final Field RECORD_SOUND = soundField(ItemRecord.class, SoundEvent.class);
    private static final Field SOUND_NAME = soundField(SoundEvent.class, ResourceLocation.class);

    private static Field soundField(Class<?> owner, Class<?> type) {
        // The accessors are stripped on a dedicated server, but the values are saved by the constructors.
        for (Field field : owner.getDeclaredFields()) {
            if (field.getType() == type) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static long length(ItemRecord record) {
        if (RECORD_SOUND == null || SOUND_NAME == null) return -1;
        try {
            Object sound = RECORD_SOUND.get(record);
            ResourceLocation name = sound == null ? null : (ResourceLocation) SOUND_NAME.get(sound);
            return LegacyDiscLengths.ticks(name == null ? null : name.toString(), record.getUnlocalizedName());
        } catch (IllegalAccessException failure) {
            return -1;
        }
    }

    private static void sound(WorldServer world, TileEntityJukebox box, ItemStack record) {
        world.playEvent(1010, box.getPos(), Item.getIdFromItem(record.getItem()));
    }
}
