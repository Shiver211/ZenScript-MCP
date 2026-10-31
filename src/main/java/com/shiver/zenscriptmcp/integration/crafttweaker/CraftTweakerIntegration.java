package com.shiver.zenscriptmcp.integration.crafttweaker;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.integration.GameIntegration;
import com.shiver.zenscriptmcp.integration.IntegrationContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.protocol.Schema;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;

/** 检查和重载走完整 ct 命令入口，保留安装版本的收尾过程；不自行执行单个 loader。 */
public final class CraftTweakerIntegration implements GameIntegration {

    private static final int DEFAULT_TIMEOUT_MILLIS = 120_000;
    private static final int MAX_TIMEOUT_MILLIS = 300_000;

    private final SnapshotStore snapshots;
    private final JobManager jobs;
    private final McpConfig config;
    private final EventBuffer events;
    private final CtDiagnostics diagnostics;
    private final CraftTweakerAccess access;
    private final String zenUtilsVersion;
    private boolean closed;

    public CraftTweakerIntegration(Path gameDirectory, SnapshotStore snapshots, JobManager jobs,
            McpConfig config, EventBuffer events, String zenUtilsVersion) {
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.config = config;
        this.events = events;
        this.zenUtilsVersion = zenUtilsVersion;
        diagnostics = new CtDiagnostics(snapshots, config.events.capacity, zenUtilsVersion != null);
        diagnostics.backfill(gameDirectory.resolve("crafttweaker.log"));
        access = new CraftTweakerAccess(diagnostics);
    }

    @Override
    public void register(IntegrationContext context) {
        JsonObject operation = Json.object("timeoutMs", Schema.integer(1, MAX_TIMEOUT_MILLIS),
                "operationId", Json.object("type", "string", "minLength", 1, "maxLength", 256));
        context.registerTool("check", "通过 ct syntax 检查整套脚本，返回加载器阶段、诊断区间和覆盖说明；"
                + "没有收到错误不能证明全部脚本正确。需要单人世界及玩家。",
                Schema.object(operation), false, arguments -> submit(arguments, false));
        context.registerTool("diagnostics", "读取 CT 有界日志缓存；保留原文与可可靠提取的位置，说明过滤和补读缺口。",
                Schema.object(Json.object("cursor", Schema.string(), "limit", Schema.integer(1, 1000))),
                true, arguments -> snapshots.result("completed", "data", diagnostics.read(arguments)));
        JsonObject reload = Json.copy(operation);
        JsonObject change = Schema.object(Json.object("kind", Schema.enumeration(
                "events", "commands", "entity_drops", "recipes", "registration", "unknown"),
                "loader", Schema.string()), "kind", "loader");
        JsonObject changes = Schema.array(change, 64);
        changes.addProperty("minItems", 1);
        reload.add("changes", changes);
        context.registerTool("reload", "请求已知的 ZenUtils 事件重载路径；注册、配方、未知操作或不支持的 loader"
                + " 返回 restart_required。只重载已标记脚本，不保证回滚，也不证明修改已经生效。",
                Schema.object(reload, "changes"), false, arguments -> submit(arguments, true));
    }

    private JsonObject submit(JsonObject arguments, boolean reload) {
        if (reload) {
            validateReload(arguments);
        }
        JsonObject server = snapshots.status().getAsJsonObject("server");
        if (!server.has("available") || !server.get("available").getAsBoolean()) {
            throw new DomainException("source_unavailable", "CT 工具需要可用的单人内置服务端");
        }
        int timeoutMillis = arguments.has("timeoutMs") ? arguments.get("timeoutMs").getAsInt()
                : DEFAULT_TIMEOUT_MILLIS;
        String tool = reload ? "ct_reload" : "ct_check";
        return jobs.submit(tool, arguments, GameExecutor.Side.SERVER, true, true, timeoutMillis,
                QueryContext.string(arguments, "operationId", null), new CheckTask(reload));
    }

    private void validateReload(JsonObject arguments) {
        if (!"1.27.1".equals(zenUtilsVersion)) {
            throw new DomainException("restart_required", "未启用该 ZenUtils 版本的重载路径，请完整重启");
        }
        for (JsonElement value : arguments.getAsJsonArray("changes")) {
            JsonObject change = value.getAsJsonObject();
            if (!"events".equals(change.get("kind").getAsString())
                    || !"crafttweaker".equals(change.get("loader").getAsString())) {
                throw new DomainException("restart_required", "当前仅支持 crafttweaker loader 的已标记事件脚本重载");
            }
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        diagnostics.close();
        // 启动失败时可在客户端解绑；JVM shutdown hook 不并发修改第三方 logger 的 ArrayList。
        if (Minecraft.getMinecraft().isCallingFromMinecraftThread()) {
            access.detach();
        }
    }

    private final class CheckTask implements JobManager.Task {

        private final boolean reload;
        private JsonObject result;

        private CheckTask(boolean reload) {
            this.reload = reload;
        }

        @Override
        public JsonObject step() {
            QueryContext context = new QueryContext(GameExecutor.Side.SERVER, snapshots,
                    events, config.queries, config.runtime.readBudgetMillis);
            MinecraftServer server = context.server();
            ICommandSender sender = context.player(Json.object());
            String subCommand = reload ? "reload" : "syntax";
            ICommand command = server.getCommandManager().getCommands().get("ct");
            if (command == null || !access.hasCommand(subCommand)) {
                throw new DomainException(reload ? "restart_required" : "unsupported_capability", "CT 子命令不可用");
            }
            if (!command.checkPermission(server, sender)) {
                throw new DomainException("permission_denied", "当前玩家没有 CT 命令权限");
            }
            JsonObject before = diagnostics.checkpoint();
            int returned = server.getCommandManager().executeCommand(sender, "ct " + subCommand);
            JsonObject after = diagnostics.checkpoint();
            JsonArray loaders = access.loaders();
            long errors = after.get("errors").getAsLong() - before.get("errors").getAsLong();
            boolean loaderFailed = false;
            for (JsonElement loader : loaders) {
                loaderFailed |= "ERROR".equals(loader.getAsJsonObject().get("stage").getAsString());
            }
            String outcome = errors > 0 || loaderFailed || returned <= 0 ? "failed" : "inconclusive";
            JsonObject data = Json.object("outcome", outcome, "command", "ct " + subCommand,
                    "commandReturn", returned, "diagnosticCursorStart", before.get("cursor"),
                    "diagnosticCursorEnd", after.get("cursor"), "observedErrors", errors,
                    "observedWarnings", after.get("warnings").getAsLong() - before.get("warnings").getAsLong(),
                    "loaders", loaders, "diagnosticCoverage", diagnostics.coverage(),
                    "requiresBehaviorAssertions", true);
            if (reload) {
                data.addProperty("rollbackAvailable", false);
                data.addProperty("restartRequiredOnFailure", true);
            }
            context.missing("filtered_or_unexecuted_diagnostics");
            result = context.result(data);
            if (returned <= 0) {
                throw new DomainException("command_failed", "CT 命令没有成功执行");
            }
            return result;
        }

        @Override
        public JsonObject lastResult() {
            return result;
        }
    }
}
