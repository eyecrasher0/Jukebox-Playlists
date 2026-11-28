package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.bukkit.Bukkit;
import org.bukkit.Effect;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Jukebox;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

/** Uses the installed Bukkit API, with a full-stack bridge for 1.7.10 and 1.12.2. */
final class JukeboxAccess {
    private final Method getRecord = ServerTasks.optional(Jukebox.class, "getRecord");
    private final Method setRecord = ServerTasks.optional(Jukebox.class, "setRecord", ItemStack.class);
    private final Method inventory = ServerTasks.optional(Jukebox.class, "getInventory");
    private final Method startPlaying = ServerTasks.optional(Jukebox.class, "startPlaying");
    private final Method itemData = ServerTasks.optional(ItemMeta.class, "getPersistentDataContainer");
    private final Method hasTag;
    private final Object recordingKey;
    private final Object tagContainer;
    private final Method bundleItems;
    private final Method songFromStack;
    private final Method copyForSong;
    private final Method worldHandle;
    private final Method paperConfig;
    private final Field hopperConfig;
    private final Field disableHopperMoveEvent;
    private final Method registryAccess;
    private final Field stackHandle;
    private final Method menuHandle;
    private final Field menuListeners;
    private final Method jukeboxHandle;
    private final Method jukeboxItem;
    private final Method blockData = ServerTasks.optional(Block.class, "getBlockData");
    private final Method liveState = ServerTasks.optional(Block.class, "getState", boolean.class);
    private final Method locked = ServerTasks.optional(Chest.class, "isLocked");
    private final Class<?> chestData;
    private final Method chestType;
    private final Method chestFacing;
    private final Method chestSnapshot;
    private final Field predicateLock;
    private final Object noLock;
    private final Method snapshot;
    private final Field liveRecordPlayer;
    private final Method markDirty;
    private final Method nativeGet;
    private final Method nativeSet;
    private final Method fromNative;
    private final Method toNative;

    JukeboxAccess() throws ReflectiveOperationException {
        if (itemData == null) {
            recordingKey = tagContainer = null;
            hasTag = null;
        } else {
            Class<?> namespacedKey = Class.forName("org.bukkit.NamespacedKey");
            Class<?> dataType = Class.forName("org.bukkit.persistence.PersistentDataType");
            recordingKey = namespacedKey.getConstructor(String.class, String.class).newInstance("lazodiscs", "disc");
            tagContainer = dataType.getField("TAG_CONTAINER").get(null);
            hasTag = itemData.getReturnType().getMethod("has", namespacedKey, dataType);
        }
        bundleItems = bundleItemsMethod();
        String craft = Bukkit.getServer().getClass().getPackage().getName();
        worldHandle = Class.forName(craft + ".CraftWorld").getMethod("getHandle");
        paperConfig = ServerTasks.optional(worldHandle.getReturnType(), "paperConfig");
        if (paperConfig == null) {
            hopperConfig = disableHopperMoveEvent = null;
        } else {
            hopperConfig = paperConfig.getReturnType().getField("hopper");
            disableHopperMoveEvent = hopperConfig.getType().getField("disableMoveEvent");
        }
        Class<?> craftStack = Class.forName(craft + ".inventory.CraftItemStack");
        stackHandle = craftStack.getDeclaredField("handle");
        stackHandle.setAccessible(true);
        menuHandle = itemData == null ? null
                : ServerTasks.optional(Class.forName(craft + ".inventory.CraftInventoryView"), "getHandle");
        menuListeners = menuHandle == null ? null : slotListeners(menuHandle.getReturnType(), stackHandle.getType());
        Class<?> craftJukebox = Class.forName(craft + ".block.CraftJukebox");
        boolean earlyLegacy = craft.endsWith(".v1_7_R4");
        jukeboxHandle = earlyLegacy ? null : liveJukeboxHandle(craftJukebox);
        if (inventory == null && getRecord != null && jukeboxHandle != null
                && craftJukebox.getGenericSuperclass() instanceof ParameterizedType) {
            Class<?> nativeJukebox = (Class<?>) ((ParameterizedType) craftJukebox.getGenericSuperclass())
                    .getActualTypeArguments()[0];
            jukeboxItem = Arrays.stream(nativeJukebox.getMethods())
                    .filter(method -> !Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                            && method.getReturnType() == stackHandle.getType())
                    .findFirst().orElse(null);
        } else {
            jukeboxItem = null;
        }
        Class<?> songs = songType();
        if (songs == null) {
            songFromStack = copyForSong = registryAccess = null;
        } else {
            copyForSong = craftStack.getMethod("asNMSCopy", ItemStack.class);
            Class<?> nativeStack = copyForSong.getReturnType();
            // Native lookup accounts for item defaults, removed components and registry resolution.
            songFromStack = Arrays.stream(songs.getMethods())
                    .filter(method -> Modifier.isStatic(method.getModifiers()) && method.getReturnType() == Optional.class)
                    .filter(method -> method.getParameterCount() >= 1 && method.getParameterCount() <= 2
                            && method.getParameterTypes()[method.getParameterCount() - 1] == nativeStack)
                    .findFirst().orElseThrow(() -> new NoSuchMethodException("JukeboxSong.fromStack"));
            if (songFromStack.getParameterCount() == 1) {
                registryAccess = null;
            } else {
                Class<?> lookup = songFromStack.getParameterTypes()[0];
                registryAccess = Arrays.stream(worldHandle.getReturnType().getMethods())
                        .filter(method -> !Modifier.isStatic(method.getModifiers()) && method.getParameterCount() == 0
                                && lookup.isAssignableFrom(method.getReturnType()))
                        .findFirst().orElseThrow(() -> new NoSuchMethodException("World.registryAccess"));
            }
        }
        if (blockData == null) {
            chestData = null;
            chestType = chestFacing = null;
        } else {
            chestData = Class.forName("org.bukkit.block.data.type.Chest");
            chestType = chestData.getMethod("getType");
            chestFacing = chestData.getMethod("getFacing");
        }
        if (ServerTasks.optional(Chest.class, "setLockItem", ItemStack.class) == null) {
            chestSnapshot = null;
            predicateLock = null;
            noLock = null;
        } else {
            Class<?> container = Class.forName(craft + ".block.CraftContainer");
            chestSnapshot = container.getSuperclass().getDeclaredMethod("getSnapshot");
            chestSnapshot.setAccessible(true);
            Class<?> nativeContainer = (Class<?>) container.getTypeParameters()[0].getBounds()[0];
            Class<?> record = Class.forName("java.lang.Record");
            predicateLock = Arrays.stream(nativeContainer.getDeclaredFields())
                    .filter(field -> !Modifier.isStatic(field.getModifiers()) && record.isAssignableFrom(field.getType()))
                    .findFirst().orElseThrow(() -> new NoSuchFieldException("Container lock predicate"));
            predicateLock.setAccessible(true);
            Class<?> lockType = predicateLock.getType();
            noLock = Arrays.stream(lockType.getFields())
                    .filter(field -> Modifier.isStatic(field.getModifiers()) && field.getType() == lockType)
                    .findFirst().orElseThrow(() -> new NoSuchFieldException("Unlocked container sentinel")).get(null);
        }
        if (getRecord != null) {
            snapshot = nativeGet = nativeSet = fromNative = toNative = null;
            liveRecordPlayer = null;
            markDirty = null;
            return;
        }
        Class<?> nativeType;
        if (earlyLegacy) {
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
        fromNative = craftStack.getMethod("asBukkitCopy", nativeGet.getReturnType());
        toNative = craftStack.getMethod("asNMSCopy", ItemStack.class);
    }

    private static Method liveJukeboxHandle(Class<?> craftJukebox) {
        Method handle = ServerTasks.optional(craftJukebox, "getBlockEntity");
        if (handle == null) handle = ServerTasks.optional(craftJukebox, "getTileEntity");
        if (handle != null) return handle;
        try {
            handle = craftJukebox.getSuperclass().getDeclaredMethod("getTileEntity");
            handle.setAccessible(true);
            return handle;
        } catch (NoSuchMethodException absent) {
            return null;
        }
    }

    boolean nativeTiming() { return startPlaying != null; }

    boolean observesHopperMoves(World world) {
        if (paperConfig == null) return true;
        Object config = ServerTasks.call(paperConfig, ServerTasks.call(worldHandle, world));
        try {
            return !disableHopperMoveEvent.getBoolean(hopperConfig.get(config));
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read the hopper move event setting", failure);
        }
    }

    Object stackIdentity(ItemStack item) {
        if (!stackHandle.getDeclaringClass().isInstance(item)) return null;
        try {
            return stackHandle.get(item);
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot identify the native item stack", failure);
        }
    }

    private static boolean isSlotChange(Method method, Class<?> menu, Class<?> stack) {
        Class<?>[] args = method.getParameterTypes();
        return (args.length == 3 || args.length == 4) && args[0] == menu && args[1] == int.class && args[2] == stack;
    }

    private static Field slotListeners(Class<?> menu, Class<?> stack) {
        for (Field field : menu.getDeclaredFields()) {
            Class<?> listener = listenerType(field);
            if (listener != null && Arrays.stream(listener.getMethods()).anyMatch(method -> isSlotChange(method, menu, stack))) {
                field.setAccessible(true);
                return field;
            }
        }
        return null;
    }

    private static Class<?> listenerType(Field field) {
        if (Modifier.isStatic(field.getModifiers()) || field.getType() != List.class
                || !(field.getGenericType() instanceof ParameterizedType)) return null;
        java.lang.reflect.Type type = ((ParameterizedType) field.getGenericType()).getActualTypeArguments()[0];
        return type instanceof Class<?> && ((Class<?>) type).isInterface() ? (Class<?>) type : null;
    }

    @SuppressWarnings("unchecked")
    Runnable observeSlotChanges(InventoryView view, Runnable committed) {
        if (menuListeners == null || !menuHandle.getDeclaringClass().isInstance(view)) return null;
        Object menu = ServerTasks.call(menuHandle, view);
        Class<?> menuType = menuHandle.getReturnType();
        try {
            List<Object> listeners = (List<Object>) menuListeners.get(menu);
            Class<?> type = listenerType(menuListeners);
            Object listener = Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
                if (method.getDeclaringClass() == Object.class) {
                    if ("equals".equals(method.getName())) return proxy == args[0];
                    if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                    return "Jukebox Playlists inventory listener";
                }
                if (isSlotChange(method, menuType, stackHandle.getType()) && args[0] == menu
                        && inClickBroadcast(menuType.getName())) committed.run();
                return null;
            });
            // The public native registration also broadcasts immediately, before Bukkit applies the click.
            listeners.add(0, listener);
            return () -> listeners.remove(listener);
        } catch (IllegalAccessException inaccessible) {
            return null;
        }
    }

    private static boolean inClickBroadcast(String menuClass) {
        boolean fromMenu = false;
        for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
            String owner = frame.getClassName();
            if (menuClass.equals(owner)) fromMenu = true;
            else if (fromMenu) {
                if (!owner.startsWith("net.minecraft.")) return false;
                if (owner.endsWith(".ServerboundContainerClickPacket") || owner.endsWith(".PacketPlayInWindowClick")) {
                    return true;
                }
            }
        }
        return false;
    }

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

    Object jukeboxIdentity(Jukebox box) {
        if (jukeboxHandle != null) return ServerTasks.call(jukeboxHandle, box);
        return liveRecordPlayer == null ? null : recordPlayer(box);
    }

    Object recordIdentity(Jukebox box, ItemStack record) {
        if (record == null) return null;
        if (inventory != null) return stackIdentity(record);
        Object player = jukeboxIdentity(box);
        if (player == null) return null;
        if (jukeboxItem != null) return ServerTasks.call(jukeboxItem, player);
        return nativeGet == null ? null : ServerTasks.call(nativeGet, player);
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

    boolean isRecording(ItemStack item) {
        if (itemData == null || empty(item) || !item.hasItemMeta()) return false;
        ItemMeta meta = item.getItemMeta();
        return meta != null && (Boolean) ServerTasks.call(hasTag, ServerTasks.call(itemData, meta), recordingKey, tagContainer);
    }

    @SuppressWarnings("unchecked")
    List<ItemStack> bundleContents(ItemStack item) {
        if (bundleItems == null || empty(item) || !item.hasItemMeta()) return Collections.emptyList();
        ItemMeta meta = item.getItemMeta();
        if (!bundleItems.getDeclaringClass().isInstance(meta)) return Collections.emptyList();
        return (List<ItemStack>) ServerTasks.call(bundleItems, meta);
    }

    private static Method bundleItemsMethod() throws NoSuchMethodException {
        try {
            return Class.forName("org.bukkit.inventory.meta.BundleMeta").getMethod("getItems");
        } catch (ClassNotFoundException legacy) {
            return null;
        }
    }

    boolean isSingleChest(Block block) {
        Material material = block.getType();
        if (!isChest(material)) return false;
        if (blockData != null) return chestPartner(block) == null;
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block adjacent = block.getRelative(face);
            if (!adjacent.getWorld().isChunkLoaded(adjacent.getX() >> 4, adjacent.getZ() >> 4)) return false;
            if (adjacent.getType() == material) return false;
        }
        return true;
    }

    Location chestPartner(Block block) {
        if (blockData == null) return null;
        Object data = ServerTasks.call(blockData, block);
        if (!chestData.isInstance(data)) return null;
        String type = ((Enum<?>) ServerTasks.call(chestType, data)).name();
        if ("SINGLE".equals(type)) return null;
        BlockFace facing = (BlockFace) ServerTasks.call(chestFacing, data);
        int turn = "LEFT".equals(type) ? 1 : -1;
        return block.getLocation().add(-turn * facing.getModZ(), 0, turn * facing.getModX());
    }

    Chest singleChest(Block block) {
        if (!isSingleChest(block)) return null;
        BlockState state = state(block);
        return state instanceof Chest ? (Chest) state : null;
    }

    boolean isLocked(Chest chest) {
        if (predicateLock == null) return locked != null && (Boolean) ServerTasks.call(locked, chest);
        try {
            return !noLock.equals(predicateLock.get(ServerTasks.call(chestSnapshot, chest)));
        } catch (IllegalAccessException failure) {
            throw new IllegalStateException("Cannot read the native chest lock", failure);
        }
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
