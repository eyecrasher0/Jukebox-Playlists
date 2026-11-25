package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Associates a recording in a chest slot with the player who put it there. */
final class RecordingApprovals {
    private final JukeboxAccess access;
    private final Map<Integer, Approval> approved = new HashMap<>();
    private final Map<Integer, List<Deposit>> pending = new HashMap<>();

    RecordingApprovals(JukeboxAccess access) { this.access = access; }

    void update(Inventory chest) {
        approved.entrySet().removeIf(entry -> {
            ItemStack actual = chest.getItem(entry.getKey());
            Approval approval = entry.getValue();
            return approval.stack != access.stackIdentity(actual) || !approval.item.equals(actual);
        });
        for (Map.Entry<Integer, List<Deposit>> entry : pending.entrySet()) {
            int slot = entry.getKey();
            ItemStack actual = chest.getItem(slot);
            if (JukeboxAccess.empty(actual)) continue;
            for (Deposit expected : entry.getValue()) {
                if (expected.item.isSimilar(actual) && (!Objects.equals(expected.before, actual)
                        || (expected.replacement != null && expected.replacement == access.stackIdentity(actual)))) {
                    store(slot, actual, expected.player);
                    break;
                }
            }
        }
        pending.clear();
    }

    void expect(Inventory chest, int slot, ItemStack incoming, UUID player) {
        ItemStack previous = chest.getItem(slot);
        Object replacement = Objects.equals(previous, incoming) ? access.stackIdentity(incoming) : null;
        if (replacement != null && replacement == access.stackIdentity(previous)) replacement = null;
        pending.computeIfAbsent(slot, ignored -> new ArrayList<>())
                .add(new Deposit(incoming.clone(), player, previous == null ? null : previous.clone(), replacement));
    }

    UUID player(int slot) {
        Approval approval = approved.get(slot);
        return approval == null ? null : approval.player;
    }

    void store(int slot, ItemStack item, UUID player) {
        Object stack = player == null || JukeboxAccess.empty(item) ? null : access.stackIdentity(item);
        if (stack == null) approved.remove(slot);
        else approved.put(slot, new Approval(item.clone(), player, stack));
    }

    boolean isEmpty() { return approved.isEmpty() && pending.isEmpty(); }

    void clear() {
        approved.clear();
        pending.clear();
    }

    private static final class Approval {
        final ItemStack item;
        final UUID player;
        final Object stack;

        Approval(ItemStack item, UUID player, Object stack) {
            this.item = item;
            this.player = player;
            this.stack = stack;
        }
    }

    private static final class Deposit {
        final ItemStack item;
        final UUID player;
        final ItemStack before;
        final Object replacement;

        Deposit(ItemStack item, UUID player, ItemStack before, Object replacement) {
            this.item = item;
            this.player = player;
            this.before = before;
            this.replacement = replacement;
        }
    }
}
