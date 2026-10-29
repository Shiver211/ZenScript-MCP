package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.fml.common.FMLCommonHandler;

/** 仅在选定的游戏线程创建和使用；HTTP 线程不解析游戏目标。 */
public final class QueryContext {

    private final SnapshotStore snapshots;
    private final EventBuffer events;
    private final McpConfig.QuerySettings settings;
    private final String source;
    private final long startedTick;
    private final long worldEpoch;
    private final long stepBudgetNanos;
    private final Set<String> unavailable = new LinkedHashSet<>();

    public QueryContext(GameExecutor.Side side, SnapshotStore snapshots,
            EventBuffer events, McpConfig.QuerySettings settings, int readBudgetMillis) {
        this.snapshots = snapshots;
        this.events = events;
        this.settings = settings;
        worldEpoch = snapshots.worldEpoch();
        stepBudgetNanos = TimeUnit.MILLISECONDS.toNanos(readBudgetMillis);
        source = side == GameExecutor.Side.CLIENT ? "client" : "server";
        JsonObject snapshot = snapshots.status().getAsJsonObject(source);
        startedTick = snapshot.has("tick") ? snapshot.get("tick").getAsLong() : 0;
    }

    public String source() {
        return source;
    }

    public long epoch() {
        return worldEpoch;
    }

    public String instanceId() {
        return snapshots.context().get("instanceId").getAsString();
    }

    public long stepDeadline() {
        return System.nanoTime() + stepBudgetNanos;
    }

    public long startedTick() {
        return startedTick;
    }

    public McpConfig.QuerySettings settings() {
        return settings;
    }

    public EntityPlayer player(JsonObject arguments) {
        UUID uuid = null;
        if (arguments.has("uuid")) {
            uuid = uuid(arguments.get("uuid").getAsString());
        }
        if ("client".equals(source)) {
            EntityPlayer player = Minecraft.getMinecraft().player;
            if (player == null) {
                throw new DomainException("player_unavailable", "当前没有客户端玩家");
            }
            if (uuid != null && !uuid.equals(player.getUniqueID())) {
                player = player.world.getPlayerEntityByUUID(uuid);
            }
            return requirePlayer(player);
        }
        if (uuid == null) {
            JsonObject snapshot = snapshots.status().getAsJsonObject("client");
            if (!snapshot.has("playerUuid") || snapshot.get("playerUuid").isJsonNull()) {
                throw new DomainException("player_unavailable", "当前玩家身份尚不可用");
            }
            uuid = uuid(snapshot.get("playerUuid").getAsString());
        }
        return requirePlayer(server().getPlayerList().getPlayerByUUID(uuid));
    }

    public World world(JsonObject arguments) {
        if ("client".equals(source)) {
            World world = Minecraft.getMinecraft().world;
            if (world == null) {
                throw new DomainException("world_unavailable", "当前没有客户端世界");
            }
            if (arguments.has("dimension") && world.provider.getDimension() != arguments.get("dimension").getAsInt()) {
                throw new DomainException("source_unavailable", "客户端没有同步该维度");
            }
            return world;
        }
        int dimension = arguments.has("dimension")
                ? arguments.get("dimension").getAsInt() : player(Json.object()).dimension;
        // MinecraftServer#getWorld 可能初始化维度；只访问已存在的世界。
        for (WorldServer world : server().worlds) {
            if (world != null && world.provider.getDimension() == dimension) {
                return world;
            }
        }
        throw new DomainException("target_unloaded", "该维度尚未加载");
    }

    public MinecraftServer server() {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null || !server.isServerRunning()) {
            throw new DomainException("source_unavailable", "内置服务端不可用");
        }
        return server;
    }

    public void missing(String field) {
        unavailable.add(field);
    }

    public void field(JsonObject result, JsonObject arguments, String name, Supplier<?> reader) {
        if (!wants(arguments, name)) {
            return;
        }
        try {
            Object value = reader.get();
            result.add(name, value instanceof JsonElement ? (JsonElement) value : Json.GSON.toJsonTree(value));
        } catch (RuntimeException exception) {
            failure(name, exception);
        }
    }

    public void failure(String field, RuntimeException exception) {
        if (unavailable.add(field)) {
            events.add(source, startedTick, "diagnostic", Json.object(
                    "field", field, "exception", exception.toString()));
        }
    }

    public JsonObject observation() {
        JsonArray fields = new JsonArray();
        for (String field : unavailable) {
            fields.add(field);
        }
        JsonObject observation = snapshots.observation(source, startedTick, fields);
        observation.addProperty("worldEpoch", worldEpoch);
        return observation;
    }

    public JsonObject result(JsonObject data) {
        return Json.object("observation", observation(), "data", data);
    }

    public void checkFields(JsonObject arguments, String... supported) {
        if (!arguments.has("fields")) {
            return;
        }
        Set<String> names = new LinkedHashSet<>();
        for (String field : supported) {
            names.add(field);
        }
        for (JsonElement field : arguments.getAsJsonArray("fields")) {
            if (!names.contains(field.getAsString())) {
                missing(field.getAsString());
            }
        }
    }

    public static boolean wants(JsonObject arguments, String field) {
        if (!arguments.has("fields")) {
            return true;
        }
        for (JsonElement value : arguments.getAsJsonArray("fields")) {
            if (field.equals(value.getAsString())) {
                return true;
            }
        }
        return false;
    }

    public static String string(JsonObject arguments, String key, String fallback) {
        return arguments.has(key) ? arguments.get(key).getAsString() : fallback;
    }

    public static UUID uuid(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException exception) {
            throw new DomainException("invalid_target", "UUID 格式无效");
        }
    }

    private static EntityPlayer requirePlayer(EntityPlayer player) {
        if (player == null) {
            throw new DomainException("player_unavailable", "指定玩家不存在或尚未同步");
        }
        return player;
    }
}
