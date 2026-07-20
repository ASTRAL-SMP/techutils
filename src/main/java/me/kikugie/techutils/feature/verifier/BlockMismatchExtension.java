package me.kikugie.techutils.feature.verifier;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.inventory.Inventory;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;

/**
 * Attaches the expected/found container pair to a Litematica {@code BlockMismatch} entry so the
 * verifier list can render the two inventories side by side.
 */
public interface BlockMismatchExtension<InventoryBE extends BlockEntity & Inventory> {
    void setInventories$techutils(Pair<InventoryBE, InventoryBE> inventories);

    @Nullable
    Pair<InventoryBE, InventoryBE> getInventories$techutils();
}
