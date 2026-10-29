package com.shiver.zenscriptmcp.protocol;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.client.ActionService;
import com.shiver.zenscriptmcp.game.query.QueryService;
import com.shiver.zenscriptmcp.model.Json;

/** 固定操作目录；混合工具按 action 路由，只读旧请求继续可用。 */
public final class ActionTools {

    public static final int MAX_DURATION_TICKS = 12_000;
    private final ToolRegistry tools;
    private final ActionService actions;
    private final QueryService queries;

    public ActionTools(ToolRegistry tools, ActionService actions, QueryService queries) {
        this.tools = tools;
        this.actions = actions;
        this.queries = queries;
    }

    public void register() {
        inputs();
        interactions();
        containers();
        gui();
        commands();
    }

    private void inputs() {
        JsonObject angles = timing();
        angles.add("yaw", Schema.number(-360_000, 360_000));
        angles.add("pitch", Schema.number(-90, 90));
        angles.add("durationTicks", Schema.integer(0, MAX_DURATION_TICKS));
        JsonObject target = timing();
        target.add("position", vector());
        target.add("durationTicks", Schema.integer(0, MAX_DURATION_TICKS));
        register("mc_look", "设置或平滑转动视角；返回实际客户端视角和准星。",
                Schema.oneOf(Schema.object(angles, "yaw", "pitch"), Schema.object(target, "position")));
        JsonObject move = timing();
        move.add("forward", Schema.number(-1, 1));
        move.add("strafe", Schema.number(-1, 1));
        move.add("jump", Schema.bool());
        move.add("sprint", Schema.bool());
        move.add("sneak", Schema.bool());
        move.add("look", look());
        move.add("durationTicks", duration());
        register("mc_move", "按逻辑 Tick 执行移动；forward 正值向前、strafe 正值向左；报告实际位移。",
                Schema.object(move, "durationTicks"));
        JsonObject input = timing();
        input.add("durationTicks", duration());
        JsonObject step = Json.object("atTick", Schema.integer(0, MAX_DURATION_TICKS),
                "press", Schema.array(Schema.string(), 32), "release", Schema.array(Schema.string(), 32),
                "look", look());
        input.add("timeline", nonempty(Schema.object(step, "atTick"), 256));
        register("mc_input", "组合输入时间线；原版别名或 mc_catalog 的绑定 ID，自动使用绑定的修饰键。",
                Schema.object(input, "durationTicks", "timeline"));
    }

    private void interactions() {
        JsonObject block = interaction("mc_block_inspect");
        block.add("action", Schema.enumeration("use", "place"));
        block.add("face", Schema.enumeration("down", "up", "north", "south", "west", "east"));
        JsonObject breaking = Json.copy(block);
        breaking.add("action", Schema.enumeration("break"));
        breaking.add("hand", Schema.enumeration("main"));
        breaking.add("durationTicks", duration());
        JsonObject stop = timing();
        stop.add("action", Schema.enumeration("stop_break"));
        register("mc_block_interact", "准星校验后使用、放置或持续破坏；停止分支请求取消当前方块作业。",
                Schema.oneOf(Schema.object(block, "action", "target"),
                        Schema.object(breaking, "action", "target", "durationTicks"), Schema.object(stop, "action")));
        JsonObject entity = interaction("mc_entity_inspect");
        entity.add("action", Schema.enumeration("use"));
        JsonObject attack = Json.copy(entity);
        attack.add("action", Schema.enumeration("attack"));
        attack.add("hand", Schema.enumeration("main"));
        register("mc_entity_interact", "使用或攻击实际准星命中的实体；返回状态证据，不推断交易/喂食业务成功。",
                Schema.oneOf(Schema.object(entity, "action", "target"), Schema.object(attack, "action", "target")));
        JsonObject use = timing();
        use.add("action", Schema.enumeration("use"));
        use.add("hand", hand());
        JsonObject hold = Json.copy(use);
        hold.add("action", Schema.enumeration("hold"));
        hold.add("durationTicks", duration());
        JsonObject release = timing();
        release.add("action", Schema.enumeration("release"));
        register("mc_item_use", "正常物品使用或有界持续使用；release 请求取消当前物品使用作业。",
                Schema.oneOf(Schema.object(use, "action"), Schema.object(hold, "action", "durationTicks"),
                        Schema.object(release, "action")));
    }

    private void containers() {
        JsonObject select = action("select_hotbar");
        select.add("index", Schema.integer(0, 8));
        JsonObject drop = action("drop");
        drop.add("all", Schema.bool());
        mixed("mc_inventory", "inspect 保留读取；select_hotbar/swap_hands/drop 走正常玩家操作。", "inspect",
                Schema.object(select, "action", "index"), Schema.object(action("swap_hands"), "action"),
                Schema.object(drop, "action"));
        mixed("mc_container", "inspect 读取容器；open_inventory/close 打开玩家库存或关闭容器。", "inspect",
                Schema.object(action("open_inventory"), "action"), Schema.object(action("close"), "action"));
        JsonArray types = new JsonArray();
        for (String name : new String[] {"PICKUP", "QUICK_MOVE", "SWAP", "CLONE", "THROW",
                "QUICK_CRAFT", "PICKUP_ALL"}) {
            JsonObject click = timing();
            click.add("containerId", Schema.string());
            click.add("revision", Schema.integer(0, Integer.MAX_VALUE));
            click.add("clickType", Schema.enumeration(name));
            JsonObject button = Schema.integer(0, "QUICK_CRAFT".equals(name) ? 2 : 1);
            if ("SWAP".equals(name)) {
                // 1.12.2 的 Container.SWAP 只接受快捷栏下标；副手通过玩家换手路径操作。
                button = Schema.integer(0, 8);
            } else if ("CLONE".equals(name)) {
                button = Schema.integer(2, 2);
            }
            click.add("button", button);
            if ("QUICK_CRAFT".equals(name)) {
                JsonObject slots = nonempty(Schema.integer(0, 65_535), 128);
                slots.addProperty("uniqueItems", true);
                click.add("slots", slots);
                types.add(Schema.object(click, "containerId", "clickType", "button", "slots"));
            } else {
                JsonObject slot = Schema.integer(0, 65_535);
                click.add("slotId", "PICKUP".equals(name)
                        ? Schema.oneOf(slot, Schema.integer(-999, -999)) : slot);
                types.add(Schema.object(click, "containerId", "clickType", "button", "slotId"));
            }
        }
        register("mc_click_slot", "七种原版槽位操作；QUICK_CRAFT 接收槽位序列；-999 只用于 PICKUP 窗口外点击。"
                + "containerId 必须来自 client 读取，等待服务器事务确认及可用的双端状态。",
                Json.object("oneOf", types));
    }

    private void gui() {
        JsonObject common = timing();
        common.add("screenId", Schema.string());
        JsonArray variants = new JsonArray();
        for (String action : new String[] {"click", "drag", "scroll", "key", "text", "close"}) {
            JsonObject fields = Json.copy(common);
            fields.add("action", Schema.enumeration(action));
            switch (action) {
                case "click":
                case "scroll":
                    fields.add("x", Schema.integer(0, 32_768));
                    fields.add("y", Schema.integer(0, 32_768));
                    String parameter = "click".equals(action) ? "button" : "delta";
                    fields.add(parameter, "click".equals(action)
                            ? Schema.integer(0, 2) : Schema.integer(-12000, 12000));
                    variants.add(Schema.object(fields, "screenId", "action", "x", "y", parameter));
                    break;
                case "drag":
                    fields.add("button", Schema.integer(0, 2));
                    fields.add("points", nonempty(Schema.object(Json.object("x", Schema.integer(0, 32_768),
                            "y", Schema.integer(0, 32_768)), "x", "y"), 256));
                    variants.add(Schema.object(fields, "screenId", "action", "button", "points"));
                    break;
                case "key":
                    fields.add("keyCode", Schema.integer(1, 255));
                    fields.add("modifiers", Schema.array(Schema.enumeration("shift", "control", "alt"), 3));
                    variants.add(Schema.object(fields, "screenId", "action", "keyCode"));
                    break;
                case "text":
                    fields.add("text", text(4096));
                    variants.add(Schema.object(fields, "screenId", "action", "text"));
                    break;
                default:
                    variants.add(Schema.object(fields, "screenId", "action"));
            }
        }
        register("mc_ui_action", "GUI 缩放坐标输入；drag 每个点占一个客户端回调；滚轮正值向上。"
                + "文本逐字符送到焦点控件，不使用剪贴板；未验证私有设备轮询路径。", Json.object("oneOf", variants));
    }

    private void commands() {
        JsonObject command = timing();
        command.add("command", text(32767));
        command.add("mode", Schema.enumeration("player", "client", "server_player", "server_console"));
        register("mc_command", "执行命令：player 保留客户端命令优先级；server_* 需要内置服并保留真实发送者。",
                Schema.object(command, "command"));
        JsonObject send = action("send");
        send.add("text", text(256));
        mixed("mc_chat", "read 读取聊天事件；send 经正常玩家聊天路径发送文本。", "read",
                Schema.object(send, "action", "text"));
    }

    private void mixed(String name, String description, String readAction, JsonObject... mutations) {
        JsonArray variants = new JsonArray();
        variants.add(tools.inputSchema(name));
        for (JsonObject schema : mutations) {
            variants.add(schema);
        }
        tools.replace(name, description, Json.object("oneOf", variants), false, arguments -> {
            String action = arguments.has("action") ? arguments.get("action").getAsString() : readAction;
            return readAction.equals(action) ? queries.submit(name, arguments) : actions.submit(name, arguments);
        });
    }

    private void register(String name, String description, JsonObject schema) {
        tools.register(name, description, schema, false, arguments -> {
            if ("mc_input".equals(name)) {
                int previous = -1;
                for (JsonElement value : arguments.getAsJsonArray("timeline")) {
                    int tick = value.getAsJsonObject().get("atTick").getAsInt();
                    if (tick < previous || tick > arguments.get("durationTicks").getAsInt()) {
                        throw new IllegalArgumentException("时间线必须升序且在 durationTicks 范围内");
                    }
                    previous = tick;
                }
            }
            return actions.submit(name, arguments);
        });
    }

    private JsonObject interaction(String query) {
        JsonObject fields = timing();
        fields.add("target", tools.inputSchema(query).getAsJsonObject("properties").get("target"));
        fields.add("hand", hand());
        fields.add("hitPoint", vector());
        fields.add("lookAt", Schema.bool());
        return fields;
    }

    private static JsonObject action(String name) {
        JsonObject fields = timing();
        fields.add("action", Schema.enumeration(name));
        return fields;
    }

    private static JsonObject timing() {
        return Json.object("timeoutMs", Schema.integer(1, 300_000), "operationId", Schema.string());
    }

    private static JsonObject duration() {
        return Schema.integer(1, MAX_DURATION_TICKS);
    }

    private static JsonObject hand() {
        return Schema.enumeration("auto", "main", "off");
    }

    private static JsonObject look() {
        return Schema.oneOf(Schema.object(Json.object("yaw", Schema.number(-360_000, 360_000),
                "pitch", Schema.number(-90, 90)), "yaw", "pitch"),
                Schema.object(Json.object("position", vector()), "position"));
    }

    private static JsonObject vector() {
        return Schema.object(Json.object("x", Schema.number(-30_000_000, 30_000_000),
                "y", Schema.number(-4096, 4096), "z", Schema.number(-30_000_000, 30_000_000)), "x", "y", "z");
    }

    private static JsonObject text(int maximum) {
        return Json.object("type", "string", "minLength", 1, "maxLength", maximum);
    }

    private static JsonObject nonempty(JsonObject item, int maximum) {
        JsonObject array = Schema.array(item, maximum);
        array.addProperty("minItems", 1);
        return array;
    }
}
