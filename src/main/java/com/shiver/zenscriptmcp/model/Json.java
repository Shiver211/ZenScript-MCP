package com.shiver.zenscriptmcp.model;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

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
}
