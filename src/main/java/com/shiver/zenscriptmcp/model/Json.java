package com.shiver.zenscriptmcp.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.Map;

/** 仅使用 Minecraft 自带 Gson 的旧版 API。 */
public final class Json {

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    /**
     * 按 key、value 交替的参数构造对象；调用点应将每个字段放在独立一行。
     */
    public static JsonObject object(Object... keyValuePairs) {
        JsonObject result = new JsonObject();

        for (int index = 0; index < keyValuePairs.length; index += 2) {
            String key = (String) keyValuePairs[index];
            Object value = keyValuePairs[index + 1];
            JsonElement jsonValue = value instanceof JsonElement
                    ? (JsonElement) value
                    : GSON.toJsonTree(value);

            result.add(key, jsonValue);
        }

        return result;
    }

    public static JsonObject parse(String json) {
        return new JsonParser().parse(json).getAsJsonObject();
    }

    public static JsonObject copy(JsonObject object) {
        return parse(GSON.toJson(object));
    }

    public static JsonArray copy(JsonArray array) {
        return copy((JsonElement) array).getAsJsonArray();
    }

    /** Gson 的树反序列化可能直接返回原对象；显式复制可变节点，兼容游戏中的不同 Gson 版本。 */
    public static JsonElement copy(JsonElement value) {
        if (value.isJsonObject()) {
            JsonObject result = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
                result.add(entry.getKey(), copy(entry.getValue()));
            }
            return result;
        }
        if (value.isJsonArray()) {
            JsonArray result = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) {
                result.add(copy(element));
            }
            return result;
        }
        return value;
    }
}
