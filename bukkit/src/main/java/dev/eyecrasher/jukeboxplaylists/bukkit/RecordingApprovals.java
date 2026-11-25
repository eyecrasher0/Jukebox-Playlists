package dev.eyecrasher.jukeboxplaylists.bukkit;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

/** Associates a recording in a chest slot with the player who put it there. */
final class RecordingApprovals {
    private final JukeboxAccess access;
    private final Map<Integer, Approval> approved = new HashMap<>();
    private final Map<Integer, Deposit> pending = new HashMap<>();

    RecordingApprovals(JukeboxAccess access) { this.access = access; }

    void update(Inventory chest) {
        approved.entrySet().removeIf(entry -> {
            ItemStack actual = chest.getItem(entry.getKey());
            Approval approval = entry.getValue();
            return approval.stack != access.stackIdentity(actual) || !approval.item.equals(actual);
        });
        for (Map.Entry<Integer, Deposit> entry : pending.entrySet()) {
            int slot = entry.getKey();
            ItemStack actual = chest.getItem(slot);
            Deposit expected = entry.getValue();
            if (!JukeboxAccess.empty(actual) && expected.chest.equals(chest) && expected.committed.test(actual)) {
                store(slot, actual, expected.player);
            }
        }
        pending.clear();
    }

    void expect(Inventory chest, int slot, UUID player, Predicate<ItemStack> committed) {
        pending.put(slot, new Deposit(chest, player, committed));
    }

    void expectPlacement(Inventory chest, int slot, ItemStack result, UUID player, BooleanSupplier transferred) {
        ItemStack before = chest.getItem(slot);
        if (amount(result) <= amount(before)) return;
        if (!JukeboxAccess.empty(before) && (!result.isSimilar(before) || !player.equals(player(slot)))) return;
        ItemStack expected = result.clone();
        expect(chest, slot, player, actual -> sameStack(expected, actual) && transferred.getAsBoolean());
    }

    void expectShift(Inventory chest, ItemStack incoming, UUID player, Predicate<ItemStack> sourceMatches) {
        int limit = Math.min(chest.getMaxStackSize(), incoming.getMaxStackSize());
        if (limit <= 0) return;
        ItemStack[] before = chest.getContents();
        int[] added = new int[before.length];
        int remaining = incoming.getAmount();
        if (incoming.getMaxStackSize() > 1) {
            for (int slot = 0; slot < before.length && remaining > 0; slot++) {
                if (JukeboxAccess.empty(before[slot]) || !incoming.isSimilar(before[slot])) continue;
                added[slot] = Math.min(remaining, Math.max(0, limit - amount(before[slot])));
                remaining -= added[slot];
            }
        }
        for (int slot = 0; slot < before.length && remaining > 0; slot++) {
            if (!JukeboxAccess.empty(before[slot])) continue;
            added[slot] = Math.min(remaining, limit);
            remaining -= added[slot];
        }
        ItemStack remainder = withAmount(incoming, remaining);
        for (int slot = 0; slot < before.length; slot++) {
            if (added[slot] == 0) continue;
            ItemStack result = withAmount(incoming, amount(before[slot]) + added[slot]);
            expectPlacement(chest, slot, result, player, () -> sourceMatches.test(remainder));
        }
    }

    boolean expectBundle(Inventory chest, int slot, ItemStack bundle, UUID player, Supplier<ItemStack> cursor,
                         BooleanSupplier committed) {
        List<ItemStack> contents = access.bundleContents(bundle);
        if (contents.isEmpty()) return false;
        if (contents.stream().anyMatch(access::isRecording)) {
            Material type = bundle.getType();
            int count = bundle.getAmount();
            expect(chest, slot, player, actual -> {
                if (!access.isRecording(actual) || !committed.getAsBoolean()) return false;
                ItemStack remainder = cursor.get();
                return amount(remainder) == count && remainder.getType() == type
                        && amount(actual) <= Math.min(actual.getMaxStackSize(), chest.getMaxStackSize())
                        && bundleAmount(contents, actual) - bundleAmount(access.bundleContents(remainder), actual) == amount(actual);
            });
        }
        return true;
    }

    private static int bundleAmount(List<ItemStack> items, ItemStack recording) {
        return items.stream().filter(recording::isSimilar).mapToInt(ItemStack::getAmount).sum();
    }

    static int amount(ItemStack item) { return JukeboxAccess.empty(item) ? 0 : item.getAmount(); }

    static ItemStack withAmount(ItemStack item, int amount) {
        if (amount == 0) return null;
        ItemStack copy = item.clone();
        copy.setAmount(amount);
        return copy;
    }

    static boolean sameStack(ItemStack expected, ItemStack actual) {
        return amount(expected) == amount(actual) && (JukeboxAccess.empty(expected) || expected.isSimilar(actual));
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
        final Inventory chest;
        final UUID player;
        final Predicate<ItemStack> committed;

        Deposit(Inventory chest, UUID player, Predicate<ItemStack> committed) {
            this.chest = chest;
            this.player = player;
            this.committed = committed;
        }
    }
}
