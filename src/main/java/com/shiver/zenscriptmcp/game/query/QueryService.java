package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.data.CapabilityReader;
import com.shiver.zenscriptmcp.data.NbtReader;
import com.shiver.zenscriptmcp.game.client.BindingRegistry;
import com.shiver.zenscriptmcp.game.client.ScreenReader;
import com.shiver.zenscriptmcp.game.client.ScreenshotService;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.shiver.zenscriptmcp.runtime.PageStore;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.ICapabilityProvider;

/** HTTP 入口只读运行时缓存；实际查询统一作为对应游戏线程的只读作业。 */
public final class QueryService {

    private final SnapshotStore snapshots;
    private final JobManager jobs;
    private final GameExecutor executor;
    private final McpConfig config;
    private final EventBuffer events;
    private final PageStore pages;
    private final CatalogReader catalog;
    private final ScreenReader screens;
    private final ScreenshotService screenshots;
    private final ContainerTracker clientContainers;
    private final ContainerTracker serverContainers;

    public QueryService(SnapshotStore snapshots, JobManager jobs, GameExecutor executor, McpConfig config,
            EventBuffer events, PageStore pages, ScreenReader screens, ScreenshotService screenshots,
            ContainerTracker clientContainers, ContainerTracker serverContainers, BindingRegistry bindings) {
        this.snapshots = snapshots;
        this.jobs = jobs;
        this.executor = executor;
        this.config = config;
        this.events = events;
        this.pages = pages;
        this.screens = screens;
        this.screenshots = screenshots;
        this.clientContainers = clientContainers;
        this.serverContainers = serverContainers;
        catalog = new CatalogReader(snapshots, bindings);
    }

    public JsonObject submit(String tool, JsonObject arguments) {
        if ("mc_events".equals(tool) || "mc_chat".equals(tool)) {
            JsonObject data = events.read(arguments, "mc_chat".equals(tool), config.queries.defaultLimit);
            return completed(Json.object("data", data,
                    "observation", snapshots.observation("runtime", 0, new JsonArray())));
        }
        if (arguments.has("cursor")) {
            return completed(pages.read(tool, arguments));
        }
        GameExecutor.Side side = chooseSide(tool, arguments);
        boolean worldBound = worldBound(tool, side);
        JsonObject source = snapshots.status().getAsJsonObject(sideName(side));
        if (!source.has("available") || !source.get("available").getAsBoolean()) {
            throw new DomainException("source_unavailable", "所需游戏线程尚不可用");
        }
        if (worldBound && (!source.has("worldAvailable") || !source.get("worldAvailable").getAsBoolean())) {
            throw new DomainException("world_unavailable", "当前没有世界");
        }
        int timeout = arguments.has("timeoutMs") ? arguments.get("timeoutMs").getAsInt()
                : config.runtime.defaultTimeoutMillis;
        JsonObject request = Json.copy(arguments);
        JobManager.Task task = new ReadTask(tool, request, side);
        return jobs.submit(tool, request, side, false, worldBound, timeout, null, task);
    }

    private JobManager.Task taskFor(String tool, QueryContext context, JsonObject arguments) {
        switch (tool) {
            case "mc_catalog":
                return catalog.task(context, arguments, pages);
            case "mc_world_query":
                if ("blocks".equals(arguments.get("kind").getAsString())) {
                    return new WorldReader.Scan(context, arguments, pages);
                }
                return () -> context.result(WorldReader.environment(context, arguments));
            case "mc_entities":
                return entityTask(context, arguments);
            case "mc_recipes":
                return RecipeReader.task(context, arguments, pages);
            case "mc_commands":
                return CommandReader.task(context, arguments, pages);
            case "mc_screenshot":
                return screenshots.task(arguments);
            case "mc_container":
                return new ContainerTask(context, arguments);
            default:
                return () -> context.result(read(tool, context, arguments));
        }
    }

    private JsonObject read(String tool, QueryContext context, JsonObject arguments) {
        switch (tool) {
            case "mc_player":
                return EntityReader.describe(context, context.player(arguments), arguments);
            case "mc_inventory":
                return containers(context).inspect(context, context.player(arguments), true, arguments);
            case "mc_block_inspect":
                JsonObject target = arguments.getAsJsonObject("target");
                TargetResolver.checkEpoch(context, target);
                return WorldReader.block(context, context.world(target),
                        WorldReader.blockPosition(target.getAsJsonObject("position")), arguments);
            case "mc_entity_inspect":
                Entity entity = TargetResolver.entity(context, arguments.getAsJsonObject("target"));
                return EntityReader.describe(context, entity, arguments);
            case "mc_raycast":
                return WorldReader.raycast(context, arguments);
            case "mc_screen":
                return screens.inspect(context, arguments, clientContainers);
            case "mc_capabilities":
                return CapabilityReader.read(context, provider(context, arguments), arguments);
            case "mc_nbt":
                JsonObject result = new NbtReader(config.queries).read(
                        TargetResolver.nbt(provider(context, arguments)), arguments);
                result.addProperty("scope", "serialized_state");
                if (result.get("truncated").getAsBoolean()) {
                    context.missing("nbt:truncated");
                }
                if ("client".equals(context.source())) {
                    context.missing("server_internal_nbt");
                }
                return result;
            default:
                throw new IllegalArgumentException("未知查询工具：" + tool);
        }
    }

    private ICapabilityProvider provider(QueryContext context, JsonObject arguments) {
        return TargetResolver.provider(context, arguments.getAsJsonObject("target"), containers(context));
    }

    private JobManager.Task entityTask(QueryContext context, JsonObject arguments) {
        World world = context.world(arguments);
        Vec3d center = arguments.has("center") ? WorldReader.vector(arguments.getAsJsonObject("center"))
                : context.player(Json.object()).getPositionVector();
        double radius = arguments.has("radius") ? arguments.get("radius").getAsDouble() : 32;
        String namespace = QueryContext.string(arguments, "namespace", "");
        String registryName = QueryContext.string(arguments, "registryName", "");
        String name = QueryContext.string(arguments, "name", "").toLowerCase(Locale.ROOT);
        return new ListQueryTask<>("mc_entities", context, arguments,
                new ArrayList<>(world.loadedEntityList).iterator(), entity -> {
            String id = String.valueOf(EntityList.getKey(entity));
            if (entity.isDead || world.getEntityByID(entity.getEntityId()) != entity
                    || entity.getPositionVector().squareDistanceTo(center) > radius * radius
                    || (!namespace.isEmpty() && !id.startsWith(namespace + ":"))
                    || (!registryName.isEmpty() && !id.equals(registryName))
                    || (!name.isEmpty() && !entity.getName().toLowerCase(Locale.ROOT).contains(name))) {
                return null;
            }
            return EntityReader.describe(context, entity, arguments);
        }, pages, true, Json.object("dimension", world.provider.getDimension(), "center", WorldReader.vector(center),
                "radius", radius, "loadedOnly", true, "visibilityFiltered", false));
    }

    private GameExecutor.Side chooseSide(String tool, JsonObject arguments) {
        String requested = QueryContext.string(arguments, "source", "auto");
        if ("mc_commands".equals(tool)) {
            return QueryContext.string(arguments, "mode", "player").startsWith("server_")
                    ? GameExecutor.Side.SERVER : GameExecutor.Side.CLIENT;
        }
        boolean clientOnly = "mc_screen".equals(tool) || "mc_screenshot".equals(tool)
                || ("mc_catalog".equals(tool) && "keybindings".equals(QueryContext.string(arguments, "kind", "")))
                || ("mc_raycast".equals(tool) && !arguments.has("start"));
        if (clientOnly) {
            if ("server".equals(requested)) {
                throw new DomainException("source_unavailable", "该查询仅有客户端来源");
            }
            return GameExecutor.Side.CLIENT;
        }
        if (!"auto".equals(requested)) {
            return "server".equals(requested) ? GameExecutor.Side.SERVER : GameExecutor.Side.CLIENT;
        }
        if (arguments.has("target")) {
            JsonObject target = arguments.getAsJsonObject("target");
            if ("item".equals(QueryContext.string(target, "kind", ""))) {
                return target.get("containerId").getAsString().contains(":client:")
                        ? GameExecutor.Side.CLIENT : GameExecutor.Side.SERVER;
            }
        }
        if ("mc_catalog".equals(tool) || "mc_recipes".equals(tool)) {
            return GameExecutor.Side.CLIENT;
        }
        return available(GameExecutor.Side.SERVER) ? GameExecutor.Side.SERVER : GameExecutor.Side.CLIENT;
    }

    private boolean worldBound(String tool, GameExecutor.Side side) {
        if ("mc_catalog".equals(tool) || "mc_recipes".equals(tool)
                || "mc_screen".equals(tool) || "mc_screenshot".equals(tool)) {
            return false;
        }
        if ("mc_commands".equals(tool) && side == GameExecutor.Side.CLIENT) {
            JsonObject client = snapshots.status().getAsJsonObject("client");
            return client.has("worldAvailable") && client.get("worldAvailable").getAsBoolean();
        }
        return true;
    }

    private boolean available(GameExecutor.Side side) {
        JsonObject snapshot = snapshots.status().getAsJsonObject(sideName(side));
        return snapshot.has("available") && snapshot.get("available").getAsBoolean();
    }

    private QueryContext context(GameExecutor.Side side) {
        return new QueryContext(side, snapshots, events, config.queries, config.runtime.readBudgetMillis);
    }

    private ContainerTracker containers(QueryContext context) {
        return "client".equals(context.source()) ? clientContainers : serverContainers;
    }

    private JsonObject completed(JsonObject observation) {
        JsonObject result = snapshots.result("completed", "data", observation.getAsJsonObject("data"));
        result.add("observation", observation.get("observation"));
        return result;
    }

    private static String sideName(GameExecutor.Side side) {
        return side == GameExecutor.Side.CLIENT ? "client" : "server";
    }

    private final class ReadTask implements JobManager.Task {

        private final String tool;
        private final JsonObject request;
        private final GameExecutor.Side side;
        private JobManager.Task delegate;
        private boolean overBudgetReported;

        private ReadTask(String tool, JsonObject request, GameExecutor.Side side) {
            this.tool = tool;
            this.request = request;
            this.side = side;
        }

        @Override
        public JsonObject step() throws Exception {
            long startedNanos = System.nanoTime();
            if (delegate == null) {
                delegate = taskFor(tool, context(side), request);
            }
            JsonObject result = delegate.step();
            long elapsedNanos = System.nanoTime() - startedNanos;
            boolean overBudget = elapsedNanos > TimeUnit.MILLISECONDS.toNanos(config.runtime.readBudgetMillis);
            if (!overBudgetReported && overBudget) {
                overBudgetReported = true;
                JsonObject snapshot = snapshots.status().getAsJsonObject(sideName(side));
                long tick = snapshot.has("tick") ? snapshot.get("tick").getAsLong() : 0;
                events.add(sideName(side), tick, "diagnostic", Json.object("tool", tool,
                        "code", "read_budget_exceeded", "elapsedMicros", elapsedNanos / 1000));
            }
            if (result != null && PageStore.byteSize(Json.GSON.toJson(result)) > config.queries.maxPageBytes) {
                throw new DomainException("result_too_large", "结果超过预算；请缩小 fields、limit 或 NBT path");
            }
            return result;
        }

        @Override
        public void cleanup() throws Exception {
            if (delegate != null) {
                delegate.cleanup();
            }
        }
    }

    private final class ContainerTask implements JobManager.Task {

        private final QueryContext context;
        private final JsonObject arguments;
        private JsonObject primary;
        private JsonObject primaryObservation;
        private boolean submitted;
        private volatile boolean active = true;
        private volatile String peer;
        private volatile String peerError;

        private ContainerTask(QueryContext context, JsonObject arguments) {
            this.context = context;
            this.arguments = arguments;
        }

        @Override
        public JsonObject step() {
            if (!submitted) {
                submitted = true;
                EntityPlayer player = context.player(arguments);
                primary = containers(context).inspect(context, player, false, arguments);
                primaryObservation = context.observation();
                boolean includePeer = arguments.has("includePeer") ? arguments.get("includePeer").getAsBoolean()
                        : "auto".equals(QueryContext.string(arguments, "source", "auto"));
                GameExecutor.Side other = "client".equals(context.source())
                        ? GameExecutor.Side.SERVER : GameExecutor.Side.CLIENT;
                if (!includePeer) {
                    return result();
                }
                if (!available(other)) {
                    primary.addProperty("peerAvailable", false);
                    return result();
                }
                JsonObject peerArguments = Json.copy(arguments);
                peerArguments.addProperty("uuid", player.getUniqueID().toString());
                long epoch = context.epoch();
                executor.submit(other, () -> readPeer(other, peerArguments, epoch));
                return null;
            }
            if (peerError != null) {
                primary.addProperty("peerUnavailable", peerError);
                primaryObservation.addProperty("coverage", "partial");
                primaryObservation.getAsJsonArray("unavailable").add("container_peer");
                return result();
            }
            if (peer == null) {
                return null;
            }
            JsonObject observation = Json.parse(peer);
            primary.add("peer", observation);
            primary.addProperty("peerSameWindowId", primary.get("windowId").equals(
                    observation.getAsJsonObject("data").get("windowId")));
            primary.addProperty("atomic", false);
            return result();
        }

        private void readPeer(GameExecutor.Side side, JsonObject request, long epoch) {
            if (!active) {
                return;
            }
            if (epoch != snapshots.worldEpoch()) {
                peerError = "stale_reference";
                return;
            }
            try {
                QueryContext other = context(side);
                JsonObject data = containers(other).inspect(other, other.player(request), false, request);
                peer = Json.GSON.toJson(other.result(data));
            } catch (RuntimeException exception) {
                peerError = exception.toString();
            }
        }

        private JsonObject result() {
            return Json.object("observation", primaryObservation, "data", primary);
        }

        @Override
        public void cleanup() {
            active = false;
        }
    }
}
