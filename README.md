# Jukebox Playlists

Turn a chest above a jukebox into a looping music disc playlist in Minecraft.

[Download](https://github.com/eyecrasher0/Jukebox-Playlists/releases) · [Changelog](CHANGELOG.md)

Choose a JAR for your exact Minecraft version and loader. Install it in `mods/` and follow the installation notes for that build. Fabric also needs Fabric API.
Place a single chest directly above a Jukebox and fill it with Music Discs — the playlist will play and loop automatically.
Vanilla and modded Music Discs work without manually configuring their durations. LazoDiscs recordings require both LazoDiscs and Plasmo Voice.
The matching release notes list the compatible LazoDiscs and Plasmo Voice builds and any loader-specific requirements, including the Voice metadata fix for affected Forge builds.

Each version folder is a separate project: `cd fabric/1.16.5`, then run `./gradlew build` (`gradlew.bat build` on Windows).
Building Forge and Minecraft 26.x projects requires JDK 25; all other projects require JDK 21. You must also install the compiler JDK specified in `build.gradle`.

License: [GPL-3.0-only](LICENSE).
