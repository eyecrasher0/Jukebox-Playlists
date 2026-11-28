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

    RecordingApprovals(JukeboxAccess access) { this.access = access; }

    void update(Inventory chest) {
        approved.entrySet().removeIf(entry -> !entry.getValue().stack.matches(access, chest.getItem(entry.getKey())));
    }

    void deposited(int slot, Stack before, ItemStack actual, UUID player) {
        if (amount(actual) <= amount(before.item)) return;
        Approval previous = approved.get(slot);
        if (before.item == null || previous != null && player.equals(previous.player)
                && previous.stack.handle == before.handle && Objects.equals(previous.stack.item, before.item)) {
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
