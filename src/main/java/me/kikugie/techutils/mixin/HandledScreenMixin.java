package me.kikugie.techutils.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import me.kikugie.techutils.config.Configs;
import me.kikugie.techutils.feature.inverifier.ContainerOverlay;
import me.kikugie.techutils.render.gui.LitematicInventoryRenderer;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HandledScreen.class, priority = 900)
public abstract class HandledScreenMixin {
    @Unique
    @Nullable
    private Inventory techutils$schematicInv = null;
    @Unique
    @Nullable
    private LitematicInventoryRenderer techutils$renderer = null;

    @Unique
    @Nullable
    private LitematicInventoryRenderer techutils$renderer() {
        if (!Configs.LitematicConfigs.INVENTORY_SCREEN_OVERLAY.getBooleanValue()) {
            return null;
        }
        Inventory inv = ContainerOverlay.getSchematicInventory();
        if (inv == null) {
            techutils$schematicInv = null;
            techutils$renderer = null;
            return null;
        }
        if (inv != techutils$schematicInv || techutils$renderer == null) {
            techutils$schematicInv = inv;
            techutils$renderer = new LitematicInventoryRenderer(inv);
        }
        return techutils$renderer;
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void resetOverlay(CallbackInfo ci) {
        ContainerOverlay.close();
    }

    @ModifyExpressionValue(method = "drawSlot", at = @At(value = "INVOKE", target = "Lnet/minecraft/screen/slot/Slot;getStack()Lnet/minecraft/item/ItemStack;", ordinal = 0))
    private ItemStack renderItem(ItemStack stack, @Local(argsOnly = true) MatrixStack matrices, @Local(argsOnly = true) Slot slot) {
        LitematicInventoryRenderer renderer = techutils$renderer();
        return renderer == null ? stack : renderer.drawStack(matrices, slot, stack);
    }
}
