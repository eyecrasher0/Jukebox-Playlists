package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.DoubleChestInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

public final class JukeboxPlaylists extends JavaPlugin implements Listener {
    private final Map<UUID, Map<Long, TrackedChunk>> worlds = new ConcurrentHashMap<>();
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
        for (Map<Long, TrackedChunk> chunks : worlds.values()) {
            for (TrackedChunk chunk : chunks.values()) chunk.cancel();
        }
        worlds.clear();
        savePositions();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkLoaded(ChunkLoadEvent event) { scheduleScan(event.getChunk()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void chunkUnloaded(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        Map<Long, TrackedChunk> chunks = worlds.get(chunk.getWorld().getUID());
        if (chunks == null) return;
        TrackedChunk tracking = chunks.remove(chunkKey(chunk));
        if (tracking != null) tracking.cancel();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void worldSaved(WorldSaveEvent event) { savePositions(); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void worldUnloaded(WorldUnloadEvent event) {
        Map<Long, TrackedChunk> chunks = worlds.remove(event.getWorld().getUID());
        if (chunks != null) for (TrackedChunk chunk : chunks.values()) chunk.cancel();
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

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void physics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.JUKEBOX && !tracked(block)) scheduleDiscovery(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opened(InventoryOpenEvent event) {
        Inventory inventory = event.getInventory();
        if (invalidateDoubleChest(inventory)) return;
        InventoryHolder holder = inventory.getHolder();
        if (holder instanceof Chest) scheduleDiscovery(((Chest) holder).getBlock());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void clicked(InventoryClickEvent event) {
        if (lazo == null) return;
        Inventory chest = event.getView().getTopInventory();
        Playback playback = playbackFor(chest);
        InventoryAction action = event.getAction();
        if (playback == null || action == InventoryAction.NOTHING) return;
        RecordingApprovals recordings = playback.recordings;
        recordings.update(chest);
        HumanEntity player = event.getWhoClicked();
        int slot = event.getRawSlot();
        boolean chestSlot = slot >= 0 && slot < chest.getSize();
        if (!(player instanceof Player)) return;
        UUID playerId = player.getUniqueId();
        boolean fromBundle = "PLACE_FROM_BUNDLE".equals(action.name())
                || (action == InventoryAction.PLACE_ONE && event.isRightClick());
        if (fromBundle && chestSlot && JukeboxAccess.empty(event.getCurrentItem())) {
            for (ItemStack item : access.bundleContents(event.getCursor())) {
                if (access.isRecording(item)) recordings.expect(chest, slot, item, playerId);
            }
        }
        ItemStack incoming;
        switch (action) {
            case PLACE_ALL:
            case PLACE_ONE:
            case PLACE_SOME:
            case SWAP_WITH_CURSOR:
                incoming = event.getCursor();
                break;
            case HOTBAR_SWAP:
            case HOTBAR_MOVE_AND_READD:
                int hotbar = event.getHotbarButton();
                if (hotbar >= 0) incoming = player.getInventory().getItem(hotbar);
                else if ("SWAP_OFFHAND".equals(event.getClick().name())) incoming = player.getInventory().getItemInOffHand();
                else return;
                break;
            case MOVE_TO_OTHER_INVENTORY:
                incoming = event.getCurrentItem();
                if (!chestSlot && slot >= chest.getSize() && access.isRecording(incoming)) {
                    for (int index = 0; index < chest.getSize(); index++) {
                        recordings.expect(chest, index, incoming, playerId);
                    }
                }
                return;
            default:
                return;
        }
        if (chestSlot && access.isRecording(incoming)) recordings.expect(chest, slot, incoming, playerId);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void dragged(InventoryDragEvent event) {
        if (lazo == null) return;
        Inventory chest = event.getView().getTopInventory();
        Playback playback = playbackFor(chest);
        if (playback == null) return;
        playback.recordings.update(chest);
        HumanEntity player = event.getWhoClicked();
        for (Map.Entry<Integer, ItemStack> change : event.getNewItems().entrySet()) {
            int slot = change.getKey();
            if (slot < 0 || slot >= chest.getSize()) continue;
            if (player instanceof Player && access.isRecording(change.getValue())) {
                playback.recordings.expect(chest, slot, change.getValue(), player.getUniqueId());
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void moved(InventoryMoveItemEvent event) {
        if (lazo == null) return;
        invalidateDoubleChest(event.getSource());
        invalidateDoubleChest(event.getDestination());
    }

    private Playback playbackFor(Inventory chest) {
        if (invalidateDoubleChest(chest)) return null;
        InventoryHolder holder = chest.getHolder();
        if (!(holder instanceof Chest)) return null;
        Block top = ((Chest) holder).getBlock();
        if (!tasks.owns(top.getLocation())) return null;
        if (!access.isSingleChest(top)) return null;
        Block box = top.getRelative(BlockFace.DOWN);
        if (box.getType() != Material.JUKEBOX) return null;
        return track(box.getChunk(), box);
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
            if (world.isChunkLoaded(x, z) && box.getType() == Material.JUKEBOX) track(box.getChunk(), box);
        });
    }

    private void scheduleScan(Chunk chunk) {
        tasks.region(chunk.getWorld(), chunk.getX(), chunk.getZ(), () -> {
            if (!chunk.isLoaded()) return;
            for (BlockState state : chunk.getTileEntities()) {
                if (state instanceof Jukebox) track(chunk, state.getBlock());
            }
        });
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

    private Playback track(Chunk chunk, Block block) {
        Map<Long, TrackedChunk> chunks = worlds.computeIfAbsent(chunk.getWorld().getUID(), ignored -> new ConcurrentHashMap<>());
        TrackedChunk tracked = chunks.computeIfAbsent(chunkKey(chunk), ignored -> new TrackedChunk(chunk, chunks));
        return tracked.boxes.computeIfAbsent(block, ignored -> new Playback(chunk, block));
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

        void tick() {
            if (chunk.isLoaded()) {
                Iterator<Map.Entry<Block, Playback>> iterator = boxes.entrySet().iterator();
                while (iterator.hasNext()) {
                    Map.Entry<Block, Playback> entry = iterator.next();
                    Block block = entry.getKey();
                    if (block.getType() != Material.JUKEBOX) {
                        positions.clear(chunk, block);
                        iterator.remove();
                        continue;
                    }
                    entry.getValue().tick(chunk, block);
                }
                if (!boxes.isEmpty()) return;
            }
            chunks.remove(chunkKey(chunk), this);
            cancel();
        }
    }

    private final class Playback {
        private static final int REPEATING = -2;
        private final RecordingApprovals recordings = new RecordingApprovals(access);
        private int position;
        private ItemStack previous;
        private boolean attached;
        private boolean advancePending;
        private UUID recordingPlayer;
        private Object recordingEntry;
        private long elapsed;

        Playback(Chunk chunk, Block block) {
            position = positions.load(chunk, block);
            advancePending = positions.pending(chunk, block);
            BlockState snapshot = access.state(block);
            if (snapshot instanceof Jukebox) reset(access.record((Jukebox) snapshot));
        }

        void tick(Chunk chunk, Block block) {
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
            BlockState snapshot = access.state(block);
            if (!(snapshot instanceof Jukebox)) return;
            Jukebox box = (Jukebox) snapshot;
            ItemStack current = access.record(box);
            boolean changed = !Objects.equals(previous, current);
            if (changed || reattached) reset(current);
            if (changed || current == null) {
                setPending(chunk, block, false);
                if (position == REPEATING) setPosition(chunk, block, -1);
            }
            if (!single) return;
            attached = true;
            if (current == null) {
                advance(chunk, box, chest, null);
            } else if (access.isRecording(current)) {
                if (lazo == null || !lazo.ready()) return;
                Object entry = lazo.activeEntry(block, current);
                if (entry != null) {
                    if (entry != recordingEntry) recordingPlayer = null;
                    recordingEntry = entry;
                    setPending(chunk, block, false);
                    elapsed = 0;
                } else {
                    if (elapsed == 0 && lazo.finished(block, recordingEntry)) setPending(chunk, block, true);
                    if (++elapsed >= 20) advance(chunk, box, chest, current);
                }
            } else if (access.nativeTiming()) {
                if (box.isPlaying()) setPending(chunk, block, false);
                else if (reattached && !advancePending && position != -1) access.start(box);
                else advance(chunk, box, chest, current);
            } else {
                if (reattached && !advancePending && position != -1) access.start(box);
                long duration = JukeboxAccess.duration(current);
                if (advancePending || (duration > 0 && ++elapsed >= duration + 20)) {
                    advance(chunk, box, chest, current);
                }
            }
        }

        private void reset(ItemStack record) {
            previous = record == null ? null : record.clone();
            recordingPlayer = null;
            recordingEntry = null;
            elapsed = 0;
        }

        private void detach() {
            attached = false;
            recordings.clear();
            recordingPlayer = null;
        }

        private void setPending(Chunk chunk, Block block, boolean pending) {
            if (advancePending == pending) return;
            advancePending = pending;
            positions.pending(chunk, block, pending);
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
            if (access.isLocked(chestState)) {
                if (current != null) setPending(chunk, block, true);
                return;
            }
            setPending(chunk, block, false);
            Inventory chest = chestState.getBlockInventory();
            boolean canReturn = current == null
                    || current.getAmount() <= Math.min(current.getMaxStackSize(), chest.getMaxStackSize());
            boolean currentRecording = access.isRecording(current);
            UUID returnedApprover = currentRecording && lazo != null && lazo.finished(block, recordingEntry)
                    ? recordingPlayer : null;
            if (lazo != null) recordings.update(chest);
            int size = chest.getSize();
            int start = position < -1 || position >= size ? -1 : position;
            for (int offset = 1; canReturn && offset <= size; offset++) {
                int slot = (start + offset) % size;
                ItemStack candidate = chest.getItem(slot);
                if (!access.isDisc(candidate, box.getWorld()) || candidate.getAmount() > candidate.getMaxStackSize()) continue;
                boolean custom = access.isRecording(candidate);
                UUID approver = custom ? recordings.player(slot) : null;
                ItemStack returned = current == null ? null : current.clone();
                if (!insert(box, candidate, custom, approver)) continue;
                chest.setItem(slot, returned);
                if (lazo != null) recordings.store(slot, returned, returnedApprover);
                setPosition(chunk, block, slot);
                afterInsert(box, candidate, custom, approver);
                return;
            }
            boolean repeated = access.isDisc(current, box.getWorld())
                    && insert(box, current, currentRecording, returnedApprover);
            if (canReturn) setPosition(chunk, block, repeated ? REPEATING : -1);
            if (repeated) afterInsert(box, current, currentRecording, returnedApprover);
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
            reset(record);
            recordingPlayer = approver;
            if (lazo != null) {
                lazo.changed(box.getBlock(), custom ? record : null);
                if (custom) recordingEntry = lazo.activeEntry(box.getBlock(), record);
            }
            if (!custom && access.nativeTiming() && !box.isPlaying()) access.start(box);
        }
    }
}
