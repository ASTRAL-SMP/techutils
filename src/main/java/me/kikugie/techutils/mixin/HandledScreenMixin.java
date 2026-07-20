package me.kikugie.techutils.mixin;

import me.kikugie.techutils.feature.inverifier.VerifierRecorder;
import me.kikugie.techutils.render.gui.LitematicInventoryRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = HandledScreen.class, priority = 900)
public abstract class HandledScreenMixin extends Screen {
    @Shadow
    protected int x;
    @Shadow
    protected int y;

    private Slot renderSlot;
    @Nullable
    private VerifierRecorder.Entry litematicEntry = null;
    @Nullable
    private LitematicInventoryRenderer litematicItemRenderer = null;

    protected HandledScreenMixin(Text title) {
        super(title);
    }

    /**
     * Resolves the renderer lazily each frame: on a server the container contents arrive in a
     * separate packet after the screen is already open, so the active recorder entry (and its
     * schematic inventory) may only appear a few ticks later.
     */
    @Nullable
    private LitematicInventoryRenderer techutils$renderer() {
        if (!me.kikugie.techutils.config.Configs.LitematicConfigs.INVENTORY_SCREEN_OVERLAY.getBooleanValue()) {
            return null;
        }
        VerifierRecorder.Entry entry = VerifierRecorder.getActive();
        if (entry == null || entry.schematicInv == null) {
            litematicEntry = null;
            litematicItemRenderer = null;
            return null;
        }
        if (entry != litematicEntry || litematicItemRenderer == null) {
            litematicEntry = entry;
            litematicItemRenderer = new LitematicInventoryRenderer(entry.schematicInv);
        }
        return litematicItemRenderer;
    }

    @Inject(method = "close", at = @At("TAIL"))
    private void resetRecorder(CallbackInfo ci) {
        VerifierRecorder.close();
    }

    @Inject(method = "drawSlot", at = @At("HEAD"))
    private void saveSlot(MatrixStack stack, Slot slot, CallbackInfo ci) {
        renderSlot = slot;
    }

    @ModifyVariable(method = "drawSlot", at = @At(value = "STORE", target = "Lnet/minecraft/screen/slot/Slot;getStack()Lnet/minecraft/item/ItemStack;"))
    private ItemStack renderItem(ItemStack stack, MatrixStack matrices) {
        LitematicInventoryRenderer renderer = techutils$renderer();
        if (renderer != null) {
            return renderer.drawStack(matrices, x, y, renderSlot, stack);
        }
        return stack;
    }

    @Inject(method = "drawSlot", at = @At("TAIL"))
    private void postRenderItem(MatrixStack matrices, Slot slot, CallbackInfo ci) {
        if (litematicItemRenderer != null) {
            litematicItemRenderer.drawTransparencyBuffer(matrices, x, y);
        }
    }
}
