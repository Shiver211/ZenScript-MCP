package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.util.function.Supplier;

/** 单次跨端读取：只有序列化 DTO 通过 volatile 发布，不阻塞任一游戏线程。 */
final class PeerObservation implements AutoCloseable {

    private final long epoch;
    private final SnapshotStore snapshots;
    private volatile boolean active = true;
    private volatile String result;
    private volatile String error;

    PeerObservation(GameExecutor executor, SnapshotStore snapshots, Supplier<QueryContext> contexts,
            JsonObject arguments, boolean container) {
        this.snapshots = snapshots;
        epoch = snapshots.worldEpoch();
        executor.submit(GameExecutor.Side.SERVER, () -> read(contexts, arguments, container));
    }

    JsonObject poll() {
        if (error != null) {
            throw new DomainException(error, "服务端观察失败");
        }
        return result == null ? null : Json.parse(result);
    }

    private void read(Supplier<QueryContext> contexts, JsonObject arguments, boolean container) {
        if (!active) {
            return;
        }
        if (snapshots.worldEpoch() != epoch) {
            error = "stale_reference";
            return;
        }
        try {
            QueryContext context = contexts.get();
            JsonObject observation = container
                    ? context.result(ActionObservation.container(context.player(Json.object())))
                    : ActionObservation.capture(context, arguments);
            result = Json.GSON.toJson(observation);
        } catch (DomainException exception) {
            error = exception.getCode();
        } catch (RuntimeException exception) {
            error = "action_rejected";
        }
    }

    @Override
    public void close() {
        active = false;
    }
}
