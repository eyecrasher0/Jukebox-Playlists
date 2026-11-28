# Changelog

## 1.1.0

- Added one plugin for Paper, Purpur, Spigot and Folia, with region scheduling on Folia.
- Added Forge builds for Minecraft 1.7.10, 1.8.9 and 1.12.2, including automatic timing for bundled mod sounds.
- Jukebox Playlists now runs on dedicated servers. Players can join without installing Jukebox Playlists.
- Playlist swaps preserve full item stacks. The Bukkit port saves queue positions and follows LazoDiscs playback sessions.
- Playlists respect chest locks and advance after unlocking. Bukkit playlists check the depositor's `lazodiscs.play` permission before starting a recording from the chest or repeating it.
- The Bukkit port recognizes playable items from the native jukebox song component. The older Forge ports replay a lone record without delaying the next loop.

## 1.0.1 — 2026-10-18

- Автоматическое определение длительности модовых пластинок в Minecraft 1.16.5–1.19 по звукам внутри мода.
- Удалена ручная настройка длительности треков.
- Учтены вложенные звуковые события и скорость воспроизведения.
- Каждая папка версии собирается самостоятельно.

## 1.0.0 — 2026-10-17

- Плейлисты из одиночного сундука над проигрывателем для Minecraft 1.16.5–26.3.
- 90 сборок для Fabric, Forge и NeoForge.
- Поддержка записей LazoDiscs с переключением по фактическому завершению аудио.
