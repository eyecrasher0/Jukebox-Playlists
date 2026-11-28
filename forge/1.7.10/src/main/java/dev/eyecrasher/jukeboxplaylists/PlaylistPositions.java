package dev.eyecrasher.jukeboxplaylists;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraft.world.WorldSavedData;
import net.minecraft.world.WorldServer;

/** Forge 1.7.10 predates persistent custom data on tile entities. */
public final class PlaylistPositions extends WorldSavedData {
    private static final String NAME = "jukebox_playlists";
    private NBTTagCompound slots = new NBTTagCompound();

    public PlaylistPositions(String name) { super(name); }

    @Override public void readFromNBT(NBTTagCompound data) { slots = data.getCompoundTag("slots"); }
    @Override public void writeToNBT(NBTTagCompound data) { data.setTag("slots", slots); }

    static int get(WorldServer world, TileEntity box) {
        PlaylistPositions state = (PlaylistPositions) world.perWorldStorage.loadData(PlaylistPositions.class, NAME);
        String key = key(box.xCoord, box.yCoord, box.zCoord);
        return state != null && state.slots.hasKey(key) ? state.slots.getInteger(key) : -1;
    }

    static void store(WorldServer world, TileEntity box, int slot) {
        if (!box.isInvalid()) store(world, key(box.xCoord, box.yCoord, box.zCoord), slot);
    }

    public static void removed(World world, int x, int y, int z) {
        if (world instanceof WorldServer) store((WorldServer) world, key(x, y, z), -1);
    }

    private static void store(WorldServer world, String key, int slot) {
        PlaylistPositions state = (PlaylistPositions) world.perWorldStorage.loadData(PlaylistPositions.class, NAME);
        if (state == null) {
            if (slot == -1) return;
            state = new PlaylistPositions(NAME);
            world.perWorldStorage.setData(NAME, state);
        }
        if (slot == -1) {
            if (!state.slots.hasKey(key)) return;
            state.slots.removeTag(key);
        } else {
            state.slots.setInteger(key, slot);
        }
        state.markDirty();
    }

    private static String key(int x, int y, int z) { return x + "," + y + "," + z; }
}
