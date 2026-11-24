package dev.eyecrasher.jukeboxplaylists;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;

/** Optional LazoDiscs 1.0.7 bridge. No audio libraries or mandatory dependency. */
public final class LazoDiscsCompat {
    private static final Api API = findApi();

    private LazoDiscsCompat() {}

    public static boolean isCustom(ItemStack stack) {
        return API != null && !stack.isEmpty() && (Boolean) invoke(API.hasCustomDisc, null, stack);
    }

    public static boolean play(PlaylistJukebox box, ItemStack stack) {
        if (!isCustom(stack)) return false;
        BlockEntity entity = (BlockEntity) box;
        // LazoDiscs' setter suppresses the underlying vanilla song. Older
        // setters also notify its playback manager themselves.
        invoke(API.setDisc, entity, stack);
        if (!API.setterStartsPlayback) {
            invoke(API.itemChanged, invoke(API.playback, null),
                    entity.getLevel(), entity.getBlockPos(), stack, "jukebox-playlists");
        }
        return true;
    }

    public static void finished(Level level, BlockPos pos) {
        // The LazoDiscs callback is dispatched to the Minecraft server thread.
        // Recheck the world: removal/chunk unload must never consume a record.
        BlockEntity entity = level.getBlockEntity(pos);
        if (entity instanceof PlaylistJukebox) {
            PlaylistJukebox box = (PlaylistJukebox) entity;
            if (isCustom(box.playlist$current())) box.playlist$customFinished();
        }
    }

    public static boolean playing(PlaylistJukebox box) {
        BlockEntity entity = (BlockEntity) box;
        try {
            Object key = API.sourceKey.newInstance(entity.getLevel().dimension(), entity.getBlockPos());
            return ((Map<?, ?>) API.active.get(invoke(API.playback, null))).containsKey(key);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("Cannot read LazoDiscs playback", failure);
        }
    }

    private static Api findApi() {
        ClassLoader loader = LazoDiscsCompat.class.getClassLoader();
        try {
            Class<?> data = Class.forName("com.eyecrasher.lazodiscs.data.DiscDataUtil", false, loader);
            Class<?> access = Class.forName("com.eyecrasher.lazodiscs.access.LazoDiscJukeboxAccess", false, loader);
            Class<?> mod = Class.forName("com.eyecrasher.lazodiscs.LazoDiscs", false, loader);
            Method playback = mod.getMethod("playback");
            Class<?> source = Class.forName("com.eyecrasher.lazodiscs.server.SourceKey", false, loader);
            Constructor<?> sourceKey = source.getConstructor(net.minecraft.resources.ResourceKey.class, BlockPos.class);
            Field active = playback.getReturnType().getDeclaredField("active");
            active.setAccessible(true);
            Method changed = null;
            for (Method method : playback.getReturnType().getMethods()) {
                if (method.getName().equals("onJukeboxItemChanged") && method.getParameterTypes().length == 4) changed = method;
            }
            if (changed == null) throw new NoSuchMethodException("onJukeboxItemChanged");
            return new Api(data.getMethod("hasCustomDisc", ItemStack.class),
                    access.getMethod("lazodiscs$setLazoDiscItem", ItemStack.class),
                    playback, changed, sourceKey, active, Platform.lazoSetterStartsPlayback());
        } catch (ClassNotFoundException absent) {
            return null;
        } catch (ReflectiveOperationException incompatible) {
            throw new IllegalStateException("Jukebox Playlists requires the LazoDiscs 1.0.7 playback API", incompatible);
        }
    }

    private static Object invoke(Method method, Object receiver, Object... args) {
        try {
            return method.invoke(receiver, args);
        } catch (ReflectiveOperationException failure) {
            throw new IllegalStateException("LazoDiscs playlist integration failed: " + method.getName(), failure);
        }
    }

    private static final class Api {
        final Method hasCustomDisc, setDisc, playback, itemChanged;
        final Constructor<?> sourceKey;
        final Field active;
        final boolean setterStartsPlayback;

        Api(Method hasCustomDisc, Method setDisc, Method playback, Method itemChanged,
            Constructor<?> sourceKey, Field active, boolean setterStartsPlayback) {
            this.hasCustomDisc = hasCustomDisc;
            this.setDisc = setDisc;
            this.playback = playback;
            this.itemChanged = itemChanged;
            this.sourceKey = sourceKey;
            this.active = active;
            this.setterStartsPlayback = setterStartsPlayback;
        }
    }
}
