package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.PageStore;
import java.util.Iterator;
import java.util.function.Function;

/** 分 Tick 构造目录与实体结果；单个模组回调无法强行中断。 */
public final class ListQueryTask<T> implements JobManager.Task {

    private static final int MAX_ENTRIES_PER_STEP = 256;

    private final String tool;
    private final QueryContext context;
    private final JsonObject arguments;
    private final Iterator<T> entries;
    private final Function<T, JsonObject> reader;
    private final PageStore pages;
    private final boolean worldBound;
    private final JsonObject metadata;
    private final JsonArray rows = new JsonArray();
    private int bytes;
    private int examined;

    public ListQueryTask(String tool, QueryContext context, JsonObject arguments, Iterator<T> entries,
            Function<T, JsonObject> reader, PageStore pages, boolean worldBound, JsonObject metadata) {
        this.tool = tool;
        this.context = context;
        this.arguments = arguments;
        this.entries = entries;
        this.reader = reader;
        this.pages = pages;
        this.worldBound = worldBound;
        this.metadata = metadata;
    }

    @Override
    public JsonObject step() {
        long deadline = context.stepDeadline();
        int processed = 0;
        while (entries.hasNext() && processed++ < MAX_ENTRIES_PER_STEP) {
            T entry = entries.next();
            examined++;
            JsonObject data;
            try {
                data = reader.apply(entry);
            } catch (RuntimeException exception) {
                context.failure("entry:callback_failed", exception);
                data = Json.object("entryIndex", examined - 1, "unavailable", true, "reason", "callback_failed");
            }
            if (data != null) {
                int size = PageStore.byteSize(Json.GSON.toJson(data));
                if (rows.size() >= context.settings().maxResults || bytes + size > context.settings().maxCacheBytes) {
                    return finish(true);
                }
                rows.add(data);
                bytes += size;
            }
            if (examined >= context.settings().maxScanBlocks) {
                return finish(entries.hasNext());
            }
            if (System.nanoTime() >= deadline) {
                break;
            }
        }
        return entries.hasNext() ? null : finish(false);
    }

    private JsonObject finish(boolean truncated) {
        metadata.addProperty("examined", examined);
        metadata.addProperty("scanTruncated", truncated);
        if (truncated) {
            context.missing("scan:result_budget");
        }
        return pages.create(tool, arguments, rows, metadata, context.observation(), worldBound);
    }
}
