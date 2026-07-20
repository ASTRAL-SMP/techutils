package me.kikugie.techutils.mixin;

import com.google.common.collect.ArrayListMultimap;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.kikugie.techutils.config.Configs;
import me.kikugie.techutils.feature.inverifier.ContainerStorage;
import me.kikugie.techutils.feature.verifier.BlockMismatchExtension;
import me.kikugie.techutils.feature.verifier.SchematicVerifierExtension;
import me.kikugie.techutils.networking.GamerQueryHandler;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.malilib.util.IntBoundingBox;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.lang3.tuple.Pair;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import static fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;

/**
 * Extends Litematica's Schematic Verifier to also report containers whose contents don't match the
 * schematic. The expected contents are read from the stored schematic NBT (Litematica 0.14.7 doesn't
 * populate the schematic world's block-entity inventories), the found contents come from the client
 * world block entity, so it's correct in singleplayer and wherever the client has the container data.
 * <p>
 * Wrong containers only feed the verifier list (and the side-by-side render); they are not fed into
 * the in-world mismatch renderer, so there are no stray block markers.
 */
@Mixin(value = SchematicVerifier.class, remap = false)
public abstract class SchematicVerifierMixin implements SchematicVerifierExtension {
    @Shadow @Final private static BlockPos.Mutable MUTABLE_POS;

    @Unique
    private final Set<WrongInventory> wrongInventories = new LinkedHashSet<>();
    @Unique
    private final List<BlockMismatch> selectedInventoryMismatches = new ArrayList<>();
    // Kept empty on purpose: the render/ignore hooks below hand these back so Litematica never draws
    // wrong-inventory markers in the world.
    @Unique
    private final ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> emptyPositions = ArrayListMultimap.create();
    @Unique
    private final List<BlockPos> emptyClosest = new ArrayList<>();

    // On a server the client's container block entities are empty, so we query the real contents with
    // rate-limited block-NBT queries (op-less if the server enables carpet-tis-addition's
    // debugNbtQueryNoPermission). Verification of a chunk is held back until its containers answer.
    @Unique
    private final Map<BlockPos, NbtCompound> foundNbtCache = new HashMap<>();
    @Unique
    private final Set<BlockPos> pendingQueries = new HashSet<>();
    @Unique
    private final Set<ChunkPos> queriedChunks = new HashSet<>();

    @Unique
    private record WrongInventory(BlockPos pos, BlockState state, Inventory expected, Inventory found) {
    }

    @ModifyExpressionValue(method = "verifyChunks", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/world/ChunkManagerSchematic;isChunkLoaded(II)Z", remap = true))
    private boolean techutils$ensureContainersQueried(boolean isLoaded, @Local ChunkPos pos) {
        return isLoaded && techutils$chunkContainersReady(pos);
    }

    /**
     * Fires block-NBT queries for the containers in a chunk and reports whether their answers are in
     * yet. On singleplayer (or when the queries can't be answered) it never blocks.
     */
    @Unique
    private boolean techutils$chunkContainersReady(ChunkPos chunkPos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.isInSingleplayer() || mc.world == null) {
            return true;
        }

        if (queriedChunks.add(chunkPos)) {
            WorldChunk chunk = mc.world.getChunk(chunkPos.x, chunkPos.z);
            for (BlockPos bePos : new ArrayList<>(chunk.getBlockEntityPositions())) {
                if (chunk.getBlockEntity(bePos) instanceof Inventory && !foundNbtCache.containsKey(bePos)) {
                    techutils$queryContainer(bePos.toImmutable());
                }
            }
        }

        for (BlockPos pending : pendingQueries) {
            if (pending.getX() >> 4 == chunkPos.x && pending.getZ() >> 4 == chunkPos.z) {
                return false;
            }
        }
        return true;
    }

    @Unique
    private void techutils$queryContainer(BlockPos pos) {
        if (!pendingQueries.add(pos)) {
            return;
        }
        int timeout = Configs.LitematicConfigs.QUERY_TIMEOUT.getIntegerValue();
        GamerQueryHandler.queryBlocks(new BlockPos[]{pos})
                .orTimeout(timeout, TimeUnit.SECONDS)
                .whenComplete((map, error) -> MinecraftClient.getInstance().execute(() -> {
                    pendingQueries.remove(pos);
                    NbtCompound nbt = map == null ? null : map.get(pos);
                    if (nbt != null) {
                        foundNbtCache.put(pos, nbt);
                    }
                }));
    }

    @Override
    public List<BlockMismatch> getSelectedInventoryMismatches$techutils() {
        return Collections.unmodifiableList(selectedInventoryMismatches);
    }

    @Override
    public int getWrongInventoriesCount$techutils() {
        return wrongInventories.size();
    }

    @Inject(method = "verifyChunk", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;checkBlockStates(IIILnet/minecraft/block/BlockState;Lnet/minecraft/block/BlockState;)V", remap = true))
    private void checkInventories(Chunk chunkClient, Chunk chunkSchematic, IntBoundingBox box, CallbackInfoReturnable<Boolean> cir) {
        var worldBE = chunkClient.getBlockEntity(MUTABLE_POS);
        if (!(worldBE instanceof Inventory)) {
            return;
        }
        BlockPos pos = MUTABLE_POS.toImmutable();
        BlockState state = worldBE.getCachedState();

        Inventory found;
        if (MinecraftClient.getInstance().isInSingleplayer()) {
            // The client is authoritative in singleplayer.
            found = (Inventory) worldBE;
        } else {
            // On a server the world block entity is empty; use the queried contents (may be absent if
            // the query was denied or timed out, in which case we don't flag the container).
            NbtCompound nbt = foundNbtCache.get(pos);
            if (nbt == null || !(BlockEntity.createFromNbt(pos, state, nbt) instanceof Inventory queried)) {
                return;
            }
            found = queried;
        }

        // The stored schematic items only reach up to the last non-empty slot, so pad them out to the
        // real container size before comparing (otherwise the sizes never line up and nothing matches).
        ItemStack[] schematicItems = ContainerStorage.getSchematicSlotItems(pos);
        if (schematicItems == null) {
            return;
        }
        int size = found.size();
        SimpleInventory expected = new SimpleInventory(size);
        for (int i = 0; i < Math.min(schematicItems.length, size); i++) {
            if (schematicItems[i] != null) {
                expected.setStack(i, schematicItems[i].copy());
            }
        }

        boolean verifyNbt = Configs.LitematicConfigs.VERIFY_ITEM_NBT.getBooleanValue();
        boolean mismatch = false;
        for (int i = size - 1; i >= 0; i--) {
            var expectedStack = expected.getStack(i);
            var foundStack = found.getStack(i);
            if (expectedStack.getItem() != foundStack.getItem()
                    || expectedStack.getCount() != foundStack.getCount()
                    || verifyNbt && !Objects.equals(expectedStack.getNbt(), foundStack.getNbt())) {
                mismatch = true;
                break;
            }
        }
        if (!mismatch) {
            return;
        }

        // Snapshot the found contents so later changes to the container don't alter the displayed diff.
        SimpleInventory foundSnapshot = new SimpleInventory(size);
        for (int i = 0; i < size; i++) {
            foundSnapshot.setStack(i, found.getStack(i).copy());
        }
        wrongInventories.add(new WrongInventory(pos, state, expected, foundSnapshot));
    }

    @Inject(method = "addCountFor", at = @At("HEAD"), cancellable = true)
    private void addCountForWrongInventories(MismatchType mismatchType, ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> map, List<BlockMismatch> list, CallbackInfo ci) {
        if (mismatchType != WRONG_INVENTORIES) {
            return;
        }
        for (WrongInventory entry : wrongInventories) {
            BlockMismatch blockMismatch = new BlockMismatch(WRONG_INVENTORIES, entry.state(), entry.state(), 1);
            ((BlockMismatchExtension) blockMismatch).setInventories$techutils(Pair.of(entry.expected(), entry.found()));
            list.add(blockMismatch);
        }
        ci.cancel();
    }

    @WrapOperation(method = "getMismatchOverviewCombined", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;addCountFor(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$MismatchType;Lcom/google/common/collect/ArrayListMultimap;Ljava/util/List;)V", ordinal = 0))
    private void addWrongInventoriesToOverview(SchematicVerifier instance, MismatchType type, ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> positions, List<BlockMismatch> list, Operation<Void> original) {
        original.call(instance, WRONG_INVENTORIES, emptyPositions, list);
        original.call(instance, type, positions, list);
    }

    @Inject(method = "toggleMismatchEntrySelected", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/HashMultimap;remove(Ljava/lang/Object;Ljava/lang/Object;)Z"))
    private void tryRemoveSelectedInventoryMismatch(BlockMismatch mismatch, CallbackInfo ci, @Local MismatchType type) {
        if (type == WRONG_INVENTORIES) {
            selectedInventoryMismatches.remove(mismatch);
        }
    }

    @Inject(method = "toggleMismatchEntrySelected", at = @At(value = "INVOKE", target = "Lcom/google/common/collect/HashMultimap;put(Ljava/lang/Object;Ljava/lang/Object;)Z"))
    private void tryAddSelectedInventoryMismatch(BlockMismatch mismatch, CallbackInfo ci, @Local MismatchType type) {
        if (type == WRONG_INVENTORIES) {
            selectedInventoryMismatches.add(mismatch);
        }
    }

    @Inject(method = "removeSelectedEntriesOfType", at = @At("HEAD"))
    private void tryRemoveSelectedInventoryMismatches(MismatchType type, CallbackInfo ci) {
        if (type == WRONG_INVENTORIES) {
            selectedInventoryMismatches.clear();
        }
    }

    @Inject(method = "getMapForMismatchType", at = @At("HEAD"), cancellable = true)
    private void addWrongInventoriesMap(MismatchType mismatchType, CallbackInfoReturnable<ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos>> cir) {
        if (mismatchType == WRONG_INVENTORIES) {
            cir.setReturnValue(emptyPositions);
        }
    }

    @Inject(method = "getClosestMismatchedPositionsFor", at = @At("HEAD"), cancellable = true)
    private void addWrongInventoriesMismatchedPositions(MismatchType type, CallbackInfoReturnable<List<BlockPos>> cir) {
        if (type == WRONG_INVENTORIES) {
            cir.setReturnValue(emptyClosest);
        }
    }

    @ModifyExpressionValue(method = "ignoreStateMismatch(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$BlockMismatch;Z)V", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;getMapForMismatchType(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$MismatchType;)Lcom/google/common/collect/ArrayListMultimap;"))
    private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> removeInventoryIfNecessary(ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> positions, @Local(argsOnly = true) BlockMismatch mismatch) {
        if (positions == emptyPositions) {
            var inventories = ((BlockMismatchExtension) mismatch).getInventories$techutils();
            if (inventories != null) {
                wrongInventories.removeIf(w -> w.expected() == inventories.getLeft() && w.found() == inventories.getRight());
            }
            selectedInventoryMismatches.remove(mismatch);
        }
        return positions;
    }

    @Inject(method = "clearData", at = @At("HEAD"))
    private void clearAdditionalData(CallbackInfo ci) {
        wrongInventories.clear();
        selectedInventoryMismatches.clear();
        foundNbtCache.clear();
        pendingQueries.clear();
        queriedChunks.clear();
    }
}
