package com.shiver.zenscriptmcp.runtime;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/** 有界事件缓存；游标记录已检查的位置，过滤不会造成重复轮询。 */
public final class EventBuffer {

    private static final int MAX_EVENT_CHARACTERS = 8192;
    private static final int MAX_PAGE_BYTES = 131_072;

    private final Deque<Entry> entries = new ArrayDeque<>();
    private final SnapshotStore snapshots;
    private final int capacity;
    private long sequence;

    public EventBuffer(SnapshotStore snapshots, int capacity) {
        this.snapshots = snapshots;
        this.capacity = capacity;
    }

    public synchronized void add(String source, long tick, String type, JsonObject data) {
        String payload = Json.GSON.toJson(data);
        if (payload.length() > MAX_EVENT_CHARACTERS) {
            JsonObject summary = Json.object("summary", payload.substring(0, MAX_EVENT_CHARACTERS), "truncated", true);
            if (data.has("target")) {
                summary.add("target", data.get("target"));
            }
            data = summary;
        }
        JsonObject event = Json.object(
                "sequence", Long.toString(++sequence),
                "source", source,
                "tick", tick,
                "capturedAt", Instant.now().toString(),
                "worldEpoch", snapshots.worldEpoch(),
                "type", type,
                "data", data);
        entries.addLast(new Entry(sequence, Json.GSON.toJson(event)));
        while (entries.size() > capacity) {
            entries.removeFirst();
        }
    }

    public synchronized String cursor() {
        return encodeCursor(sequence);
    }

    public synchronized JsonObject read(JsonObject arguments, boolean chatOnly, int defaultLimit) {
        long cursor = arguments.has("cursor") ? decodeCursor(arguments.get("cursor").getAsString()) : 0;
        int limit = arguments.has("limit") ? arguments.get("limit").getAsInt() : defaultLimit;
        String type = arguments.has("type") ? arguments.get("type").getAsString() : "";
        String source = arguments.has("source") ? arguments.get("source").getAsString() : "auto";
        String since = arguments.has("since") ? arguments.get("since").getAsString() : "";
        Instant sinceTime = null;
        try {
            if (!since.isEmpty()) {
                sinceTime = Instant.parse(since);
            }
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("since 必须为 ISO-8601 时间");
        }
        long oldest = entries.isEmpty() ? sequence + 1 : entries.getFirst().sequence;
        JsonArray events = new JsonArray();
        int bytes = 2048;
        long checked = cursor;
        for (Entry entry : entries) {
            if (entry.sequence <= cursor) {
                continue;
            }
            JsonObject event = Json.parse(entry.json);
            String eventType = event.get("type").getAsString();
            boolean matches = (!chatOnly || "chat".equals(eventType))
                    && (type.isEmpty() || type.equals(eventType))
                    && ("auto".equals(source) || source.equals(event.get("source").getAsString()))
                    && (sinceTime == null || !Instant.parse(event.get("capturedAt").getAsString()).isBefore(sinceTime))
                    && matchesTarget(event.getAsJsonObject("data"), arguments);
            if (matches) {
                int eventBytes = PageStore.byteSize(entry.json);
                if (bytes + eventBytes > MAX_PAGE_BYTES) {
                    break;
                }
                events.add(event);
                bytes += eventBytes;
            }
            checked = entry.sequence;
            if (events.size() >= limit) {
                break;
            }
        }
        return Json.object(
                "items", events,
                "nextCursor", encodeCursor(checked),
                "cursorGap", cursor < oldest - 1,
                "oldestCursor", encodeCursor(oldest - 1),
                "truncated", checked < sequence);
    }

    private static boolean matchesTarget(JsonObject data, JsonObject arguments) {
        if (!arguments.has("target")) {
            return true;
        }
        if (!data.has("target") || !data.get("target").isJsonObject()) {
            return false;
        }
        JsonObject target = data.getAsJsonObject("target");
        for (Map.Entry<String, JsonElement> field : arguments.getAsJsonObject("target").entrySet()) {
            if (!field.getValue().equals(target.get(field.getKey()))) {
                return false;
            }
        }
        return true;
    }

    private String encodeCursor(long value) {
        return snapshots.context().get("instanceId").getAsString() + ":" + value;
    }

    private long decodeCursor(String cursor) {
        String prefix = snapshots.context().get("instanceId").getAsString() + ":";
        try {
            if (!cursor.startsWith(prefix)) {
                throw new NumberFormatException();
            }
            long value = Long.parseLong(cursor.substring(prefix.length()));
            if (value < 0 || value > sequence) {
                throw new NumberFormatException();
            }
            return value;
        } catch (NumberFormatException exception) {
            throw new DomainException("stale_reference", "事件游标无效或来自其他实例");
        }
    }

    private static final class Entry {

        private final long sequence;
        private final String json;

        private Entry(long sequence, String json) {
            this.sequence = sequence;
            this.json = json;
        }
    }
}
