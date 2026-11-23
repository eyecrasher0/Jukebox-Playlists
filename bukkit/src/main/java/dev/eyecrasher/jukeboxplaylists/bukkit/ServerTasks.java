package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.Plugin;

/** Schedules block work on the owning region on Folia, and the main thread elsewhere. */
final class ServerTasks {
    private final Plugin plugin;
    private final Object regions;
    private final Object global;
    private final Method executeRegion;
    private final Method repeatRegion;
    private final Method executeGlobal;
    private final Method cancel;
    private final Method ownsEntity = optional(Bukkit.class, "isOwnedByCurrentRegion", Entity.class);
    private final Method ownsLocation = optional(Bukkit.class, "isOwnedByCurrentRegion", Location.class);

    ServerTasks(Plugin plugin) throws ReflectiveOperationException {
        this.plugin = plugin;
        Method scheduler = optional(Bukkit.class, "getRegionScheduler");
        if (scheduler == null) {
            regions = global = null;
            executeRegion = repeatRegion = executeGlobal = cancel = null;
            return;
        }
        regions = scheduler.invoke(null);
        Method globalScheduler = Bukkit.class.getMethod("getGlobalRegionScheduler");
        global = globalScheduler.invoke(null);
        Class<?> type = scheduler.getReturnType();
        executeRegion = type.getMethod("execute", Plugin.class, World.class, int.class, int.class, Runnable.class);
        repeatRegion = type.getMethod("runAtFixedRate", Plugin.class, World.class, int.class, int.class,
                                      Consumer.class, long.class, long.class);
        executeGlobal = globalScheduler.getReturnType().getMethod("execute", Plugin.class, Runnable.class);
        cancel = repeatRegion.getReturnType().getMethod("cancel");
    }

    void global(Runnable action) {
        if (global == null) Bukkit.getScheduler().runTask(plugin, action);
        else call(executeGlobal, global, plugin, action);
    }

    void region(World world, int x, int z, Runnable action) {
        if (regions == null) Bukkit.getScheduler().runTask(plugin, action);
        else call(executeRegion, regions, plugin, world, x, z, action);
    }

    boolean owns(Entity entity) {
        return ownsEntity == null || (Boolean) call(ownsEntity, null, entity);
    }

    boolean owns(Location location) {
        return ownsLocation == null || (Boolean) call(ownsLocation, null, location);
    }

    Runnable repeat(World world, int x, int z, Runnable action) {
        if (regions == null) return Bukkit.getScheduler().runTaskTimer(plugin, action, 1, 1)::cancel;
        Consumer<Object> callback = ignored -> action.run();
        Object task = call(repeatRegion, regions, plugin, world, x, z, callback, 1L, 1L);
        return () -> call(cancel, task);
    }

    static Method optional(Class<?> type, String name, Class<?>... parameters) {
        try {
            return type.getMethod(name, parameters);
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    static Object call(Method method, Object receiver, Object... arguments) {
        try {
            return method.invoke(receiver, arguments);
        } catch (InvocationTargetException failure) {
            Throwable reason = failure.getCause();
            if (reason instanceof RuntimeException) throw (RuntimeException) reason;
            if (reason instanceof Error) throw (Error) reason;
            throw new IllegalStateException(method.getName(), reason);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException(method.getName(), failure);
        }
    }
}
