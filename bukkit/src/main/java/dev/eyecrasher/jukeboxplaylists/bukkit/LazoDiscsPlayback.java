package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

/** Optional bridge to the Bukkit and native Paper LazoDiscs playback managers. */
final class LazoDiscsPlayback {
    static final String PLAY_PERMISSION = "lazodiscs.play";
    private final Plugin plugin;
    private final Object manager;
    private final Method read;
    private final Method key;
    private final Method sync;
    private final Method retire;
    private final Map<?, ?> active;
    private final Map<?, ?> completed;
    private final Field entryDisc;
    private final Field entrySession;
    private final Field stream;
    private final Field closed;
    private final Field failure;
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
        active = (Map<?, ?>) field(type, "active").get(manager);
        Field completedField = optionalField(type, "completed");
        completed = completedField == null ? null : (Map<?, ?>) completedField.get(manager);
        Class<?> entry = Class.forName(type.getName() + "$Entry", false, loader);
        entryDisc = field(entry, "disc");
        entrySession = field(entry, "session");
        Class<?> sessionType = entrySession.getType();
        stream = optionalField(sessionType, "stream");
        Class<?> state = stream == null ? sessionType : stream.getType();
        closed = field(state, "closed");
        failure = field(state, stream == null ? "failure" : "sinkStopped");
        retire = optionalMethod(type, "retireCompleted", position);
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

    Object session(Block block, ItemStack record) {
        Object entry = active.get(position(block));
        if (entry == null) return null;
        Optional<?> disc = (Optional<?>) ServerTasks.call(read, null, record);
        return disc.isPresent() && disc.get().equals(value(entryDisc, entry)) ? value(entrySession, entry) : null;
    }

    boolean finished(Block block, Object session) {
        if (session == null) return false;
        if (completed != null) {
            Object entry = completed.get(position(block));
            if (entry == null || value(entrySession, entry) != session) return false;
        }
        Object state = stream == null ? session : value(stream, session);
        if (!((AtomicBoolean) value(closed, state)).get()) return false;
        Object outcome = value(failure, state);
        return stream == null ? ((AtomicReference<?>) outcome).get() == null : !((AtomicBoolean) outcome).get();
    }

    void changed(Block block, boolean replay) {
        if (!plugin.isEnabled()) return;
        if (replay && retire != null) ServerTasks.call(retire, manager, position(block));
        ServerTasks.call(sync, manager, block, true);
    }

    Runnable suppressStart(Block block) {
        if (expect == null) return null;
        Object marker = ServerTasks.call(expect, sounds, block);
        return marker == null ? null : () -> ServerTasks.call(clear, sounds, marker);
    }

    private Object position(Block block) { return ServerTasks.call(key, null, block); }

    private static Object value(Field field, Object target) {
        try {
            return field.get(target);
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
