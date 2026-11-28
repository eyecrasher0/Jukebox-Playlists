package dev.eyecrasher.jukeboxplaylists;

import net.minecraftforge.fml.IExtensionPoint;
import net.minecraftforge.fml.ModLoadingContext;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fmllegacy.network.FMLNetworkConstants;

@Mod("jukebox_playlists")
public final class JukeboxPlaylistsMod {
    public JukeboxPlaylistsMod() {
        ModLoadingContext.get().registerExtensionPoint(IExtensionPoint.DisplayTest.class,
                () -> new IExtensionPoint.DisplayTest(() -> FMLNetworkConstants.IGNORESERVERONLY,
                        (remoteVersion, fromServer) -> true));
    }
}
