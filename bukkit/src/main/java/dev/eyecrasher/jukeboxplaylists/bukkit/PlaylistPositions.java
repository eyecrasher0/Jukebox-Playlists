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
import java.util.Objects;
import java.util.Properties;
import org.bukkit.Chunk;
import org.bukkit.block.Block;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Stores the cursor with its chunk. Bukkit 1.12.2 has no persistent data API. */
final class PlaylistPositions {
    private static final String SLOT = "slot";
    private static final String PENDING = "pending";
    private final Properties legacy = new Properties();
    private final Path legacyFile;
    private final Method container = ServerTasks.optional(Chunk.class, "getPersistentDataContainer");
    private final Constructor<?> key;
    private final Object integer;
    private final Object string;
    private final Method has;
    private final Method get;
    private final Method set;
    private final Method remove;
    private boolean dirty;

    PlaylistPositions(Plugin plugin) throws ReflectiveOperationException, IOException {
        legacyFile = plugin.getDataFolder().toPath().resolve("positions.properties");
        if (container == null) {
            key = null;
            integer = string = null;
            has = get = set = remove = null;
            if (Files.isRegularFile(legacyFile)) {
                try (InputStream input = Files.newInputStream(legacyFile)) { legacy.load(input); }
            }
            return;
        }
        Class<?> namespacedKey = Class.forName("org.bukkit.NamespacedKey");
        key = namespacedKey.getConstructor(String.class, String.class);
        Class<?> type = Class.forName("org.bukkit.persistence.PersistentDataType");
        integer = type.getField("INTEGER").get(null);
        string = type.getField("STRING").get(null);
        has = container.getReturnType().getMethod("has", namespacedKey, type);
        get = container.getReturnType().getMethod("get", namespacedKey, type);
        set = container.getReturnType().getMethod("set", namespacedKey, type, Object.class);
        remove = container.getReturnType().getMethod("remove", namespacedKey);
    }

    int load(Chunk chunk, Block block) {
        if (container != null) {
            Object value = ServerTasks.call(get, ServerTasks.call(container, chunk), key(block, SLOT), integer);
            return value instanceof Integer ? (Integer) value : -1;
        }
        try {
            return Integer.parseInt(legacy.getProperty(legacyKey(block, SLOT), "-1"));
        } catch (NumberFormatException invalid) {
            return -1;
        }
    }

    boolean restorePending(Chunk chunk, Block block, ItemStack current, boolean recording) {
        String saved;
        if (container == null) {
            saved = legacy.getProperty(legacyKey(block, PENDING));
        } else {
            Object data = ServerTasks.call(container, chunk);
            Object positionKey = key(block, PENDING);
            if ((Boolean) ServerTasks.call(has, data, positionKey, string)) {
                saved = (String) ServerTasks.call(get, data, positionKey, string);
            } else {
                saved = (Boolean) ServerTasks.call(has, data, positionKey, integer)
                        && Integer.valueOf(1).equals(ServerTasks.call(get, data, positionKey, integer)) ? "1" : null;
            }
        }
        if (saved == null) return false;
        if (current != null && ("1".equals(saved) ? !recording : matchesRecord(saved, current))) return true;
        pending(chunk, block, null);
        return false;
    }

    private static boolean matchesRecord(String saved, ItemStack current) {
        YamlConfiguration data = new YamlConfiguration();
        try {
            data.loadFromString(saved);
            return current.equals(data.getItemStack("record"));
        } catch (InvalidConfigurationException | IllegalArgumentException invalid) {
            return false;
        }
    }

    void store(Chunk chunk, Block block, int slot) {
        if (container != null) {
            Object data = ServerTasks.call(container, chunk);
            Object positionKey = key(block, SLOT);
            if (slot == -1) ServerTasks.call(remove, data, positionKey);
            else ServerTasks.call(set, data, positionKey, integer, slot);
        } else {
            storeLegacy(block, SLOT, slot == -1 ? null : Integer.toString(slot));
        }
    }

    void pending(Chunk chunk, Block block, ItemStack record) {
        String encoded = null;
        if (record != null) {
            YamlConfiguration data = new YamlConfiguration();
            data.set("record", record);
            encoded = data.saveToString();
        }
        if (container == null) {
            storeLegacy(block, PENDING, encoded);
        } else {
            Object data = ServerTasks.call(container, chunk);
            Object positionKey = key(block, PENDING);
            if (encoded == null) ServerTasks.call(remove, data, positionKey);
            else ServerTasks.call(set, data, positionKey, string, encoded);
        }
    }

    void clear(Chunk chunk, Block block) {
        store(chunk, block, -1);
        pending(chunk, block, null);
    }

    private void storeLegacy(Block block, String property, String encoded) {
        String positionKey = legacyKey(block, property);
        Object previous = encoded == null ? legacy.remove(positionKey) : legacy.setProperty(positionKey, encoded);
        if (!Objects.equals(previous, encoded)) dirty = true;
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

    private Object key(Block block, String property) {
        try {
            return key.newInstance("jukebox_playlists", property + "/" + (block.getX() & 15) + "/" + block.getY() + "/" + (block.getZ() & 15));
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot create jukebox position key", failure);
        }
    }

    private static String legacyKey(Block block, String property) {
        String location = block.getWorld().getUID() + "." + block.getX() + "." + block.getY() + "." + block.getZ();
        return SLOT.equals(property) ? location : location + "." + property;
    }
}
