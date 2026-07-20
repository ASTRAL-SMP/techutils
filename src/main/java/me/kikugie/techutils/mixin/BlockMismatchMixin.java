package me.kikugie.techutils.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.kikugie.techutils.feature.verifier.BlockMismatchExtension;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import net.minecraft.inventory.Inventory;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

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

    @ModifyReturnValue(method = "hashCode", at = @At("RETURN"))
    private int techutils$hashInventories(int result, @Local(ordinal = 0) int prime) {
        return prime * result + ((inventories == null) ? 0 : inventories.hashCode());
    }
}
