package com.shiver.zenscriptmcp.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 线程间只传递不可变序列化快照，读取方拿到独立的 JSON 树。 */
public final class SnapshotStore {

    private static final String API_VERSION = "1.0";
    private static final int SCHEMA_VERSION = 1;

    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicLong worldEpoch = new AtomicLong();
    private final AtomicLong revision = new AtomicLong();

    private volatile String loadingState = "loading";
    private volatile String clientSnapshotJson = "{}";
    private volatile String serverSnapshotJson = "{}";

    public long worldEpoch() {
        return worldEpoch.get();
    }

    public long nextWorld() {
        return worldEpoch.incrementAndGet();
    }

    public void setLoadingState(String loadingState) {
        this.loadingState = loadingState;
    }

    public void publishClient(JsonObject snapshot) {
        clientSnapshotJson = serializeSnapshot(snapshot);
    }

    public void publishServer(JsonObject snapshot) {
        serverSnapshotJson = serializeSnapshot(snapshot);
    }

    public JsonObject context() {
        return Json.object(
                "instanceId", instanceId,
                "worldEpoch", worldEpoch());
    }

    public JsonObject observation(String source, long startedTick, JsonArray unavailable) {
        JsonObject snapshot = status().getAsJsonObject(source);
        long tick = snapshot != null && snapshot.has("tick") ? snapshot.get("tick").getAsLong() : 0;
        return Json.object(
                "source", source,
                "worldEpoch", worldEpoch(),
                "tick", tick,
                "startedTick", startedTick,
                "endedTick", tick,
                "capturedAt", Instant.now().toString(),
                "revision", revision.incrementAndGet(),
                "coverage", unavailable.size() == 0 ? "complete" : "partial",
                "unavailable", unavailable);
    }

    public JsonObject status() {
        return Json.object(
                "apiVersion", API_VERSION,
                "loadingState", loadingState,
                "client", Json.parse(clientSnapshotJson),
                "server", Json.parse(serverSnapshotJson));
    }

    public JsonObject result(String status, String payloadField, JsonObject payload) {
        return Json.object(
                "schemaVersion", SCHEMA_VERSION,
                "status", status,
                "context", context(),
                payloadField, payload);
    }

    public JsonObject error(String code, String message) {
        JsonObject error = Json.object(
                "code", code,
                "message", message);

        return result("error", "error", error);
    }

    private String serializeSnapshot(JsonObject snapshot) {
        JsonObject copy = Json.copy(snapshot);
        copy.addProperty("capturedAt", Instant.now().toString());
        copy.addProperty("revision", revision.incrementAndGet());
        return Json.GSON.toJson(copy);
    }
}
