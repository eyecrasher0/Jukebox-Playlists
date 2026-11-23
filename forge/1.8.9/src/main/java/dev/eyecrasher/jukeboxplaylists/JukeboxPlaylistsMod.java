package dev.eyecrasher.jukeboxplaylists;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraft.world.WorldServer;

@Mod(modid = "jukebox_playlists", name = "Jukebox Playlists", version = "1.1.0",
        acceptedMinecraftVersions = "[1.8.9]", acceptableRemoteVersions = "*", serverSideOnly = true)
public final class JukeboxPlaylistsMod {
    private final Playlists playlists = new Playlists();

    @Mod.EventHandler
    public void initialize(FMLPreInitializationEvent event) {
        if (event.getSide().isServer()) FMLCommonHandler.instance().bus().register(this);
    }

    @SubscribeEvent
    public void tick(TickEvent.WorldTickEvent event) {
        if (event.phase == TickEvent.Phase.END && event.world instanceof WorldServer) {
            playlists.tick((WorldServer) event.world);
        }
    }
}
