package dev.eyecrasher.jukeboxplaylists;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.block.Block;
import net.minecraft.block.BlockJukebox.TileEntityJukebox;
import net.minecraft.init.Blocks;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemRecord;
import net.minecraft.item.ItemStack;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

final class Playlists {
    private final Map<TileEntityJukebox, Playback> active = new WeakHashMap<>();
    private final List<TileEntityJukebox> ticking = new ArrayList<>();

    void load(Chunk chunk) {
        if (!(chunk.worldObj instanceof WorldServer)) return;
        WorldServer world = (WorldServer) chunk.worldObj;
        for (Object tile : chunk.chunkTileEntityMap.values()) {
            if (!(tile instanceof TileEntityJukebox)) continue;
            TileEntityJukebox box = (TileEntityJukebox) tile;
            if (!empty(box.func_145856_a())) active.computeIfAbsent(box, key -> new Playback(world, key, true));
        }
    }

    void tick(WorldServer world) {
        // Changes to a jukebox can notify neighbors, which may change the world's tile list.
        for (Object tile : world.loadedTileEntityList) {
            if (tile instanceof TileEntityJukebox) ticking.add((TileEntityJukebox) tile);
        }
        try {
            for (TileEntityJukebox box : ticking) {
                if (box.isInvalid() || !world.blockExists(box.xCoord, box.yCoord, box.zCoord)) continue;
                if (world.getBlock(box.xCoord, box.yCoord, box.zCoord) != Blocks.jukebox) continue;
                IInventory chest = chest(world, box.xCoord, box.yCoord + 1, box.zCoord);
                if (chest == null) {
                    Playback playback = active.get(box);
                    if (playback != null && !playback.resume) active.remove(box);
                    continue;
                }
                active.computeIfAbsent(box, key -> new Playback(world, key, false)).tick(world, box, chest);
            }
        } finally {
            ticking.clear();
        }
    }

    private static final int[][] SIDES = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

    private static IInventory chest(WorldServer world, int x, int y, int z) {
        if (y >= world.getHeight()) return null;
        Block block = world.getBlock(x, y, z);
        if (block != Blocks.chest && block != Blocks.trapped_chest) return null;
        for (int[] side : SIDES) {
            int adjacentX = x + side[0], adjacentZ = z + side[1];
            if (!world.blockExists(adjacentX, y, adjacentZ) || world.getBlock(adjacentX, y, adjacentZ) == block) return null;
        }
        TileEntity tile = world.getTileEntity(x, y, z);
        return tile instanceof IInventory ? (IInventory) tile : null;
    }

    private static final class Playback {
        private static final int REPEATING = -2;
        private ItemStack previous;
        private long elapsed;
        private boolean resume;

        private int position;

        Playback(WorldServer world, TileEntityJukebox box, boolean loaded) {
            position = PlaylistPositions.get(world, box);
            previous = box.func_145856_a();
            resume = loaded && !empty(previous);
        }

        void tick(WorldServer world, TileEntityJukebox box, IInventory chest) {
            ItemStack current = box.func_145856_a();
            if (current != previous) {
                previous = current;
                elapsed = 0;
                resume = false;
            }
            if (empty(current)) {
                advance(world, box, chest, null);
            } else if (current.getItem() instanceof ItemRecord) {
                if (resume) sound(world, box, current);
                long length = length((ItemRecord) current.getItem());
                if (++elapsed >= length + 20 && length > 0) advance(world, box, chest, current);
            }
            resume = false;
        }

        private void advance(WorldServer world, TileEntityJukebox box, IInventory chest, ItemStack current) {
            boolean canReturn = current == null
                    || current.stackSize <= Math.min(current.getMaxStackSize(), chest.getInventoryStackLimit());
            int size = chest.getSizeInventory();
            int cursor = position < -1 || position >= size ? -1 : position;
            for (int offset = 1; canReturn && offset <= size; offset++) {
                int slot = (cursor + offset) % size;
                ItemStack next = chest.getStackInSlot(slot);
                if (empty(next) || !(next.getItem() instanceof ItemRecord)
                        || next.stackSize > next.getMaxStackSize()) continue;
                play(world, box, next.copy());
                chest.setInventorySlotContents(slot, current);
                setPosition(world, box, slot);
                chest.markDirty();
                return;
            }
            if (canReturn) setPosition(world, box, current == null ? -1 : REPEATING);
            if (current != null) play(world, box, current);
        }

        private void setPosition(WorldServer world, TileEntityJukebox box, int slot) {
            if (position == slot) return;
            position = slot;
            PlaylistPositions.store(world, box, slot);
        }

        private void play(WorldServer world, TileEntityJukebox box, ItemStack record) {
            box.func_145857_a(record);
            world.setBlockMetadataWithNotify(box.xCoord, box.yCoord, box.zCoord, 1, 3);
            world.func_147453_f(box.xCoord, box.yCoord, box.zCoord, Blocks.jukebox);
            sound(world, box, record);
            previous = box.func_145856_a();
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
        world.playAuxSFX(1005, box.xCoord, box.yCoord, box.zCoord, Item.getIdFromItem(record.getItem()));
    }
}
