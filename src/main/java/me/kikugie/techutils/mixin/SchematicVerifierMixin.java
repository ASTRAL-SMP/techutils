package me.kikugie.techutils.mixin;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableMap;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import fi.dy.masa.litematica.schematic.verifier.SchematicVerifier;
import fi.dy.masa.litematica.util.ItemUtils;
import fi.dy.masa.malilib.gui.Message;
import fi.dy.masa.malilib.util.InfoUtils;
import fi.dy.masa.malilib.util.IntBoundingBox;
import fi.dy.masa.malilib.util.WorldUtils;
import me.kikugie.techutils.config.Configs;
import me.kikugie.techutils.feature.inverifier.ContainerStorage;
import me.kikugie.techutils.feature.verifier.BlockMismatchExtension;
import me.kikugie.techutils.feature.verifier.SchematicVerifierExtension;
import me.kikugie.techutils.networking.GamerQueryHandler;
import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.inventory.Inventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.WorldChunk;
import org.apache.commons.lang3.tuple.Pair;
import org.jetbrains.annotations.Nullable;
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
import java.util.LinkedHashMap;
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
 * populate the schematic world's block-entity inventories); the found contents come from the client
 * world in singleplayer, and from block-NBT queries on a server.
 * <p>
 * Every wrong container gets its own throwaway {@link BlockState} for the "found" side. Litematica
 * groups verifier entries and their in-world marker positions by the (expected, found) state pair and
 * {@code BlockState} compares by identity, so this is what keeps containers of the same block type
 * from collapsing into a single entry with no usable positions.
 */
@Mixin(value = SchematicVerifier.class, remap = false)
public abstract class SchematicVerifierMixin implements SchematicVerifierExtension {
    @Shadow @Final private static BlockPos.Mutable MUTABLE_POS;
    @Shadow private ClientWorld worldClient;
    @Shadow private fi.dy.masa.litematica.schematic.placement.SchematicPlacement schematicPlacement;

    @Shadow protected abstract void addAndSortPositions(MismatchType type, ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> sourceMap, List<BlockPos> listOut, int maxEntries);

    @Unique
    private final Map<BlockPos, WrongInventory> wrongInventories = new LinkedHashMap<>();
    @Unique
    private final List<BlockMismatch> selectedInventoryMismatches = new ArrayList<>();
    @Unique
    private final ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> wrongInventoriesPositions = ArrayListMultimap.create();
    @Unique
    private final List<BlockPos> wrongInventoriesPositionsClosest = new ArrayList<>();

    // The client's container block entities are always empty (chunk packets don't include container
    // contents), so the real contents are fetched per chunk before it is verified: on an integrated
    // server by snapshotting the server world's block entities on the server thread (World#getBlockEntity
    // returns null from any other thread), on a remote server with rate-limited block-NBT queries
    // (op-less if the server enables carpet-tis-addition's debugNbtQueryNoPermission). Verification of
    // a chunk is held back until its containers answer.
    @Unique
    private final Map<BlockPos, NbtCompound> foundNbtCache = new HashMap<>();
    @Unique
    private final Set<BlockPos> pendingQueries = new HashSet<>();
    @Unique
    private final Set<ChunkPos> queriedChunks = new HashSet<>();
    // Set once a query times out without the server ever answering one, so a server that denies NBT
    // queries costs one timeout instead of stalling every chunk.
    @Unique
    private boolean queriesUnsupported = false;
    @Unique
    private boolean queryAnswered = false;

    // Listed containers are re-read on demand (opening the verifier screen), so filling one clears its
    // entry without re-running the whole verification. The cooldown runs from the last answer, which
    // also keeps the refresh it triggers from starting another round.
    @Unique
    private static final long RECHECK_COOLDOWN_MS = 2000;
    @Unique
    private boolean recheckInFlight = false;
    @Unique
    private long recheckedAt = 0;

    @Unique
    private record WrongInventory(BlockPos pos, BlockState expectedState, BlockState foundState,
                                  Inventory expected, Inventory found) {
    }

    /**
     * The integrated server's world when there is one, which is the only local source of real
     * container contents. {@code null} on a remote server, where they have to be queried instead.
     */
    @Unique
    @Nullable
    private static World techutils$authoritativeWorld() {
        MinecraftClient mc = MinecraftClient.getInstance();
        World world = WorldUtils.getBestWorld(mc);
        return world != null && world != mc.world ? world : null;
    }

    @ModifyExpressionValue(method = "verifyChunks", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/world/ChunkManagerSchematic;isChunkLoaded(II)Z", remap = true))
    private boolean techutils$ensureContainersQueried(boolean isLoaded, @Local ChunkPos pos) {
        return isLoaded && techutils$chunkContainersReady(pos);
    }

    /**
     * Fetches the real contents of the containers in a chunk and reports whether they are in yet.
     * Once a remote server has shown it won't answer queries, it never blocks.
     */
    @Unique
    private boolean techutils$chunkContainersReady(ChunkPos chunkPos) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (queriesUnsupported || mc.world == null) {
            return true;
        }

        if (queriedChunks.add(chunkPos)) {
            WorldChunk chunk = mc.world.getChunk(chunkPos.x, chunkPos.z);
            List<BlockPos> containers = new ArrayList<>();
            for (BlockPos bePos : new ArrayList<>(chunk.getBlockEntityPositions())) {
                if (chunk.getBlockEntity(bePos) instanceof Inventory && !foundNbtCache.containsKey(bePos)) {
                    containers.add(bePos.toImmutable());
                }
            }
            if (techutils$authoritativeWorld() instanceof ServerWorld serverWorld) {
                techutils$snapshotContainers(serverWorld, containers);
            } else {
                for (BlockPos pos : containers) {
                    techutils$queryContainer(pos);
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

    /**
     * Reads the containers' NBT out of the integrated server's world on the server thread, the only
     * thread {@link World#getBlockEntity} answers for it.
     */
    @Unique
    private void techutils$snapshotContainers(ServerWorld world, List<BlockPos> positions) {
        if (positions.isEmpty()) {
            return;
        }
        pendingQueries.addAll(positions);
        world.getServer().execute(() -> {
            Map<BlockPos, NbtCompound> snapshot = new HashMap<>();
            for (BlockPos pos : positions) {
                if (world.getBlockEntity(pos) instanceof Inventory inventory) {
                    snapshot.put(pos, ((BlockEntity) inventory).createNbtWithId());
                }
            }
            MinecraftClient.getInstance().execute(() -> {
                for (BlockPos pos : positions) {
                    pendingQueries.remove(pos);
                    NbtCompound nbt = snapshot.get(pos);
                    if (nbt != null) {
                        foundNbtCache.put(pos, nbt);
                    }
                }
            });
        });
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
                        queryAnswered = true;
                        foundNbtCache.put(pos, nbt);
                    } else if (!queryAnswered) {
                        queriesUnsupported = true;
                        pendingQueries.clear();
                        InfoUtils.showGuiOrInGameMessage(Message.MessageType.WARNING,
                                "Tech Utils: the server didn't answer block NBT queries, container contents were not verified (needs op, or carpet-tis-addition's debugNbtQueryNoPermission)");
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

        // Client-side container block entities never carry their contents (chunk packets send them
        // through toInitialChunkDataNbt, which is empty for containers), so the real contents always
        // come from the per-chunk NBT cache filled before this chunk was verified. An absent entry
        // means the snapshot/query failed, an empty one that the server answered without a block
        // entity; the container isn't flagged in either case. The block entity is instantiated from
        // the client one's own type because vanilla's query responses carry no "id" tag, which rules
        // out BlockEntity.createFromNbt.
        NbtCompound nbt = foundNbtCache.get(pos);
        if (nbt == null || nbt.isEmpty()) {
            return;
        }
        Inventory found = techutils$readInventory(worldBE, pos, state, nbt);
        if (found == null) {
            return;
        }

        SimpleInventory expected = ContainerStorage.getSchematicSlotItems(pos, state, schematicPlacement);
        if (expected == null || expected.size() != found.size()) {
            return;
        }

        if (techutils$inventoriesMatch(expected, found)) {
            techutils$forget(pos);
            return;
        }

        SimpleInventory foundSnapshot = techutils$snapshot(found);
        techutils$forget(pos);
        BlockState foundState = techutils$distinctState(state);
        ItemUtilsAccessor.getItemsForStates().put(foundState, ItemUtils.getItemForBlock(worldClient, pos, state, true));
        wrongInventoriesPositions.put(Pair.of(state, foundState), pos);
        wrongInventories.put(pos, new WrongInventory(pos, state, foundState, expected, foundSnapshot));
    }

    /**
     * Rebuilds a container's contents from queried NBT, using the block entity in the client world as
     * the template for the type and size.
     */
    @Unique
    @Nullable
    private static Inventory techutils$readInventory(BlockEntity worldBE, BlockPos pos, BlockState state, NbtCompound nbt) {
        BlockEntity copy = worldBE.getType().instantiate(pos, state);
        if (copy == null) {
            return null;
        }
        try {
            copy.readNbt(nbt);
        } catch (Exception e) {
            return null;
        }
        return copy instanceof Inventory inventory ? inventory : null;
    }

    @Unique
    private static boolean techutils$inventoriesMatch(Inventory expected, Inventory found) {
        if (expected.size() != found.size()) {
            return false;
        }
        boolean verifyNbt = Configs.LitematicConfigs.VERIFY_ITEM_NBT.getBooleanValue();
        for (int i = found.size() - 1; i >= 0; i--) {
            var expectedStack = expected.getStack(i);
            var foundStack = found.getStack(i);
            if (expectedStack.getItem() != foundStack.getItem()
                    || expectedStack.getCount() != foundStack.getCount()
                    || verifyNbt && !Objects.equals(expectedStack.getNbt(), foundStack.getNbt())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Copies the contents so later changes to the container don't alter the displayed diff.
     */
    @Unique
    private static SimpleInventory techutils$snapshot(Inventory inventory) {
        SimpleInventory copy = new SimpleInventory(inventory.size());
        for (int i = 0; i < inventory.size(); i++) {
            copy.setStack(i, inventory.getStack(i).copy());
        }
        return copy;
    }

    @Override
    public void recheckWrongInventories$techutils(Runnable onChanged) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (wrongInventories.isEmpty() || recheckInFlight || queriesUnsupported || mc.world == null) {
            return;
        }
        if (System.currentTimeMillis() - recheckedAt < RECHECK_COOLDOWN_MS) {
            return;
        }
        recheckInFlight = true;
        List<BlockPos> positions = new ArrayList<>(wrongInventories.keySet());

        if (techutils$authoritativeWorld() instanceof ServerWorld serverWorld) {
            serverWorld.getServer().execute(() -> {
                Map<BlockPos, NbtCompound> snapshot = new HashMap<>();
                for (BlockPos pos : positions) {
                    if (serverWorld.getBlockEntity(pos) instanceof Inventory inventory) {
                        snapshot.put(pos, ((BlockEntity) inventory).createNbtWithId());
                    }
                }
                mc.execute(() -> techutils$applyRecheck(snapshot, onChanged));
            });
        } else {
            int timeout = Configs.LitematicConfigs.QUERY_TIMEOUT.getIntegerValue();
            GamerQueryHandler.queryBlocks(positions.toArray(new BlockPos[0]))
                    .orTimeout(timeout, TimeUnit.SECONDS)
                    .whenComplete((map, error) -> mc.execute(() ->
                            techutils$applyRecheck(map == null ? Map.of() : map, onChanged)));
        }
    }

    @Unique
    private void techutils$applyRecheck(Map<BlockPos, NbtCompound> results, Runnable onChanged) {
        recheckInFlight = false;
        recheckedAt = System.currentTimeMillis();
        ClientWorld world = MinecraftClient.getInstance().world;
        if (world == null) {
            return;
        }

        boolean changed = false;
        for (Map.Entry<BlockPos, NbtCompound> result : results.entrySet()) {
            BlockPos pos = result.getKey();
            NbtCompound nbt = result.getValue();
            WrongInventory entry = wrongInventories.get(pos);
            if (entry == null || nbt == null || nbt.isEmpty()) {
                continue;
            }
            BlockEntity worldBE = world.getBlockEntity(pos);
            if (worldBE == null) {
                continue;
            }
            Inventory found = techutils$readInventory(worldBE, pos, worldBE.getCachedState(), nbt);
            if (found == null || found.size() != entry.expected().size()) {
                continue;
            }

            foundNbtCache.put(pos, nbt);
            if (techutils$inventoriesMatch(entry.expected(), found)) {
                techutils$forget(pos);
                changed = true;
            } else if (!techutils$inventoriesMatch(entry.found(), found)) {
                wrongInventories.put(pos, new WrongInventory(pos, entry.expectedState(), entry.foundState(),
                        entry.expected(), techutils$snapshot(found)));
                changed = true;
            }
        }
        if (changed) {
            onChanged.run();
        }
    }

    /**
     * A {@link BlockState} equal to the given one in every property but not identical to it, which is
     * all that's needed to tell two mismatched containers of the same block apart.
     */
    @Unique
    private static BlockState techutils$distinctState(BlockState state) {
        return new BlockState(state.getBlock(), ImmutableMap.copyOf(state.getEntries()), null);
    }

    @Unique
    private void techutils$forget(BlockPos pos) {
        WrongInventory previous = wrongInventories.remove(pos);
        if (previous == null) {
            return;
        }
        wrongInventoriesPositions.remove(Pair.of(previous.expectedState(), previous.foundState()), pos);
        ItemUtilsAccessor.getItemsForStates().remove(previous.foundState());
        selectedInventoryMismatches.removeIf(m -> m.stateFound == previous.foundState());
    }

    @Inject(method = "addCountFor", at = @At("HEAD"), cancellable = true)
    private void addCountForWrongInventories(MismatchType mismatchType, ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> map, List<BlockMismatch> list, CallbackInfo ci) {
        if (mismatchType != WRONG_INVENTORIES) {
            return;
        }
        for (WrongInventory entry : wrongInventories.values()) {
            BlockMismatch blockMismatch = new BlockMismatch(WRONG_INVENTORIES, entry.expectedState(), entry.foundState(), 1);
            ((BlockMismatchExtension) blockMismatch).setInventories$techutils(Pair.of(entry.expected(), entry.found()));
            list.add(blockMismatch);
        }
        ci.cancel();
    }

    @WrapOperation(method = "getMismatchOverviewCombined", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;addCountFor(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$MismatchType;Lcom/google/common/collect/ArrayListMultimap;Ljava/util/List;)V", ordinal = 0))
    private void addWrongInventoriesToOverview(SchematicVerifier instance, MismatchType type, ArrayListMultimap<Pair<BlockState, BlockState>, BlockPos> positions, List<BlockMismatch> list, Operation<Void> original) {
        original.call(instance, WRONG_INVENTORIES, wrongInventoriesPositions, list);
        original.call(instance, type, positions, list);
    }

    @Inject(method = "updateClosestPositions", at = @At("TAIL"))
    private void updateClosestWrongInventoryPositions(BlockPos centerPos, int maxEntries, CallbackInfo ci) {
        addAndSortPositions(WRONG_INVENTORIES, wrongInventoriesPositions, wrongInventoriesPositionsClosest, maxEntries);
    }

    @WrapOperation(method = "combineClosestPositions", at = @At(value = "INVOKE", target = "Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier;getMismatchRenderPositionFor(Lfi/dy/masa/litematica/schematic/verifier/SchematicVerifier$MismatchType;Ljava/util/List;)V", ordinal = 0))
    private void combineWrongInventoryPositions(SchematicVerifier instance, MismatchType type, List<SchematicVerifier.MismatchRenderPos> tempList, Operation<Void> original) {
        original.call(instance, WRONG_INVENTORIES, tempList);
        original.call(instance, type, tempList);
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
            wrongInventories.values().stream()
                    .filter(entry -> entry.foundState() == mismatch.stateFound)
                    .findFirst()
                    .ifPresent(entry -> techutils$forget(entry.pos()));
        }
        return positions;
    }

    @Inject(method = "clearData", at = @At("HEAD"))
    private void clearAdditionalData(CallbackInfo ci) {
        var itemsForStates = ItemUtilsAccessor.getItemsForStates();
        for (WrongInventory entry : wrongInventories.values()) {
            itemsForStates.remove(entry.foundState());
        }
        wrongInventories.clear();
        wrongInventoriesPositions.clear();
        wrongInventoriesPositionsClosest.clear();
        selectedInventoryMismatches.clear();
        foundNbtCache.clear();
        pendingQueries.clear();
        queriedChunks.clear();
        queriesUnsupported = false;
        queryAnswered = false;
        recheckInFlight = false;
        recheckedAt = 0;
    }
}
