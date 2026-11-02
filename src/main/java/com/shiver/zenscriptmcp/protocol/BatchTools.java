package com.shiver.zenscriptmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.client.ActionService;
import com.shiver.zenscriptmcp.game.client.SlotClickTask;
import com.shiver.zenscriptmcp.game.client.SwapSlotsTask;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.game.query.QueryService;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.ConditionMatcher;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.shiver.zenscriptmcp.runtime.PageStore;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** 批量只复用任务入口，整个批次只有一个外部作业及一次修改权限。 */
public final class BatchTools {

    private static final int MAX_STEPS = 64;
    private static final Set<String> READS = new HashSet<>(Arrays.asList("mc_status", "mc_catalog", "mc_player",
            "mc_world_query", "mc_block_inspect", "mc_entities", "mc_entity_inspect", "mc_raycast", "mc_screen",
            "mc_capabilities", "mc_nbt", "mc_recipes", "mc_commands", "mc_events", "mc_screenshot", "mc_image",
            "mc_item_inspect"));
    private static final Set<String> ACTIONS = new HashSet<>(Arrays.asList("mc_look", "mc_move", "mc_input",
            "mc_block_interact", "mc_entity_interact", "mc_item_use", "mc_click_slot", "mc_ui_action", "mc_command",
            "mc_inventory", "mc_container", "mc_chat", "mc_swap_slots"));
    private static final Set<String> WAITS = new HashSet<>(Arrays.asList("mc_wait", "mc_assert", "mc_wait_for_world"));
    private final ToolRegistry tools;
    private final QueryService queries;
    private final ActionService actions;
    private final WaitTools waits;
    private final SnapshotStore snapshots;
    private final JobManager jobs;
    private final PageStore pages;
    private final McpConfig config;

    public BatchTools(ToolRegistry tools, QueryService queries, ActionService actions, WaitTools waits,
            SnapshotStore snapshots, JobManager jobs, PageStore pages, McpConfig config) {
        this.tools = tools;
        this.queries = queries;
        this.actions = actions;
        this.waits = waits;
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.pages = pages;
        this.config = config;
    }

    public void register() {
        JsonObject step = Schema.object(Json.object("id", Schema.string(), "tool", Schema.string(),
                "arguments", Json.object()), "id", "tool", "arguments");
        JsonObject steps = Schema.array(step, MAX_STEPS);
        steps.addProperty("minItems", 1);
        JsonObject submit = Schema.object(Json.object("steps", steps, "timeoutMs", Schema.integer(1, 300000),
                "operationId", Schema.string(), "limit", Schema.integer(1, config.queries.maxLimit)), "steps");
        JsonObject page = Schema.object(Json.object("cursor", Schema.string(),
                "limit", Schema.integer(1, config.queries.maxLimit)), "cursor");
        tools.register("mc_batch", "有界混合批量，支持前序结果 $ref；首个失败停止，交换尽力恢复，其他副作用不回滚。",
                Schema.oneOf(submit, page), false, this::submit);
    }

    private JsonObject submit(JsonObject request) {
        if (request.has("cursor")) {
            JsonObject page = pages.read("mc_batch", request);
            JsonObject result = snapshots.result("completed", "data", page.getAsJsonObject("data"));
            result.add("pagination", page.get("pagination"));
            result.add("observation", page.get("observation"));
            return result;
        }
        validateSteps(request.getAsJsonArray("steps"));
        int timeout = request.has("timeoutMs") ? request.get("timeoutMs").getAsInt() : 120000;
        JsonObject client = snapshots.status().getAsJsonObject("client");
        boolean worldBound = client.has("worldAvailable") && client.get("worldAvailable").getAsBoolean();
        return jobs.submit("mc_batch", request, GameExecutor.Side.CLIENT, true, worldBound, timeout,
                QueryContext.string(request, "operationId", null), new BatchTask(Json.copy(request)));
    }

    private void validateSteps(JsonArray steps) {
        Set<String> previous = new HashSet<>();
        for (JsonElement value : steps) {
            JsonObject step = value.getAsJsonObject();
            String id = step.get("id").getAsString();
            String tool = step.get("tool").getAsString();
            if (!id.matches("[A-Za-z0-9_-]+") || previous.contains(id)
                    || !READS.contains(tool) && !ACTIONS.contains(tool) && !WAITS.contains(tool)
                    || !step.get("arguments").isJsonObject()) {
                throw new IllegalArgumentException("批量步骤 ID、工具范围或 arguments 无效");
            }
            boolean references = validateReferences(step.get("arguments"), previous);
            if (!references) {
                validateArguments(tool, step.getAsJsonObject("arguments"));
            }
            previous.add(id);
        }
    }

    private void validateArguments(String tool, JsonObject arguments) {
        tools.validate(tool, arguments);
        String action = QueryContext.string(arguments, "action", "");
        if ("stop_break".equals(action) || "release".equals(action)) {
            throw new IllegalArgumentException("批量内不能执行取消控制命令");
        }
        if (arguments.has("operationId") || arguments.has("waitMs")) {
            throw new IllegalArgumentException("waitMs 和 operationId 只能在批量外层指定");
        }
    }

    private static boolean validateReferences(JsonElement value, Set<String> previous) {
        boolean found = false;
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("$ref")) {
                if (object.size() != 1 || !object.get("$ref").isJsonPrimitive()
                        || !object.getAsJsonPrimitive("$ref").isString()) {
                    throw new IllegalArgumentException("$ref 必须是仅包含字符串引用的对象");
                }
                String reference = object.get("$ref").getAsString();
                int separator = reference.indexOf('#');
                if (separator < 1 || !previous.contains(reference.substring(0, separator))) {
                    throw new IllegalArgumentException("$ref 只能引用已完成的前序步骤");
                }
                ConditionMatcher.resolve(Json.object(), reference.substring(separator + 1));
                return true;
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                found |= validateReferences(entry.getValue(), previous);
            }
        } else if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                found |= validateReferences(element, previous);
            }
        }
        return found;
    }

    private static JsonElement resolve(JsonElement value, Map<String, JsonObject> results) {
        if (value.isJsonObject()) {
            JsonObject object = value.getAsJsonObject();
            if (object.has("$ref")) {
                String reference = object.get("$ref").getAsString();
                int separator = reference.indexOf('#');
                JsonElement resolved = ConditionMatcher.resolve(results.get(reference.substring(0, separator)),
                        reference.substring(separator + 1));
                if (resolved == null) {
                    throw new DomainException("reference_missing", "前序结果不存在引用路径：" + reference);
                }
                return Json.copy(resolved);
            }
            JsonObject resolved = new JsonObject();
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                resolved.add(entry.getKey(), resolve(entry.getValue(), results));
            }
            return resolved;
        }
        if (value.isJsonArray()) {
            JsonArray resolved = new JsonArray();
            for (JsonElement element : value.getAsJsonArray()) {
                resolved.add(resolve(element, results));
            }
            return resolved;
        }
        return value;
    }

    private static boolean readOnly(String tool, JsonObject arguments) {
        if (READS.contains(tool) || WAITS.contains(tool)) {
            return true;
        }
        String action = QueryContext.string(arguments, "action", "inspect");
        return ("mc_inventory".equals(tool) || "mc_container".equals(tool)) && "inspect".equals(action)
                || "mc_chat".equals(tool) && "read".equals(QueryContext.string(arguments, "action", "read"));
    }

    private final class BatchTask implements JobManager.Task {

        private final JsonObject request;
        private final JsonArray definitions;
        private final Map<String, JsonObject> results = new LinkedHashMap<>();
        private final JsonArray completed = new JsonArray();
        private final List<SwapSlotsTask> swaps = new ArrayList<>();
        private final List<String> swapIds = new ArrayList<>();
        private final JsonArray irreversible = new JsonArray();
        private final JsonArray recovered = new JsonArray();
        private GameExecutor.Side side = GameExecutor.Side.CLIENT;
        private JobManager.Task active;
        private JsonObject arguments;
        private JsonObject result;
        private long stepDeadlineNanos;
        private int index;
        private int recoveryIndex = -1;
        private int retainedBytes;
        private String failedStep;
        private boolean finishing;
        private boolean recoveryRouted;
        private boolean activeCleaned;
        private boolean recoveryFailed;
        private SlotClickTask cursorOwner;
        private JsonObject cursorRecovery;

        private BatchTask(JsonObject request) {
            this.request = request;
            definitions = request.getAsJsonArray("steps");
        }

        @Override
        public GameExecutor.Side executionSide() {
            if (active != null && !activeCleaned && active.executionSide() != null) {
                return active.executionSide();
            }
            return side;
        }

        @Override
        public JsonObject step() throws Exception {
            JsonObject definition = definitions.get(index).getAsJsonObject();
            String tool = definition.get("tool").getAsString();
            String id = definition.get("id").getAsString();
            try {
                if (active == null) {
                    arguments = resolve(definition.get("arguments"), results).getAsJsonObject();
                    validateArguments(tool, arguments);
                    prepare(tool, id);
                    return null;
                }
                if (System.nanoTime() - stepDeadlineNanos >= 0) {
                    throw new DomainException("deadline_exceeded", "批量步骤超过 timeoutMs");
                }
                JsonObject value = active.step();
                if (value == null) {
                    return null;
                }
                int bytes = PageStore.byteSize(Json.GSON.toJson(value));
                if (retainedBytes + bytes > config.queries.maxCacheBytes) {
                    throw new DomainException("result_too_large", "批量结果超过缓存预算");
                }
                retainedBytes += bytes;
                results.put(id, Json.copy(value));
                completed.add(Json.object("id", id, "tool", tool, "status", "succeeded", "result", value));
                if ("mc_assert".equals(tool) && !"passed".equals(QueryContext.string(value.getAsJsonObject("data"),
                        "outcome", "inconclusive"))) {
                    throw new DomainException("assertion_failed", "批量断言未通过");
                }
                active.cleanup();
                active = null;
                index++;
                if (index == definitions.size()) {
                    return Json.object("data", Json.object("outcome", "succeeded"));
                }
                return null;
            } catch (Exception exception) {
                failedStep = id;
                if (completed.size() > index) {
                    completed.get(index).getAsJsonObject().addProperty("status", "failed");
                } else {
                    completed.add(Json.object("id", id, "tool", tool, "status", "failed",
                            "result", active == null ? null : active.lastResult()));
                }
                JsonObject error = exception instanceof DomainException ? ((DomainException) exception).toJson()
                        : Json.object("code", "step_failed", "message", exception.toString(), "stage", "batch",
                                "details", Json.object("stepId", id));
                completed.get(index).getAsJsonObject().add("error", error);
                throw new DomainException(error.get("code").getAsString(), error.get("message").getAsString(),
                        "batch", Json.object("stepId", id, "cause", error));
            }
        }

        private void prepare(String tool, String id) {
            int timeout = request.has("timeoutMs") ? request.get("timeoutMs").getAsInt() : 120000;
            if (arguments.has("timeoutMs")) {
                timeout = arguments.get("timeoutMs").getAsInt();
            }
            stepDeadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeout);
            if ("mc_status".equals(tool)) {
                active = tools::queryStatus;
                side = GameExecutor.Side.CLIENT;
            } else if (WAITS.contains(tool)) {
                active = waits.internalTask(tool, arguments);
                side = GameExecutor.Side.CLIENT;
            } else if (readOnly(tool, arguments)) {
                active = queries.internalTask(tool, arguments);
                side = queries.sideFor(tool, arguments);
            } else {
                active = actions.internalTask(tool, arguments);
                cursorOwner = active instanceof SlotClickTask ? (SlotClickTask) active : null;
                side = actions.sideFor(tool, arguments);
                if (active instanceof SwapSlotsTask) {
                    swaps.add((SwapSlotsTask) active);
                    swapIds.add(id);
                } else {
                    irreversible.add(Json.object("stepId", id, "tool", tool, "status", "side_effects_not_reversible"));
                }
            }
            activeCleaned = false;
        }

        @Override
        public boolean finishStep(String reason, boolean expired) throws Exception {
            if ("succeeded".equals(reason) && failedStep == null && index == definitions.size()) {
                if (!recoveryRouted) {
                    side = GameExecutor.Side.CLIENT;
                    recoveryRouted = true;
                    return false;
                }
                result = document("succeeded", "not_needed");
                return true;
            }
            if (!finishing) {
                finishing = true;
                recoveryIndex = swaps.size() - 1;
                if (failedStep == null && index < definitions.size()) {
                    JsonObject definition = definitions.get(index).getAsJsonObject();
                    failedStep = definition.get("id").getAsString();
                    completed.add(Json.object("id", failedStep, "tool", definition.get("tool"),
                            "status", reason, "result", active == null ? null : active.lastResult()));
                }
            }
            if (!recoveryRouted) {
                // 活动步骤先在原线程清理输入，再切回客户端按逆序恢复交换。
                if (active != null && !activeCleaned) {
                    active.cleanup();
                }
                activeCleaned = true;
                side = GameExecutor.Side.CLIENT;
                recoveryRouted = true;
                return false;
            }
            if (cursorOwner != null) {
                if (!cursorOwner.recoverCursor(expired)) {
                    return false;
                }
                cursorRecovery = cursorOwner.cursorRecovery();
                recoveryFailed |= "incomplete".equals(cursorRecovery.get("status").getAsString());
                cursorOwner.cleanup();
                cursorOwner = null;
            }
            if (recoveryIndex >= 0) {
                SwapSlotsTask swap = swaps.get(recoveryIndex);
                if (!swap.recover(expired)) {
                    return false;
                }
                JsonObject recovery = swap.recovery();
                recovery.addProperty("stepId", swapIds.get(recoveryIndex));
                recovered.add(recovery);
                recoveryFailed |= !"restored".equals(recovery.get("status").getAsString());
                swap.cleanup();
                recoveryIndex--;
                if (recoveryIndex >= 0 && !expired) {
                    return false;
                }
                if (expired) {
                    while (recoveryIndex >= 0) {
                        SwapSlotsTask remaining = swaps.get(recoveryIndex);
                        remaining.recover(true);
                        JsonObject pending = remaining.recovery();
                        pending.addProperty("stepId", swapIds.get(recoveryIndex));
                        recovered.add(pending);
                        recoveryFailed |= !"restored".equals(pending.get("status").getAsString());
                        remaining.cleanup();
                        recoveryIndex--;
                    }
                }
            }
            String recoveryStatus = swaps.isEmpty() ? "not_needed" : "restored";
            if (recoveryFailed) {
                recoveryStatus = "incomplete";
            }
            result = document(reason, recoveryStatus);
            return true;
        }

        private JsonObject document(String outcome, String recoveryStatus) {
            JsonArray steps = Json.copy(completed);
            for (int skipped = steps.size(); skipped < definitions.size(); skipped++) {
                JsonObject definition = definitions.get(skipped).getAsJsonObject();
                steps.add(Json.object("id", definition.get("id"), "tool", definition.get("tool"), "status", "skipped"));
            }
            for (JsonElement value : steps) {
                JsonObject step = value.getAsJsonObject();
                if (step.has("result") && step.get("result").isJsonObject()) {
                    step.add("result", ResultView.apply(step.getAsJsonObject("result"),
                            Json.object("view", QueryContext.string(request, "view", "summary"))));
                }
            }
            JsonObject document = Json.object("observation", snapshots.observation("client", 0, new JsonArray()),
                    "data", Json.object("outcome", outcome, "steps", steps, "failedStep", failedStep,
                            "irreversibleSteps", irreversible, "recovery", Json.object("status", recoveryStatus,
                                    "steps", recovered, "cursorRecovery", cursorRecovery,
                                    "remainingCursor", actions.cursorState(),
                                    "strictTransaction", false)));
            JsonObject pageArguments = new JsonObject();
            if (request.has("limit")) {
                pageArguments.add("limit", request.get("limit"));
            }
            if (request.has("fields")) {
                pageArguments.add("fields", request.get("fields"));
            }
            return pages.document("mc_batch", pageArguments, document, true);
        }

        @Override
        public void cleanup() throws Exception {
            if (active != null && !activeCleaned) {
                active.cleanup();
                activeCleaned = true;
            }
        }

        @Override
        public JsonObject lastResult() {
            return result;
        }
    }
}
