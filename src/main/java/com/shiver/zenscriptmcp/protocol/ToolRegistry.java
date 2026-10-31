package com.shiver.zenscriptmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.Tags;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Supplier;

/** 保存固定工具定义，校验输入，并将领域结果包装为 MCP 工具结果。 */
public final class ToolRegistry {

    private static final int MAX_WAIT_TICKS = 12_000;
    private static final int MAX_TIMEOUT_MILLIS = 300_000;

    private final Map<String, Tool> tools = new LinkedHashMap<>();
    private final SnapshotStore snapshots;
    private final JobManager jobs;
    private final McpConfig config;
    private Function<JsonObject, JsonArray> contentProvider = result -> new JsonArray();
    private Supplier<JsonObject> integrationStatus = JsonObject::new;

    public ToolRegistry(SnapshotStore snapshots, JobManager jobs, McpConfig config) {
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.config = config;

        registerStatusTool();
        registerJobTools();
        registerWaitTool();
    }

    public void register(
            String name,
            String description,
            JsonObject inputSchema,
            boolean readOnly,
            Function<JsonObject, JsonObject> handler) {
        register(name, description, inputSchema, readOnly, !readOnly, handler);
    }

    public void register(
            String name,
            String description,
            JsonObject inputSchema,
            boolean readOnly,
            boolean destructive,
            Function<JsonObject, JsonObject> handler) {
        if (tools.containsKey(name)) {
            throw new IllegalArgumentException("工具重名：" + name);
        }

        // MCP 工具输入的根类型必须显式为 object，包含 oneOf 的动作分支也遵守此约定。
        inputSchema = Json.copy(inputSchema);
        inputSchema.addProperty("type", "object");
        JsonObject annotations = Json.object(
                "readOnlyHint", readOnly,
                "destructiveHint", destructive,
                "openWorldHint", false);
        JsonObject definition = Json.object(
                "name", name,
                "description", description,
                "inputSchema", inputSchema,
                "outputSchema", createOutputSchema(),
                "annotations", annotations);

        tools.put(name, new Tool(definition, handler));
    }

    public JsonObject list() {
        JsonArray definitions = new JsonArray();
        for (Tool tool : tools.values()) {
            definitions.add(Json.copy(tool.definition));
        }

        return Json.object("tools", definitions);
    }

    public void replace(String name, String description, JsonObject schema, boolean readOnly,
            Function<JsonObject, JsonObject> handler) {
        if (!tools.containsKey(name)) {
            throw new IllegalArgumentException("待扩展工具不存在：" + name);
        }
        tools.remove(name);
        register(name, description, schema, readOnly, handler);
    }

    public JsonObject inputSchema(String name) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具：" + name);
        }
        return Json.copy(tool.definition.getAsJsonObject("inputSchema"));
    }

    public boolean contains(String name) {
        return tools.containsKey(name);
    }

    public void validate(String name, JsonObject arguments) {
        Schema.validate(inputSchema(name), arguments);
    }

    public void setContentProvider(Function<JsonObject, JsonArray> contentProvider) {
        this.contentProvider = contentProvider;
    }

    public void setIntegrationStatus(Supplier<JsonObject> integrationStatus) {
        this.integrationStatus = integrationStatus;
    }

    public JsonObject call(String name, JsonObject arguments) {
        Tool tool = tools.get(name);
        if (tool == null) {
            throw new IllegalArgumentException("未知工具：" + name);
        }

        Schema.validate(tool.definition.getAsJsonObject("inputSchema"), arguments);

        JsonObject domainResult;
        try {
            domainResult = tool.handler.apply(arguments);
        } catch (DomainException exception) {
            domainResult = snapshots.error(exception.getCode(), exception.getMessage());
        }

        return createToolResult(domainResult);
    }

    private void registerStatusTool() {
        register(
                "mc_status",
                "读取缓存的实例、加载、世界、两端心跳与作业状态；不等待游戏线程。",
                Schema.object(Json.object()),
                true,
                arguments -> queryStatus());
    }

    private void registerJobTools() {
        JsonObject statusSchema = Schema.object(Json.object("jobId", Schema.string()), "jobId");
        register(
                "job_status",
                "查询当前实例的作业；调用成功不代表 job.state 成功。来源：运行时缓存。",
                statusSchema,
                true,
                arguments -> jobs.status(arguments.get("jobId").getAsString()));

        JsonObject cancelSchema = Schema.object(Json.object("jobId", Schema.string()), "jobId");
        register(
                "job_cancel",
                "请求协作式取消；cleanupComplete=true 才表示清理完成。来源：运行时缓存。",
                cancelSchema,
                false,
                arguments -> jobs.cancel(arguments.get("jobId").getAsString()));
    }

    private void registerWaitTool() {
        JsonObject properties = Json.object(
                "side", Schema.enumeration("client", "server"),
                "ticks", Schema.integer(0, MAX_WAIT_TICKS),
                "timeoutMs", Schema.integer(1, MAX_TIMEOUT_MILLIS));
        JsonObject inputSchema = Schema.object(properties, "side", "ticks");

        register(
                "mc_wait",
                "按 client/server 的实际逻辑 Tick 等待；P0 仅支持 Tick 等待。返回作业，不占修改权限。",
                inputSchema,
                true,
                this::submitWait);
    }

    private JsonObject queryStatus() {
        JsonObject data = snapshots.status();
        data.add("jobs", jobs.summary());
        data.addProperty("javaVersion", System.getProperty("java.version"));
        data.addProperty("processId", ManagementFactory.getRuntimeMXBean().getName().split("@", 2)[0]);
        data.addProperty("modVersion", Tags.VERSION);
        data.add("protocolVersions", Json.GSON.toJsonTree(ProtocolAdapter.SUPPORTED_VERSIONS));
        data.add("integrations", integrationStatus.get());
        data.add("queryBudget", Json.GSON.toJsonTree(config.queries));
        data.add("eventBudget", Json.GSON.toJsonTree(config.events));
        data.add("screenshotBudget", Json.GSON.toJsonTree(config.screenshots));

        return snapshots.result("completed", "data", data);
    }

    private JsonObject submitWait(JsonObject arguments) {
        String sourceName = arguments.get("side").getAsString();
        JsonObject sourceSnapshot = snapshots.status().getAsJsonObject(sourceName);

        if (!sourceSnapshot.has("available") || !sourceSnapshot.get("available").getAsBoolean()) {
            throw new DomainException("source_unavailable", "指定 Tick 来源不可用");
        }

        int requestedTicks = arguments.get("ticks").getAsInt();
        int timeoutMillis = arguments.has("timeoutMs")
                ? arguments.get("timeoutMs").getAsInt()
                : config.runtime.defaultTimeoutMillis;
        GameExecutor.Side side = "client".equals(sourceName) ? GameExecutor.Side.CLIENT : GameExecutor.Side.SERVER;
        boolean worldBound = sourceSnapshot.has("worldAvailable")
                && sourceSnapshot.get("worldAvailable").getAsBoolean();
        JobManager.Task task = new WaitForTicksTask(snapshots, sourceName, requestedTicks);

        return jobs.submit("mc_wait", arguments, side, false, worldBound, timeoutMillis, null, task);
    }

    private static JsonObject createOutputSchema() {
        JsonObject properties = Json.object(
                "schemaVersion", Json.object("type", "integer", "const", 1),
                "status", Schema.enumeration("accepted", "completed", "error"),
                "context", createContextSchema(),
                "observation", createObservationSchema(),
                "data", Json.object("type", "object"),
                "job", Json.object("type", "object"),
                "error", Json.object("type", "object"));

        JsonArray payloadAlternatives = new JsonArray();
        payloadAlternatives.add(Json.object("required", new String[] {"data"}));
        payloadAlternatives.add(Json.object("required", new String[] {"job"}));
        payloadAlternatives.add(Json.object("required", new String[] {"error"}));

        return Json.object(
                "type", "object",
                "required", new String[] {"schemaVersion", "status", "context"},
                "properties", properties,
                "oneOf", payloadAlternatives);
    }

    private static JsonObject createContextSchema() {
        JsonObject properties = Json.object(
                "instanceId", Json.object("type", "string"),
                "worldEpoch", Json.object("type", "integer"));

        return Json.object(
                "type", "object",
                "required", new String[] {"instanceId", "worldEpoch"},
                "properties", properties);
    }

    private static JsonObject createObservationSchema() {
        JsonObject properties = Json.object(
                "source", Schema.enumeration("client", "server", "runtime"),
                "worldEpoch", Json.object("type", "integer"),
                "tick", Json.object("type", "integer"),
                "startedTick", Json.object("type", "integer"),
                "endedTick", Json.object("type", "integer"),
                "capturedAt", Json.object("type", "string"),
                "revision", Json.object("type", "integer"),
                "coverage", Schema.enumeration("complete", "partial"),
                "unavailable", Json.object("type", "array", "items", Json.object("type", "string")));
        return Json.object("type", "object", "properties", properties,
                "required", new String[] {"source", "worldEpoch", "capturedAt", "revision", "coverage", "unavailable"});
    }

    private JsonObject createToolResult(JsonObject domainResult) {
        boolean error = "error".equals(domainResult.get("status").getAsString());
        JsonArray media = contentProvider.apply(domainResult);
        JsonArray content = new JsonArray();

        // 文本保留完整数据，兼容不读取 structuredContent 的客户端。
        content.add(Json.object(
                "type", "text",
                "text", Json.GSON.toJson(domainResult)));
        for (JsonElement item : media) {
            content.add(item);
        }

        return Json.object(
                "content", content,
                "structuredContent", domainResult,
                "isError", error);
    }

    private static final class Tool {

        private final JsonObject definition;
        private final Function<JsonObject, JsonObject> handler;

        private Tool(JsonObject definition, Function<JsonObject, JsonObject> handler) {
            this.definition = definition;
            this.handler = handler;
        }
    }

    private static final class WaitForTicksTask implements JobManager.Task {

        private final SnapshotStore snapshots;
        private final String sourceName;
        private final int requestedTicks;

        private long startedTick = -1;

        private WaitForTicksTask(SnapshotStore snapshots, String sourceName, int requestedTicks) {
            this.snapshots = snapshots;
            this.sourceName = sourceName;
            this.requestedTicks = requestedTicks;
        }

        @Override
        public JsonObject step() {
            JsonObject snapshot = snapshots.status().getAsJsonObject(sourceName);
            if (!snapshot.get("available").getAsBoolean()) {
                throw new DomainException("source_unavailable", "Tick 来源已停止");
            }

            long currentTick = snapshot.get("tick").getAsLong();
            if (startedTick < 0) {
                startedTick = currentTick;
            }

            long elapsedTicks = currentTick - startedTick;
            if (elapsedTicks < requestedTicks) {
                return null;
            }

            return Json.object(
                    "source", sourceName,
                    "ticks", elapsedTicks,
                    "observation", snapshot);
        }
    }
}
