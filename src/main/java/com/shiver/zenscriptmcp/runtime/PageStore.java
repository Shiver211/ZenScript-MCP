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
        List<String> serialized = new ArrayList<>();
        int bytes = 0;
        for (JsonElement row : rows) {
            String json = Json.GSON.toJson(row);
            int rowBytes = byteSize(json);
            if (rowBytes > settings.maxPageBytes / 2 || bytes + rowBytes > settings.maxCacheBytes) {
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
                Json.copy(observation), epoch, worldBound, bytes);
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
        String id = cursor.substring(0, separator);
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
        int bytes = byteSize(Json.GSON.toJson(data)) + 2048;
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
        data.add("items", rows);
        data.addProperty("total", page.rows.size());
        data.addProperty("offset", offset);
        data.addProperty("truncated", end < page.rows.size() || flag(data, "scanTruncated"));
        data.add("nextCursor", Json.GSON.toJsonTree(end < page.rows.size() ? id + ":" + end : null));
        return Json.object("observation", Json.copy(page.observation), "data", data);
    }

    private static boolean flag(JsonObject data, String key) {
        return data.has(key) && data.get(key).getAsBoolean();
    }

    private static JsonObject signature(JsonObject arguments) {
        JsonObject signature = Json.copy(arguments);
        signature.remove("cursor");
        signature.remove("limit");
        signature.remove("timeoutMs");
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
        private final long createdNanos = System.nanoTime();

        private Page(String tool, JsonObject arguments, List<String> rows, JsonObject metadata,
                JsonObject observation, long worldEpoch, boolean worldBound, int bytes) {
            this.tool = tool;
            this.arguments = arguments;
            this.rows = rows;
            this.metadata = metadata;
            this.observation = observation;
            this.worldEpoch = worldEpoch;
            this.worldBound = worldBound;
            this.bytes = bytes;
        }
    }
}
