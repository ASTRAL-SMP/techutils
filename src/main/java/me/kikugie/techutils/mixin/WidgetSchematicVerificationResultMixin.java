package me.kikugie.techutils.mixin;

import com.mojang.blaze3d.systems.RenderSystem;
import me.kikugie.techutils.feature.verifier.BlockMismatchExtension;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.gui.widgets.WidgetSchematicVerificationResult;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import fi.dy.masa.malilib.gui.LeftRight;
import fi.dy.masa.malilib.gui.widgets.WidgetListEntrySortable;
import fi.dy.masa.malilib.render.InventoryOverlay;
import fi.dy.masa.malilib.render.InventoryOverlay.InventoryRenderType;
import fi.dy.masa.malilib.util.GuiUtils;
import net.minecraft.block.BlockState;
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
    @Unique
    private static final int[][] FURNACE_SLOTS = {{8, 8}, {8, 44}, {68, 26}};
    @Unique
    private static final int[][] BREWING_STAND_SLOTS = {{47, 42}, {70, 49}, {93, 42}, {70, 8}, {8, 8}};

    @Shadow @Final private GuiSchematicVerifier.BlockMismatchEntry mismatchEntry;

    public WidgetSchematicVerificationResultMixin(int x, int y, int width, int height, @Nullable GuiSchematicVerifier.BlockMismatchEntry entry, int listIndex) {
        super(x, y, width, height, entry, listIndex);
    }

    @Inject(method = "postRenderHovered", at = @At("HEAD"), cancellable = true)
    private void techutils$renderInventories(int mouseX, int mouseY, boolean selected, MatrixStack matrixStack, CallbackInfo ci) {
        BlockMismatch mismatch = mismatchEntry.blockMismatch;
        Pair<Inventory, Inventory> inventories = mismatch == null
                ? null
                : ((BlockMismatchExtension) mismatch).getInventories$techutils();
        if (inventories == null) {
            return;
        }

        InventoryRenderType type = techutils$getRenderType(mismatch.stateExpected);
        MinecraftClient mc = MinecraftClient.getInstance();
        MatrixStack modelViewStack = RenderSystem.getModelViewStack();
        modelViewStack.push();
        modelViewStack.translate(0, 0, 256);
        RenderSystem.applyModelViewMatrix();

        ItemStack hovered = techutils$renderInventoryOverlay(LeftRight.LEFT, type, inventories.getLeft(), mc, mouseX, mouseY);
        ItemStack hoveredRight = techutils$renderInventoryOverlay(LeftRight.RIGHT, type, inventories.getRight(), mc, mouseX, mouseY);
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
     * The contents are copied into plain {@code SimpleInventory}s, which MaliLib can only classify as
     * a horse inventory, laying every container out as a single column of {@code size / 3} slots. The
     * layout therefore comes from the container block, so a hopper is drawn as one row of five.
     */
    @Unique
    private static InventoryRenderType techutils$getRenderType(BlockState state) {
        return InventoryOverlay.getInventoryType(new ItemStack(state.getBlock()));
    }

    /**
     * Draws one inventory and returns the stack the mouse is hovering over (empty if none). The slot
     * geometry mirrors MaliLib's layout used by {@code renderInventoryStacks}.
     */
    @Unique
    private static ItemStack techutils$renderInventoryOverlay(LeftRight side, InventoryRenderType type, Inventory inv, MinecraftClient mc, int mouseX, int mouseY) {
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
        int[][] fixedSlots = techutils$fixedSlots(type);
        int slots = fixedSlots == null ? inv.size() : Math.min(inv.size(), fixedSlots.length);
        for (int i = 0; i < slots; i++) {
            int slotX = fixedSlots == null ? baseX + (i % perRow) * SLOT_PITCH : baseX + fixedSlots[i][0];
            int slotY = fixedSlots == null ? baseY + (i / perRow) * SLOT_PITCH : baseY + fixedSlots[i][1];
            if (mouseX >= slotX && mouseX < slotX + 16 && mouseY >= slotY && mouseY < slotY + 16) {
                ItemStack stack = inv.getStack(i);
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
        }
        return ItemStack.EMPTY;
    }

    /**
     * Slot offsets for the two layouts MaliLib places by hand instead of on a grid, null for the rest.
     */
    @Unique
    @Nullable
    private static int[][] techutils$fixedSlots(InventoryRenderType type) {
        if (type == InventoryRenderType.FURNACE) {
            return FURNACE_SLOTS;
        }
        return type == InventoryRenderType.BREWING_STAND ? BREWING_STAND_SLOTS : null;
    }
}
