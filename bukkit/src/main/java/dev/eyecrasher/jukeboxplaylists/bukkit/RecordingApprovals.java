package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Associates a recording in a chest slot with the player who put it there. */
final class RecordingApprovals {
    private final JukeboxAccess access;
    private final Map<Integer, Approval> approved = new HashMap<>();
    private final Map<Integer, Approval> pending = new HashMap<>();

    RecordingApprovals(JukeboxAccess access) { this.access = access; }

    void update(Inventory chest) {
        approved.entrySet().removeIf(entry -> !entry.getValue().item.equals(chest.getItem(entry.getKey())));
        for (Map.Entry<Integer, Approval> entry : pending.entrySet()) {
            int slot = entry.getKey();
            Approval expected = entry.getValue();
            ItemStack actual = chest.getItem(slot);
            if (!JukeboxAccess.empty(actual) && expected.item.isSimilar(actual)
                    && (!Objects.equals(expected.before, actual)
                        || (expected.replacement != null && expected.replacement == access.stackIdentity(actual)))) {
                store(slot, actual, expected.player);
            }
        }
        pending.clear();
    }

    void expect(Inventory chest, int slot, ItemStack incoming, UUID player) {
        ItemStack previous = chest.getItem(slot);
        Object replacement = Objects.equals(previous, incoming) ? access.stackIdentity(incoming) : null;
        if (replacement != null && replacement == access.stackIdentity(previous)) replacement = null;
        pending.put(slot, new Approval(incoming.clone(), player, previous == null ? null : previous.clone(), replacement));
    }

    UUID player(int slot) {
        Approval approval = approved.get(slot);
        return approval == null ? null : approval.player;
    }

    void store(int slot, ItemStack item, UUID player) {
        if (player == null || JukeboxAccess.empty(item)) approved.remove(slot);
        else approved.put(slot, new Approval(item.clone(), player, null, null));
    }

    boolean isEmpty() { return approved.isEmpty() && pending.isEmpty(); }

    void clear() {
        approved.clear();
        pending.clear();
    }

    private static final class Approval {
        final ItemStack item;
        final UUID player;
        final ItemStack before;
        final Object replacement;

        Approval(ItemStack item, UUID player, ItemStack before, Object replacement) {
            this.item = item;
            this.player = player;
            this.before = before;
            this.replacement = replacement;
        }
    }
}
