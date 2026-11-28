package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
    private final Method legacyDiscId;
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
        Type marker = completedField == null ? null
                : ((ParameterizedType) completedField.getGenericType()).getActualTypeArguments()[1];
        if (marker != null && marker != UUID.class && marker != entry) {
            throw new IllegalStateException("Unsupported LazoDiscs completion marker");
        }
        legacyDiscId = marker == UUID.class ? entryDisc.getType().getMethod("id") : null;
        retire = marker == entry ? method(type, "retireCompleted", position) : null;
        Class<?> sessionType = entrySession.getType();
        stream = optionalField(sessionType, "stream");
        Class<?> state = stream == null ? sessionType : stream.getType();
        closed = field(state, "closed");
        failure = field(state, stream == null ? "failure" : "sinkStopped");
        voice = field(type, "voice").get(manager);
        ready = voice.getClass().getMethod("isReady");
        Field soundField = optionalField(type, "sounds");
        sounds = soundField == null ? null : soundField.get(manager);
        expect = sounds == null ? null : method(sounds.getClass(), "expect", Block.class);
        clear = expect == null ? null : method(sounds.getClass(), "clear", expect.getReturnType());
    }

    boolean canStart(ItemStack item) {
        return ready() && disc(item).isPresent();
    }

    boolean ready() { return plugin.isEnabled() && (Boolean) ServerTasks.call(ready, voice); }

    Object activeEntry(Block block, ItemStack record) {
        Object entry = active.get(position(block));
        if (entry == null) return null;
        return disc(record).filter(value(entryDisc, entry)::equals).isPresent() ? entry : null;
    }

    boolean finished(Block block, Object entry) {
        if (entry == null) return false;
        if (completed != null) {
            Object marker = completed.get(position(block));
            if (legacyDiscId == null ? marker != entry
                    : !ServerTasks.call(legacyDiscId, value(entryDisc, entry)).equals(marker)) return false;
        }
        Object session = value(entrySession, entry);
        Object state = stream == null ? session : value(stream, session);
        if (!((AtomicBoolean) value(closed, state)).get()) return false;
        Object outcome = value(failure, state);
        return stream == null ? ((AtomicReference<?>) outcome).get() == null : !((AtomicBoolean) outcome).get();
    }

    void changed(Block block, ItemStack recording) {
        if (!plugin.isEnabled()) return;
        if (recording != null) {
            if (retire != null) ServerTasks.call(retire, manager, position(block));
            else if (legacyDiscId != null) {
                disc(recording).ifPresent(disc -> completed.remove(position(block), ServerTasks.call(legacyDiscId, disc)));
            }
        }
        ServerTasks.call(sync, manager, block, true);
    }

    private Optional<?> disc(ItemStack item) { return (Optional<?>) ServerTasks.call(read, null, item); }

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
}
