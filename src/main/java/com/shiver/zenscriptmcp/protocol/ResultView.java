package com.shiver.zenscriptmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 展示只处理 DTO，不改变作业保存的完整证据。 */
public final class ResultView {

    private static final Set<String> ALWAYS = new LinkedHashSet<>(Arrays.asList(
            "status", "state", "outcome", "confirmation", "confirmationScope", "cleanupComplete",
            "observation", "pagination", "coverage", "unavailable", "truncated", "scanTruncated",
            "cursorGap", "nextCursor", "total", "offset", "revision", "source", "worldEpoch", "instanceId",
            "error", "fieldErrors", "collectionComplete", "recovery", "imageAvailable", "expiresAt",
            "actionable", "actionTarget", "unmappedReason", "hit", "unverifiedSteps", "verificationRequired",
            "containerChanged", "slotComparison", "containerTransition",
            "emptySlotsIncluded", "supported", "unsupported", "providerAvailable",
            "empty", "containerSlotIndex", "playerInventoryIndex", "protocolSlotNumber"));

    private ResultView() {
    }

    public static JsonObject apply(JsonObject domain, JsonObject arguments) {
        JsonObject result = Json.copy(domain);
        if (!result.has("data")) {
            return result;
        }
        JsonObject data = result.getAsJsonObject("data");
        if (!arguments.has("view") || "summary".equals(arguments.get("view").getAsString())) {
            summarize(data);
        }
        if (arguments.has("fields")) {
            result.add("data", project(data, arguments.getAsJsonArray("fields")));
        }
        return result;
    }

    public static JsonObject project(JsonObject data, JsonArray fields) {
        Set<String> paths = new LinkedHashSet<>();
        for (JsonElement field : fields) {
            paths.add(field.getAsString());
        }
        return select(data, paths).getAsJsonObject();
    }

    private static JsonElement select(JsonElement value, Set<String> paths) {
        if (paths.contains("") || !value.isJsonObject() && !value.isJsonArray()) {
            return value;
        }
        if (value.isJsonArray()) {
            JsonArray array = new JsonArray();
            for (JsonElement item : value.getAsJsonArray()) {
                array.add(select(item, paths));
            }
            return array;
        }
        JsonObject result = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            String key = entry.getKey();
            Set<String> children = new LinkedHashSet<>();
            for (String path : paths) {
                if (path.equals(key)) {
                    children.add("");
                } else if (path.startsWith(key + ".")) {
                    children.add(path.substring(key.length() + 1));
                }
            }
            // 目录类查询允许直接使用记录字段名；显式 items.xxx 也有效。
            if ("items".equals(key) && children.isEmpty()) {
                children.addAll(paths);
            }
            if (always(key)) {
                result.add(key, entry.getValue());
            } else if (!children.isEmpty()) {
                result.add(key, select(entry.getValue(), children));
            } else if (entry.getValue().isJsonObject() || entry.getValue().isJsonArray()) {
                JsonElement identity = select(entry.getValue(), children);
                if (identity.isJsonObject() && identity.getAsJsonObject().size() > 0
                        || identity.isJsonArray() && hasIdentity(identity.getAsJsonArray())) {
                    result.add(key, identity);
                }
            }
        }
        return result;
    }

    private static boolean always(String key) {
        return ALWAYS.contains(key) || key.endsWith("Id") || key.endsWith("Ref") || key.endsWith("Truncated");
    }

    private static boolean hasIdentity(JsonArray array) {
        for (JsonElement item : array) {
            if (item.isJsonObject() && item.getAsJsonObject().size() > 0) {
                return true;
            }
        }
        return false;
    }

    private static void summarize(JsonObject data) {
        if (data.has("before") && data.has("after")) {
            JsonObject before = payload(data.get("before"));
            JsonObject after = payload(data.get("after"));
            if (before != null && after != null) {
                JsonObject changes = new JsonObject();
                Set<String> keys = new LinkedHashSet<>();
                before.entrySet().forEach(entry -> keys.add(entry.getKey()));
                after.entrySet().forEach(entry -> keys.add(entry.getKey()));
                boolean containerChanged = containerChanged(before, after);
                if (containerChanged) {
                    data.addProperty("containerChanged", true);
                    data.addProperty("slotComparison", "not_comparable");
                    data.add("containerTransition", Json.object("before", containerIdentity(before),
                            "after", containerIdentity(after)));
                }
                for (String key : keys) {
                    if (containerChanged && "slots".equals(key)) {
                        continue;
                    }
                    if (!Objects.equals(before.get(key), after.get(key))) {
                        changes.add(key, change(before.get(key), after.get(key)));
                    }
                }
                data.add("changes", changes);
            }
            data.remove("before");
            data.remove("after");
            data.remove("serverBefore");
            data.remove("serverAfter");
        }
    }

    private static boolean containerChanged(JsonObject before, JsonObject after) {
        if (!before.has("slots") || !after.has("slots")) {
            return false;
        }
        for (String key : Arrays.asList("containerId", "windowId", "class", "containerClass")) {
            if (!Objects.equals(before.get(key), after.get(key))) {
                return true;
            }
        }
        return false;
    }

    private static JsonObject containerIdentity(JsonObject value) {
        JsonObject identity = new JsonObject();
        for (String key : Arrays.asList("containerId", "windowId", "class", "containerClass")) {
            if (value.has(key)) {
                identity.add(key, value.get(key));
            }
        }
        return identity;
    }

    private static JsonElement change(JsonElement before, JsonElement after) {
        if (before != null && after != null && before.isJsonArray() && after.isJsonArray()) {
            JsonArray changes = new JsonArray();
            JsonArray left = before.getAsJsonArray();
            JsonArray right = after.getAsJsonArray();
            for (int index = 0; index < Math.max(left.size(), right.size()); index++) {
                JsonElement oldValue = index < left.size() ? left.get(index) : null;
                JsonElement newValue = index < right.size() ? right.get(index) : null;
                if (!Objects.equals(oldValue, newValue)) {
                    changes.add(Json.object("index", index, "before", oldValue, "after", newValue));
                }
            }
            return changes;
        }
        return Json.object("before", before, "after", after);
    }

    private static JsonObject payload(JsonElement value) {
        if (value == null || !value.isJsonObject()) {
            return null;
        }
        JsonObject object = value.getAsJsonObject();
        return object.has("data") ? object.getAsJsonObject("data") : object;
    }
}
