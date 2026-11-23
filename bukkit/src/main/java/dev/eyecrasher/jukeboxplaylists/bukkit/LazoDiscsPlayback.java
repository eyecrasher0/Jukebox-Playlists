package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Optional bridge to the Bukkit and native Paper LazoDiscs playback managers. */
final class LazoDiscsPlayback {
    private final Plugin plugin;
    private final Object manager;
    private final Method read;
    private final Method key;
    private final Method sync;
    private final Method retire;
    private final Field active;
    private final Field completed;
    private final Object voice;
    private final Method ready;
    private final Object sounds;
    private final Method expect;
    private final Method clear;

    LazoDiscsPlayback(Plugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        ClassLoader loader = plugin.getClass().getClassLoader();
        read = Class.forName("com.eyecrasher.lazodiscs.data.DiscDataUtil", false, loader)
                .getMethod("read", ItemStack.class);
        manager = field(plugin.getClass(), "playback").get(plugin);
        if (manager == null) throw new IllegalStateException("LazoDiscs playback is unavailable");
        Class<?> type = manager.getClass();
        Class<?> position = Class.forName(type.getName() + "$Key", false, loader);
        key = method(position, "of", Block.class);
        sync = method(type, "sync", Block.class, boolean.class);
        active = field(type, "active");
        completed = optionalField(type, "completed");
        retire = completed == null ? null : optionalMethod(type, "retireCompleted", position);
        voice = field(type, "voice").get(manager);
        ready = voice.getClass().getMethod("isReady");
        Field soundField = optionalField(type, "sounds");
        sounds = soundField == null ? null : soundField.get(manager);
        expect = sounds == null ? null : method(sounds.getClass(), "expect", Block.class);
        clear = expect == null ? null : method(sounds.getClass(), "clear", expect.getReturnType());
    }

    boolean custom(ItemStack item) {
        return plugin.isEnabled() && !JukeboxAccess.empty(item)
                && ((Optional<?>) ServerTasks.call(read, null, item)).isPresent();
    }

    boolean ready() { return plugin.isEnabled() && (Boolean) ServerTasks.call(ready, voice); }

    boolean playing(Block block) { return entries(active).containsKey(position(block)); }

    boolean ended(Block block) { return completed != null && entries(completed).containsKey(position(block)); }

    void changed(Block block, boolean replay) {
        if (!plugin.isEnabled()) return;
        if (replay && completed != null) {
            Object position = position(block);
            if (retire != null) ServerTasks.call(retire, manager, position);
            else entries(completed).remove(position);
        }
        ServerTasks.call(sync, manager, block, true);
    }

    Runnable suppressStart(Block block) {
        if (expect == null) return () -> {};
        Object marker = ServerTasks.call(expect, sounds, block);
        return marker == null ? () -> {} : () -> ServerTasks.call(clear, sounds, marker);
    }

    private Object position(Block block) { return ServerTasks.call(key, null, block); }

    private Map<?, ?> entries(Field field) {
        try {
            return (Map<?, ?>) field.get(manager);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException(field.getName(), failure);
        }
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        Field field = type.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    private static Field optionalField(Class<?> type, String name) {
        try {
            return field(type, name);
        } catch (NoSuchFieldException absent) {
            return null;
        }
    }

    private static Method method(Class<?> type, String name, Class<?>... parameters) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name, parameters);
        method.setAccessible(true);
        return method;
    }

    private static Method optionalMethod(Class<?> type, String name, Class<?>... parameters) {
        try {
            return method(type, name, parameters);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }
}
