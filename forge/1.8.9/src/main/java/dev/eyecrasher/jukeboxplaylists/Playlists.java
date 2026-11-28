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
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityLockable;
import net.minecraft.util.BlockPos;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

final class Playlists {
    private static final String CURSOR = "jukebox_playlists:position";
    private static final String ADVANCE_PENDING = "jukebox_playlists:advance_pending";
    private final Map<TileEntityJukebox, Playback> active = new WeakHashMap<>();
    private final List<TileEntityJukebox> ticking = new ArrayList<>();

    void load(Chunk chunk) {
        for (TileEntity tile : chunk.getTileEntityMap().values()) {
            if (!(tile instanceof TileEntityJukebox)) continue;
            TileEntityJukebox box = (TileEntityJukebox) tile;
            if (!empty(box.getRecord()) || box.getTileData().hasKey(ADVANCE_PENDING)) {
                active.computeIfAbsent(box, key -> new Playback(key, true));
            }
        }
    }

    void save(Chunk chunk, NBTTagCompound data) {
        NBTTagList tiles = data.getCompoundTag("Level").getTagList("TileEntities", 10);
        for (int i = 0; i < tiles.tagCount(); i++) {
            NBTTagCompound tile = tiles.getCompoundTagAt(i);
            NBTTagCompound forgeData = tile.getCompoundTag("ForgeData");
            if (!forgeData.hasKey(ADVANCE_PENDING)) continue;
            BlockPos pos = new BlockPos(tile.getInteger("x"), tile.getInteger("y"), tile.getInteger("z"));
            TileEntity live = chunk.getTileEntityMap().get(pos);
            Playback playback = active.get(live);
            NBTTagCompound saved = (NBTTagCompound) forgeData.copy();
            if (playback == null || !playback.pendingFor((TileEntityJukebox) live)) {
                saved.removeTag(ADVANCE_PENDING);
            }
            tile.setTag("ForgeData", saved);
        }
    }

    void tick(WorldServer world) {
        // Changes to a jukebox can notify neighbors, which may change the world's tile list.
        for (TileEntity tile : world.loadedTileEntityList) {
            if (tile instanceof TileEntityJukebox) ticking.add((TileEntityJukebox) tile);
        }
        try {
            for (TileEntityJukebox box : ticking) {
                if (box.isInvalid() || !world.isBlockLoaded(box.getPos())) continue;
                IBlockState state = world.getBlockState(box.getPos());
                if (state.getBlock() != Blocks.jukebox) continue;
                IInventory chest = chest(world, box.getPos().up());
                if (chest == null && !active.containsKey(box) && !box.getTileData().hasKey(ADVANCE_PENDING)) continue;
                boolean canAdvance = chest != null
                        && (!(chest instanceof TileEntityLockable) || !((TileEntityLockable) chest).isLocked());
                active.computeIfAbsent(box, key -> new Playback(key, false)).tick(world, box, chest, canAdvance);
            }
        } finally {
            ticking.clear();
        }
    }

    private static IInventory chest(WorldServer world, BlockPos pos) {
        if (pos.getY() >= world.getHeight()) return null;
        Block block = world.getBlockState(pos).getBlock();
        if (block != Blocks.chest && block != Blocks.trapped_chest) return null;
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

        Playback(TileEntityJukebox box, boolean loaded) {
            previous = box.getRecord();
            pending = box.getTileData().hasKey(ADVANCE_PENDING);
            if (pending && (!loaded || !matchesPending(box, previous))) setPending(box, null);
            resume = loaded && !pending && !empty(previous);
        }

        void tick(WorldServer world, TileEntityJukebox box, IInventory chest, boolean canAdvance) {
            ItemStack current = box.getRecord();
            if (current != previous || pending && !matchesPending(box, current)) {
                previous = current;
                elapsed = 0;
                resume = false;
                setPending(box, null);
            }
            if (empty(current)) {
                setPending(box, null);
                if (canAdvance) advance(world, box, chest, null);
            } else if (current.getItem() instanceof ItemRecord) {
                if (resume) {
                    if (chest == null) return;
                    sound(world, box, current);
                }
                long length = length((ItemRecord) current.getItem());
                if (pending || (++elapsed >= length + 20 && length > 0)) {
                    setPending(box, canAdvance ? null : current);
                    if (canAdvance) advance(world, box, chest, current);
                }
            } else {
                setPending(box, null);
            }
            resume = false;
        }

        private boolean pendingFor(TileEntityJukebox box) {
            ItemStack current = box.getRecord();
            return pending && previous == current && matchesPending(box, current);
        }

        private static boolean matchesPending(TileEntityJukebox box, ItemStack record) {
            return !empty(record) && record.writeToNBT(new NBTTagCompound()).equals(box.getTileData().getTag(ADVANCE_PENDING));
        }

        private void setPending(TileEntityJukebox box, ItemStack record) {
            if (pending == (record != null)) return;
            pending = record != null;
            if (pending) box.getTileData().setTag(ADVANCE_PENDING, record.writeToNBT(new NBTTagCompound()).copy());
            else box.getTileData().removeTag(ADVANCE_PENDING);
            box.markDirty();
        }

        private void advance(WorldServer world, TileEntityJukebox box, IInventory chest, ItemStack current) {
            boolean canReturn = current == null
                    || current.stackSize <= Math.min(current.getMaxStackSize(), chest.getInventoryStackLimit());
            int size = chest.getSizeInventory();
            int cursor = box.getTileData().hasKey(CURSOR) ? box.getTileData().getInteger(CURSOR) : -1;
            if (cursor < -1 || cursor >= size) cursor = -1;
            for (int offset = 1; canReturn && offset <= size; offset++) {
                int slot = (cursor + offset) % size;
                ItemStack next = chest.getStackInSlot(slot);
                if (empty(next) || !(next.getItem() instanceof ItemRecord)
                        || next.stackSize > next.getMaxStackSize()) continue;
                play(world, box, next.copy());
                chest.setInventorySlotContents(slot, current);
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

    private static boolean empty(ItemStack item) { return item == null || item.stackSize <= 0; }

    private static long length(ItemRecord record) {
        String defaultSound = "records." + record.recordName;
        ResourceLocation sound = record.getRecordResource(defaultSound);
        if (sound == null) sound = new ResourceLocation(defaultSound);
        return LegacyDiscLengths.ticks(sound.toString(), record.getUnlocalizedName());
    }

    private static void sound(WorldServer world, TileEntityJukebox box, ItemStack record) {
        world.playAuxSFX(1005, box.getPos(), Item.getIdFromItem(record.getItem()));
    }
}
