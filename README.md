# Jukebox Playlists

Brings Minecraft 26.4 jukebox playlists to **dedicated Java Edition servers**.

[Download](https://github.com/eyecrasher0/Jukebox-Playlists/releases) · [Changelog](CHANGELOG.md)

| Server | Minecraft | Installation |
|---|---|---|
| Fabric | 1.16.5–26.3 | Matching JAR and Fabric API in `mods/` |
| Forge | 1.7.10, 1.8.9, 1.12.2, and 1.16.5–26.3 where Forge builds exist | Matching JAR in `mods/` |
| NeoForge | 1.20.1–26.3 | Matching JAR in `mods/` |
| Paper, Purpur, Spigot, Folia | 1.7.10 (Paper), 1.12.2, and 1.16.5–26.3 where official builds exist | The same Bukkit JAR in `plugins/` |

Install Jukebox Playlists on the server. Clients use the server's usual game and mod setup.
Place a single chest directly above a jukebox and add music discs. Each completed track returns to the chest, and the jukebox takes the next disc in slot order. The queue loops automatically.
Vanilla and modded discs need no duration configuration. LazoDiscs recordings use their actual playback completion on supported LazoDiscs and Plasmo Voice setups. Forge 1.20.2 with LazoDiscs 1.0.7 uses the Plasmo Voice fix from the [1.0.1 release](https://github.com/eyecrasher0/Jukebox-Playlists/releases/tag/v1.0.1).
Use an unlocked chest for the playlist. On Bukkit, starting a LazoDiscs recording from the chest or repeating it requires its depositor to be online with `lazodiscs.play`. On Folia, that player must be in the jukebox's region when playback starts. LazoDiscs handles resuming recordings already in the jukebox after a chunk reload.

Run `./gradlew build` in `bukkit/` or in the relevant loader and version directory. Launch the Bukkit wrapper with JDK 17 or newer. The plugin targets Java 8.

Launch Fabric 1.16.5–1.20.4 with JDK 21 and provide JDK 17 for its compiler toolchain. Use JDK 8 for Forge 1.7.10, 1.8.9 and 1.12.2. For other loader builds, use the JDK specified by the toolchain in `build.gradle`.

[GPL-3.0-only](LICENSE)
