package com.shiver.zenscriptmcp.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** 分页只读取已复制的 DTO；游标绑定查询参数、世界代次与有界缓存。 */
public final class PageStore {

    private static final long RETENTION_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final Map<String, Page> pages = new LinkedHashMap<>();
    private final SnapshotStore snapshots;
    private final McpConfig.QuerySettings settings;
    private int retainedBytes;

    public PageStore(SnapshotStore snapshots, McpConfig.QuerySettings settings) {
        this.snapshots = snapshots;
        this.settings = settings;
    }

    public synchronized JsonObject create(
            String tool, JsonObject arguments, JsonArray rows, JsonObject metadata,
            JsonObject observation, boolean worldBound) {
        return create(tool, arguments, rows, metadata, observation, worldBound, false);
    }

    public synchronized JsonObject document(String tool, JsonObject arguments, JsonObject result, boolean worldBound) {
        if (result.has("pagination")) {
            return result;
        }
        JsonObject metadata = Json.copy(result.getAsJsonObject("data"));
        JsonArray rows = new JsonArray();
        extractLists(metadata, new JsonArray(), rows);
        return create(tool, arguments, rows, metadata, result.getAsJsonObject("observation"), worldBound, true);
    }

    private static void extractLists(JsonObject object, JsonArray path, JsonArray rows) {
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            JsonArray childPath = Json.copy(path);
            childPath.add(entry.getKey());
            JsonElement value = entry.getValue();
            if (value.isJsonArray() && isList(entry.getKey())) {
                for (JsonElement row : value.getAsJsonArray()) {
                    rows.add(Json.object("path", childPath, "value", row));
                }
                entry.setValue(new JsonArray());
            } else if (value.isJsonObject()) {
                extractLists(value.getAsJsonObject(), childPath, rows);
            }
        }
    }

    private static boolean isList(String name) {
        return "steps".equals(name) || "items".equals(name) || "slots".equals(name) || "widgets".equals(name)
                || "added".equals(name) || "changed".equals(name) || "removed".equals(name)
                || "enchantments".equals(name) || "attributes".equals(name) || "oreDictionary".equals(name)
                || "buttons".equals(name) || "tanks".equals(name) || "value".equals(name);
    }

    private JsonObject create(String tool, JsonObject arguments, JsonArray rows, JsonObject metadata,
            JsonObject observation, boolean worldBound, boolean document) {
        List<String> serialized = new ArrayList<>();
        int bytes = byteSize(Json.GSON.toJson(metadata));
        for (JsonElement row : rows) {
            String json = Json.GSON.toJson(row);
            int rowBytes = byteSize(json);
            if (bytes + rowBytes > settings.maxCacheBytes) {
                throw new DomainException("result_too_large", "查询结果超过预算；请缩小范围或 fields");
            }
            bytes += rowBytes;
            serialized.add(json);
        }
        expire();
        while (!pages.isEmpty() && (pages.size() >= settings.retainedPages
                || retainedBytes + bytes > settings.maxCacheBytes)) {
            removeFirst();
        }
        String id = UUID.randomUUID().toString();
        long epoch = observation.has("worldEpoch")
                ? observation.get("worldEpoch").getAsLong() : snapshots.worldEpoch();
        if (worldBound && epoch != snapshots.worldEpoch()) {
            throw stale();
        }
        Page page = new Page(tool, signature(arguments), serialized, Json.copy(metadata),
                Json.copy(observation), epoch, worldBound, bytes, document);
        pages.put(id, page);
        retainedBytes += bytes;
        return readPage(id, page, 0, limit(arguments));
    }

    public synchronized JsonObject read(String tool, JsonObject arguments) {
        expire();
        String cursor = arguments.get("cursor").getAsString();
        int separator = cursor.lastIndexOf(':');
        if (separator < 0) {
            throw stale();
        }
        if (!cursor.startsWith("page:")) {
            throw stale();
        }
        String id = cursor.substring(5, separator);
        Page page = pages.get(id);
        if (page == null || !tool.equals(page.tool) || !signature(arguments).equals(page.arguments)
                || (page.worldBound && page.worldEpoch != snapshots.worldEpoch())) {
            throw stale();
        }
        int offset;
        try {
            offset = Integer.parseInt(cursor.substring(separator + 1));
        } catch (NumberFormatException exception) {
            throw stale();
        }
        if (offset < 0 || offset > page.rows.size()) {
            throw stale();
        }
        return readPage(id, page, offset, limit(arguments));
    }

    public synchronized void clearWorld() {
        Iterator<Page> iterator = pages.values().iterator();
        while (iterator.hasNext()) {
            Page page = iterator.next();
            if (page.worldBound) {
                retainedBytes -= page.bytes;
                iterator.remove();
            }
        }
    }

    public int limit(JsonObject arguments) {
        return arguments.has("limit") ? arguments.get("limit").getAsInt() : settings.defaultLimit;
    }

    public static int byteSize(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    private JsonObject readPage(String id, Page page, int offset, int limit) {
        JsonObject data = Json.copy(page.metadata);
        JsonArray rows = new JsonArray();
        int bytes = byteSize(Json.GSON.toJson(data)) + byteSize(Json.GSON.toJson(page.observation)) + 2048;
        if (bytes > settings.maxPageBytes) {
            throw new DomainException("result_too_large", "分页元数据超过预算；请缩小 fields 或 NBT path");
        }
        int end = offset;
        while (end < page.rows.size() && rows.size() < limit) {
            String row = page.rows.get(end);
            int rowBytes = byteSize(row);
            if (bytes + rowBytes > settings.maxPageBytes) {
                break;
            }
            rows.add(Json.parse(row));
            bytes += rowBytes;
            end++;
        }
        if (end == offset && end < page.rows.size()) {
            throw new DomainException("result_too_large", "单条记录超过分页预算；请缩小 fields");
        }
        if (page.document) {
            for (JsonElement row : rows) {
                JsonObject record = row.getAsJsonObject();
                JsonArray path = record.getAsJsonArray("path");
                JsonObject parent = data;
                for (int index = 0; index < path.size() - 1; index++) {
                    parent = parent.getAsJsonObject(path.get(index).getAsString());
                }
                parent.getAsJsonArray(path.get(path.size() - 1).getAsString()).add(record.get("value"));
            }
        } else {
            data.add("items", rows);
        }
        JsonObject pagination = Json.object("snapshotId", id, "total", page.rows.size(), "offset", offset,
                "returned", rows.size(), "hasMore", end < page.rows.size(),
                "nextCursor", end < page.rows.size() ? "page:" + id + ":" + end : null,
                "collectionComplete", !incomplete(data)
                        && "complete".equals(page.observation.get("coverage").getAsString()));
        return Json.object("observation", Json.copy(page.observation), "data", data, "pagination", pagination);
    }

    private static boolean incomplete(JsonElement value) {
        if (value.isJsonObject()) {
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                String key = entry.getKey();
                if (("scanTruncated".equals(key) || "truncated".equals(key) || "cursorGap".equals(key)
                        || key.endsWith("Truncated")) && entry.getValue().isJsonPrimitive()
                        && entry.getValue().getAsBoolean()) {
                    return true;
                }
                if (incomplete(entry.getValue())) {
                    return true;
                }
            }
        } else if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                if (incomplete(element)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JsonObject signature(JsonObject arguments) {
        JsonObject signature = Json.copy(arguments);
        signature.remove("cursor");
        signature.remove("limit");
        signature.remove("timeoutMs");
        signature.remove("waitMs");
        signature.remove("view");
        return signature;
    }

    private void expire() {
        Iterator<Page> iterator = pages.values().iterator();
        while (iterator.hasNext()) {
            Page page = iterator.next();
            if (System.nanoTime() - page.createdNanos >= RETENTION_NANOS) {
                retainedBytes -= page.bytes;
                iterator.remove();
            }
        }
    }

    private void removeFirst() {
        Iterator<Page> iterator = pages.values().iterator();
        retainedBytes -= iterator.next().bytes;
        iterator.remove();
    }

    private static DomainException stale() {
        return new DomainException("stale_reference", "分页游标已失效或查询参数不一致");
    }

    private static final class Page {

        private final String tool;
        private final JsonObject arguments;
        private final List<String> rows;
        private final JsonObject metadata;
        private final JsonObject observation;
        private final long worldEpoch;
        private final boolean worldBound;
        private final int bytes;
        private final boolean document;
        private final long createdNanos = System.nanoTime();

        private Page(String tool, JsonObject arguments, List<String> rows, JsonObject metadata,
                JsonObject observation, long worldEpoch, boolean worldBound, int bytes, boolean document) {
            this.tool = tool;
            this.arguments = arguments;
            this.rows = rows;
            this.metadata = metadata;
            this.observation = observation;
            this.worldEpoch = worldEpoch;
            this.worldBound = worldBound;
            this.bytes = bytes;
            this.document = document;
        }
    }
}
