package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.PageStore;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 所属游戏线程保存投影前的固定快照；分页与差异基线使用各自的引用。 */
final class InventorySnapshots {

    private static final int MAX_SNAPSHOTS = 32;
    private static final long RETENTION_NANOS = TimeUnit.MINUTES.toNanos(5);
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private int retainedBytes;

    JsonObject capture(JsonObject current, JsonObject arguments, String scope, int maxBytes) {
        expire();
        Entry baseline = null;
        boolean diff = "diff".equals(QueryContext.string(arguments, "view", "summary"));
        if (diff) {
            baseline = entries.get(QueryContext.string(arguments, "sinceSnapshot", ""));
            if (baseline == null) {
                throw new DomainException("snapshot_expired", "差异基线已过期或被淘汰");
            }
            if (!scope.equals(baseline.scope)) {
                throw new DomainException("snapshot_scope_mismatch", "差异基线与当前容器、来源或查询范围不一致");
            }
        }
        String id = "inventory:" + UUID.randomUUID();
        current.addProperty("snapshotId", id);
        int bytes = PageStore.byteSize(Json.GSON.toJson(current));
        if (bytes > maxBytes) {
            throw new DomainException("result_too_large", "库存快照超过缓存预算");
        }
        while (!entries.isEmpty() && (entries.size() >= MAX_SNAPSHOTS || retainedBytes + bytes > maxBytes)) {
            Iterator<Entry> iterator = entries.values().iterator();
            retainedBytes -= iterator.next().bytes;
            iterator.remove();
        }
        entries.put(id, new Entry(scope, Json.copy(current), bytes));
        retainedBytes += bytes;
        return diff ? difference(baseline.data, current) : current;
    }

    private static JsonObject difference(JsonObject before, JsonObject after) {
        Map<String, JsonObject> previous = indexed(before.getAsJsonArray("slots"));
        Map<String, JsonObject> next = indexed(after.getAsJsonArray("slots"));
        JsonArray added = new JsonArray();
        JsonArray changed = new JsonArray();
        JsonArray removed = new JsonArray();
        for (Map.Entry<String, JsonObject> entry : next.entrySet()) {
            JsonObject old = previous.get(entry.getKey());
            if (old == null) {
                added.add(entry.getValue());
            } else if (!old.equals(entry.getValue())) {
                changed.add(Json.object("slotRef", entry.getKey(), "before", old, "after", entry.getValue()));
            }
        }
        for (Map.Entry<String, JsonObject> entry : previous.entrySet()) {
            if (!next.containsKey(entry.getKey())) {
                removed.add(entry.getValue());
            }
        }
        JsonObject result = Json.copy(after);
        result.remove("slots");
        result.addProperty("sinceSnapshot", before.get("snapshotId").getAsString());
        result.add("added", added);
        result.add("changed", changed);
        result.add("removed", removed);
        result.add("cursorChange", before.get("cursor").equals(after.get("cursor")) ? null
                : Json.object("before", before.get("cursor"), "after", after.get("cursor")));
        return result;
    }

    private static Map<String, JsonObject> indexed(JsonArray slots) {
        Map<String, JsonObject> result = new LinkedHashMap<>();
        for (JsonElement slot : slots) {
            JsonObject value = slot.getAsJsonObject();
            result.put(value.get("slotRef").getAsString(), value);
        }
        return result;
    }

    private void expire() {
        Iterator<Entry> iterator = entries.values().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (System.nanoTime() - entry.createdNanos >= RETENTION_NANOS) {
                retainedBytes -= entry.bytes;
                iterator.remove();
            }
        }
    }

    private static final class Entry {

        private final String scope;
        private final JsonObject data;
        private final int bytes;
        private final long createdNanos = System.nanoTime();

        private Entry(String scope, JsonObject data, int bytes) {
            this.scope = scope;
            this.data = data;
            this.bytes = bytes;
        }
    }
}
