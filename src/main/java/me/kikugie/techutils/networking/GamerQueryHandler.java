package me.kikugie.techutils.networking;

import me.kikugie.techutils.config.Configs;
import me.kikugie.techutils.config.Configs.LitematicConfigs;
import net.minecraft.client.MinecraftClient;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.packet.c2s.play.QueryBlockNbtC2SPacket;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Advanced version of {@link net.minecraft.client.network.DataQueryHandler} that provides support for multi-response handling and async requests. Currently only supports block nbt queries.
 * <p>
 * - Pending requests wait in a queue that is drained at {@link LitematicConfigs#PACKET_RATE} packets per client tick.
 * <p>
 * - A request that stays unanswered for {@link LitematicConfigs#PACKET_TIMEOUT} after being sent is resent, up to 3 sends total.
 * <p>
 * - A request that runs out of resends resolves to {@code null} instead of failing the whole query, so one lost packet can't poison a batch.
 *
 * @see Configs.LitematicConfigs#PACKET_RATE
 * @see Configs.LitematicConfigs#PACKET_TIMEOUT
 */
public class GamerQueryHandler {
    private static final int MAX_SENDS = 3;

    public static final GamerQueryHandler INSTANCE = new GamerQueryHandler();

    private final Map<Integer, Request> pendingRequests = new ConcurrentHashMap<>();
    private final Queue<Request> sendQueue = new ConcurrentLinkedQueue<>();
    private int id = -1;
    private double packetBuffer = 0;

    private static final class Request {
        final int id;
        final BlockPos pos;
        final CompletableFuture<@Nullable NbtCompound> future = new CompletableFuture<>();
        int sendsLeft = MAX_SENDS;
        // Wall-clock time of the last send; -1 while waiting in the queue, so the timeout only runs
        // while a packet is actually in flight.
        long sentAt = -1;

        Request(int id, BlockPos pos) {
            this.id = id;
            this.pos = pos;
        }
    }

    /**
     * Requests nbt for given array of positions returning completable future of map position to response.
     * <p>
     * The future always completes normally; positions the server never answered map to {@code null}.
     * {@link Configs.LitematicConfigs#QUERY_TIMEOUT} can be applied to the returned future as a backstop.
     *
     * @param positions {@link BlockPos}[].
     * @return {@link CompletableFuture}<{@link Map}<{@link BlockPos}, {@link NbtCompound}>>.
     */
    @SuppressWarnings("unchecked")
    public static synchronized CompletableFuture<Map<BlockPos, @Nullable NbtCompound>> queryBlocks(BlockPos[] positions) {
        final int len = positions.length;
        CompletableFuture<@Nullable NbtCompound>[] futures = new CompletableFuture[len];

        for (int i = 0; i < len; i++) {
            Request request = new Request(INSTANCE.nextId(), positions[i]);
            futures[i] = request.future;
            INSTANCE.pendingRequests.put(request.id, request);
            INSTANCE.sendQueue.offer(request);
        }

        return CompletableFuture.allOf(futures).thenApply(__ -> {
            // HashMap rather than a concurrent one: null values are how unanswered queries report back.
            Map<BlockPos, @Nullable NbtCompound> result = new HashMap<>();
            for (int i = 0; i < len; i++) {
                result.put(positions[i], futures[i].join());
            }
            return result;
        });
    }

    public void handleQueryResponse(int id, @Nullable NbtCompound nbt) {
        Request request = pendingRequests.remove(id);
        // An answer with no data (no block entity server-side) is normalized to an empty tag, keeping
        // null to mean the server never answered at all.
        if (request != null) request.future.complete(nbt != null ? nbt : new NbtCompound());
    }

    public void onTick() {
        if (pendingRequests.isEmpty() && sendQueue.isEmpty()) return;

        var networkHandler = MinecraftClient.getInstance().getNetworkHandler();
        if (networkHandler == null) {
            // Disconnected; nothing will ever answer, so resolve everything instead of leaking it.
            sendQueue.clear();
            for (Request request : pendingRequests.values()) {
                request.future.complete(null);
            }
            pendingRequests.clear();
            packetBuffer = 0;
            return;
        }

        long now = System.currentTimeMillis();
        int timeout = LitematicConfigs.PACKET_TIMEOUT.getIntegerValue();
        for (Iterator<Request> iterator = pendingRequests.values().iterator(); iterator.hasNext(); ) {
            Request request = iterator.next();
            if (request.sentAt < 0 || now - request.sentAt < timeout) continue;
            if (--request.sendsLeft > 0) {
                request.sentAt = -1;
                sendQueue.offer(request);
            } else {
                iterator.remove();
                request.future.complete(null);
            }
        }

        if (sendQueue.isEmpty()) {
            packetBuffer = 0;
            return;
        }
        packetBuffer += LitematicConfigs.PACKET_RATE.getDoubleValue();
        int availablePackets = (int) packetBuffer;
        packetBuffer -= availablePackets;
        Request request;
        while (availablePackets-- > 0 && (request = sendQueue.poll()) != null) {
            // Answered while waiting for a resend.
            if (!pendingRequests.containsKey(request.id)) continue;
            request.sentAt = now;
            networkHandler.sendPacket(new QueryBlockNbtC2SPacket(request.id, request.pos));
        }
    }

    private int nextId() {
        return id--;
    }

    public int getRemainingPackets() {
        return pendingRequests.size();
    }
}
