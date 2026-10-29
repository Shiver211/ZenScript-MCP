package com.shiver.zenscriptmcp.protocol;

import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.Tags;
import com.shiver.zenscriptmcp.model.Json;
import com.sun.net.httpserver.Headers;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;

/** 两种协议外壳共用工具目录，每个请求独立选择协议版本。 */
public enum ProtocolAdapter {
    LEGACY("2025-11-25"),
    MODERN("2026-07-28");

    public static final List<String> SUPPORTED_VERSIONS =
            Collections.unmodifiableList(Arrays.asList(MODERN.version, LEGACY.version));

    private static final String METADATA_PREFIX = "io.modelcontextprotocol/";
    private static final String VERSION_HEADER = "MCP-Protocol-Version";
    private static final String METHOD_HEADER = "Mcp-Method";
    private static final String NAME_HEADER = "Mcp-Name";
    private static final String BASE64_PREFIX = "=?base64?";
    private static final String BASE64_SUFFIX = "?=";

    private final String version;

    ProtocolAdapter(String version) {
        this.version = version;
    }

    public static ProtocolAdapter select(Headers headers, String method) {
        String requestedVersion = headers.getFirst(VERSION_HEADER);

        if ("initialize".equals(method) && requestedVersion == null) {
            return LEGACY;
        }
        if (LEGACY.version.equals(requestedVersion)) {
            return LEGACY;
        }
        if (MODERN.version.equals(requestedVersion)) {
            return MODERN;
        }
        if (requestedVersion == null) {
            throw new RpcException(RpcException.HEADER_MISMATCH, HTTP_BAD_REQUEST, "缺少 MCP-Protocol-Version");
        }

        JsonObject details = Json.object(
                "supported", SUPPORTED_VERSIONS,
                "requested", requestedVersion);
        throw new RpcException(
                RpcException.UNSUPPORTED_PROTOCOL_VERSION, HTTP_BAD_REQUEST, "Unsupported protocol version", details);
    }

    public void validate(Headers headers, String method, JsonObject parameters) {
        if (this == LEGACY) {
            validateLegacyMetadata(parameters);
            return;
        }

        JsonObject metadata = requiredObject(parameters, "_meta");
        matchHeader(headers, VERSION_HEADER, requiredString(metadata, METADATA_PREFIX + "protocolVersion"), false);
        matchHeader(headers, METHOD_HEADER, method, false);

        if ("tools/call".equals(method)) {
            matchHeader(headers, NAME_HEADER, requiredString(parameters, "name"), true);
        }

        validateClientInfo(requiredObject(metadata, METADATA_PREFIX + "clientInfo"));
        requiredObject(metadata, METADATA_PREFIX + "clientCapabilities");
    }

    public JsonObject invoke(String method, JsonObject parameters, ToolRegistry tools) {
        JsonObject result = invokeMethod(method, parameters, tools);

        if (this == MODERN) {
            result.addProperty("resultType", "complete");
            result.add("_meta", Json.object(METADATA_PREFIX + "serverInfo", serverInfo()));
        }

        return result;
    }

    public static JsonObject requiredObject(JsonObject parent, String key) {
        if (!parent.has(key) || !parent.get(key).isJsonObject()) {
            throw new IllegalArgumentException("必须为对象：" + key);
        }

        return parent.getAsJsonObject(key);
    }

    public static String requiredString(JsonObject parent, String key) {
        JsonElement value = parent.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException("必须为字符串：" + key);
        }

        return value.getAsString();
    }

    private void validateLegacyMetadata(JsonObject parameters) {
        if (!parameters.has("_meta") || !parameters.get("_meta").isJsonObject()) {
            return;
        }

        JsonObject metadata = parameters.getAsJsonObject("_meta");
        String versionKey = METADATA_PREFIX + "protocolVersion";

        if (metadata.has(versionKey) && !version.equals(requiredString(metadata, versionKey))) {
            throw headerMismatch(VERSION_HEADER);
        }
    }

    private static void validateClientInfo(JsonObject clientInfo) {
        requiredString(clientInfo, "name");
        requiredString(clientInfo, "version");
    }

    private static void matchHeader(Headers headers, String name, String expectedValue, boolean decodeBase64) {
        String headerValue = headers.getFirst(name);
        if (headers.get(name) != null && headers.get(name).size() != 1) {
            headerValue = null;
        }

        if (decodeBase64 && headerValue != null) {
            headerValue = decodeHeaderValue(headerValue);
        }

        if (!expectedValue.equals(headerValue)) {
            throw headerMismatch(name);
        }
    }

    private static String decodeHeaderValue(String headerValue) {
        if (!headerValue.startsWith(BASE64_PREFIX) || !headerValue.endsWith(BASE64_SUFFIX)) {
            return headerValue;
        }

        String encodedValue = headerValue.substring(
                BASE64_PREFIX.length(), headerValue.length() - BASE64_SUFFIX.length());
        try {
            return new String(Base64.getDecoder().decode(encodedValue), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException exception) {
            return null; // 交给统一的头与正文一致性校验返回 HeaderMismatch。
        }
    }

    private JsonObject invokeMethod(String method, JsonObject parameters, ToolRegistry tools) {
        switch (method) {
            case "initialize":
                return initialize(parameters);
            case "server/discover":
                return discover();
            case "ping":
                return Json.object();
            case "tools/list":
                return listTools(parameters, tools);
            case "tools/call":
                return callTool(parameters, tools);
            default:
                throw unknownMethod(method);
        }
    }

    private JsonObject initialize(JsonObject parameters) {
        if (this != LEGACY) {
            throw unknownMethod("initialize");
        }

        requiredString(parameters, "protocolVersion");
        validateClientInfo(requiredObject(parameters, "clientInfo"));
        requiredObject(parameters, "capabilities");

        return Json.object(
                "protocolVersion", LEGACY.version,
                "capabilities", capabilities(),
                "serverInfo", serverInfo());
    }

    private JsonObject discover() {
        if (this != MODERN) {
            throw unknownMethod("server/discover");
        }

        return Json.object(
                "supportedVersions", SUPPORTED_VERSIONS,
                "capabilities", capabilities());
    }

    private JsonObject listTools(JsonObject parameters, ToolRegistry tools) {
        if (parameters.has("cursor")) {
            throw new IllegalArgumentException("工具目录只有一页，不接受 cursor");
        }

        return tools.list();
    }

    private JsonObject callTool(JsonObject parameters, ToolRegistry tools) {
        String toolName = requiredString(parameters, "name");
        JsonObject arguments = parameters.has("arguments")
                ? requiredObject(parameters, "arguments")
                : Json.object();

        return tools.call(toolName, arguments);
    }

    private RpcException unknownMethod(String method) {
        int httpStatus = this == MODERN ? HTTP_NOT_FOUND : HTTP_OK;
        return new RpcException(RpcException.METHOD_NOT_FOUND, httpStatus, "Method not found: " + method);
    }

    private static RpcException headerMismatch(String headerName) {
        return new RpcException(RpcException.HEADER_MISMATCH, HTTP_BAD_REQUEST, "Header mismatch: " + headerName);
    }

    private static JsonObject capabilities() {
        return Json.object("tools", Json.object());
    }

    private static JsonObject serverInfo() {
        return Json.object(
                "name", Tags.MOD_ID,
                "version", Tags.VERSION);
    }
}
