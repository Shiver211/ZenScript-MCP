package com.shiver.zenscriptmcp.runtime;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.Map;

/** 受限只读条件；缺字段、部分覆盖和 NBT 类型不同不能被当作匹配。 */
public final class ConditionMatcher {

    private ConditionMatcher() {
    }

    public static String match(JsonObject observation, String pointer, String operator, JsonElement expected) {
        JsonElement actual = resolve(observation, pointer);
        boolean partial = observation.has("observation") && "partial".equals(
                observation.getAsJsonObject("observation").get("coverage").getAsString());
        if (actual == null) {
            if (partial || !"exists".equals(operator)) {
                return "unknown";
            }
            return expected == null || expected.getAsBoolean() ? "unmatched" : "matched";
        }
        if ("exists".equals(operator)) {
            return expected == null || expected.getAsBoolean() ? "matched" : "unmatched";
        }
        if (expected == null) {
            throw new IllegalArgumentException("比较条件需要 expected");
        }
        if ("eq".equals(operator) || "ne".equals(operator)) {
            boolean equal = equal(actual, expected);
            return equal == "eq".equals(operator) ? "matched" : "unmatched";
        }
        if ("contains".equals(operator)) {
            return contains(actual, expected) ? "matched" : "unmatched";
        }
        if (actual.isJsonObject() && expected.isJsonObject()
                && actual.getAsJsonObject().has("type") && expected.getAsJsonObject().has("type")
                && !actual.getAsJsonObject().get("type").equals(expected.getAsJsonObject().get("type"))) {
            return "unmatched";
        }
        BigDecimal left = number(actual);
        BigDecimal right = number(expected);
        if (left == null || right == null) {
            return "unknown";
        }
        int comparison = left.compareTo(right);
        boolean matched;
        switch (operator) {
            case "gt":
                matched = comparison > 0;
                break;
            case "gte":
                matched = comparison >= 0;
                break;
            case "lt":
                matched = comparison < 0;
                break;
            case "lte":
                matched = comparison <= 0;
                break;
            default:
                throw new IllegalArgumentException("未知比较操作");
        }
        return matched ? "matched" : "unmatched";
    }

    public static JsonElement resolve(JsonElement value, String pointer) {
        if (pointer.isEmpty()) {
            return value;
        }
        if (!pointer.startsWith("/")) {
            throw new IllegalArgumentException("path 必须是 JSON Pointer");
        }
        String[] segments = pointer.substring(1).split("/", -1);
        for (String encoded : segments) {
            if (encoded.matches(".*~([^01]|$).*")) {
                throw new IllegalArgumentException("JSON Pointer 转义无效");
            }
        }
        for (String encoded : segments) {
            String key = encoded.replace("~1", "/").replace("~0", "~");
            if (value != null && value.isJsonObject()) {
                value = value.getAsJsonObject().get(key);
            } else if (value != null && value.isJsonArray()) {
                try {
                    int index = Integer.parseInt(key);
                    value = index >= 0 && index < value.getAsJsonArray().size()
                            ? value.getAsJsonArray().get(index) : null;
                } catch (NumberFormatException exception) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return value;
    }

    private static BigDecimal number(JsonElement value) {
        if (value.isJsonObject() && value.getAsJsonObject().has("type") && value.getAsJsonObject().has("value")) {
            String type = value.getAsJsonObject().get("type").getAsString();
            if (!type.matches("byte|short|int|long|float|double")) {
                return null;
            }
            value = value.getAsJsonObject().get("value");
        }
        if (!value.isJsonPrimitive()) {
            return null;
        }
        try {
            return new BigDecimal(value.getAsString());
        } catch (NumberFormatException exception) {
            return null;
        }
    }

    private static boolean equal(JsonElement left, JsonElement right) {
        if (left.isJsonPrimitive() && right.isJsonPrimitive()
                && left.getAsJsonPrimitive().isNumber() && right.getAsJsonPrimitive().isNumber()) {
            return left.getAsBigDecimal().compareTo(right.getAsBigDecimal()) == 0;
        }
        if (left.isJsonArray() && right.isJsonArray()) {
            if (left.getAsJsonArray().size() != right.getAsJsonArray().size()) {
                return false;
            }
            for (int index = 0; index < left.getAsJsonArray().size(); index++) {
                if (!equal(left.getAsJsonArray().get(index), right.getAsJsonArray().get(index))) {
                    return false;
                }
            }
            return true;
        }
        if (left.isJsonObject() && right.isJsonObject()) {
            if (left.getAsJsonObject().entrySet().size() != right.getAsJsonObject().entrySet().size()) {
                return false;
            }
            for (Map.Entry<String, JsonElement> entry : left.getAsJsonObject().entrySet()) {
                JsonElement other = right.getAsJsonObject().get(entry.getKey());
                if (other == null || !equal(entry.getValue(), other)) {
                    return false;
                }
            }
            return true;
        }
        return left.equals(right);
    }

    private static boolean contains(JsonElement actual, JsonElement expected) {
        if (actual.isJsonArray()) {
            for (JsonElement entry : actual.getAsJsonArray()) {
                if (equal(entry, expected)) {
                    return true;
                }
            }
        } else if (actual.isJsonPrimitive() && actual.getAsJsonPrimitive().isString()
                && expected.isJsonPrimitive() && expected.getAsJsonPrimitive().isString()) {
            return actual.getAsString().contains(expected.getAsString());
        } else if (actual.isJsonObject() && expected.isJsonPrimitive() && expected.getAsJsonPrimitive().isString()) {
            return actual.getAsJsonObject().has(expected.getAsString());
        }
        return false;
    }
}
