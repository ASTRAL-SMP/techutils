package me.kikugie.techutils.mixin;

import me.kikugie.techutils.feature.verifier.BlockMismatchExtension;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import net.minecraft.inventory.Inventory;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

@Mixin(value = BlockMismatch.class, remap = false)
public class BlockMismatchMixin implements BlockMismatchExtension {
    @Unique
    @Nullable
    private Pair<Inventory, Inventory> inventories;

    @Override
    public void setInventories$techutils(Pair<Inventory, Inventory> inventories) {
        this.inventories = inventories;
    }

    @Override
    @Nullable
    public Pair<Inventory, Inventory> getInventories$techutils() {
        return inventories;
    }
}
