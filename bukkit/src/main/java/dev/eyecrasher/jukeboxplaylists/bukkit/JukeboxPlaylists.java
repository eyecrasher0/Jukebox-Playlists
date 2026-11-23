package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.io.IOException;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.Jukebox;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPhysicsEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldSaveEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.Inventory;
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
    public void placed(BlockPlaceEvent event) { scheduleDiscovery(event.getBlockPlaced()); }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void physics(BlockPhysicsEvent event) {
        Block block = event.getBlock();
        if (block.getType() == Material.JUKEBOX && !tracked(block)) scheduleDiscovery(block);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void opened(InventoryOpenEvent event) {
        if (event.getInventory().getHolder() instanceof org.bukkit.block.Chest) {
            scheduleDiscovery(((org.bukkit.block.Chest) event.getInventory().getHolder()).getBlock());
        }
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
        Map<Long, TrackedChunk> chunks = worlds.get(block.getWorld().getUID());
        if (chunks == null) return false;
        TrackedChunk chunk = chunks.get(chunkKey(block.getX() >> 4, block.getZ() >> 4));
        return chunk != null && chunk.boxes.containsKey(block);
    }

    private void track(Chunk chunk, Block block) {
        Map<Long, TrackedChunk> chunks = worlds.computeIfAbsent(chunk.getWorld().getUID(), ignored -> new ConcurrentHashMap<>());
        TrackedChunk tracked = chunks.computeIfAbsent(chunkKey(chunk), ignored -> new TrackedChunk(chunk, chunks));
        tracked.boxes.computeIfAbsent(block, ignored -> new Playback(positions.load(chunk, block)));
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
                        positions.store(chunk, block, -1);
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
        private int cursor;
        private ItemStack previous;
        private boolean attached;
        private boolean customStarted;
        private long elapsed;

        Playback(int cursor) { this.cursor = cursor; }

        void tick(Chunk chunk, Block block) {
            Inventory chest = block.getY() + 1 < block.getWorld().getMaxHeight()
                    ? access.chest(block.getRelative(BlockFace.UP)) : null;
            if (chest == null) {
                attached = false;
                return;
            }
            BlockState snapshot = block.getState();
            if (!(snapshot instanceof Jukebox)) return;
            Jukebox box = (Jukebox) snapshot;
            ItemStack current = access.record(box);
            boolean changed = !java.util.Objects.equals(previous, current);
            if (changed || !attached) reset(current);
            boolean reattached = !attached;
            attached = true;
            if (current == null) {
                advance(chunk, box, chest, null);
            } else if (lazo != null && lazo.custom(current)) {
                if (!lazo.ready()) return;
                if (!customStarted) {
                    if (!lazo.playing(block) && !lazo.ended(block)) lazo.changed(block, false);
                    customStarted = true;
                }
                if (lazo.playing(block)) elapsed = 0;
                else if (++elapsed >= 20) advance(chunk, box, chest, current);
            } else if (access.nativeTiming()) {
                if (!box.isPlaying()) {
                    if (reattached && cursor >= 0) access.start(box);
                    else advance(chunk, box, chest, current);
                }
            } else {
                if (reattached && cursor >= 0) access.start(box);
                long duration = JukeboxAccess.duration(current);
                if (duration > 0 && ++elapsed >= duration + 20) advance(chunk, box, chest, current);
            }
        }

        private void reset(ItemStack record) {
            previous = record == null ? null : record.clone();
            customStarted = false;
            elapsed = 0;
        }

        private void advance(Chunk chunk, Jukebox box, Inventory chest, ItemStack current) {
            if (current != null && current.getAmount() != 1) return;
            int size = chest.getSize();
            int start = cursor < -1 || cursor >= size ? -1 : cursor;
            for (int offset = 1; offset <= size; offset++) {
                int slot = (start + offset) % size;
                ItemStack candidate = chest.getItem(slot);
                if (!access.isDisc(candidate) || candidate.getAmount() != 1) continue;
                boolean custom = lazo != null && lazo.custom(candidate);
                ItemStack returned = current == null ? null : current.clone();
                if (!insert(box, candidate, custom)) return;
                chest.setItem(slot, returned);
                cursor = slot;
                positions.store(chunk, box.getBlock(), slot);
                afterInsert(box, candidate, custom);
                return;
            }
            if (cursor != -1) {
                cursor = -1;
                positions.store(chunk, box.getBlock(), -1);
            }
            if (access.isDisc(current)) {
                boolean custom = lazo != null && lazo.custom(current);
                if (insert(box, current, custom)) afterInsert(box, current, custom);
            }
        }

        private boolean insert(Jukebox box, ItemStack record, boolean custom) {
            Block block = box.getBlock();
            if (custom && !lazo.ready()) return false;
            Runnable clear = custom ? lazo.suppressStart(block) : null;
            try {
                return access.insert(box, record, custom);
            } finally {
                if (clear != null) tasks.region(block.getWorld(), block.getX() >> 4, block.getZ() >> 4, clear);
            }
        }

        private void afterInsert(Jukebox box, ItemStack record, boolean custom) {
            reset(record);
            if (lazo != null) {
                lazo.changed(box.getBlock(), custom);
                customStarted = custom;
            }
            if (!custom && access.nativeTiming() && !box.isPlaying()) access.start(box);
        }
    }
}
