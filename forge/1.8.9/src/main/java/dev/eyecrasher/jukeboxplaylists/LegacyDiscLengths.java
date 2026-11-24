package dev.eyecrasher.jukeboxplaylists;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.apache.logging.log4j.LogManager;

/** Song metadata for releases before the server had native record durations. */
public final class LegacyDiscLengths {
    private static final Map<String, Long> VANILLA = vanilla();
    private static final LegacySoundLengths SOUNDS = new LegacySoundLengths(LegacyDiscLengths.class.getClassLoader());
    private static final ConcurrentHashMap<String, CompletableFuture<Long>> DISCOVERED = new ConcurrentHashMap<>();
    private static final ExecutorService READER = Executors.newSingleThreadExecutor(task -> {
        Thread thread = new Thread(task, "Jukebox Playlists sound lengths");
        thread.setDaemon(true);
        return thread;
    });

    private LegacyDiscLengths() {}

    public static long ticks(String sound, String item) {
        Long vanilla = VANILLA.get(sound);
        if (vanilla != null) return vanilla;
        return DISCOVERED.computeIfAbsent(sound, id -> CompletableFuture.supplyAsync(() -> {
            long ticks = SOUNDS.ticks(id);
            if (ticks <= 0) LogManager.getLogger("Jukebox Playlists").warn(
                "Cannot determine length of {} ({}): no readable bundled OGG Vorbis audio.", item, id);
            return ticks;
        }, READER)).getNow(-1L);
    }

    private static Map<String, Long> vanilla() {
        Map<String, Long> lengths = new HashMap<>();
        String[] discs = {"13", "cat", "blocks", "chirp", "far", "mall", "mellohi",
                          "stal", "strad", "ward", "11", "wait"};
        int[] seconds = {178, 185, 345, 185, 174, 197, 96, 150, 188, 251, 71, 238};
        for (int i = 0; i < discs.length; i++) {
            lengths.put("minecraft:records." + discs[i], seconds[i] * 20L);
        }
        return lengths;
    }
}
