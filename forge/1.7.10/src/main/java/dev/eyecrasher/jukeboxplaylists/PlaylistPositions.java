package dev.eyecrasher.jukeboxplaylists;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
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
        String key = key(box);
        return state != null && state.slots.hasKey(key) ? state.slots.getInteger(key) : -1;
    }

    static void store(WorldServer world, TileEntity box, int slot) {
        PlaylistPositions state = (PlaylistPositions) world.perWorldStorage.loadData(PlaylistPositions.class, NAME);
        if (state == null) {
            if (slot < 0) return;
            state = new PlaylistPositions(NAME);
            world.perWorldStorage.setData(NAME, state);
        }
        if (slot < 0) state.slots.removeTag(key(box));
        else state.slots.setInteger(key(box), slot);
        state.markDirty();
    }

    private static String key(TileEntity box) { return box.xCoord + "," + box.yCoord + "," + box.zCoord; }
}
