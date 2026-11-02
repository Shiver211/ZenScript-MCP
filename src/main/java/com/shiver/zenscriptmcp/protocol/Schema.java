package com.shiver.zenscriptmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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

    public static void validate(JsonObject schema, JsonElement value, String path) {
        // expected 接受任意 JSON 值；外层请求体预算仍限制其大小。
        if (schema.entrySet().isEmpty()) {
            return;
        }
        if (schema.has("oneOf")) {
            int matches = 0;
            IllegalArgumentException failure = null;
            int failureScore = -1;
            for (JsonElement alternative : schema.getAsJsonArray("oneOf")) {
                try {
                    validate(alternative.getAsJsonObject(), value, path);
                    matches++;
                } catch (IllegalArgumentException exception) {
                    // 优先报告与已提供字段、动作枚举匹配的分支，保留嵌套参数提示。
                    int score = branchScore(alternative.getAsJsonObject(), value);
                    if (score > failureScore) {
                        failure = exception;
                        failureScore = score;
                    }
                }
            }
            if (matches == 0 && failure != null) {
                throw failure;
            }
            if (matches != 1) {
                throw invalidParameter(path, schema);
            }
            return;
        }
        String type = schema.get("type").getAsString();
        if (value == null || value.isJsonNull()) {
            throw invalidParameter(path, schema);
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
                    throw invalidParameter(path, schema);
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

    private static int branchScore(JsonObject schema, JsonElement value) {
        if (value == null || !value.isJsonObject() || !schema.has("properties")) {
            return 0;
        }
        int score = 0;
        for (Map.Entry<String, JsonElement> property : schema.getAsJsonObject("properties").entrySet()) {
            JsonElement supplied = value.getAsJsonObject().get(property.getKey());
            if (supplied != null) {
                score++;
                JsonObject constraint = property.getValue().getAsJsonObject();
                if (constraint.has("enum") && constraint.getAsJsonArray("enum").contains(supplied)) {
                    score++;
                }
            }
        }
        return score;
    }

    private static void validateObject(JsonObject schema, JsonElement value, String path) {
        if (!value.isJsonObject()) {
            throw invalidParameter(path, schema);
        }

        JsonObject object = value.getAsJsonObject();
        JsonObject properties = schema.getAsJsonObject("properties");

        for (JsonElement required : schema.getAsJsonArray("required")) {
            String propertyName = required.getAsString();
            if (!object.has(propertyName)) {
                throw invalidParameter(path + "." + propertyName, schema);
            }
        }

        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String propertyName = entry.getKey();
            String propertyPath = path + "." + propertyName;

            if (!properties.has(propertyName)) {
                if (schema.has("additionalProperties") && schema.get("additionalProperties").getAsBoolean()) {
                    continue;
                }
                throw invalidParameter(propertyPath, schema);
            }

            validate(properties.getAsJsonObject(propertyName), entry.getValue(), propertyPath);
        }
    }

    private static void validateString(JsonObject schema, JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw invalidParameter(path, schema);
        }

        int length = value.getAsString().length();
        boolean belowMinimum = schema.has("minLength") && length < schema.get("minLength").getAsInt();
        boolean aboveMaximum = schema.has("maxLength") && length > schema.get("maxLength").getAsInt();

        if (belowMinimum || aboveMaximum) {
            throw invalidParameter(path, schema);
        }
    }

    private static void validateInteger(JsonObject schema, JsonElement value, String path) {
        validateNumber(schema, value, path);
        if (value.getAsBigDecimal().stripTrailingZeros().scale() > 0) {
            throw invalidParameter(path, schema);
        }
    }

    private static void validateNumber(JsonObject schema, JsonElement value, String path) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalidParameter(path, schema);
        }

        BigDecimal number = value.getAsBigDecimal();
        BigDecimal minimum = schema.get("minimum").getAsBigDecimal();
        BigDecimal maximum = schema.get("maximum").getAsBigDecimal();
        boolean outOfRange = number.compareTo(minimum) < 0 || number.compareTo(maximum) > 0;

        if (outOfRange) {
            throw invalidParameter(path, schema);
        }
    }

    private static void validateArray(JsonObject schema, JsonElement value, String path) {
        if (!value.isJsonArray() || value.getAsJsonArray().size() > schema.get("maxItems").getAsInt()) {
            throw invalidParameter(path, schema);
        }
        if (schema.has("minItems") && value.getAsJsonArray().size() < schema.get("minItems").getAsInt()) {
            throw invalidParameter(path, schema);
        }
        Set<JsonElement> unique = new HashSet<>();
        int index = 0;
        for (JsonElement item : value.getAsJsonArray()) {
            if (schema.has("uniqueItems") && schema.get("uniqueItems").getAsBoolean() && !unique.add(item)) {
                throw invalidParameter(path, schema);
            }
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

        throw invalidParameter(path, schema);
    }

    private static IllegalArgumentException invalidParameter(String path, JsonObject schema) {
        StringBuilder message = new StringBuilder("参数不符合 schema：").append(path);
        if (schema.has("type")) {
            message.append("；要求类型 ").append(schema.get("type").getAsString());
        }
        if (schema.has("enum")) {
            message.append("；允许值 ").append(schema.get("enum"));
        }
        if (schema.has("minimum") && schema.has("maximum")) {
            message.append("；范围 ").append(schema.get("minimum")).append("..").append(schema.get("maximum"));
        }
        if (schema.has("required") && schema.getAsJsonArray("required").size() > 0) {
            message.append("；必填字段 ").append(schema.get("required"));
        }
        if (schema.has("description")) {
            message.append("；").append(schema.get("description").getAsString());
        }
        return new IllegalArgumentException(message.toString());
    }
}
