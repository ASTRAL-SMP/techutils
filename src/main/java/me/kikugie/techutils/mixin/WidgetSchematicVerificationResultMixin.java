package me.kikugie.techutils.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import me.kikugie.techutils.feature.verifier.BlockMismatchExtension;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.util.GuiUtils;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.inventory.Inventory;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Renders the expected and found container contents side by side when a wrong-inventory verifier
 * entry is hovered/selected. Reimplemented against MaliLib 0.15.4's {@link InventoryOverlay} (the
 * upstream 1.20.1/1.21 versions use DrawContext, which does not exist in 1.19.4).
 */
@Mixin(value = WidgetSchematicVerificationResult.class, remap = false)
public abstract class WidgetSchematicVerificationResultMixin<InventoryBE extends BlockEntity & Inventory> extends WidgetListEntrySortable<GuiSchematicVerifier.BlockMismatchEntry> {
    @Shadow @Final private GuiSchematicVerifier.BlockMismatchEntry mismatchEntry;

    public WidgetSchematicVerificationResultMixin(int x, int y, int width, int height, @Nullable GuiSchematicVerifier.BlockMismatchEntry entry, int listIndex) {
        super(x, y, width, height, entry, listIndex);
    }

    @Inject(method = "postRenderHovered", at = @At("HEAD"), cancellable = true)
    private void techutils$renderInventories(int mouseX, int mouseY, boolean selected, MatrixStack matrixStack, CallbackInfo ci) {
        //noinspection unchecked
        Pair<InventoryBE, InventoryBE> inventories = mismatchEntry.blockMismatch == null
                ? null
                : ((BlockMismatchExtension<InventoryBE>) mismatchEntry.blockMismatch).getInventories$techutils();
        if (inventories == null) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        MatrixStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.push();
        modelViewStack.translate(0, 0, 256);
        RenderSystem.applyModelViewMatrix();

        techutils$renderInventoryOverlay(LeftRight.LEFT, inventories.getLeft(), mc);
        techutils$renderInventoryOverlay(LeftRight.RIGHT, inventories.getRight(), mc);

        modelViewStack.pop();
        RenderSystem.applyModelViewMatrix();
        ci.cancel();
    }

    @Unique
    private static void techutils$renderInventoryOverlay(LeftRight side, Inventory inv, MinecraftClient mc) {
        InventoryOverlay.InventoryRenderType type = InventoryOverlay.getInventoryType(inv);
        InventoryOverlay.InventoryProperties props = InventoryOverlay.getInventoryPropsTemp(type, inv.size());

        int xInv = GuiUtils.getScaledWindowWidth() / 2 - props.width / 2;
        int yInv = GuiUtils.getScaledWindowHeight() / 2 - props.height;
        if (side == LeftRight.LEFT) {
            xInv -= props.width / 2 + 4;
        } else if (side == LeftRight.RIGHT) {
            xInv += props.width / 2 + 4;
        }

        RenderSystem.setShaderColor(1f, 1f, 1f, 1f);
        InventoryOverlay.renderInventoryBackground(type, xInv, yInv, props.slotsPerRow, props.totalSlots, mc);
        InventoryOverlay.renderInventoryStacks(type, inv, xInv + props.slotOffsetX, yInv + props.slotOffsetY, props.slotsPerRow, 0, inv.size(), mc);
    }
}
