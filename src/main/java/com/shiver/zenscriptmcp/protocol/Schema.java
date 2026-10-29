package com.shiver.zenscriptmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import java.math.BigDecimal;
import java.util.Map;

/** 只构造和校验当前工具目录实际使用的 JSON Schema 子集。 */
public final class Schema {

    private static final int MIN_STRING_LENGTH = 1;
    private static final int MAX_STRING_LENGTH = 256;

    private Schema() {
    }

    public static JsonObject object(JsonObject properties, String... requiredProperties) {
        return Json.object(
                "type", "object",
                "properties", properties,
                "required", requiredProperties,
                "additionalProperties", false);
    }

    public static JsonObject string() {
        return Json.object(
                "type", "string",
                "minLength", MIN_STRING_LENGTH,
                "maxLength", MAX_STRING_LENGTH);
    }

    public static JsonObject enumeration(String... values) {
        return Json.object(
                "type", "string",
                "enum", values);
    }

    public static JsonObject integer(int minimum, int maximum) {
        return Json.object(
                "type", "integer",
                "minimum", minimum,
                "maximum", maximum);
    }

    public static JsonObject number(double minimum, double maximum) {
        return Json.object("type", "number", "minimum", minimum, "maximum", maximum);
    }

    public static JsonObject bool() {
        return Json.object("type", "boolean");
    }

    public static JsonObject array(JsonObject items, int maximum) {
        return Json.object("type", "array", "items", items, "maxItems", maximum);
    }

    public static JsonObject oneOf(JsonObject... variants) {
        JsonArray alternatives = new JsonArray();
        for (JsonObject variant : variants) {
            alternatives.add(variant);
        }
        return Json.object("oneOf", alternatives);
    }

    public static void validate(JsonObject schema, JsonElement value) {
        validate(schema, value, "arguments");
    }

    private static void validate(JsonObject schema, JsonElement value, String path) {
        if (schema.has("oneOf")) {
            int matches = 0;
            for (JsonElement alternative : schema.getAsJsonArray("oneOf")) {
                try {
                    validate(alternative.getAsJsonObject(), value, path);
                    matches++;
                } catch (IllegalArgumentException exception) {
                    // 只捕获参数不匹配；schema 实现错误继续向上传播。
                }
            }
            if (matches != 1) {
                throw invalidParameter(path);
            }
            return;
        }
        String type = schema.get("type").getAsString();
        if (value == null || value.isJsonNull()) {
            throw invalidParameter(path);
        }

        switch (type) {
            case "object":
                validateObject(schema, value, path);
                break;
            case "string":
                validateString(schema, value, path);
                break;
            case "integer":
                validateInteger(schema, value, path);
                break;
            case "number":
                validateNumber(schema, value, path);
                break;
            case "boolean":
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
                    throw invalidParameter(path);
                }
                break;
            case "array":
                validateArray(schema, value, path);
                break;
            default:
                throw new IllegalStateException("未实现的 schema 类型：" + type);
        }

        validateEnum(schema, value, path);
    }

    private static void validateObject(JsonObject schema, JsonElement value, String path) {
        if (!value.isJsonObject()) {
            throw invalidParameter(path);
        }

        JsonObject object = value.getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties");

        for (JsonElement required : schema.getAsJsonArray("required")) {
            String propertyName = required.getAsString();
            if (!object.has(propertyName)) {
                throw invalidParameter(path + "." + propertyName);
            }
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String propertyName = entry.getKey();
            String propertyPath = path + "." + propertyName;

            if (!properties.has(propertyName)) {
                throw invalidParameter(propertyPath);
            }

            validate(properties.getAsJsonObject(propertyName), entry.getValue(), propertyPath);
        }
    }

    private static void validateString(JsonObject schema, JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalidParameter(path);
        }

        int length = value.getAsString().length();
        boolean belowMinimum = schema.has("minLength") && length < schema.get("minLength").getAsInt();
        boolean aboveMaximum = schema.has("maxLength") && length > schema.get("maxLength").getAsInt();

        if (belowMinimum || aboveMaximum) {
            throw invalidParameter(path);
        }
    }

    private static void validateInteger(JsonObject schema, JsonElement value, String path) {
        validateNumber(schema, value, path);
        if (value.getAsBigDecimal().stripTrailingZeros().scale() > 0) {
            throw invalidParameter(path);
        }
    }

    private static void validateNumber(JsonObject schema, JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalidParameter(path);
        }

        BigDecimal number = value.getAsBigDecimal();
        BigDecimal minimum = schema.get("minimum").getAsBigDecimal();
        BigDecimal maximum = schema.get("maximum").getAsBigDecimal();
        boolean outOfRange = number.compareTo(minimum) < 0 || number.compareTo(maximum) > 0;

        if (outOfRange) {
            throw invalidParameter(path);
        }
    }

    private static void validateArray(JsonObject schema, JsonElement value, String path) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() > schema.get("maxItems").getAsInt()) {
            throw invalidParameter(path);
        }
        int index = 0;
        for (JsonElement item : value.getAsJsonArray()) {
            validate(schema.getAsJsonObject("items"), item, path + "[" + index++ + "]");
        }
    }

    private static void validateEnum(JsonObject schema, JsonElement value, String path) {
        if (!schema.has("enum")) {
            return;
        }

        for (JsonElement allowedValue : schema.getAsJsonArray("enum")) {
            if (allowedValue.equals(value)) {
                return;
            }
        }

        throw invalidParameter(path);
    }

    private static IllegalArgumentException invalidParameter(String path) {
        return new IllegalArgumentException("参数不符合 schema：" + path);
    }
}
