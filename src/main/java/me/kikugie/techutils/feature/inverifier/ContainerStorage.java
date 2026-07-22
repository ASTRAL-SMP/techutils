package me.kikugie.techutils.feature.inverifier;

import fi.dy.masa.litematica.data.DataManager;
import fi.dy.masa.litematica.schematic.container.LitematicaBlockStateContainer;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacementManager;
import fi.dy.masa.litematica.schematic.placement.SubRegionPlacement;
import fi.dy.masa.litematica.util.SchematicUtils;
import net.minecraft.block.BlockEntityProvider;
import net.minecraft.block.BlockState;
import net.minecraft.block.ChestBlock;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3i;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

/**
 * Reads container contents out of a placed schematic.
 * <p>
 * Litematica 0.14.7 leaves the inventories of the block entities its schematic world hands out empty,
 * so the contents are rebuilt from the stored region NBT instead.
 */
public final class ContainerStorage {
    private ContainerStorage() {
    }

    /**
     * The schematic's contents for the container at a world position, sized like the real container so
     * slot indices line up. Double chests are not merged, so this matches the block entity at that
     * exact position.
     */
    @Nullable
    public static SimpleInventory getSchematicSlotItems(BlockPos worldPos, BlockState worldState) {
        return getItems(worldPos, worldState, null);
    }

    /**
     * Same as {@link #getSchematicSlotItems(BlockPos, BlockState)}, but only considers the given
     * placement. The verifier uses this so overlapping placements can't feed it another placement's
     * containers.
     */
    @Nullable
    public static SimpleInventory getSchematicSlotItems(BlockPos worldPos, BlockState worldState,
                                                        @Nullable SchematicPlacement only) {
        return getItems(worldPos, worldState, only);
    }

    /**
     * Same as {@link #getSchematicSlotItems}, but merges the halves of a double chest into the 54-slot
     * inventory the container screen shows.
     */
    @Nullable
    public static SimpleInventory getSchematicInventory(BlockPos worldPos, BlockState worldState) {
        ChestType type = worldState.getBlock() instanceof ChestBlock
                ? worldState.get(ChestBlock.CHEST_TYPE)
                : ChestType.SINGLE;
        if (type == ChestType.SINGLE) {
            return getItems(worldPos, worldState, null);
        }

        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return null;
        }
        BlockPos otherPos = worldPos.offset(ChestBlock.getFacing(worldState));
        SimpleInventory clicked = getItems(worldPos, worldState, null);
        SimpleInventory other = getItems(otherPos, world.getBlockState(otherPos), null);
        if (clicked == null && other == null) {
            return null;
        }
        // A half that isn't in the schematic still has to occupy its 27 slots, otherwise the other
        // half's items line up against the wrong screen slots.
        if (clicked == null) clicked = new SimpleInventory(27);
        if (other == null) other = new SimpleInventory(27);

        // ChestType.RIGHT is the first half of the vanilla DoubleInventory, LEFT is the second.
        return type == ChestType.RIGHT ? merge(clicked, other) : merge(other, clicked);
    }

    private static SimpleInventory merge(Inventory first, Inventory second) {
        SimpleInventory merged = new SimpleInventory(first.size() + second.size());
        for (int i = 0; i < first.size(); i++) {
            merged.setStack(i, first.getStack(i));
        }
        for (int i = 0; i < second.size(); i++) {
            merged.setStack(first.size() + i, second.getStack(i));
        }
        return merged;
    }

    /**
     * Placements can overlap, so every placement part covering the position is tried and the first
     * one that actually stores a matching container there wins.
     */
    @Nullable
    private static SimpleInventory getItems(BlockPos worldPos, BlockState worldState,
                                            @Nullable SchematicPlacement only) {
        List<SchematicPlacementManager.PlacementPart> parts =
                DataManager.getSchematicPlacementManager().getAllPlacementsTouchingChunk(worldPos);

        for (SchematicPlacementManager.PlacementPart part : parts) {
            if (!part.getBox().containsPos(worldPos)) continue;

            SchematicPlacement placement = part.placement;
            if (only != null && placement != only) continue;
            String region = part.subRegionName;
            LitematicaBlockStateContainer container = placement.getSchematic().getSubRegionContainer(region);
            SubRegionPlacement subRegion = placement.getRelativeSubRegionPlacement(region);
            if (container == null || subRegion == null) continue;

            BlockPos schematicPos = SchematicUtils.getSchematicContainerPositionFromWorldPosition(
                    worldPos, placement.getSchematic(), region, placement, subRegion, container);
            if (schematicPos == null || !isInside(container, schematicPos)) continue;

            BlockState schematicState = container.get(schematicPos.getX(), schematicPos.getY(), schematicPos.getZ());
            // A different block in the schematic means the slot layouts wouldn't line up.
            if (schematicState == null || schematicState.getBlock() != worldState.getBlock()) continue;

            Map<BlockPos, NbtCompound> blockEntities = placement.getSchematic().getBlockEntityMapForRegion(region);
            if (blockEntities == null) continue;
            NbtCompound nbt = blockEntities.get(schematicPos);
            if (nbt == null) continue;

            SimpleInventory inventory = readInventory(schematicPos, schematicState, nbt);
            if (inventory != null) {
                return inventory;
            }
        }
        return null;
    }

    @Nullable
    private static SimpleInventory readInventory(BlockPos pos, BlockState state, NbtCompound nbt) {
        // Instantiated from the block rather than BlockEntity.createFromNbt, because not every
        // schematic format keeps the "id" tag that createFromNbt requires.
        if (!(state.getBlock() instanceof BlockEntityProvider provider)) {
            return null;
        }
        BlockEntity blockEntity = provider.createBlockEntity(pos, state);
        if (blockEntity == null) {
            return null;
        }
        try {
            blockEntity.readNbt(nbt);
        } catch (Exception e) {
            return null;
        }
        if (!(blockEntity instanceof Inventory schematicInv)) {
            return null;
        }

        SimpleInventory inventory = new SimpleInventory(schematicInv.size());
        for (int i = 0; i < schematicInv.size(); i++) {
            inventory.setStack(i, schematicInv.getStack(i).copy());
        }
        return inventory;
    }

    private static boolean isInside(LitematicaBlockStateContainer container, BlockPos pos) {
        Vec3i size = container.getSize();
        return pos.getX() >= 0 && pos.getX() < size.getX()
                && pos.getY() >= 0 && pos.getY() < size.getY()
                && pos.getZ() >= 0 && pos.getZ() < size.getZ();
    }
}
