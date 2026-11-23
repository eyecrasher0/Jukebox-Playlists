package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Jukebox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Uses the installed Bukkit API, with a full-stack bridge for 1.7.10 and 1.12.2. */
final class JukeboxAccess {
    private final Method getRecord = ServerTasks.optional(Jukebox.class, "getRecord");
    private final Method setRecord = ServerTasks.optional(Jukebox.class, "setRecord", ItemStack.class);
    private final Method inventory = ServerTasks.optional(Jukebox.class, "getInventory");
    private final Method startPlaying = ServerTasks.optional(Jukebox.class, "startPlaying");
    private final Method songFromStack;
    private final Method copyForSong;
    private final Method worldHandle;
    private final Method registryAccess;
    private final Method blockData = ServerTasks.optional(Block.class, "getBlockData");
    private final Method liveState = ServerTasks.optional(Block.class, "getState", boolean.class);
    private final Method locked = ServerTasks.optional(Chest.class, "isLocked");
    private final Class<?> chestData;
    private final Method chestType;
    private final Method snapshot;
    private final Field liveRecordPlayer;
    private final Method markDirty;
    private final Method nativeGet;
    private final Method nativeSet;
    private final Method fromNative;
    private final Method toNative;

    JukeboxAccess() throws ReflectiveOperationException {
        String craft = Bukkit.getServer().getClass().getPackage().getName();
        Class<?> songs = songType();
        if (songs == null) {
            songFromStack = copyForSong = worldHandle = registryAccess = null;
        } else {
            copyForSong = Class.forName(craft + ".inventory.CraftItemStack").getMethod("asNMSCopy", ItemStack.class);
            Class<?> nativeStack = copyForSong.getReturnType();
            // Native lookup accounts for item defaults, removed components and registry resolution.
            songFromStack = Arrays.stream(songs.getMethods())
                    .filter(method -> Modifier.isStatic(method.getModifiers()) && method.getReturnType() == Optional.class)
                    .filter(method -> method.getParameterCount() >= 1 && method.getParameterCount() <= 2
                            && method.getParameterTypes()[method.getParameterCount() - 1] == nativeStack)
                    .findFirst().orElseThrow(() -> new NoSuchMethodException("JukeboxSong.fromStack"));
            if (songFromStack.getParameterCount() == 1) {
                worldHandle = registryAccess = null;
            } else {
                worldHandle = Class.forName(craft + ".CraftWorld").getMethod("getHandle");
                Class<?> lookup = songFromStack.getParameterTypes()[0];
                registryAccess = Arrays.stream(worldHandle.getReturnType().getMethods())
                        .filter(method -> !Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                                && lookup.isAssignableFrom(method.getReturnType()))
                        .findFirst().orElseThrow(() -> new NoSuchMethodException("World.registryAccess"));
            }
        }
        if (blockData == null) {
            chestData = null;
            chestType = null;
        } else {
            chestData = Class.forName("org.bukkit.block.data.type.Chest");
            chestType = chestData.getMethod("getType");
        }
        if (getRecord != null) {
            snapshot = nativeGet = nativeSet = fromNative = toNative = null;
            liveRecordPlayer = null;
            markDirty = null;
            return;
        }
        Class<?> craftJukebox = Class.forName(craft + ".block.CraftJukebox");
        Class<?> nativeType;
        if (craft.endsWith(".v1_7_R4")) {
            snapshot = null;
            liveRecordPlayer = craftJukebox.getDeclaredField("jukebox");
            liveRecordPlayer.setAccessible(true);
            nativeType = liveRecordPlayer.getType();
            markDirty = nativeType.getMethod("update");
        } else {
            liveRecordPlayer = null;
            markDirty = null;
            snapshot = craftJukebox.getSuperclass().getDeclaredMethod("getSnapshot");
            snapshot.setAccessible(true);
            nativeType = Class.forName("net.minecraft.server.v1_12_R1.BlockJukeBox$TileEntityRecordPlayer");
        }
        nativeGet = nativeType.getMethod("getRecord");
        nativeSet = nativeType.getMethod("setRecord", nativeGet.getReturnType());
        Class<?> craftStack = Class.forName(craft + ".inventory.CraftItemStack");
        fromNative = craftStack.getMethod("asBukkitCopy", nativeGet.getReturnType());
        toNative = craftStack.getMethod("asNMSCopy", ItemStack.class);
    }

    boolean nativeTiming() { return startPlaying != null; }

    private static Class<?> songType() {
        try {
            return Class.forName("net.minecraft.world.item.JukeboxSong");
        } catch (ClassNotFoundException legacy) {
            return null;
        }
    }

    BlockState state(Block block) {
        return liveState == null ? block.getState() : (BlockState) ServerTasks.call(liveState, block, false);
    }

    ItemStack record(Jukebox box) {
        ItemStack item;
        if (inventory != null) item = ((Inventory) ServerTasks.call(inventory, box)).getItem(0);
        else if (getRecord != null) item = (ItemStack) ServerTasks.call(getRecord, box);
        else {
            Object stack = ServerTasks.call(nativeGet, recordPlayer(box));
            item = stack == null ? null : (ItemStack) ServerTasks.call(fromNative, null, stack);
        }
        return empty(item) ? null : item;
    }

    void start(Jukebox box) {
        if (startPlaying != null) ServerTasks.call(startPlaying, box);
        else box.getWorld().playEffect(box.getLocation(), Effect.RECORD_PLAY, box.getPlaying());
    }

    boolean insert(Jukebox box, ItemStack disc, boolean silent) {
        if (inventory != null) {
            Inventory live = (Inventory) ServerTasks.call(inventory, box);
            ItemStack old = live.getItem(0);
            ItemStack previous = empty(old) ? null : old.clone();
            live.setItem(0, disc.clone());
            if (disc.equals(live.getItem(0))) return true;
            live.setItem(0, previous);
            return false;
        }
        if (liveRecordPlayer != null) {
            // 1.7's Bukkit method sets the native flag, plays the record, and
            // updates neighbors. The complete stack then replaces its default stack.
            box.setPlaying(disc.getType());
            Object player = recordPlayer(box);
            ServerTasks.call(nativeSet, player, ServerTasks.call(toNative, null, disc));
            ServerTasks.call(markDirty, player);
            return true;
        }
        BlockState state = box.getBlock().getState();
        if (!(state instanceof Jukebox)) return false;
        Jukebox snapshot = (Jukebox) state;
        if (setRecord != null) ServerTasks.call(setRecord, snapshot, disc);
        else ServerTasks.call(nativeSet, recordPlayer(snapshot), ServerTasks.call(toNative, null, disc));
        if (!disc.equals(record(snapshot))) return false;
        return silent ? updateSilently(snapshot) : snapshot.update(false, true);
    }

    private Object recordPlayer(Jukebox box) {
        if (snapshot != null) return ServerTasks.call(snapshot, box);
        try {
            return liveRecordPlayer.get(box);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read the native record player", failure);
        }
    }

    private boolean updateSilently(Jukebox box) {
        // Old CraftJukebox.update always emits RECORD_PLAY. LazoDiscs uses
        // the block-entity update underneath it for silent insertion.
        try {
            Class<?> implementation = box.getClass().getMethod("update", boolean.class, boolean.class).getDeclaringClass();
            Method lookup = MethodHandles.class.getMethod("privateLookupIn", Class.class, MethodHandles.Lookup.class);
            MethodHandles.Lookup privateLookup = (MethodHandles.Lookup) lookup.invoke(null, implementation, MethodHandles.lookup());
            return (boolean) privateLookup.findSpecial(implementation.getSuperclass(), "update",
                    MethodType.methodType(boolean.class, boolean.class, boolean.class), implementation).invoke(box, false, true);
        } catch (RuntimeException | Error failure) {
            throw failure;
        } catch (Throwable failure) {
            throw new IllegalStateException("Cannot insert a LazoDiscs recording silently", failure);
        }
    }

    boolean isDisc(ItemStack item, World world) {
        if (empty(item)) return false;
        if (songFromStack == null) return item.getType().isRecord();
        Object stack = ServerTasks.call(copyForSong, null, item);
        if (registryAccess == null) return ((Optional<?>) ServerTasks.call(songFromStack, null, stack)).isPresent();
        Object registries = ServerTasks.call(registryAccess, ServerTasks.call(worldHandle, world));
        return ((Optional<?>) ServerTasks.call(songFromStack, null, registries, stack)).isPresent();
    }

    Inventory chest(Block block) {
        if (!isChest(block.getType())) return null;
        if (blockData != null) {
            Object data = ServerTasks.call(blockData, block);
            if (chestData.isInstance(data) && !"SINGLE".equals(((Enum<?>) ServerTasks.call(chestType, data)).name()))
                return null;
        } else {
            for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
                Block adjacent = block.getRelative(face);
                if (!adjacent.getWorld().isChunkLoaded(adjacent.getX() >> 4, adjacent.getZ() >> 4)) return null;
                if (adjacent.getType() == block.getType()) return null;
            }
        }
        BlockState state = state(block);
        if (!(state instanceof Chest) || (locked != null && (Boolean) ServerTasks.call(locked, state))) return null;
        return ((Chest) state).getBlockInventory();
    }

    static boolean isChest(Material material) {
        String name = material.name();
        return material == Material.CHEST || material == Material.TRAPPED_CHEST || name.endsWith("COPPER_CHEST");
    }

    static boolean empty(ItemStack item) {
        return item == null || item.getAmount() <= 0 || item.getType() == Material.AIR;
    }

    static long duration(ItemStack record) {
        // Pre-1.19.4 Bukkit has no playback completion API. Values are
        // native song lengths in ticks for the discs on those servers.
        String name = record.getType().name();
        if (name.startsWith("MUSIC_DISC_")) name = name.substring(11);
        switch (name) {
            case "GOLD_RECORD": case "13": return 3560;
            case "GREEN_RECORD": case "CAT": return 3700;
            case "RECORD_3": case "BLOCKS": return 6900;
            case "RECORD_4": case "CHIRP": return 3700;
            case "RECORD_5": case "FAR": return 3480;
            case "RECORD_6": case "MALL": return 3940;
            case "RECORD_7": case "MELLOHI": return 1920;
            case "RECORD_8": case "STAL": return 3000;
            case "RECORD_9": case "STRAD": return 3760;
            case "RECORD_10": case "WARD": return 5020;
            case "RECORD_11": case "11": return 1420;
            case "RECORD_12": case "WAIT": return 4760;
            case "PIGSTEP": return 2980;
            case "OTHERSIDE": return 3900;
            case "5": return 3560;
            default: return -1;
        }
    }
}
