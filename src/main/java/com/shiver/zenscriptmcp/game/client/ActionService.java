package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.ContainerTracker;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.shiver.zenscriptmcp.runtime.PageStore;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;

/** HTTP 只提交动作；任务和清理始终在选定的游戏线程执行。 */
public final class ActionService {

    private final SnapshotStore snapshots;
    private final GameExecutor executor;
    private final JobManager jobs;
    private final McpConfig config;
    private final EventBuffer events;
    private final InputController inputs;
    private final ContainerTracker containers;
    private final GuiInputBridge gui;
    private final TransactionTracker transactions;

    public ActionService(SnapshotStore snapshots, GameExecutor executor, JobManager jobs, McpConfig config,
            EventBuffer events, InputController inputs, ContainerTracker containers,
            GuiInputBridge gui, TransactionTracker transactions) {
        this.snapshots = snapshots;
        this.executor = executor;
        this.jobs = jobs;
        this.config = config;
        this.events = events;
        this.inputs = inputs;
        this.containers = containers;
        this.gui = gui;
        this.transactions = transactions;
    }

    public JsonObject submit(String tool, JsonObject arguments) {
        String action = QueryContext.string(arguments, "action", "");
        if ("stop_break".equals(action) || "release".equals(action)) {
            return jobs.cancelMutation("stop_break".equals(action) ? "mc_block_interact" : "mc_item_use");
        }
        boolean server = "mc_command".equals(tool)
                && QueryContext.string(arguments, "mode", "player").startsWith("server_");
        GameExecutor.Side side = server ? GameExecutor.Side.SERVER : GameExecutor.Side.CLIENT;
        boolean worldBound = !"mc_ui_action".equals(tool);
        JsonObject source = snapshots.status().getAsJsonObject(server ? "server" : "client");
        if (!source.has("available") || !source.get("available").getAsBoolean()) {
            throw new DomainException("source_unavailable", "操作所需的游戏线程不可用");
        }
        if (worldBound && (!source.has("worldAvailable") || !source.get("worldAvailable").getAsBoolean())) {
            throw new DomainException("world_unavailable", "当前没有可操作的世界");
        }
        JsonObject request = Json.copy(arguments);
        int timeout = request.has("timeoutMs") ? request.get("timeoutMs").getAsInt()
                : config.runtime.defaultTimeoutMillis;
        return jobs.submit(tool, request, side, true, worldBound, timeout,
                QueryContext.string(request, "operationId", null), new DeferredTask(tool, request, side));
    }

    QueryContext context(GameExecutor.Side side) {
        return new QueryContext(side, snapshots, events, config.queries, config.runtime.readBudgetMillis);
    }

    McpConfig config() {
        return config;
    }

    EventBuffer events() {
        return events;
    }

    InputController inputs() {
        return inputs;
    }

    ContainerTracker containers() {
        return containers;
    }

    TransactionTracker transactions() {
        return transactions;
    }

    long tick() {
        return snapshots.status().getAsJsonObject("client").get("tick").getAsLong();
    }

    boolean serverAvailable() {
        JsonObject server = snapshots.status().getAsJsonObject("server");
        return server.has("available") && server.get("available").getAsBoolean();
    }

    PeerObservation peer(JsonObject arguments, boolean container) {
        return new PeerObservation(executor, snapshots, () -> context(GameExecutor.Side.SERVER), arguments, container);
    }

    static EntityPlayerSP player() {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.world == null) {
            throw new DomainException("world_unavailable", "当前没有世界");
        }
        if (minecraft.player == null || !minecraft.player.isEntityAlive()) {
            throw new DomainException("player_unavailable", "玩家不存在或已经死亡");
        }
        return minecraft.player;
    }

    private JobManager.Task create(String tool, JsonObject request, GameExecutor.Side side) {
        switch (tool) {
            case "mc_look":
            case "mc_move":
            case "mc_input":
                return new InputTask(this, tool, request);
            case "mc_block_interact":
            case "mc_entity_interact":
            case "mc_item_use":
                return new InteractionTask(this, tool, request);
            case "mc_click_slot":
            case "mc_inventory":
            case "mc_container":
                return new ContainerActionTask(this, tool, request);
            case "mc_ui_action":
                return gui.task(request);
            case "mc_command":
            case "mc_chat":
                return new CommandTask(this, tool, request, side);
            default:
                throw new IllegalArgumentException("未知操作工具：" + tool);
        }
    }

    private final class DeferredTask implements JobManager.Task {

        private final String tool;
        private final JsonObject request;
        private final GameExecutor.Side side;
        private JobManager.Task delegate;

        private DeferredTask(String tool, JsonObject request, GameExecutor.Side side) {
            this.tool = tool;
            this.request = request;
            this.side = side;
        }

        @Override
        public JsonObject step() throws Exception {
            if (delegate == null) {
                delegate = create(tool, request, side);
            }
            JsonObject result = delegate.step();
            if (result != null && PageStore.byteSize(Json.GSON.toJson(result)) > config.queries.maxPageBytes) {
                throw new DomainException("result_too_large", "操作证据超过结果预算");
            }
            return result;
        }

        @Override
        public void cleanup() throws Exception {
            if (delegate != null) {
                delegate.cleanup();
            }
        }

        @Override
        public JsonObject lastResult() {
            return delegate == null ? null : delegate.lastResult();
        }
    }
}
