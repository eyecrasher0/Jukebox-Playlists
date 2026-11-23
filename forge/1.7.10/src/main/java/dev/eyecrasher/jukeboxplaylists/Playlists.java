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

final class Playlists {
    private final Map<TileEntityJukebox, Playback> active = new WeakHashMap<>();
    private final List<TileEntityJukebox> ticking = new ArrayList<>();

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
                    active.remove(box);
                    continue;
                }
                active.computeIfAbsent(box, ignored -> new Playback(world, box)).tick(world, box, chest);
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
        private ItemStack previous;
        private long elapsed;
        private boolean resume;

        private int cursor;

        Playback(WorldServer world, TileEntityJukebox box) {
            cursor = empty(box.func_145856_a()) ? -1 : PlaylistPositions.get(world, box);
            resume = cursor >= 0;
        }

        void tick(WorldServer world, TileEntityJukebox box, IInventory chest) {
            ItemStack current = box.func_145856_a();
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
            if (current != null && current.stackSize > chest.getInventoryStackLimit()) return;
            int size = chest.getSizeInventory();
            if (cursor < -1 || cursor >= size) cursor = -1;
            for (int offset = 1; offset <= size; offset++) {
                int slot = (cursor + offset) % size;
                ItemStack next = chest.getStackInSlot(slot);
                if (empty(next) || !(next.getItem() instanceof ItemRecord)) continue;
                cursor = slot;
                PlaylistPositions.store(world, box, slot);
                chest.setInventorySlotContents(slot, current);
                play(world, box, next);
                chest.markDirty();
                return;
            }
            if (current != null) {
                play(world, box, current);
                return;
            }
            if (cursor >= 0) {
                cursor = -1;
                PlaylistPositions.store(world, box, -1);
            }
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
        ResourceLocation sound = record.getRecordResource("records." + record.recordName);
        return LegacyDiscLengths.ticks(sound == null ? null : sound.toString(), record.getUnlocalizedName());
    }

    private static void sound(WorldServer world, TileEntityJukebox box, ItemStack record) {
        world.playAuxSFX(1010, box.xCoord, box.yCoord, box.zCoord, Item.getIdFromItem(record.getItem()));
    }
}
