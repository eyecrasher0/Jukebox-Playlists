package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.plugin.Plugin;

/** Stores the cursor with its chunk. Bukkit 1.12.2 has no persistent data API. */
final class PlaylistPositions {
    private final Properties legacy = new Properties();
    private final Path legacyFile;
    private final Method container = ServerTasks.optional(Chunk.class, "getPersistentDataContainer");
    private final Constructor<?> key;
    private final Object integer;
    private final Method get;
    private final Method set;
    private final Method remove;
    private boolean dirty;

    PlaylistPositions(Plugin plugin) throws ReflectiveOperationException, IOException {
        legacyFile = plugin.getDataFolder().toPath().resolve("positions.properties");
        if (container == null) {
            key = null;
            integer = null;
            get = set = remove = null;
            if (Files.isRegularFile(legacyFile)) {
                try (InputStream input = Files.newInputStream(legacyFile)) { legacy.load(input); }
            }
            return;
        }
        Class<?> namespacedKey = Class.forName("org.bukkit.NamespacedKey");
        key = namespacedKey.getConstructor(String.class, String.class);
        Class<?> type = Class.forName("org.bukkit.persistence.PersistentDataType");
        integer = type.getField("INTEGER").get(null);
        get = container.getReturnType().getMethod("get", namespacedKey, type);
        set = container.getReturnType().getMethod("set", namespacedKey, type, Object.class);
        remove = container.getReturnType().getMethod("remove", namespacedKey);
    }

    int load(Chunk chunk, Block block) {
        if (container != null) {
            Object value = ServerTasks.call(get, ServerTasks.call(container, chunk), key(block), integer);
            return value instanceof Integer ? (Integer) value : -1;
        }
        try {
            return Integer.parseInt(legacy.getProperty(legacyKey(block), "-1"));
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    void store(Chunk chunk, Block block, int slot) {
        if (container != null) {
            Object data = ServerTasks.call(container, chunk);
            if (slot < 0) ServerTasks.call(remove, data, key(block));
            else ServerTasks.call(set, data, key(block), integer, slot);
        } else {
            if (slot < 0) legacy.remove(legacyKey(block));
            else legacy.setProperty(legacyKey(block), Integer.toString(slot));
            dirty = true;
        }
    }

    void saveLegacy() throws IOException {
        if (!dirty) return;
        Files.createDirectories(legacyFile.getParent());
        Path temporary = Files.createTempFile(legacyFile.getParent(), "positions", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) { legacy.store(output, null); }
            try {
                Files.move(temporary, legacyFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, legacyFile, StandardCopyOption.REPLACE_EXISTING);
            }
            dirty = false;
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Object key(Block block) {
        try {
            return key.newInstance("jukebox_playlists", "slot/" + (block.getX() & 15) + "/" + block.getY() + "/" + (block.getZ() & 15));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot create jukebox position key", failure);
        }
    }

    private static String legacyKey(Block block) {
        return block.getWorld().getUID() + "." + block.getX() + "." + block.getY() + "." + block.getZ();
    }
}
