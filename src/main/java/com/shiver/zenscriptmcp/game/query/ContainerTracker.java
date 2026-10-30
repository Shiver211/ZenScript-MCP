package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.data.ItemData;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import java.util.UUID;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** 每侧独立实例，由所属线程维护；窗口编号复用时仍生成新引用。 */
public final class ContainerTracker {

    private final EventBuffer events;
    private final String source;
    private final String instancePrefix = UUID.randomUUID().toString();
    private final Identity inventory = new Identity();
    private final Identity open = new Identity();
    private long sequence;
    private long revision;

    public ContainerTracker(EventBuffer events, String source) {
        this.events = events;
        this.source = source;
    }

    public void update(EntityPlayer player, long epoch, long tick) {
        updateIdentity(inventory, player == null ? null : player.inventoryContainer, epoch);
        if (updateIdentity(open, player == null ? null : player.openContainer, epoch)) {
            events.add(source, tick, "container_changed", Json.object("containerId", open.id));
        }
    }

    public JsonObject inspect(QueryContext context, EntityPlayer player, boolean inventoryOnly, JsonObject arguments) {
        update(player, context.epoch(), context.startedTick());
        Identity identity = inventoryOnly ? inventory : open;
        Container container = identity.container;
        JsonObject data = Json.object("containerId", identity.id, "windowId", container.windowId,
                "class", container.getClass().getName(), "dimension", player.dimension);
        context.checkFields(arguments, "slots", "cursor", "selectedHotbar", "mainHand", "offHand");
        context.field(data, arguments, "cursor", () -> ItemData.describe(player.inventory.getItemStack()));
        context.field(data, arguments, "selectedHotbar", () -> player.inventory.currentItem);
        context.field(data, arguments, "mainHand", () -> ItemData.describe(player.getHeldItemMainhand()));
        context.field(data, arguments, "offHand", () -> ItemData.describe(player.getHeldItemOffhand()));
        context.field(data, arguments, "slots", () -> slots(context, player, container, arguments));
        int offset = arguments.has("offset") ? arguments.get("offset").getAsInt() : 0;
        int count = data.has("slots") ? data.getAsJsonArray("slots").size() : 0;
        data.addProperty("slotTotal", container.inventorySlots.size());
        data.addProperty("slotOffset", offset);
        if (data.has("slots")) {
            data.addProperty("slotsTruncated", offset + count < container.inventorySlots.size());
            data.add("nextSlotOffset", Json.GSON.toJsonTree(
                    offset + count < container.inventorySlots.size() ? offset + count : null));
        }
        // 修订只取决于实际完整状态，不取决于本次分页或字段投影。
        StringBuilder state = new StringBuilder();
        state.append(player.inventory.currentItem).append(':').append(itemState(player.inventory.getItemStack()));
        for (int index = 0; index < container.inventorySlots.size(); index++) {
            Slot slot = container.inventorySlots.get(index);
            state.append('|').append(index).append(':').append(itemState(slot.getStack()));
        }
        String serialized = state.toString();
        if (!serialized.equals(identity.lastJson)) {
            identity.revision = ++revision;
            identity.lastJson = serialized;
        }
        data.addProperty("revision", identity.revision);
        return data;
    }

    private static String itemState(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.serializeNBT().toString();
    }

    public ItemStack item(QueryContext context, EntityPlayer player, JsonObject target) {
        update(player, context.epoch(), context.startedTick());
        String id = target.get("containerId").getAsString();
        Container container = id.equals(open.id) ? open.container : null;
        if (id.equals(inventory.id)) {
            container = inventory.container;
        }
        if (container == null) {
            throw new DomainException("stale_reference", "容器引用已过期或来自其他来源");
        }
        int slotId = target.get("slotId").getAsInt();
        if (slotId < 0 || slotId >= container.inventorySlots.size()) {
            throw new DomainException("target_missing", "槽位不存在");
        }
        return container.getSlot(slotId).getStack();
    }

    public String currentId() {
        return open.id;
    }

    public Container requireOpen(EntityPlayer player, long epoch, long tick, String id) {
        update(player, epoch, tick);
        if (id == null || !id.equals(open.id)) {
            throw new DomainException("stale_reference", "操作必须使用当前客户端打开容器的 containerId");
        }
        return open.container;
    }

    private JsonArray slots(QueryContext context, EntityPlayer player, Container container, JsonObject arguments) {
        JsonArray result = new JsonArray();
        int offset = arguments.has("offset") ? arguments.get("offset").getAsInt() : 0;
        int limit = arguments.has("limit") ? arguments.get("limit").getAsInt() : context.settings().defaultLimit;
        int maximum = Math.min(container.inventorySlots.size(), offset + limit);
        for (int index = offset; index < maximum; index++) {
            Slot slot = container.inventorySlots.get(index);
            JsonObject data = Json.object("slotId", index,
                    "vanillaSlotId", ContainerSlots.isVanillaSlot(container, index) ? index : null,
                    "inventoryIndex", slot.getSlotIndex(),
                    "inventoryClass", slot.inventory.getClass().getName(),
                    "playerInventoryIndex", slot.inventory == player.inventory ? slot.getSlotIndex() : null,
                    "x", slot.xPos, "y", slot.yPos, "coordinateSpace", "container_relative");
            context.field(data, Json.object(), "item", () -> ItemData.describe(slot.getStack()));
            context.field(data, Json.object(), "limit", slot::getSlotStackLimit);
            result.add(data);
        }
        return result;
    }

    private boolean updateIdentity(Identity identity, Container container, long epoch) {
        int windowId = container == null ? -1 : container.windowId;
        if (identity.container == container && identity.epoch == epoch && identity.windowId == windowId) {
            return false;
        }
        identity.container = container;
        identity.epoch = epoch;
        identity.windowId = windowId;
        identity.id = container == null ? null
                : instancePrefix + ":" + epoch + ":" + source + ":" + ++sequence + ":" + windowId;
        identity.lastJson = null;
        return true;
    }

    private static final class Identity {

        private Container container;
        private long epoch = -1;
        private int windowId = -1;
        private String id;
        private String lastJson;
        private long revision;
    }
}
