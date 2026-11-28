package dev.eyecrasher.jukeboxplaylists;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.world.ChunkEvent;

@Mod(modid = "jukebox_playlists", name = "Jukebox Playlists", version = "1.1.0",
        acceptedMinecraftVersions = "[1.7.10]", acceptableRemoteVersions = "*")
public final class JukeboxPlaylistsMod {
    private final Playlists playlists = new Playlists();

    @Mod.EventHandler
    public void initialize(FMLPreInitializationEvent event) {
        if (event.getSide().isServer()) {
            FMLCommonHandler.instance().bus().register(this);
            MinecraftForge.EVENT_BUS.register(this);
        }
    }

    @SubscribeEvent
    public void chunkLoaded(ChunkEvent.Load event) {
        playlists.load(event.getChunk());
    }

    @SubscribeEvent
    public void tick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.world instanceof WorldServer) {
            playlists.tick((WorldServer) event.world);
        }
    }
}
