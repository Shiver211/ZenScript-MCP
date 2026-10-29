package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.ZenScriptMcpMod;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.protocol.McpHttpServer;
import com.shiver.zenscriptmcp.protocol.ToolRegistry;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

/** 装配服务并在客户端、内置服务端各自的 Tick 中发布快照和推进作业。 */
public final class ClientRuntime implements AutoCloseable {

    private static final long MAINTENANCE_INTERVAL_MILLIS = 100;

    private final SnapshotStore snapshots = new SnapshotStore();
    private final Thread shutdownHook = new Thread(this::close, "zenscriptmcp-shutdown");
    private final Path gameDirectory;
    private final McpConfig config;
    private final GameExecutor executor;
    private final JobManager jobs;
    private final ToolRegistry tools;

    private McpHttpServer httpServer;
    private ScheduledExecutorService maintenanceExecutor;
    private WorldClient currentWorld;
    private long clientTicks;
    private long serverTicks;

    public ClientRuntime() throws IOException {
        gameDirectory = Minecraft.getMinecraft().gameDir.toPath();
        config = McpConfig.load(gameDirectory);
        executor = new GameExecutor(config.runtime);
        jobs = new JobManager(snapshots, executor, config.runtime.completedJobsRetained);
        tools = new ToolRegistry(snapshots, jobs, config);
    }

    public void start() throws IOException {
        if (!config.enabled) {
            return;
        }

        MinecraftForge.EVENT_BUS.register(this);

        try {
            httpServer = new McpHttpServer(config.http, config.loadToken(gameDirectory), tools);
            startMaintenance();
            snapshots.setLoadingState("ready");
            publishServerUnavailable();

            httpServer.start();
            Runtime.getRuntime().addShutdownHook(shutdownHook);
            ZenScriptMcpMod.LOGGER.info(
                    "MCP 已启动：http://{}:{}/mcp；令牌位于 {}",
                    config.http.host, config.http.port, config.http.tokenFile);
        } catch (IOException | RuntimeException exception) {
            close();
            throw exception;
        }
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        Minecraft minecraft = Minecraft.getMinecraft();
        updateWorldContext(minecraft.world);

        boolean paused = minecraft.isGamePaused();
        if (!paused) {
            clientTicks++;
        }

        snapshots.publishClient(createClientSnapshot(minecraft, paused));
        executor.tick(GameExecutor.Side.CLIENT);
    }

    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.START) {
            return;
        }

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) {
            return;
        }

        JsonObject snapshot = Json.object(
                "source", "server",
                "available", true,
                "worldAvailable", true,
                "tick", ++serverTicks,
                "heartbeatAt", Instant.now().toString());

        snapshots.publishServer(snapshot);
        executor.tick(GameExecutor.Side.SERVER);
    }

    public void serverStopping() {
        jobs.cancelSide(GameExecutor.Side.SERVER);
        executor.drain(GameExecutor.Side.SERVER);
        publishServerUnavailable();
    }

    @Override
    public synchronized void close() {
        snapshots.setLoadingState("stopping");
        jobs.close();

        if (httpServer != null) {
            httpServer.close();
            httpServer = null;
        }
        if (maintenanceExecutor != null) {
            maintenanceExecutor.shutdownNow();
            maintenanceExecutor = null;
        }

        MinecraftForge.EVENT_BUS.unregister(this);
    }

    private void startMaintenance() {
        maintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "zenscriptmcp-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        maintenanceExecutor.scheduleAtFixedRate(
                jobs::maintain,
                MAINTENANCE_INTERVAL_MILLIS,
                MAINTENANCE_INTERVAL_MILLIS,
                TimeUnit.MILLISECONDS);
    }

    private void updateWorldContext(WorldClient world) {
        if (world == currentWorld) {
            return;
        }

        currentWorld = world;
        snapshots.nextWorld();
        jobs.worldChanged();

        if (currentWorld == null) {
            publishServerUnavailable();
        }
    }

    private JsonObject createClientSnapshot(Minecraft minecraft, boolean paused) {
        String screenClass = minecraft.currentScreen == null ? null : minecraft.currentScreen.getClass().getName();
        Integer dimension = currentWorld == null ? null : currentWorld.provider.getDimension();

        return Json.object(
                "source", "client",
                "available", true,
                "worldAvailable", currentWorld != null,
                "playerAvailable", minecraft.player != null,
                "paused", paused,
                "tick", clientTicks,
                "heartbeatAt", Instant.now().toString(),
                "screen", screenClass,
                "dimension", dimension,
                "mode", connectionMode(minecraft));
    }

    private String connectionMode(Minecraft minecraft) {
        if (currentWorld == null) {
            return "menu";
        }

        return minecraft.isIntegratedServerRunning() ? "singleplayer" : "remote";
    }

    private void publishServerUnavailable() {
        snapshots.publishServer(Json.object(
                "source", "server",
                "available", false));
    }
}
