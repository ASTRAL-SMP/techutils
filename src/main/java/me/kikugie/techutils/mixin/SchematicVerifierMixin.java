package me.kikugie.techutils.mixin;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import me.kikugie.techutils.config.Configs;
import me.kikugie.techutils.feature.verifier.BlockMismatchExtension;
import me.kikugie.techutils.feature.verifier.SchematicVerifierExtension;
import me.kikugie.techutils.networking.GamerQueryHandler;
import fi.dy.masa.litematica.gui.GuiSchematicVerifier;
import fi.dy.masa.litematica.schematic.placement.SchematicPlacement;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.malilib.util.IntBoundingBox;
import net.minecraft.client.MinecraftClient;
import it.unimi.dsi.fastutil.objects.Reference2ObjectArrayMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.item.ItemStack;
import net.minecraft.state.property.BooleanProperty;
import net.minecraft.state.property.Property;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.chunk.Chunk;
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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.BlockMismatch;
import static fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchRenderPos;
import static fi.dy.masa.litematica.schematic.verifier.SchematicVerifier.MismatchType;

/**
 * Extends Litematica's Schematic Verifier to also report containers whose contents don't match the
 * schematic. Container contents are compared straight from the client world's block entities, so this
 * works in singleplayer and wherever the client already has the container data (opened containers,
 * NBT queries, Servux).
 * <p>
 * Ported from the 1.20.1 branch to NBT; the item-component and Servux-bulk-request paths are dropped.
 */
@Mixin(value = SchematicVerifier.class, remap = false)
public abstract class SchematicVerifierMixin<InventoryBE extends BlockEntity & Inventory> implements SchematicVerifierExtension {
    @Shadow @Final private static BlockPos.Mutable MUTABLE_POS;
    @Shadow private SchematicPlacement schematicPlacement;
    @Shadow private ClientWorld worldClient;

    @Shadow protected abstract void addAndSortPositions(MismatchType type, ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> sourceMap, List<BlockPos> listOut, int maxEntries);

    @Unique
    private final Set<Pair<InventoryBE, InventoryBE>> wrongInventories = new ReferenceOpenHashSet<>();
    @Unique
    private final ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongInventoriesPositions = ArrayListMultimap.create();
    @Unique
    private final List<BlockPos> wrongInventoriesPositionsClosest = new ArrayList<>();
    @Unique
    private final List<BlockMismatch> selectedInventoryMismatches = new ArrayList<>();

    @Override
    public List<BlockMismatch> getSelectedInventoryMismatches$techutils() {
        return Collections.unmodifiableList(selectedInventoryMismatches);
    }

    @Override
    public int getWrongInventoriesCount$techutils() {
        return wrongInventories.size();
    }

    @Unique
    private final Map<BlockPos, InventoryBE> pendingExpected = new HashMap<>();

    @Inject(method = "verifyChunk", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;checkBlockStates(IIILnet/minecraft/block/BlockState;Lnet/minecraft/block/BlockState;)V", remap = true))
    private void checkInventories(Chunk chunkClient, Chunk chunkSchematic, IntBoundingBox box, CallbackInfoReturnable<Boolean> cir) {
        var expectedBE = chunkSchematic.getBlockEntity(MUTABLE_POS);
        if (!(expectedBE instanceof Inventory expected)) {
            return;
        }
        var pos = MUTABLE_POS.toImmutable();

        if (MinecraftClient.getInstance().isInSingleplayer()) {
            // The client has the authoritative container contents, so compare straight away.
            var foundBE = chunkClient.getBlockEntity(pos);
            if (foundBE instanceof Inventory found && expectedBE.getType() == foundBE.getType()) {
                //noinspection unchecked
                compareAndRecord(pos, (InventoryBE) expected, (InventoryBE) found);
            }
        } else {
            // On a server the client doesn't have container contents; snapshot the schematic side
            // and query the real contents from the server after verification completes.
            //noinspection unchecked
            pendingExpected.put(pos, snapshotOne((InventoryBE) expected));
        }
    }

    /**
     * Queries the server for the real contents of every schematic container found during
     * verification and records the ones that don't match. Runs on multiplayer only (in singleplayer
     * {@link #pendingExpected} is empty because contents are compared inline). Requires the block
     * NBT query permission (op / a permissions mod) or Servux on the server.
     */
    @Override
    public void runQueryPass$techutils() {
        if (pendingExpected.isEmpty()) {
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        int queryTimeout = Configs.LitematicConfigs.QUERY_TIMEOUT.getIntegerValue();
        var positions = pendingExpected.keySet().toArray(new BlockPos[0]);
        pendingExpected.keySet().retainAll(java.util.Arrays.asList(positions));

        for (BlockPos pos : positions) {
            InventoryBE expected = pendingExpected.get(pos);
            if (expected == null) {
                continue;
            }
            GamerQueryHandler.queryBlocks(new BlockPos[]{pos})
                    .orTimeout(queryTimeout, TimeUnit.SECONDS)
                    .whenComplete((map, error) -> mc.execute(() -> {
                        if (map == null) {
                            return;
                        }
                        var nbt = map.get(pos);
                        var world = mc.world;
                        if (nbt == null || world == null) {
                            return;
                        }
                        BlockEntity foundBE = BlockEntity.createFromNbt(pos, world.getBlockState(pos), nbt);
                        if (!(foundBE instanceof Inventory)) {
                            return;
                        }
                        //noinspection unchecked
                        if (compareAndRecord(pos, expected, (InventoryBE) foundBE)
                                && mc.currentScreen instanceof GuiSchematicVerifier gui) {
                            gui.initGui();
                        }
                    }));
        }
    }

    @Unique
    private boolean compareAndRecord(BlockPos pos, InventoryBE expected, InventoryBE found) {
        int size = expected.size();
        if (size != found.size()) {
            return false;
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
            return false;
        }
        var pair = snapshot(expected, found);
        wrongInventories.add(pair);
        warCrime(pair.getLeft(), pair.getRight(), ItemUtilsAccessor.getItemsForStates(), pos);
        return true;
    }

    @SuppressWarnings("unchecked")
    @Unique
    private InventoryBE snapshotOne(InventoryBE be) {
        return (InventoryBE) BlockEntity.createFromNbt(be.getPos(), be.getCachedState(), be.createNbtWithIdentifyingData());
    }

    /**
     * I ask for your forgiveness, future viewer (this makes differentiating inventories with the same block state possible)
     */
    @Unique
    private void warCrime(InventoryBE expected, InventoryBE found, IdentityHashMap<BlockState, ItemStack> itemsForStates, BlockPos pos) {
        BlockState foundState = found.getCachedState();
        HashMap<Property<?>, Comparable<?>> propertyMap = new HashMap<>(foundState.getEntries());

        propertyMap.put(BooleanProperty.of("war_crime"), true);
        BlockState newState = new BlockState(foundState.getBlock(), ImmutableMap.copyOf(new Reference2ObjectArrayMap<>(propertyMap)), null);

        itemsForStates.put(newState, ItemUtils.getItemForBlock(worldClient, pos, foundState, true));
        wrongInventoriesPositions.put(Pair.of(expected.getCachedState(), newState), pos);
        found.setCachedState(newState);
    }

    @SuppressWarnings("unchecked")
    @Unique
    private Pair<InventoryBE, InventoryBE> snapshot(InventoryBE expected, InventoryBE found) {
        final var expectedNew = (InventoryBE) BlockEntity.createFromNbt(expected.getPos(), expected.getCachedState(), expected.createNbtWithIdentifyingData());
        final var foundNew = (InventoryBE) BlockEntity.createFromNbt(found.getPos(), found.getCachedState(), found.createNbtWithIdentifyingData());
        return Pair.of(expectedNew, foundNew);
    }

    @Inject(method = "addCountFor", at = @At("HEAD"), cancellable = true)
    private void addCountForWrongInventories(MismatchType mismatchType, ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> map, List<BlockMismatch> list, CallbackInfo ci) {
        if (mismatchType != WRONG_INVENTORIES) {
            return;
        }

        for (var pair : wrongInventories) {
            BlockState leftState = pair.getLeft().getCachedState();
            BlockState rightState = pair.getRight().getCachedState();
            BlockMismatch blockMismatch = new BlockMismatch(WRONG_INVENTORIES, leftState, rightState, 1);
            //noinspection unchecked
            ((BlockMismatchExtension<InventoryBE>) blockMismatch).setInventories$techutils(pair);
            list.add(blockMismatch);
        }
        ci.cancel();
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

    @WrapOperation(method = "getMismatchOverviewCombined", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;addCountFor(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$MismatchType;Lcom/google/common/collect/ArrayListMultimap;Ljava/util/List;)V", ordinal = 0))
    private void addWrongInventoriesToOverview(SchematicVerifier instance, MismatchType type, ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> positions, List<BlockMismatch> list, Operation<Void> original) {
        original.call(instance, WRONG_INVENTORIES, wrongInventoriesPositions, list);
        original.call(instance, type, positions, list);
    }

    @Inject(method = "updateClosestPositions", at = @At("TAIL"))
    private void updateClosestWrongInventoriesPositions(BlockPos centerPos, int maxEntries, CallbackInfo ci) {
        addAndSortPositions(WRONG_INVENTORIES, wrongInventoriesPositions, wrongInventoriesPositionsClosest, maxEntries);
    }

    @WrapOperation(method = "combineClosestPositions", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;getMismatchRenderPositionFor(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$MismatchType;Ljava/util/List;)V", ordinal = 0))
    private void combineWrongInventoriesPositions(SchematicVerifier instance, MismatchType type, List<MismatchRenderPos> tempList, Operation<Void> original) {
        original.call(instance, WRONG_INVENTORIES, tempList);
        original.call(instance, type, tempList);
    }

    @Inject(method = "getMapForMismatchType", at = @At("HEAD"), cancellable = true)
    private void addWrongInventoriesMap(MismatchType mismatchType, CallbackInfoReturnable<ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos>> cir) {
        if (mismatchType == WRONG_INVENTORIES) {
            cir.setReturnValue(wrongInventoriesPositions);
        }
    }

    @Inject(method = "getClosestMismatchedPositionsFor", at = @At("HEAD"), cancellable = true)
    private void addWrongInventoriesMismatchedPositions(MismatchType type, CallbackInfoReturnable<List<BlockPos>> cir) {
        if (type == WRONG_INVENTORIES) {
            cir.setReturnValue(wrongInventoriesPositionsClosest);
        }
    }

    @ModifyExpressionValue(method = "ignoreStateMismatch(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$BlockMismatch;Z)V", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;getMapForMismatchType(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$MismatchType;)Lcom/google/common/collect/ArrayListMultimap;"))
    private ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> removeInventoryIfNecessary(ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> positions, @Local(argsOnly = true) BlockMismatch mismatch) {
        if (positions == wrongInventoriesPositions) {
            wrongInventories.remove(((BlockMismatchExtension<?>) mismatch).getInventories$techutils());
            selectedInventoryMismatches.remove(mismatch);
        }
        return positions;
    }

    @Inject(method = "clearData", at = @At("HEAD"))
    private void clearAdditionalData(CallbackInfo ci) {
        var itemsForStates = ItemUtilsAccessor.getItemsForStates();
        for (Pair<BlockState, BlockState> pair : wrongInventoriesPositions.keySet()) {
            itemsForStates.remove(pair.getRight());
        }
        wrongInventories.clear();
        wrongInventoriesPositions.clear();
        selectedInventoryMismatches.clear();
        pendingExpected.clear();
    }
}
