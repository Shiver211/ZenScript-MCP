package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.PageStore;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.ClickType;
import net.minecraft.inventory.Container;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;

/** 槽位点击使用 windowClick；确认包、服务端状态和客户端同步各自保留证据。 */
final class ContainerActionTask implements JobManager.Task {

    private static final int OUTSIDE_SLOT = -999;
    private static final Field TRANSACTION_ID = InputAccess.field(Container.class, "transactionID", "field_75150_e");

    private final ActionService service;
    private final String tool;
    private final String action;
    private final JsonObject request;
    private final QueryContext context;
    private final List<String> transactions = new ArrayList<>();
    private JsonObject before;
    private JsonObject serverBefore;
    private JsonObject result;
    private PeerObservation peer;
    private Container container;
    private long dispatchedTick = -1;
    private boolean dragging;

    ContainerActionTask(ActionService service, String tool, JsonObject request) {
        this.service = service;
        this.tool = tool;
        this.request = request;
        action = QueryContext.string(request, "action", "click");
        context = service.context(GameExecutor.Side.CLIENT);
    }

    @Override
    public JsonObject step() {
        EntityPlayerSP player = ActionService.player();
        if (dispatchedTick < 0) {
            if (before == null) {
                container = player.openContainer;
                validate(player);
                before = context.result(ActionObservation.container(player));
                checkSize(before);
                if (service.serverAvailable()) {
                    peer = service.peer(Json.object(), true);
                }
            }
            if (peer != null) {
                serverBefore = peer.poll();
                if (serverBefore == null) {
                    return null;
                }
                peer.close();
                peer = null;
                if (!serverBefore.getAsJsonObject("data").get("windowId")
                        .equals(before.getAsJsonObject("data").get("windowId"))) {
                    throw new DomainException("stale_reference", "两端当前窗口不一致，尚不能操作");
                }
            }
            validate(player);
            if ("mc_click_slot".equals(tool) && serverBefore != null
                    && !ActionObservation.container(player).equals(serverBefore.getAsJsonObject("data"))) {
                throw new DomainException("stale_reference", "两端容器内容尚未同步，请重新读取后操作");
            }
            if (Minecraft.getMinecraft().isGamePaused()) {
                return null;
            }
            dispatchedTick = service.tick();
            dispatch(player);
            result = evidence(player, null, "unconfirmed");
            return null;
        }
        if ("mc_click_slot".equals(tool) && player.openContainer != container) {
            throw new DomainException("stale_reference", "点击期间当前容器已更换");
        }
        if (service.tick() <= dispatchedTick && !"open_inventory".equals(action) && !"close".equals(action)) {
            return null;
        }
        boolean confirmed = true;
        for (String key : transactions) {
            Boolean accepted = service.transactions().confirmation(key);
            if (Boolean.FALSE.equals(accepted)) {
                result = evidence(player, null, "rejected");
                throw new DomainException("action_rejected", "服务器拒绝了容器事务：" + key);
            }
            confirmed &= Boolean.TRUE.equals(accepted);
        }
        if (!confirmed) {
            result = evidence(player, null, "unconfirmed");
            return null;
        }
        if (serverBefore == null) {
            result = evidence(player, null, transactions.isEmpty() ? "unconfirmed" : "observed");
            return result;
        }
        if (peer == null) {
            peer = service.peer(Json.object(), true);
            return null;
        }
        JsonObject serverAfter = peer.poll();
        if (serverAfter == null) {
            return null;
        }
        peer.close();
        peer = null;
        JsonObject clientAfter = ActionObservation.container(player);
        JsonObject serverData = serverAfter.getAsJsonObject("data");
        // 创造物品目录是客户端专用容器；打开库存确认界面和窗口，不比较目录与服务端背包。
        boolean synchronizedState = "open_inventory".equals(action)
                ? clientAfter.get("windowId").equals(serverData.get("windowId")) : clientAfter.equals(serverData);
        boolean postcondition = postcondition(player, serverAfter.getAsJsonObject("data"));
        result = evidence(player, serverAfter, synchronizedState && postcondition ? "observed" : "unconfirmed");
        if (synchronizedState && postcondition) {
            return result;
        }
        return null;
    }

    private void validate(EntityPlayerSP player) {
        if (player.isSpectator() && !"close".equals(action)) {
            throw new DomainException("permission_denied", "旁观模式不允许该库存操作");
        }
        if ("mc_click_slot".equals(tool)) {
            container = service.containers().requireOpen(player, context.epoch(), service.tick(),
                    request.get("containerId").getAsString());
            if (!container.getCanCraft(player)) {
                throw new DomainException("action_rejected", "容器正在等待被拒事务的重新同步");
            }
            JsonObject current = service.containers().inspect(
                    context, player, false, Json.object("fields", new String[0]));
            if (request.has("revision") && request.get("revision").getAsLong() != current.get("revision").getAsLong()) {
                throw new DomainException("stale_reference", "容器内容已偏离预期 revision");
            }
            ClickType type = ClickType.valueOf(request.get("clickType").getAsString());
            if (type == ClickType.CLONE && !player.capabilities.isCreativeMode) {
                throw new DomainException("permission_denied", "CLONE 只适用于创造模式");
            }
            if (type == ClickType.QUICK_CRAFT) {
                for (JsonElement value : request.getAsJsonArray("slots")) {
                    requireSlot(value.getAsInt());
                }
                if (request.get("button").getAsInt() == 2 && !player.capabilities.isCreativeMode) {
                    throw new DomainException("permission_denied", "中键拖放只适用于创造模式");
                }
            } else {
                int slot = request.get("slotId").getAsInt();
                if (slot != OUTSIDE_SLOT || type != ClickType.PICKUP) {
                    requireSlot(slot);
                }
            }
        }
    }

    private void requireSlot(int slot) {
        if (slot < 0 || slot >= container.inventorySlots.size()) {
            throw new DomainException("target_missing", "槽位编号不属于当前容器");
        }
    }

    private void dispatch(EntityPlayerSP player) {
        Minecraft minecraft = Minecraft.getMinecraft();
        switch (action) {
            case "select_hotbar":
                player.inventory.currentItem = request.get("index").getAsInt();
                minecraft.playerController.updateController();
                break;
            case "swap_hands":
                player.connection.sendPacket(new CPacketPlayerDigging(
                        CPacketPlayerDigging.Action.SWAP_HELD_ITEMS, BlockPos.ORIGIN, EnumFacing.DOWN));
                break;
            case "drop":
                player.dropItem(request.has("all") && request.get("all").getAsBoolean());
                break;
            case "open_inventory":
                if (minecraft.currentScreen != null) {
                    throw new DomainException("action_rejected", "打开库存前需要关闭当前界面");
                }
                service.inputs().press("inventory", true);
                break;
            case "close":
                player.closeScreen();
                break;
            default:
                ClickType type = ClickType.valueOf(request.get("clickType").getAsString());
                int button = request.get("button").getAsInt();
                if (type == ClickType.QUICK_CRAFT) {
                    dragging = true;
                    click(player, OUTSIDE_SLOT, Container.getQuickcraftMask(0, button), type);
                    for (JsonElement value : request.getAsJsonArray("slots")) {
                        click(player, value.getAsInt(), Container.getQuickcraftMask(1, button), type);
                    }
                    click(player, OUTSIDE_SLOT, Container.getQuickcraftMask(2, button), type);
                    dragging = false;
                } else {
                    click(player, request.get("slotId").getAsInt(), button, type);
                }
        }
    }

    private void click(EntityPlayerSP player, int slot, int button, ClickType type) {
        short next = (short) ((Short) InputAccess.get(TRANSACTION_ID, container) + 1);
        transactions.add(service.transactions().expect(container.windowId, next));
        Minecraft.getMinecraft().playerController.windowClick(container.windowId, slot, button, type, player);
    }

    private boolean postcondition(EntityPlayerSP player, JsonObject after) {
        JsonObject original = serverBefore.getAsJsonObject("data");
        switch (action) {
            case "select_hotbar":
                return after.get("selectedHotbar").equals(request.get("index"));
            case "swap_hands":
            case "drop":
                return !original.equals(after);
            case "open_inventory":
                return Minecraft.getMinecraft().currentScreen instanceof GuiContainer;
            case "close":
                return player.openContainer == player.inventoryContainer && after.get("windowId").getAsInt() == 0;
            default:
                return true;
        }
    }

    private JsonObject evidence(EntityPlayerSP player, JsonObject serverAfter, String confirmation) {
        JsonArray acknowledgements = new JsonArray();
        for (String key : transactions) {
            acknowledgements.add(Json.object("transaction", key, "accepted", service.transactions().confirmation(key)));
        }
        return context.result(Json.object("before", before,
                "after", context.result(ActionObservation.container(player)),
                "serverBefore", serverBefore, "serverAfter", serverAfter,
                "transactions", acknowledgements, "confirmation", confirmation,
                "confirmationScope", confirmationScope(),
                "stateChanged", !before.getAsJsonObject("data").equals(ActionObservation.container(player))));
    }

    private String confirmationScope() {
        if ("open_inventory".equals(action)) {
            return "container_open";
        }
        return transactions.isEmpty() ? "state_observation" : "container_transaction";
    }

    private void checkSize(JsonObject data) {
        if (PageStore.byteSize(Json.GSON.toJson(data)) * 4 > service.config().queries.maxPageBytes) {
            throw new DomainException("result_too_large", "容器前后证据超过预算，操作尚未执行");
        }
    }

    @Override
    public void cleanup() {
        service.inputs().clear();
        if (peer != null) {
            peer.close();
        }
        if (dragging && Minecraft.getMinecraft().player != null
                && Minecraft.getMinecraft().player.openContainer == container) {
            // 非法拖放阶段只重置协议状态，不提交已有槽位、不丢弃光标物品。
            click(Minecraft.getMinecraft().player, OUTSIDE_SLOT, 3, ClickType.QUICK_CRAFT);
            dragging = false;
        }
    }

    @Override
    public JsonObject lastResult() {
        return result;
    }
}
