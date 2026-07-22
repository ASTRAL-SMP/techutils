package me.kikugie.techutils.feature.inverifier;

import me.kikugie.techutils.TechUtilsMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

/**
 * Remembers which container the player opened, so the container screen can draw the schematic's
 * contents on top of the real ones.
 * <p>
 * Only the schematic side is resolved here; the real contents come from the open screen's own slots,
 * which is the one source that works both in singleplayer and on a server.
 */
public final class ContainerOverlay {
    @Nullable
    private static BlockPos clickedPos = null;
    @Nullable
    private static SimpleInventory schematicInventory = null;

    private ContainerOverlay() {
    }

    public static void onContainerClick(BlockHitResult hit) {
        clickedPos = hit.getBlockPos().toImmutable();
    }

    /**
     * Called when a container screen opens. On a server this happens a few ticks after the click, so
     * the position is kept around until then.
     */
    public static void onScreenOpened() {
        BlockPos pos = clickedPos;
        clickedPos = null;
        schematicInventory = null;
        if (pos == null) {
            return;
        }
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }
        schematicInventory = ContainerStorage.getSchematicInventory(pos, world.getBlockState(pos));
        TechUtilsMod.LOGGER.debug("Container overlay at {}: {}", pos,
                schematicInventory == null ? "no schematic container" : schematicInventory.size() + " slots");
    }

    @Nullable
    public static SimpleInventory getSchematicInventory() {
        return schematicInventory;
    }

    public static void close() {
        clickedPos = null;
        schematicInventory = null;
    }
}
