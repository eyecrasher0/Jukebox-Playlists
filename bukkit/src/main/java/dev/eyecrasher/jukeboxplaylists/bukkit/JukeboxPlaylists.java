package dev.eyecrasher.jukeboxplaylists.bukkit;

import static dev.eyecrasher.jukeboxplaylists.bukkit.RecordingApprovals.amount;
import static dev.eyecrasher.jukeboxplaylists.bukkit.RecordingApprovals.spaceFor;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryInteractEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class JukeboxPlaylists extends JavaPlugin implements Listener {
    private final Map<UUID, Map<Long, TrackedChunk>> worlds = new ConcurrentHashMap<>();
    private final Map<UUID, Runnable> deposits = new ConcurrentHashMap<>();
    private final Method physicsSource = ServerTasks.optional(BlockPhysicsEvent.class, "getSourceBlock");
    private ServerTasks tasks;
    private JukeboxAccess access;
    private PlaylistPositions positions;
    private LazoDiscsPlayback lazo;

    @Override
    public void onEnable() {
        try {
            access = new JukeboxAccess();
            positions = new PlaylistPositions(this);
            tasks = new ServerTasks(this);
        } catch (ReflectiveOperationException | IOException failure) {
            getLogger().log(Level.SEVERE, "Could not initialize the jukebox adapter for this server", failure);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        lazo = null;
        Plugin discs = getServer().getPluginManager().getPlugin("LazoDiscs");
        if (discs != null && discs.isEnabled()) {
            try {
                lazo = new LazoDiscsPlayback(discs);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
                getLogger().log(Level.WARNING, "Could not connect to LazoDiscs playback", failure);
            }
        }
        getServer().getPluginManager().registerEvents(this, this);
        tasks.global(() -> {
            for (World world : Bukkit.getWorlds()) {
                for (Chunk chunk : world.getLoadedChunks()) scheduleScan(chunk);
            }
        });
    }

    @Override
    public void onDisable() {
        deposits.clear();
        for (Map<Long, TrackedChunk> chunks : worlds.values()) {
            for (TrackedChunk chunk : chunks.values()) {
                chunk.beforeSave();
                chunk.cancel();
            }
        }
        worlds.clear();
        savePositions();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkLoaded(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        if (owns(chunk) && chunk.isLoaded()) scan(chunk);
        else scheduleScan(chunk);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chunkUnloaded(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        Map<Long, TrackedChunk> chunks = worlds.get(chunk.getWorld().getUID());
        if (chunks == null) return;
        TrackedChunk tracking = chunks.remove(chunkKey(chunk));
        if (tracking != null) {
            tracking.refresh(false);
            tracking.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void worldSaved(WorldSaveEvent event) {
        Map<Long, TrackedChunk> chunks = worlds.get(event.getWorld().getUID());
        if (chunks != null) for (TrackedChunk chunk : chunks.values()) chunk.beforeSave();
        savePositions();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void worldUnloaded(WorldUnloadEvent event) {
        Map<Long, TrackedChunk> chunks = worlds.remove(event.getWorld().getUID());
        if (chunks == null) return;
        for (TrackedChunk chunk : chunks.values()) {
            chunk.beforeSave();
            chunk.cancel();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placed(BlockPlaceEvent event) {
        if (!event.canBuild()) return;
        Block block = event.getBlockPlaced();
        if (lazo != null && JukeboxAccess.isChest(block.getType())) invalidateChest(block);
        scheduleDiscovery(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.JUKEBOX) {
            TrackedChunk chunk = trackedChunk(block);
            if (chunk != null) chunk.boxes.remove(block);
            positions.clear(block.getChunk(), block);
        } else if (lazo != null && JukeboxAccess.isChest(block.getType())) {
            invalidateChest(block);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void physics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        reconcileObservedRecord(block);
        if (physicsSource != null) {
            Block source = (Block) ServerTasks.call(physicsSource, event);
            if (!source.equals(block)) reconcileObservedRecord(source);
        }
        if (!event.isCancelled() && block.getType() == Material.JUKEBOX && !tracked(block)) scheduleDiscovery(block);
    }

    private void reconcileObservedRecord(Block block) {
        TrackedChunk chunk = trackedChunk(block);
        if (chunk == null || !tasks.owns(block.getLocation())) return;
        Playback playback = chunk.boxes.get(block);
        if (playback == null || !playback.advancePending && !playback.resume) return;
        Jukebox box = liveJukebox(block);
        Object identity = box == null ? null : access.jukeboxIdentity(box);
        if (box == null || identity != null && playback.boxIdentity != null && identity != playback.boxIdentity) {
            playback.resume = false;
            playback.setPending(chunk.chunk, block, null);
        } else {
            playback.reconcileRecord(chunk.chunk, box, false);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opened(InventoryOpenEvent event) {
        Inventory inventory = event.getInventory();
        if (invalidateDoubleChest(inventory)) return;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest) scheduleDiscovery(((Chest) holder).getBlock());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeClick(InventoryClickEvent event) { finishDeposit(event.getWhoClicked().getUniqueId()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void beforeDrag(InventoryDragEvent event) { finishDeposit(event.getWhoClicked().getUniqueId()); }

    @EventHandler(priority = EventPriority.LOWEST)
    public void closed(InventoryCloseEvent event) { finishDeposit(event.getPlayer().getUniqueId()); }

    private void finishDeposit(UUID player) {
        Runnable finish = deposits.remove(player);
        if (finish != null) finish.run();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void clicked(InventoryClickEvent event) {
        if (lazo == null || !(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        InventoryView view = event.getView();
        if (!stillOpen(player, view)) return;
        Inventory chest = view.getTopInventory();
        if (invalidateDoubleChest(chest)) return;
        Location location = chest.getLocation();
        if (location == null || !tasks.owns(location)) return;
        if (event.getAction() == InventoryAction.NOTHING) return;
        Playback playback = reconcileRecordings(chest, location);
        if (!observeClickDeposit(event, player, chest, location)
                && playback != null && !playback.recordings.isEmpty()) {
            observeInteraction(event, player, location, playback, null, null);
        }
    }

    private boolean observeClickDeposit(InventoryClickEvent event, Player player, Inventory chest, Location location) {
        InventoryView view = event.getView();
        InventoryAction action = event.getAction();
        int slot = event.getRawSlot();
        int size = chest.getSize();
        boolean shift = action == InventoryAction.MOVE_TO_OTHER_INVENTORY;
        if (slot < 0 || (shift ? slot < size || slot >= view.countSlots() : slot >= size)) return false;

        Supplier<ItemStack> source;
        List<ItemStack> bundle = Collections.emptyList();
        boolean swap = action == InventoryAction.HOTBAR_SWAP || action == InventoryAction.HOTBAR_MOVE_AND_READD;
        if (shift) {
            if (!event.isShiftClick()) return false;
            source = event::getCurrentItem;
        } else if (swap) {
            int button = event.getHotbarButton();
            if (button >= 0 && button < 9) source = () -> player.getInventory().getItem(button);
            else if ("SWAP_OFFHAND".equals(event.getClick().name())) {
                source = () -> player.getInventory().getItemInOffHand();
            } else return false;
        } else {
            if (!"LEFT".equals(event.getClick().name()) && !"RIGHT".equals(event.getClick().name())) return false;
            source = player::getItemOnCursor;
            boolean fromBundle = "PLACE_FROM_BUNDLE".equals(action.name())
                    || action == InventoryAction.PLACE_ONE && event.isRightClick();
            if (fromBundle && JukeboxAccess.empty(event.getCurrentItem())) bundle = access.bundleContents(source.get());
            if (bundle.isEmpty() && action != InventoryAction.PLACE_ALL && action != InventoryAction.PLACE_ONE
                    && action != InventoryAction.PLACE_SOME && action != InventoryAction.SWAP_WITH_CURSOR) return false;
        }
        ItemStack incoming = source.get();
        if (bundle.isEmpty() ? !access.isRecording(incoming) : bundle.stream().noneMatch(access::isRecording)) return false;
        Playback playback = playbackFor(chest, location);
        if (playback == null) return false;
        UUID depositor = player.getUniqueId();
        if (swap || action == InventoryAction.SWAP_WITH_CURSOR) {
            RecordingApprovals.Stack original = new RecordingApprovals.Stack(access, incoming);
            if (original.handle == null) return false;
            RecordingApprovals.Stack previous = new RecordingApprovals.Stack(access, chest.getItem(slot));
            Runnable confirm = () -> {
                ItemStack actual = chest.getItem(slot);
                if (original.matches(access, actual) && !previous.matches(access, actual)
                        && original.handle != access.stackIdentity(source.get())) {
                    playback.recordings.store(slot, actual, depositor);
                }
            };
            observeInteraction(event, player, location, playback, confirm, confirm);
            return true;
        }

        Map<Integer, RecordingApprovals.Stack> before = new HashMap<>();
        if (shift) shiftSlots(chest, incoming, before);
        else before.put(slot, new RecordingApprovals.Stack(access, chest.getItem(slot)));
        if (before.isEmpty()) return false;
        ItemStack original = incoming.clone();
        ToIntFunction<ItemStack> consumed;
        if (bundle.isEmpty()) {
            consumed = actual -> original.isSimilar(actual) ? consumedAmount(original, source.get()) : 0;
        } else {
            List<ItemStack> contents = bundle;
            Object handle = access.stackIdentity(incoming);
            consumed = actual -> {
                ItemStack remainder = source.get();
                return handle != null && handle == access.stackIdentity(remainder) && access.isRecording(actual)
                        ? bundleAmount(contents, actual) - bundleAmount(access.bundleContents(remainder), actual) : 0;
            };
        }
        observeInteraction(event, player, location, playback,
                () -> confirmTransfer(view, before, consumed, playback.recordings, depositor), null);
        return true;
    }

    private Playback reconcileRecordings(Inventory chest, Location location) {
        Playback playback = trackedChest(chest, location);
        if (playback != null) playback.recordings.update(chest);
        return playback;
    }

    private Playback trackedChest(Inventory chest, Location location) {
        if (location == null || !tasks.owns(location)) return null;
        Block top = location.getBlock();
        Playback playback = playback(top.getRelative(BlockFace.DOWN));
        if (playback == null || !isLiveChest(chest, top)) return null;
        for (HumanEntity viewer : chest.getViewers()) {
            if (tasks.owns(viewer)) finishDeposit(viewer.getUniqueId());
        }
        return playback;
    }

    private static int consumedAmount(ItemStack original, ItemStack remainder) {
        return JukeboxAccess.empty(remainder) || original.isSimilar(remainder)
                ? original.getAmount() - amount(remainder) : 0;
    }

    private static int bundleAmount(List<ItemStack> contents, ItemStack item) {
        return contents.stream().filter(item::isSimilar).mapToInt(ItemStack::getAmount).sum();
    }

    private void shiftSlots(Inventory chest, ItemStack incoming, Map<Integer, RecordingApprovals.Stack> slots) {
        int limit = Math.min(chest.getMaxStackSize(), incoming.getMaxStackSize());
        int remaining = incoming.getAmount();
        for (int i = 0; i < chest.getSize() && remaining > 0; i++) {
            ItemStack item = chest.getItem(i);
            int space = limit - amount(item);
            if (!JukeboxAccess.empty(item) && incoming.isSimilar(item) && space > 0) {
                slots.put(i, new RecordingApprovals.Stack(access, item));
                remaining -= space;
            }
        }
        for (int i = 0; i < chest.getSize() && remaining > 0; i++) {
            if (JukeboxAccess.empty(chest.getItem(i))) {
                slots.put(i, new RecordingApprovals.Stack(access, null));
                remaining -= limit;
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dragged(InventoryDragEvent event) {
        if (lazo == null || !(event.getWhoClicked() instanceof Player)) return;
        Player player = (Player) event.getWhoClicked();
        InventoryView view = event.getView();
        if (!stillOpen(player, view)) return;
        Inventory chest = view.getTopInventory();
        if (invalidateDoubleChest(chest)) return;
        Location location = chest.getLocation();
        if (location == null || !tasks.owns(location)) return;
        reconcileRecordings(chest, location);
        ItemStack source = event.getOldCursor();
        if (!access.isRecording(source)) return;
        Map<Integer, ItemStack> expected = event.getNewItems();
        if (expected.keySet().stream().noneMatch(slot -> slot >= 0 && slot < chest.getSize())) return;
        Playback playback = playbackFor(chest, location);
        if (playback == null) return;
        Map<Integer, RecordingApprovals.Stack> before = new HashMap<>();
        for (int slot : expected.keySet()) {
            if (slot < 0 || slot >= view.countSlots()) return;
            before.put(slot, new RecordingApprovals.Stack(access, view.getItem(slot)));
        }
        observeInteraction(event, player, location, playback, () -> {
            if (expected.entrySet().stream().allMatch(change -> change.getValue().equals(view.getItem(change.getKey())))) {
                confirmTransfer(view, before, actual -> source.isSimilar(actual)
                        ? consumedAmount(source, player.getItemOnCursor()) : 0, playback.recordings, player.getUniqueId());
            }
        }, null);
    }

    private void confirmTransfer(InventoryView view, Map<Integer, RecordingApprovals.Stack> before,
                                 ToIntFunction<ItemStack> consumed, RecordingApprovals recordings, UUID player) {
        ItemStack moved = null;
        int count = 0;
        for (Map.Entry<Integer, RecordingApprovals.Stack> entry : before.entrySet()) {
            ItemStack actual = view.getItem(entry.getKey());
            RecordingApprovals.Stack previous = entry.getValue();
            if (previous.matches(access, actual)) continue;
            int added = amount(actual) - amount(previous.item);
            if (added <= 0 || previous.item != null && !previous.item.isSimilar(actual)
                    || moved != null && !moved.isSimilar(actual)) return;
            moved = actual;
            count += added;
        }
        if (moved == null || count != consumed.applyAsInt(moved)) return;
        Inventory chest = view.getTopInventory();
        before.forEach((slot, previous) -> {
            if (slot < chest.getSize()) recordings.deposited(slot, previous, chest.getItem(slot), player);
        });
    }

    private void observeInteraction(InventoryInteractEvent event, Player player, Location location, Playback playback,
                                    Runnable commit, Runnable confirmSwap) {
        InventoryView view = event.getView();
        UUID depositor = player.getUniqueId();
        BooleanSupplier valid = () -> !event.isCancelled() && stillOpen(player, view) && tasks.owns(location)
                && playback(location.getBlock().getRelative(BlockFace.DOWN)) == playback;
        Runnable finish = () -> {
            if (confirmSwap != null && valid.getAsBoolean()) {
                confirmSwap.run();
                playback.recordings.update(view.getTopInventory());
            }
        };
        Runnable remove = access.observeSlotChanges(view, () -> {
            if (deposits.remove(depositor, finish) && valid.getAsBoolean()) {
                if (commit != null) commit.run();
                playback.recordings.update(view.getTopInventory());
            }
        });
        if (remove == null && confirmSwap == null) return;
        deposits.put(depositor, finish);
        tasks.region(location.getWorld(), location.getBlockX() >> 4, location.getBlockZ() >> 4, () -> {
            try {
                if (deposits.remove(depositor, finish)) finish.run();
            } finally {
                if (remove != null) remove.run();
            }
        });
    }

    private boolean stillOpen(Player player, InventoryView view) {
        return tasks.owns(player) && player.isOnline() && player.getOpenInventory() == view;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void moved(InventoryMoveItemEvent event) {
        if (lazo == null) return;
        Inventory source = event.getSource();
        Inventory destination = event.getDestination();
        Playback from = transferChest(source);
        Playback to = transferChest(destination);
        if (from == null && to == null) return;
        ItemStack item = event.getItem();
        if (JukeboxAccess.empty(item)) return;
        if (from != null && !from.recordings.isEmpty() && canReceive(destination, item)) {
            from.recordings.movingOut(source, item);
        }
        if (to != null) to.recordings.movingIn(destination, item);
    }

    private Playback transferChest(Inventory inventory) {
        return invalidateDoubleChest(inventory) ? null : trackedChest(inventory, inventory.getLocation());
    }

    private boolean canReceive(Inventory inventory, ItemStack item) {
        Location location = inventory.getLocation();
        if (location != null && !tasks.owns(location)) return true;
        for (ItemStack current : inventory.getStorageContents()) {
            if (spaceFor(inventory, item, current) > 0) return true;
        }
        return false;
    }

    private Playback playbackFor(Inventory chest, Location location) {
        if (location == null || !tasks.owns(location)) return null;
        Block top = location.getBlock();
        Block box = top.getRelative(BlockFace.DOWN);
        if (box.getType() != Material.JUKEBOX || !isLiveChest(chest, top)) return null;
        return track(box.getChunk(), box, false);
    }

    private boolean isLiveChest(Inventory chest, Block top) {
        Chest state = access.singleChest(top);
        return state != null && chest.equals(state.getBlockInventory());
    }

    private boolean invalidateDoubleChest(Inventory inventory) {
        if (!(inventory instanceof DoubleChestInventory)) return false;
        if (lazo != null) {
            DoubleChestInventory chest = (DoubleChestInventory) inventory;
            invalidateApprovals(chest.getLeftSide().getLocation());
            invalidateApprovals(chest.getRightSide().getLocation());
        }
        return true;
    }

    private void invalidateChest(Block chest) {
        invalidateApprovals(chest.getLocation());
        invalidateApprovals(access.chestPartner(chest));
    }

    private void invalidateApprovals(Location chest) {
        if (chest == null || chest.getWorld() == null) return;
        World world = chest.getWorld();
        int x = chest.getBlockX(), y = chest.getBlockY() - 1, z = chest.getBlockZ();
        Runnable clear = () -> {
            if (!world.isChunkLoaded(x >> 4, z >> 4)) return;
            Playback playback = playback(world.getBlockAt(x, y, z));
            if (playback != null) playback.recordings.clear();
        };
        if (tasks.owns(chest)) clear.run();
        else tasks.region(world, x >> 4, z >> 4, clear);
    }

    private void scheduleDiscovery(Block placed) {
        Block box = JukeboxAccess.isChest(placed.getType()) ? placed.getRelative(BlockFace.DOWN) : placed;
        if (box.getType() != Material.JUKEBOX || tracked(box)) return;
        World world = box.getWorld();
        int x = box.getX() >> 4, z = box.getZ() >> 4;
        tasks.region(world, x, z, () -> {
            if (world.isChunkLoaded(x, z) && box.getType() == Material.JUKEBOX) track(box.getChunk(), box, false);
        });
    }

    private void scheduleScan(Chunk chunk) {
        tasks.region(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> scan(chunk));
    }

    private void scan(Chunk chunk) {
        if (!chunk.isLoaded()) return;
        for (BlockState state : chunk.getTileEntities()) {
            if (state instanceof Jukebox) track(chunk, state.getBlock(), true);
        }
    }

    private boolean owns(Chunk chunk) {
        return tasks.owns(new Location(chunk.getWorld(), chunk.getX() << 4, 0, chunk.getZ() << 4));
    }

    private boolean tracked(Block block) {
        return playback(block) != null;
    }

    private Playback playback(Block block) {
        TrackedChunk chunk = trackedChunk(block);
        return chunk == null ? null : chunk.boxes.get(block);
    }

    private TrackedChunk trackedChunk(Block block) {
        Map<Long, TrackedChunk> chunks = worlds.get(block.getWorld().getUID());
        return chunks == null ? null : chunks.get(chunkKey(block.getX() >> 4, block.getZ() >> 4));
    }

    private Playback track(Chunk chunk, Block block, boolean loaded) {
        Jukebox box = liveJukebox(block);
        if (box == null) return null;
        Map<Long, TrackedChunk> chunks = worlds.computeIfAbsent(chunk.getWorld().getUID(), ignored -> new ConcurrentHashMap<>());
        TrackedChunk tracked = chunks.computeIfAbsent(chunkKey(chunk), ignored -> new TrackedChunk(chunk, chunks));
        return tracked.boxes.compute(block, (ignored, previous) -> livePlayback(chunk, box, previous, loaded));
    }

    private Jukebox liveJukebox(Block block) {
        if (block.getType() != Material.JUKEBOX) return null;
        BlockState state = access.state(block);
        return state instanceof Jukebox ? (Jukebox) state : null;
    }

    private Playback livePlayback(Chunk chunk, Jukebox box, Playback previous, boolean loaded) {
        Object identity = access.jukeboxIdentity(box);
        if (previous != null) {
            if (identity == null || previous.boxIdentity == null || identity == previous.boxIdentity) return previous;
            positions.clear(chunk, box.getBlock());
        }
        return new Playback(chunk, box, identity, previous == null && loaded);
    }

    private void savePositions() {
        if (positions == null) return;
        try {
            positions.saveLegacy();
        } catch (IOException failure) {
            getLogger().log(Level.WARNING, "Could not save playlist positions", failure);
        }
    }

    private static long chunkKey(Chunk chunk) { return chunkKey(chunk.getX(), chunk.getZ()); }

    private static long chunkKey(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }

    private final class TrackedChunk {
        final Chunk chunk;
        final Map<Long, TrackedChunk> chunks;
        final Map<Block, Playback> boxes = new HashMap<>();
        final Runnable cancel;

        TrackedChunk(Chunk chunk, Map<Long, TrackedChunk> chunks) {
            this.chunk = chunk;
            this.chunks = chunks;
            cancel = tasks.repeat(chunk.getWorld(), chunk.getX(), chunk.getZ(), this::tick);
        }

        void cancel() { cancel.run(); }

        void beforeSave() {
            if (owns(chunk) && chunk.isLoaded()) refresh(false);
        }

        void tick() {
            if (chunk.isLoaded()) {
                refresh(true);
                if (!boxes.isEmpty()) return;
            }
            chunks.remove(chunkKey(chunk), this);
            cancel();
        }

        void refresh(boolean tick) {
            Iterator<Map.Entry<Block, Playback>> iterator = boxes.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Block, Playback> entry = iterator.next();
                Block block = entry.getKey();
                Jukebox box = liveJukebox(block);
                if (box == null) {
                    positions.clear(chunk, block);
                    iterator.remove();
                    continue;
                }
                Playback playback = livePlayback(chunk, box, entry.getValue(), false);
                entry.setValue(playback);
                if (tick) playback.tick(chunk, box);
                else playback.reconcileRecord(chunk, box, false);
            }
        }
    }

    private final class Playback {
        private static final int REPEATING = -2;
        private final RecordingApprovals recordings = new RecordingApprovals(access);
        private final Object boxIdentity;
        private int position;
        private ItemStack previous;
        private Object previousStack;
        private boolean attached;
        private boolean resume;
        private boolean advancePending;
        private UUID recordingPlayer;
        private Object recordingEntry;
        private Object recordingEntryStack;
        private long elapsed;

        Playback(Chunk chunk, Jukebox box, Object identity, boolean loaded) {
            boxIdentity = identity;
            Block block = box.getBlock();
            position = positions.load(chunk, block);
            ItemStack current = access.record(box);
            reset(current, access.recordIdentity(box, current));
            boolean recording = access.isRecording(previous);
            advancePending = positions.restorePending(chunk, block, recording && previousStack == null ? null : previous);
            resume = loaded && !access.nativeTiming() && previous != null && !recording && !advancePending;
        }

        void tick(Chunk chunk, Jukebox box) {
            Block block = box.getBlock();
            if (block.getY() + 1 >= block.getWorld().getMaxHeight()) return;
            Block chest = block.getRelative(BlockFace.UP);
            boolean single = access.isSingleChest(chest);
            if (!single) {
                detach();
                if (!advancePending) return;
            }
            if (!recordings.isEmpty()) {
                BlockState chestState = access.state(chest);
                if (chestState instanceof Chest) recordings.update(((Chest) chestState).getBlockInventory());
            }
            boolean reattached = !attached;
            ItemStack current = reconcileRecord(chunk, box, reattached);
            if (!single) return;
            attached = true;
            if (current == null) {
                advance(chunk, box, chest, null);
            } else if (access.isRecording(current)) {
                if (lazo == null || !lazo.ready()) {
                    if (advancePending) advance(chunk, box, chest, current);
                    return;
                }
                Object entry = lazo.activeEntry(block, current);
                if (entry != null) {
                    if (rememberEntry(entry)) recordingPlayer = null;
                    if (recordingEntryStack == previousStack) setPending(chunk, block, null);
                    elapsed = 0;
                } else {
                    if (elapsed == 0 && finished(block)) setPending(chunk, block, current);
                    if (++elapsed >= 20) advance(chunk, box, chest, current);
                }
            } else if (access.nativeTiming()) {
                if (box.isPlaying()) setPending(chunk, block, null);
                else if (reattached && !advancePending && position != -1) access.start(box);
                else advance(chunk, box, chest, current);
            } else {
                long duration = JukeboxAccess.duration(current);
                if (resume && duration > 0) access.start(box);
                resume = false;
                if (advancePending || (duration > 0 && ++elapsed >= duration + 20)) {
                    advance(chunk, box, chest, current);
                }
            }
        }

        private ItemStack reconcileRecord(Chunk chunk, Jukebox box, boolean reattached) {
            ItemStack current = access.record(box);
            Object stack = access.recordIdentity(box, current);
            boolean changed = previousStack != stack || !Objects.equals(previous, current);
            if (changed || reattached) reset(current, stack);
            if (changed || current == null) {
                resume = false;
                Block block = box.getBlock();
                setPending(chunk, block, null);
                if (position == REPEATING) setPosition(chunk, block, -1);
            }
            return current;
        }

        private void reset(ItemStack record, Object stack) {
            previous = record == null ? null : record.clone();
            previousStack = stack;
            recordingPlayer = null;
            if (!access.isRecording(record)) rememberEntry(null);
            elapsed = 0;
        }

        private boolean rememberEntry(Object entry) {
            if (recordingEntry == entry) return false;
            recordingEntry = entry;
            recordingEntryStack = entry == null ? null : previousStack;
            return true;
        }

        private boolean finished(Block block) {
            return lazo != null && previousStack != null && recordingEntryStack == previousStack
                    && lazo.finished(block, recordingEntry);
        }

        private void detach() {
            attached = false;
            recordings.clear();
            recordingPlayer = null;
        }

        private void setPending(Chunk chunk, Block block, ItemStack completed) {
            boolean pending = completed != null;
            if (advancePending == pending) return;
            positions.pending(chunk, block, completed);
            advancePending = pending;
        }

        private void setPosition(Chunk chunk, Block block, int slot) {
            if (position == slot) return;
            position = slot;
            positions.store(chunk, block, slot);
        }

        private boolean canPlayRecording(UUID playerId) {
            Player player = playerId == null ? null : Bukkit.getPlayer(playerId);
            return player != null && tasks.owns(player) && player.isOnline()
                    && player.hasPermission(LazoDiscsPlayback.PLAY_PERMISSION);
        }

        private void advance(Chunk chunk, Jukebox box, Block chestBlock, ItemStack current) {
            Block block = box.getBlock();
            Chest chestState = access.singleChest(chestBlock);
            if (chestState == null) {
                detach();
                return;
            }
            boolean currentRecording = access.isRecording(current);
            if (access.isLocked(chestState)) {
                if (current != null && !currentRecording) setPending(chunk, block, current);
                return;
            }
            Inventory chest = chestState.getBlockInventory();
            boolean canReturn = current == null
                    || current.getAmount() <= Math.min(current.getMaxStackSize(), chest.getMaxStackSize());
            UUID returnedApprover = currentRecording && finished(block) ? recordingPlayer : null;
            if (lazo != null) recordings.update(chest);
            int size = chest.getSize();
            int start = position < -1 || position >= size ? -1 : position;
            for (int offset = 1; canReturn && offset <= size; offset++) {
                int slot = (start + offset) % size;
                ItemStack candidate = chest.getItem(slot);
                if (!access.isDisc(candidate, box.getWorld()) || candidate.getAmount() > candidate.getMaxStackSize()) continue;
                boolean custom = access.isRecording(candidate);
                UUID approver = custom ? recordings.player(slot, candidate) : null;
                ItemStack returned = current == null ? null : current.clone();
                if (!insert(box, candidate, custom, approver)) continue;
                chest.setItem(slot, returned);
                if (lazo != null) recordings.store(slot, chest.getItem(slot), returnedApprover);
                setPosition(chunk, block, slot);
                setPending(chunk, block, null);
                afterInsert(box, candidate, custom, approver);
                return;
            }
            boolean repeated = access.isDisc(current, box.getWorld())
                    && insert(box, current, currentRecording, returnedApprover);
            if (canReturn && (current == null || repeated)) setPosition(chunk, block, repeated ? REPEATING : -1);
            if (repeated) {
                setPending(chunk, block, null);
                afterInsert(box, current, currentRecording, returnedApprover);
            }
        }

        private boolean insert(Jukebox box, ItemStack record, boolean custom, UUID approver) {
            if (custom && (lazo == null || !canPlayRecording(approver) || !lazo.canStart(record))) return false;
            Block block = box.getBlock();
            Runnable clear = custom ? lazo.suppressStart(block) : null;
            try {
                return access.insert(box, record, custom);
            } finally {
                if (clear != null) tasks.region(block.getWorld(), block.getX() >> 4, block.getZ() >> 4, clear);
            }
        }

        private void afterInsert(Jukebox box, ItemStack record, boolean custom, UUID approver) {
            BlockState state = access.state(box.getBlock());
            if (!(state instanceof Jukebox)) return;
            Jukebox installed = (Jukebox) state;
            ItemStack current = access.record(installed);
            reset(current, access.recordIdentity(installed, current));
            if (!record.equals(current)) return;
            recordingPlayer = approver;
            if (lazo != null) {
                lazo.changed(box.getBlock(), custom ? record : null);
                if (custom) rememberEntry(lazo.activeEntry(box.getBlock(), record));
            }
            if (!custom && access.nativeTiming() && !box.isPlaying()) access.start(box);
        }
    }
}
