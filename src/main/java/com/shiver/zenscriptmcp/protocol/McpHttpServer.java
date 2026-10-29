package com.shiver.zenscriptmcp.protocol;

import static java.net.HttpURLConnection.HTTP_ACCEPTED;
import static java.net.HttpURLConnection.HTTP_BAD_METHOD;
import static java.net.HttpURLConnection.HTTP_BAD_REQUEST;
import static java.net.HttpURLConnection.HTTP_ENTITY_TOO_LARGE;
import static java.net.HttpURLConnection.HTTP_FORBIDDEN;
import static java.net.HttpURLConnection.HTTP_INTERNAL_ERROR;
import static java.net.HttpURLConnection.HTTP_NOT_ACCEPTABLE;
import static java.net.HttpURLConnection.HTTP_NOT_FOUND;
import static java.net.HttpURLConnection.HTTP_OK;
import static java.net.HttpURLConnection.HTTP_UNAUTHORIZED;
import static java.net.HttpURLConnection.HTTP_UNSUPPORTED_TYPE;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.shiver.zenscriptmcp.ZenScriptMcpMod;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** 本地 HTTP 传输入口，负责认证、请求解析和协议分派。 */
public final class McpHttpServer implements AutoCloseable {

    private static final String ENDPOINT_PATH = "/mcp";
    private static final String JSON_CONTENT_TYPE = "application/json";
    private static final String SSE_CONTENT_TYPE = "text/event-stream";
    private static final int CONNECTION_BACKLOG = 16;
    private static final int WORKER_COUNT = 4;
    private static final int WORK_QUEUE_CAPACITY = 32;
    private static final int READ_BUFFER_BYTES = 4_096;
    private static final int MAX_JSON_DEPTH = 64;

    private final HttpServer server;
    private final ThreadPoolExecutor workers;
    private final McpConfig.HttpSettings settings;
    private final byte[] expectedAuthorization;
    private final ToolRegistry tools;

    public McpHttpServer(McpConfig.HttpSettings settings, String token, ToolRegistry tools) throws IOException {
        this.settings = settings;
        this.tools = tools;
        expectedAuthorization = ("Bearer " + token).getBytes(StandardCharsets.UTF_8);

        InetAddress address = InetAddress.getByName(settings.host);
        server = HttpServer.create(new InetSocketAddress(address, settings.port), CONNECTION_BACKLOG);
        workers = createWorkerPool();
        server.setExecutor(workers);
        server.createContext(ENDPOINT_PATH, this::handle);
    }

    public void start() {
        server.start();
    }

    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
        workers.shutdownNow();
    }

    private static ThreadPoolExecutor createWorkerPool() {
        return new ThreadPoolExecutor(
                WORKER_COUNT,
                WORKER_COUNT,
                0,
                TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(WORK_QUEUE_CAPACITY),
                runnable -> {
                    Thread thread = new Thread(runnable, "zenscriptmcp-http");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    private void handle(HttpExchange exchange) throws IOException {
        JsonElement requestId = JsonNull.INSTANCE;
        boolean notification = false;

        try {
            if (!validateHttpRequest(exchange)) {
                return;
            }

            JsonObject request = parseRequestBody(exchange.getRequestBody());
            JsonElement suppliedId = readRequestId(request);
            if (suppliedId != null) {
                requestId = suppliedId;
            }

            validateRpcEnvelope(request);
            String method = request.get("method").getAsString();
            notification = suppliedId == null;
            JsonObject parameters = request.has("params")
                    ? ProtocolAdapter.requiredObject(request, "params")
                    : Json.object();
            dispatch(exchange, requestId, notification, method, parameters);
        } catch (RpcException exception) {
            sendProtocolError(exchange, requestId, notification, exception);
        } catch (IllegalArgumentException exception) {
            RpcException error = new RpcException(
                    RpcException.INVALID_PARAMS, HTTP_BAD_REQUEST, exception.getMessage());
            sendProtocolError(exchange, requestId, notification, error);
        } catch (Exception exception) {
            ZenScriptMcpMod.LOGGER.error("MCP 请求执行失败", exception);
            RpcException error = new RpcException(RpcException.INTERNAL_ERROR, HTTP_INTERNAL_ERROR, "Internal error");
            sendProtocolError(exchange, requestId, notification, error);
        } finally {
            exchange.close();
        }
    }

    private boolean validateHttpRequest(HttpExchange exchange) throws IOException {
        if (!ENDPOINT_PATH.equals(exchange.getRequestURI().getPath())) {
            return rejectHttpRequest(exchange, HTTP_NOT_FOUND);
        }

        Headers headers = exchange.getRequestHeaders();
        if (!isAllowedOrigin(headers)) {
            return rejectHttpRequest(exchange, HTTP_FORBIDDEN);
        }

        if (!isAuthorized(headers)) {
            exchange.getResponseHeaders().set("WWW-Authenticate", "Bearer");
            return rejectHttpRequest(exchange, HTTP_UNAUTHORIZED);
        }

        if (!"POST".equals(exchange.getRequestMethod())) {
            exchange.getResponseHeaders().set("Allow", "POST");
            return rejectHttpRequest(exchange, HTTP_BAD_METHOD);
        }

        String contentType = headers.getFirst("Content-Type");
        if (contentType == null || !JSON_CONTENT_TYPE.equalsIgnoreCase(contentType.split(";", 2)[0].trim())) {
            return rejectHttpRequest(exchange, HTTP_UNSUPPORTED_TYPE);
        }

        String accept = headers.getFirst("Accept");
        if (accept == null || !accept.contains(JSON_CONTENT_TYPE) || !accept.contains(SSE_CONTENT_TYPE)) {
            return rejectHttpRequest(exchange, HTTP_NOT_ACCEPTABLE);
        }

        return true;
    }

    private boolean isAllowedOrigin(Headers headers) {
        String origin = headers.getFirst("Origin");
        return origin == null
                || (headers.get("Origin").size() == 1 && settings.allowedOrigins.contains(origin));
    }

    private boolean isAuthorized(Headers headers) {
        String authorization = headers.getFirst("Authorization");
        if (authorization == null || headers.get("Authorization").size() != 1) {
            return false;
        }

        return MessageDigest.isEqual(expectedAuthorization, authorization.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean rejectHttpRequest(HttpExchange exchange, int httpStatus) throws IOException {
        send(exchange, httpStatus, null);
        return false;
    }

    private JsonObject parseRequestBody(InputStream input) throws IOException {
        byte[] body = readBoundedBody(input);
        JsonElement parsed;

        try (JsonReader reader = new JsonReader(new StringReader(new String(body, StandardCharsets.UTF_8)))) {
            reader.setLenient(false);
            parsed = readJson(reader, 0);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new IOException("多余的 JSON 内容");
            }
        } catch (IOException | NumberFormatException exception) {
            throw new RpcException(RpcException.PARSE_ERROR, HTTP_BAD_REQUEST, "Parse error");
        }

        if (!parsed.isJsonObject()) {
            throw new RpcException(RpcException.INVALID_REQUEST, HTTP_BAD_REQUEST, "仅接受单个 JSON-RPC 对象");
        }

        return parsed.getAsJsonObject();
    }

    private static JsonElement readRequestId(JsonObject request) {
        JsonElement requestId = request.get("id");
        if (requestId != null && (!requestId.isJsonPrimitive() || requestId.getAsJsonPrimitive().isBoolean())) {
            throw new RpcException(RpcException.INVALID_REQUEST, HTTP_BAD_REQUEST, "无效的请求 ID");
        }

        return requestId;
    }

    private static void validateRpcEnvelope(JsonObject request) {
        boolean validVersion = new JsonPrimitive("2.0").equals(request.get("jsonrpc"));
        JsonElement method = request.get("method");
        boolean validMethod = method != null && method.isJsonPrimitive() && method.getAsJsonPrimitive().isString();

        if (!validVersion || !validMethod) {
            throw new RpcException(RpcException.INVALID_REQUEST, HTTP_BAD_REQUEST, "Invalid Request");
        }
    }

    private void dispatch(
            HttpExchange exchange,
            JsonElement requestId,
            boolean notification,
            String method,
            JsonObject parameters) throws IOException {
        Headers headers = exchange.getRequestHeaders();
        ProtocolAdapter adapter = ProtocolAdapter.select(headers, method);

        if (notification) {
            // 工具立即返回缓存或作业 ID，协议通知不能取消已经受理的游戏作业。
            send(exchange, HTTP_ACCEPTED, null);
            return;
        }

        adapter.validate(headers, method, parameters);
        JsonObject result = adapter.invoke(method, parameters, tools);
        JsonObject response = Json.object(
                "jsonrpc", "2.0",
                "id", requestId,
                "result", result);

        send(exchange, HTTP_OK, response);
    }

    private byte[] readBoundedBody(InputStream input) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[READ_BUFFER_BYTES];
        int bytesRead;

        while ((bytesRead = input.read(buffer)) >= 0) {
            if (output.size() + bytesRead > settings.maxRequestBytes) {
                throw new RpcException(RpcException.INVALID_REQUEST, HTTP_ENTITY_TOO_LARGE, "请求过大");
            }
            output.write(buffer, 0, bytesRead);
        }

        return output.toByteArray();
    }

    private static JsonElement readJson(JsonReader reader, int depth) throws IOException {
        if (depth > MAX_JSON_DEPTH) {
            throw new IOException("JSON 嵌套过深");
        }

        switch (reader.peek()) {
            case BEGIN_OBJECT:
                return readJsonObject(reader, depth);
            case BEGIN_ARRAY:
                return readJsonArray(reader, depth);
            case STRING:
                return new JsonPrimitive(reader.nextString());
            case NUMBER:
                return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN:
                return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default:
                throw new IOException("无效 JSON");
        }
    }

    private static JsonObject readJsonObject(JsonReader reader, int depth) throws IOException {
        JsonObject object = new JsonObject();
        reader.beginObject();

        while (reader.hasNext()) {
            String key = reader.nextName();
            if (object.has(key)) {
                throw new IOException("重复 JSON 字段");
            }
            object.add(key, readJson(reader, depth + 1));
        }

        reader.endObject();
        return object;
    }

    private static JsonArray readJsonArray(JsonReader reader, int depth) throws IOException {
        JsonArray array = new JsonArray();
        reader.beginArray();

        while (reader.hasNext()) {
            array.add(readJson(reader, depth + 1));
        }

        reader.endArray();
        return array;
    }

    private static void sendProtocolError(
            HttpExchange exchange,
            JsonElement requestId,
            boolean notification,
            RpcException exception) throws IOException {
        if (notification) {
            send(exchange, exception.getHttpStatus(), null);
            return;
        }

        JsonObject error = Json.object(
                "code", exception.getCode(),
                "message", exception.getMessage());

        if (exception.getData() != null) {
            error.add("data", exception.getData());
        }

        JsonObject response = Json.object(
                "jsonrpc", "2.0",
                "id", requestId,
                "error", error);

        send(exchange, exception.getHttpStatus(), response);
    }

    private static void send(HttpExchange exchange, int httpStatus, JsonObject response) throws IOException {
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        if (response == null) {
            exchange.sendResponseHeaders(httpStatus, -1);
            return;
        }

        byte[] bytes = Json.GSON.toJson(response).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", JSON_CONTENT_TYPE + "; charset=utf-8");
        exchange.sendResponseHeaders(httpStatus, bytes.length);
        exchange.getResponseBody().write(bytes);
    }
}
