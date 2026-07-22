package me.kikugie.techutils.render.gui;

import com.mojang.blaze3d.systems.RenderSystem;
import fi.dy.masa.litematica.config.Configs;
import me.kikugie.techutils.access.DrawableHelperAccessor;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.CraftingResultInventory;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.screen.slot.Slot;

public class LitematicInventoryRenderer extends DrawableHelper {
    private final int MISSING_COLOR = Configs.Colors.SCHEMATIC_OVERLAY_COLOR_MISSING.getIntegerValue();
    private final int WRONG_COLOR = Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_BLOCK.getIntegerValue();
    private final int MISMATCHED_COLOR = Configs.Colors.SCHEMATIC_OVERLAY_COLOR_WRONG_STATE.getIntegerValue();
    private final int EXTRA_COLOR = Configs.Colors.SCHEMATIC_OVERLAY_COLOR_EXTRA.getIntegerValue();
    private final Inventory inventory;

    public LitematicInventoryRenderer(Inventory inventory) {
        this.inventory = inventory;
    }

    public ItemStack drawStack(MatrixStack matrices, Slot slot, ItemStack stack) {
        if (slot.inventory instanceof PlayerInventory || slot.inventory instanceof CraftingResultInventory) {
            return stack;
        }
        int index = slot.getIndex();
        if (index < 0 || index >= inventory.size()) {
            return stack;
        }

        ItemStack schematicStack = inventory.getStack(index);
        if (schematicStack == null) {
            schematicStack = ItemStack.EMPTY;
        }

        int color = 0;
        if (stack.isEmpty() && !schematicStack.isEmpty()) {
            color = MISSING_COLOR;
            stack = schematicStack;
        } else if (!stack.isEmpty() && schematicStack.isEmpty()) {
            color = EXTRA_COLOR;
        } else if (!stack.getItem().equals(schematicStack.getItem())) {
            color = WRONG_COLOR;
        } else if (stack.getCount() != schematicStack.getCount()) {
            color = MISMATCHED_COLOR;
        }

        if (color != 0) {
            drawBackground(matrices, slot, color);
        }

        return stack;
    }

    private void drawBackground(MatrixStack matrices, Slot slot, int color) {
        // slot.x / slot.y are relative to the already-translated screen matrix, so draw with it as-is
        // (the original reset the matrix to identity, which pushed the highlight into the top-left corner).
        RenderSystem.disableDepthTest();
        RenderSystem.colorMask(true, true, true, false);
        ((DrawableHelperAccessor) this).fillGradientSafe(matrices, slot.x, slot.y, slot.x + 16, slot.y + 16, color, color);
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableDepthTest();
    }
}
