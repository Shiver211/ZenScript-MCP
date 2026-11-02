package com.shiver.zenscriptmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryService;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.ConditionMatcher;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.shiver.zenscriptmcp.runtime.PageStore;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.util.LinkedHashMap;
import java.util.Map;

/** 等待仅复用经过 schema 校验的只读工具，不递归调用操作或任意表达式。 */
public final class WaitTools {

    private static final int RESULT_METADATA_BYTES = 2048;

    private final ToolRegistry tools;
    private final QueryService queries;
    private final JobManager jobs;
    private final SnapshotStore snapshots;
    private final McpConfig config;
    private final Map<String, JsonObject> querySchemas = new LinkedHashMap<>();

    public WaitTools(ToolRegistry tools, QueryService queries, JobManager jobs,
            SnapshotStore snapshots, McpConfig config) {
        this.tools = tools;
        this.queries = queries;
        this.jobs = jobs;
        this.snapshots = snapshots;
        this.config = config;
    }

    public void register() {
        JsonObject ticks = tools.inputSchema("mc_wait");
        for (String name : new String[] {"mc_player", "mc_block_inspect", "mc_entity_inspect", "mc_inventory",
                "mc_container", "mc_screen", "mc_capabilities", "mc_nbt", "mc_world_query"}) {
            JsonObject schema = tools.inputSchema(name);
            if (schema.has("oneOf")) {
                schema = schema.getAsJsonArray("oneOf").get(0).getAsJsonObject();
            }
            if ("mc_world_query".equals(name)) {
                schema.getAsJsonObject("properties").add("kind", Schema.enumeration("environment"));
            }
            querySchemas.put(name, schema);
        }
        JsonObject queryArguments = Schema.object(Json.object());
        queryArguments.addProperty("additionalProperties", true);
        queryArguments.addProperty("description", "所选只读工具的参数对象；无参数也必须传 {}，执行前按工具 schema 校验。");
        JsonObject toolNames = Schema.enumeration(querySchemas.keySet().toArray(new String[0]));
        JsonObject query = Schema.object(Json.object("tool", toolNames,
                "arguments", queryArguments), "tool", "arguments");
        query.addProperty("description", "例如 {\"tool\":\"mc_player\",\"arguments\":{\"source\":\"client\"}}");
        JsonObject condition = Schema.object(Json.object("query", query,
                "path", Json.object("type", "string", "maxLength", 256),
                "operator", Schema.enumeration("exists", "eq", "ne", "gt", "gte", "lt", "lte", "contains"),
                "expected", Json.object()), "query", "path", "operator");
        JsonObject conditions = Schema.array(condition, 16);
        conditions.addProperty("minItems", 1);
        JsonObject wait = Schema.object(Json.object("conditions", conditions,
                "timeoutMs", Schema.integer(1, 300_000)), "conditions");
        tools.replace("mc_wait", "按实际 client/server Tick 等待，或等待只读条件全部满足；不占修改权限。"
                + "条件 query 必须为 {tool, arguments}，arguments 无参数时传 {}；path 使用 JSON Pointer。",
                Schema.oneOf(ticks, wait), true, arguments -> submit("mc_wait", arguments));
        tools.register("mc_assert", "只采样一轮只读条件，返回 passed/failed/inconclusive 与各项观察。"
                + "query 必须为 {tool, arguments}，如 {tool:mc_player, arguments:{source:client}}；"
                + "path 从查询结果根开始，如 /data/entityRef/dimension。"
                + "作业成功只表示断言已执行，测试结论必须读取 data.outcome。",
                wait, true, arguments -> submit("mc_assert", arguments));
        tools.register("mc_wait_for_world", "等待世界、玩家、未暂停及有效心跳；可要求内置服务端。",
                Schema.object(Json.object("requireServer", Schema.bool(),
                        "timeoutMs", Schema.integer(1, 300_000))), true, this::waitForWorld);
    }

    private JsonObject waitForWorld(JsonObject arguments) {
        boolean requireServer = arguments.has("requireServer") && arguments.get("requireServer").getAsBoolean();
        int timeoutMillis = arguments.has("timeoutMs") ? arguments.get("timeoutMs").getAsInt() : 120_000;
        return jobs.submit("mc_wait_for_world", arguments, GameExecutor.Side.CLIENT, false, false,
                timeoutMillis, null, internalTask("mc_wait_for_world", arguments));
    }

    public JobManager.Task internalTask(String tool, JsonObject arguments) {
        if ("mc_wait_for_world".equals(tool)) {
            boolean requireServer = arguments.has("requireServer") && arguments.get("requireServer").getAsBoolean();
            return new JobManager.Task() {
                private JsonObject result;

                @Override
                public JsonObject step() {
                    JsonObject readiness = snapshots.readiness(requireServer);
                    result = Json.object("data", Json.object("readiness", readiness, "runtime", snapshots.status()));
                    return readiness.get("ready").getAsBoolean() ? result : null;
                }

                @Override
                public JsonObject lastResult() {
                    return result;
                }
            };
        }
        if (arguments.has("conditions")) {
            validateConditions(arguments);
            return new ConditionsTask(arguments, "mc_assert".equals(tool));
        }
        return new TicksTask(arguments, arguments.get("side").getAsString());
    }

    private void validateConditions(JsonObject arguments) {
        int index = 0;
        for (JsonElement value : arguments.getAsJsonArray("conditions")) {
            JsonObject condition = value.getAsJsonObject();
            JsonObject query = condition.getAsJsonObject("query");
            // 目录明确展示公共结构；此处仍严格校验只读分支，禁止借条件执行动作。
            Schema.validate(querySchemas.get(query.get("tool").getAsString()), query.get("arguments"),
                    "arguments.conditions[" + index++ + "].query.arguments");
            ConditionMatcher.resolve(Json.object(), condition.get("path").getAsString());
            String operator = condition.get("operator").getAsString();
            if (!"exists".equals(operator) && !condition.has("expected")) {
                throw new IllegalArgumentException("比较条件必须提供 expected");
            }
            if ("exists".equals(operator) && condition.has("expected")) {
                Schema.validate(Schema.bool(), condition.get("expected"));
            }
        }
    }

    private JsonObject submit(String tool, JsonObject arguments) {
        boolean conditions = arguments.has("conditions");
        String sideName = conditions ? "client" : arguments.get("side").getAsString();
        JsonObject sideState = snapshots.status().getAsJsonObject(sideName);
        if (!sideState.has("available") || !sideState.get("available").getAsBoolean()) {
            throw new DomainException("source_unavailable", "等待所需线程不可用");
        }
        int timeout = arguments.has("timeoutMs") ? arguments.get("timeoutMs").getAsInt()
                : config.runtime.defaultTimeoutMillis;
        GameExecutor.Side side = "server".equals(sideName) ? GameExecutor.Side.SERVER : GameExecutor.Side.CLIENT;
        boolean worldBound = sideState.has("worldAvailable") && sideState.get("worldAvailable").getAsBoolean();
        return jobs.submit(tool, arguments, side, false, worldBound, timeout, null,
                internalTask(tool, arguments));
    }

    private final class ConditionsTask implements JobManager.Task {

        private final JsonArray conditions;
        private final boolean singlePass;
        private JsonArray observations = new JsonArray();
        private JsonObject result;
        private int index;
        private boolean matched = true;
        private boolean unmatched;
        private JobManager.Task child;
        private GameExecutor.Side side = GameExecutor.Side.CLIENT;

        private ConditionsTask(JsonObject arguments, boolean singlePass) {
            conditions = arguments.getAsJsonArray("conditions");
            this.singlePass = singlePass;
        }

        @Override
        public GameExecutor.Side executionSide() {
            return side;
        }

        @Override
        public JsonObject step() throws Exception {
            JsonObject condition = conditions.get(index).getAsJsonObject();
            JsonObject query = condition.getAsJsonObject("query");
            if (child == null) {
                JsonObject request = query.getAsJsonObject("arguments");
                String tool = query.get("tool").getAsString();
                side = queries.sideFor(tool, request);
                child = queries.internalTask(tool, request);
                return null;
            }
            JsonObject observation = child.step();
            if (observation == null) {
                return null;
            }
            child.cleanup();
            child = null;
            String match = ConditionMatcher.match(observation, condition.get("path").getAsString(),
                    condition.get("operator").getAsString(), condition.get("expected"));
            if (PageStore.byteSize(Json.GSON.toJson(observations))
                    + PageStore.byteSize(Json.GSON.toJson(observation)) + RESULT_METADATA_BYTES
                    > config.queries.maxPageBytes) {
                throw new DomainException("result_too_large", "条件观察超过结果预算，请缩小查询字段或条件数量");
            }
            observations.add(Json.object("conditionIndex", index, "match", match, "observation", observation));
            matched &= "matched".equals(match);
            unmatched |= "unmatched".equals(match);
            result = Json.object("data", Json.object("matched", matched && index == conditions.size() - 1,
                    "observations", observations, "atomic", false));
            child = null;
            if (++index < conditions.size()) {
                return null;
            }
            if (singlePass) {
                String outcome = "inconclusive";
                if (unmatched) {
                    outcome = "failed";
                } else if (matched) {
                    outcome = "passed";
                }
                result.getAsJsonObject("data").addProperty("outcome", outcome);
            }
            if (matched || singlePass) {
                return result;
            }
            index = 0;
            matched = true;
            unmatched = false;
            observations = new JsonArray();
            return null;
        }

        @Override
        public void cleanup() throws Exception {
            if (child != null) {
                child.cleanup();
            }
        }

        @Override
        public JsonObject lastResult() {
            return result;
        }
    }

    private final class TicksTask implements JobManager.Task {

        private final JsonObject request;
        private final String side;
        private long started = -1;
        private JsonObject result;

        private TicksTask(JsonObject request, String side) {
            this.request = request;
            this.side = side;
        }

        @Override
        public GameExecutor.Side executionSide() {
            return "server".equals(side) ? GameExecutor.Side.SERVER : GameExecutor.Side.CLIENT;
        }

        @Override
        public JsonObject step() {
            JsonObject snapshot = snapshots.status().getAsJsonObject(side);
            if (!snapshot.get("available").getAsBoolean()) {
                throw new DomainException("source_unavailable", "等待来源已经停止");
            }
            long tick = snapshot.get("tick").getAsLong();
            if (started < 0) {
                started = tick;
            }
            result = Json.object("data", Json.object("source", side, "ticks", tick - started),
                    "observation", snapshots.observation(side, started, new JsonArray()));
            return tick - started >= request.get("ticks").getAsLong() ? result : null;
        }

        @Override
        public JsonObject lastResult() {
            return result;
        }
    }
}
