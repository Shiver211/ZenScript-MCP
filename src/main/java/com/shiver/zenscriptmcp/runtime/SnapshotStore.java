package com.shiver.zenscriptmcp.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/** 线程间只传递不可变序列化快照，读取方拿到独立的 JSON 树。 */
public final class SnapshotStore {

    private static final String API_VERSION = "2.0";
    private static final int SCHEMA_VERSION = 2;
    private static final long HEARTBEAT_TIMEOUT_MILLIS = 5000;

    private final String instanceId = UUID.randomUUID().toString();
    private final AtomicLong worldEpoch = new AtomicLong();
    private final AtomicLong revision = new AtomicLong();

    private volatile String runtimeState = "starting";
    private volatile String clientSnapshotJson = "{}";
    private volatile String serverSnapshotJson = "{}";

    public long worldEpoch() {
        return worldEpoch.get();
    }

    public long nextWorld() {
        return worldEpoch.incrementAndGet();
    }

    public void setLoadingState(String loadingState) {
        runtimeState = loadingState;
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
        JsonObject client = Json.parse(clientSnapshotJson);
        JsonObject server = Json.parse(serverSnapshotJson);
        return Json.object(
                "apiVersion", API_VERSION,
                "runtimeState", runtimeState,
                "worldState", worldState(client),
                "paused", flag(client, "paused"),
                "readiness", readiness(client, server, false),
                "client", client,
                "server", server);
    }

    public JsonObject readiness(boolean requireServer) {
        return readiness(Json.parse(clientSnapshotJson), Json.parse(serverSnapshotJson), requireServer);
    }

    private JsonObject readiness(JsonObject client, JsonObject server, boolean requireServer) {
        JsonObject conditions = Json.object(
                "runtimeReady", "ready".equals(runtimeState),
                "clientAvailable", current(client, "client"),
                "worldAvailable", flag(client, "worldAvailable"),
                "playerAvailable", flag(client, "playerAvailable"),
                "unpaused", !flag(client, "paused"),
                "serverAvailable", !requireServer || current(server, "server") && flag(server, "worldAvailable"));
        JsonArray reasons = new JsonArray();
        conditions.entrySet().forEach(entry -> {
            if (!entry.getValue().getAsBoolean()) {
                reasons.add(entry.getKey());
            }
        });
        return Json.object("ready", reasons.size() == 0, "requireServer", requireServer,
                "conditions", conditions, "unmetReasons", reasons);
    }

    private boolean current(JsonObject snapshot, String source) {
        return flag(snapshot, "available") && snapshot.has("source")
                && source.equals(snapshot.get("source").getAsString())
                && snapshot.has("worldEpoch") && snapshot.get("worldEpoch").getAsLong() == worldEpoch()
                && snapshot.has("heartbeatAt")
                && Instant.now().toEpochMilli() - Instant.parse(snapshot.get("heartbeatAt").getAsString())
                        .toEpochMilli() <= HEARTBEAT_TIMEOUT_MILLIS;
    }

    private String worldState(JsonObject client) {
        if ("stopping".equals(runtimeState) && flag(client, "worldAvailable")) {
            return "unloading";
        }
        return client.has("worldState") ? client.get("worldState").getAsString() : "none";
    }

    private static boolean flag(JsonObject object, String name) {
        return object.has(name) && object.get(name).getAsBoolean();
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
                "message", message,
                "stage", "execution",
                "details", new JsonObject());

        return result("error", "error", error);
    }

    private String serializeSnapshot(JsonObject snapshot) {
        JsonObject copy = Json.copy(snapshot);
        copy.addProperty("worldEpoch", worldEpoch());
        copy.addProperty("capturedAt", Instant.now().toString());
        copy.addProperty("revision", revision.incrementAndGet());
        return Json.GSON.toJson(copy);
    }
}
