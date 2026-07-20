package me.kikugie.techutils.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import me.kikugie.techutils.feature.verifier.BlockMismatchExtension;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.util.GuiUtils;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
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
 * entry is hovered/selected, with an item tooltip on hover. Reimplemented against MaliLib 0.15.4's
 * {@link InventoryOverlay} with MatrixStack (upstream uses 1.20+ DrawContext).
 */
@Mixin(value = WidgetSchematicVerificationResult.class, remap = false)
public abstract class WidgetSchematicVerificationResultMixin extends WidgetListEntrySortable<GuiSchematicVerifier.BlockMismatchEntry> {
    @Unique
    private static final int SLOT_PITCH = 18;

    @Shadow @Final private GuiSchematicVerifier.BlockMismatchEntry mismatchEntry;

    public WidgetSchematicVerificationResultMixin(int x, int y, int width, int height, @Nullable GuiSchematicVerifier.BlockMismatchEntry entry, int listIndex) {
        super(x, y, width, height, entry, listIndex);
    }

    @Inject(method = "postRenderHovered", at = @At("HEAD"), cancellable = true)
    private void techutils$renderInventories(int mouseX, int mouseY, boolean selected, MatrixStack matrixStack, CallbackInfo ci) {
        Pair<Inventory, Inventory> inventories = mismatchEntry.blockMismatch == null
                ? null
                : ((BlockMismatchExtension) mismatchEntry.blockMismatch).getInventories$techutils();
        if (inventories == null) {
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        MatrixStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.push();
        modelViewStack.translate(0, 0, 256);
        RenderSystem.applyModelViewMatrix();

        ItemStack hovered = techutils$renderInventoryOverlay(LeftRight.LEFT, inventories.getLeft(), mc, mouseX, mouseY);
        ItemStack hoveredRight = techutils$renderInventoryOverlay(LeftRight.RIGHT, inventories.getRight(), mc, mouseX, mouseY);
        if (hovered.isEmpty()) {
            hovered = hoveredRight;
        }

        if (!hovered.isEmpty()) {
            InventoryOverlay.renderStackToolTip(mouseX, mouseY, hovered, mc, matrixStack);
        }

        modelViewStack.pop();
        RenderSystem.applyModelViewMatrix();
        ci.cancel();
    }

    /**
     * Draws one inventory and returns the stack the mouse is hovering over (empty if none). The slot
     * geometry mirrors MaliLib's grid layout used by {@code renderInventoryStacks}.
     */
    @Unique
    private static ItemStack techutils$renderInventoryOverlay(LeftRight side, Inventory inv, MinecraftClient mc, int mouseX, int mouseY) {
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

        int baseX = xInv + props.slotOffsetX;
        int baseY = yInv + props.slotOffsetY;
        int perRow = Math.max(1, props.slotsPerRow);
        for (int i = 0; i < inv.size(); i++) {
            int slotX = baseX + (i % perRow) * SLOT_PITCH;
            int slotY = baseY + (i / perRow) * SLOT_PITCH;
            if (mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16) {
                ItemStack stack = inv.getStack(i);
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }
}
