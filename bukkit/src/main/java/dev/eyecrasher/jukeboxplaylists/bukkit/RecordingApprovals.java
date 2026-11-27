package dev.eyecrasher.jukeboxplaylists.bukkit;

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

    RecordingApprovals(JukeboxAccess access) { this.access = access; }

    void update(Inventory chest) {
        approved.entrySet().removeIf(entry -> !entry.getValue().stack.matches(access, chest.getItem(entry.getKey())));
    }

    void transfer(Inventory chest, int first, int end, List<ItemStack> sources, UUID player, boolean swap, Runnable move) {
        update(chest);
        Stack[] before = new Stack[end - first];
        for (int slot = first; slot < end; slot++) before[slot - first] = new Stack(access, chest.getItem(slot));
        move.run();
        for (int slot = first; slot < end; slot++) {
            ItemStack actual = chest.getItem(slot);
            if (!JukeboxAccess.empty(actual) && sources.stream().anyMatch(actual::isSimilar)) {
                deposited(slot, before[slot - first], actual, player, swap);
            }
        }
    }

    void deposited(int slot, Stack before, ItemStack actual, UUID player, boolean swap) {
        if (JukeboxAccess.empty(actual) || before.matches(access, actual)) return;
        Approval previous = approved.get(slot);
        if (swap && before.handle != access.stackIdentity(actual)
                || amount(actual) > amount(before.item) && (before.item == null
                || before.item.isSimilar(actual) && previous != null && player.equals(previous.player))) {
            store(slot, actual, player);
        }
    }

    UUID player(int slot, ItemStack item) {
        Approval approval = approved.get(slot);
        return approval != null && approval.stack.matches(access, item) ? approval.player : null;
    }

    void store(int slot, ItemStack item, UUID player) {
        Stack stack = new Stack(access, item);
        if (player == null || stack.handle == null) approved.remove(slot);
        else approved.put(slot, new Approval(player, stack));
    }

    boolean isEmpty() { return approved.isEmpty(); }

    void clear() { approved.clear(); }

    static int amount(ItemStack item) { return JukeboxAccess.empty(item) ? 0 : item.getAmount(); }

    static final class Stack {
        final ItemStack item;
        final Object handle;

        Stack(JukeboxAccess access, ItemStack item) {
            this.item = JukeboxAccess.empty(item) ? null : item.clone();
            this.handle = this.item == null ? null : access.stackIdentity(item);
        }

        boolean matches(JukeboxAccess access, ItemStack actual) {
            if (JukeboxAccess.empty(actual)) return item == null;
            return handle == access.stackIdentity(actual) && Objects.equals(item, actual);
        }
    }

    private static final class Approval {
        final UUID player;
        final Stack stack;

        Approval(UUID player, Stack stack) {
            this.player = player;
            this.stack = stack;
        }
    }
}
