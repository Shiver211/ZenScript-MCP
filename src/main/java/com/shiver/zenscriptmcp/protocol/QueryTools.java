package com.shiver.zenscriptmcp.protocol;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryService;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.McpConfig;

/** 登记实际实现的读取分支，工具参数是唯一对外契约。 */
public final class QueryTools {

    private static final int WORLD_BORDER = 30_000_000;
    private static final int MAX_FIELDS = 64;

    private final ToolRegistry tools;
    private final QueryService queries;
    private final McpConfig config;

    public QueryTools(ToolRegistry tools, QueryService queries, McpConfig config) {
        this.tools = tools;
        this.queries = queries;
        this.config = config;
    }

    public void register() {
        catalog();
        world();
        entities();
        inventory();
        data();
        events();
        commands();
        screenshot();
    }

    private void catalog() {
        JsonObject properties = paged();
        properties.add("kind", Schema.enumeration("mods", "items", "blocks", "entities", "effects", "biomes",
                "dimensions", "keybindings"));
        filters(properties);
        register("mc_catalog", "发现实际注册目录；按键仅 client，其他目录可指定来源。返回作业或缓存分页。",
                properties, "kind");
        register("mc_player", "读取当前或指定 UUID 玩家；auto 优先内置服；fields 投影。", withPlayer(common()));
    }

    private void world() {
        JsonObject properties = paged();
        properties.add("kind", Schema.enumeration("environment", "blocks"));
        properties.add("dimension", dimension());
        properties.add("position", position(true));
        properties.add("min", position(true));
        properties.add("max", position(true));
        properties.add("center", position(true));
        properties.add("radius", Schema.integer(0, 64));
        properties.add("includeAir", Schema.bool());
        properties.add("namespace", Schema.string());
        properties.add("registryName", Schema.string());
        register("mc_world_query", "环境或有界方块扫描，radius 表示盒形半径；只读取已加载区块，记录 Tick 与截断。",
                properties, "kind");
        JsonObject block = common();
        block.add("target", blockTarget());
        register("mc_block_inspect", "读取已加载方块状态、光照、碰撞及 TileEntity 摘要；auto 优先内置服。", block, "target");
        JsonObject ray = common();
        ray.remove("fields");
        ray.add("dimension", dimension());
        ray.add("start", position(false));
        ray.add("end", position(false));
        ray.add("distance", Schema.number(0.01, 256));
        ray.add("blocks", Schema.bool());
        ray.add("entities", Schema.bool());
        ray.add("stopOnLiquid", Schema.bool());
        register("mc_raycast", "碰撞射线：当前视角仅 client；显式 start/end 支持两端。不授予交互距离。", ray);
    }

    private void entities() {
        JsonObject properties = paged();
        properties.add("dimension", dimension());
        properties.add("center", position(false));
        properties.add("radius", Schema.number(0, 256));
        properties.add("registryName", Schema.string());
        properties.add("name", Schema.string());
        properties.add("namespace", Schema.string());
        register("mc_entities", "在球形范围内查询已加载实体；不表示视线可见，auto 优先内置服。", properties);
        JsonObject inspect = common();
        inspect.add("target", entityTarget());
        register("mc_entity_inspect", "按 worldEpoch、维度及 UUID 读取实体；旧世界引用失效。", inspect, "target");
    }

    private void inventory() {
        JsonObject inventory = common();
        offsetPagination(inventory);
        inventory.add("action", Schema.enumeration("inspect"));
        register("mc_inventory", "P1 只读玩家库存；slotId 和 inventoryIndex 分开，快捷栏为 0–8。", inventory);
        JsonObject container = common();
        offsetPagination(container);
        container.add("action", Schema.enumeration("inspect"));
        container.add("includePeer", Schema.bool());
        register("mc_container", "P1 只读当前容器；auto 在内置服可用时采集两端，快照不是同一 Tick 的原子事务。", container);
        JsonObject screen = common();
        screen.add("source", Schema.enumeration("client", "auto"));
        register("mc_screen", "读取 GUI 缩放坐标、screenId、标准按钮和槽位；自绘区域明确标为未知。", screen);
    }

    private void data() {
        JsonObject capabilities = common();
        offsetPagination(capabilities);
        capabilities.remove("fields");
        capabilities.add("target", target());
        capabilities.add("face", Schema.enumeration("internal", "down", "up", "north", "south", "west", "east"));
        capabilities.add("capabilities", Schema.array(Schema.enumeration("items", "fluids", "energy"), 3));
        register("mc_capabilities", "按指定面读取 Forge 标准物品、流体和 FE 能力；不修改 handler 数据。",
                capabilities, "target");
        JsonObject nbt = common();
        nbt.remove("fields");
        nbt.add("target", target());
        nbt.add("format", Schema.enumeration("typed", "snbt"));
        nbt.add("path", Schema.array(Schema.string(), 32));
        nbt.add("offset", Schema.integer(0, Integer.MAX_VALUE - config.queries.maxLimit));
        nbt.add("limit", Schema.integer(1, config.queries.maxLimit));
        register("mc_nbt", "读取实际目标序列化 NBT；path 为 compound 键/list 下标数组；long 使用十进制字符串。", nbt, "target");
        JsonObject recipes = paged();
        filters(recipes);
        recipes.add("system", Schema.enumeration("systems", "minecraft:crafting", "minecraft:smelting"));
        recipes.add("recipeId", Schema.string());
        recipes.add("output", Schema.string());
        register("mc_recipes", "列出已支持配方系统或注册声明；不执行动态匹配及合成回调。", recipes);
    }

    private void events() {
        JsonObject events = Json.object("cursor", Schema.string(), "limit", Schema.integer(1, config.queries.maxLimit),
                "source", Schema.enumeration("auto", "client", "server"),
                "type", Schema.string(), "since", Schema.string());
        events.add("target", target());
        register("mc_events", "读取有界事件缓存；返回 nextCursor、cursorGap；观察时机不代表最终执行结果。", events);
        JsonObject chat = Json.copy(events);
        chat.remove("type");
        chat.remove("target");
        chat.add("action", Schema.enumeration("read"));
        register("mc_chat", "P1 只读聊天缓存，保留组件 JSON、原文、侧与时间；历史缺口通过 cursorGap 返回。", chat);
    }

    private void commands() {
        JsonObject properties = paged();
        properties.remove("fields");
        properties.remove("source");
        properties.add("mode", Schema.enumeration("player", "client", "server_player", "server_console"));
        properties.add("action", Schema.enumeration("list", "help", "complete"));
        properties.add("command", Schema.string());
        properties.add("query", Schema.string());
        properties.add("prefix", Json.object("type", "string", "maxLength", 2048));
        register("mc_commands", "命令发现、帮助与补全；player/client 查看客户端注册，server_* 查看内置服注册及权限。", properties);
    }

    private void screenshot() {
        JsonObject properties = Json.object("maxWidth", Schema.integer(1, 4096), "maxHeight", Schema.integer(1, 4096),
                "keepFile", Schema.bool(), "timeoutMs", Schema.integer(1, 300_000));
        tools.register("mc_screenshot", "渲染时采集、后台编码；返回作业，完成后 job_status 返回 MCP image 及尺寸映射。",
                Schema.object(properties), false, false, arguments -> queries.submit("mc_screenshot", arguments));
    }

    private void register(String name, String description, JsonObject properties, String... required) {
        tools.register(name, description, Schema.object(properties, required), true,
                arguments -> queries.submit(name, arguments));
    }

    private JsonObject common() {
        return Json.object("source", Schema.enumeration("auto", "client", "server"),
                "fields", Schema.array(Schema.string(), MAX_FIELDS), "timeoutMs", Schema.integer(1, 300_000));
    }

    private JsonObject paged() {
        JsonObject properties = common();
        properties.add("limit", Schema.integer(1, config.queries.maxLimit));
        properties.add("cursor", Schema.string());
        return properties;
    }

    private void offsetPagination(JsonObject properties) {
        properties.add("offset", Schema.integer(0, Integer.MAX_VALUE - config.queries.maxLimit));
        properties.add("limit", Schema.integer(1, config.queries.maxLimit));
    }

    private static JsonObject withPlayer(JsonObject properties) {
        properties.add("uuid", Schema.string());
        return properties;
    }

    private static void filters(JsonObject properties) {
        properties.add("namespace", Schema.string());
        properties.add("query", Schema.string());
    }

    private static JsonObject dimension() {
        return Schema.integer(Integer.MIN_VALUE, Integer.MAX_VALUE);
    }

    private static JsonObject position(boolean block) {
        JsonObject horizontal = block ? Schema.integer(-WORLD_BORDER, WORLD_BORDER)
                : Schema.number(-WORLD_BORDER, WORLD_BORDER);
        JsonObject vertical = block ? Schema.integer(0, 255) : Schema.number(-4096, 4096);
        return Schema.object(Json.object("x", horizontal, "y", vertical, "z", horizontal), "x", "y", "z");
    }

    private static JsonObject blockTarget() {
        return Schema.object(Json.object("kind", Schema.enumeration("block"), "dimension", dimension(),
                "instanceId", Schema.string(), "worldEpoch", Schema.integer(0, Integer.MAX_VALUE),
                "position", position(true)),
                "kind", "dimension", "position");
    }

    private static JsonObject entityTarget() {
        return Schema.object(Json.object("kind", Schema.enumeration("entity"), "dimension", dimension(),
                "instanceId", Schema.string(), "worldEpoch", Schema.integer(0, Integer.MAX_VALUE),
                "uuid", Schema.string()),
                "kind", "dimension", "worldEpoch", "uuid");
    }

    private static JsonObject target() {
        JsonObject player = Schema.object(Json.object("kind", Schema.enumeration("player"), "uuid", Schema.string(),
                "instanceId", Schema.string(),
                "worldEpoch", Schema.integer(0, Integer.MAX_VALUE)), "kind");
        JsonObject item = Schema.object(Json.object("kind", Schema.enumeration("item"), "containerId", Schema.string(),
                "slotId", Schema.integer(0, 65_535)), "kind", "containerId", "slotId");
        return Schema.oneOf(blockTarget(), entityTarget(), player, item);
    }
}
