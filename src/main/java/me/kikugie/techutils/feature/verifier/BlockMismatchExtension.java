package me.kikugie.techutils.feature.verifier;

import net.minecraft.inventory.Inventory;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

/**
 * Attaches the expected/found container contents to a Litematica {@code BlockMismatch} entry so the
 * verifier list can render the two inventories side by side.
 */
public interface BlockMismatchExtension {
    void setInventories$techutils(Pair<Inventory, Inventory> inventories);

    @Nullable
    Pair<Inventory, Inventory> getInventories$techutils();
}
