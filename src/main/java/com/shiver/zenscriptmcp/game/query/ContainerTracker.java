package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.data.ItemData;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** 每侧独立实例，由所属线程维护；引用同时校验世界、容器及实际槽位对象。 */
public final class ContainerTracker {

    private final EventBuffer events;
    private final String source;
    private final String instancePrefix = UUID.randomUUID().toString();
    private final Identity inventory = new Identity();
    private final Identity open = new Identity();
    private final InventorySnapshots snapshots = new InventorySnapshots();
    private Function<Slot, JsonObject> adapterTarget = slot -> null;
    private Runnable adapterRefresh = () -> { };
    private long sequence;
    private long revision;

    public ContainerTracker(EventBuffer events, String source) {
        this.events = events;
        this.source = source;
    }

    public void setAdapterTarget(Function<Slot, JsonObject> provider, Runnable refresh) {
        adapterTarget = provider;
        adapterRefresh = refresh;
    }

    public void update(EntityPlayer player, long epoch, long tick) {
        updateIdentity(inventory, player == null ? null : player.inventoryContainer, epoch);
        if (updateIdentity(open, player == null ? null : player.openContainer, epoch)) {
            // 库存物品本身仍存在，但旧操作映射不能跨越打开容器的生命周期。
            inventory.references.clear();
            inventory.bySlot.clear();
            events.add(source, tick, "container_changed", Json.object("containerId", open.id));
        }
    }

    public JsonObject inspect(QueryContext context, EntityPlayer player, boolean inventoryOnly, JsonObject arguments) {
        update(player, context.epoch(), context.startedTick());
        adapterRefresh.run();
        Identity identity = inventoryOnly ? inventory : open;
        Container container = identity.container;
        boolean includeEmpty = arguments.has("includeEmpty") && arguments.get("includeEmpty").getAsBoolean();
        JsonObject data = Json.object("containerId", identity.id, "windowId", container.windowId,
                "class", container.getClass().getName(), "dimension", player.dimension, "source", source,
                "cursor", item(player.inventory.getItemStack()), "selectedHotbar", player.inventory.currentItem);
        context.field(data, arguments, "mainHand", () -> ItemData.describe(player.getHeldItemMainhand()));
        context.field(data, arguments, "offHand", () -> ItemData.describe(player.getHeldItemOffhand()));
        JsonArray slots = new JsonArray();
        int nonempty = 0;
        StringBuilder state = new StringBuilder(itemState(player.inventory.getItemStack()));
        for (int index = 0; index < container.inventorySlots.size(); index++) {
            Slot slot = container.inventorySlots.get(index);
            state.append('|').append(index).append(':').append(itemState(slot.getStack()));
            if (!slot.getStack().isEmpty()) {
                nonempty++;
            }
            if (includeEmpty || !slot.getStack().isEmpty()) {
                slots.add(describeSlot(context, player, identity, index));
            }
        }
        String serialized = state.append(':').append(player.inventory.currentItem).toString();
        if (!serialized.equals(identity.lastJson)) {
            identity.revision = ++revision;
            identity.lastJson = serialized;
        }
        data.add("slots", slots);
        data.add("statistics", Json.object("total", container.inventorySlots.size(), "nonempty", nonempty,
                "empty", container.inventorySlots.size() - nonempty));
        data.addProperty("revision", identity.revision);
        data.addProperty("collectionComplete", true);
        String scope = identity.id + ":" + open.id + ":" + includeEmpty;
        return snapshots.capture(data, arguments, scope, context.settings().maxCacheBytes);
    }

    public JsonObject describeSlot(QueryContext context, EntityPlayer player, boolean inventoryOnly, int index) {
        update(player, context.epoch(), context.startedTick());
        return describeSlot(context, player, inventoryOnly ? inventory : open, index);
    }

    private JsonObject describeSlot(QueryContext context, EntityPlayer player, Identity identity, int index) {
        Slot slot = identity.container.inventorySlots.get(index);
        String reference = reference(identity, slot, index);
        Slot actionSlot = identity == open ? slot : mapOpenSlot(slot, player);
        JsonObject target = actionSlot == null ? null : actionTarget(actionSlot, reference);
        String unmappedReason = "server".equals(source) ? "server_evidence_only" : "no_verified_click_path";
        JsonObject data = Json.object("slotRef", reference, "containerSlotIndex", index,
                "protocolSlotNumber", ContainerSlots.isVanillaSlot(identity.container, index) ? slot.slotNumber : null,
                "playerInventoryIndex", ContainerSlots.playerIndex(slot, player),
                "inventoryIndex", slot.getSlotIndex(), "inventoryClass", slot.inventory.getClass().getName(),
                "item", item(slot.getStack()), "x", slot.xPos, "y", slot.yPos,
                "coordinateSpace", "container_relative", "actionable", target != null, "actionTarget", target,
                "unmappedReason", target == null ? unmappedReason : null);
        context.field(data, Json.object(), "limit", slot::getSlotStackLimit);
        context.field(data, Json.object(), "canTake", () -> slot.canTakeStack(player));
        return data;
    }

    private JsonObject actionTarget(Slot slot, String reference) {
        if (!"client".equals(source)) {
            return null;
        }
        int index = open.container.inventorySlots.indexOf(slot);
        if (ContainerSlots.isVanillaSlot(open.container, index)) {
            return Json.object("slotRef", reference, "path", "window_click");
        }
        JsonObject target = adapterTarget.apply(slot);
        if (target != null) {
            target.addProperty("slotRef", reference);
        }
        return target;
    }

    private Slot mapOpenSlot(Slot slot, EntityPlayer player) {
        if (open.container == null) {
            return null;
        }
        for (Slot candidate : open.container.inventorySlots) {
            if (ContainerSlots.sameStorage(candidate, slot, player)) {
                return candidate;
            }
        }
        return null;
    }

    public Slot resolve(EntityPlayer player, long epoch, long tick, String reference, boolean forAction) {
        update(player, epoch, tick);
        adapterRefresh.run();
        Reference entry = open.references.get(reference);
        Identity identity = open;
        if (entry == null) {
            entry = inventory.references.get(reference);
            identity = inventory;
        }
        if (entry == null || entry.index >= identity.container.inventorySlots.size()
                || identity.container.inventorySlots.get(entry.index) != entry.slot) {
            throw new DomainException("stale_reference", "槽位引用已过期、重建或来自其他实例/来源");
        }
        if (!forAction) {
            return entry.slot;
        }
        Slot actionSlot = identity == open ? entry.slot : mapOpenSlot(entry.slot, player);
        if (actionSlot == null || actionTarget(actionSlot, reference) == null) {
            throw new DomainException("unsupported_capability", "槽位没有可验证的客户端操作路径");
        }
        return actionSlot;
    }

    public JsonObject operationTarget(EntityPlayer player, long epoch, long tick, String reference) {
        return actionTarget(resolve(player, epoch, tick, reference, true), reference);
    }

    public ItemStack item(QueryContext context, EntityPlayer player, JsonObject target) {
        return resolve(player, context.epoch(), context.startedTick(), target.get("slotRef").getAsString(), false)
                .getStack();
    }

    public long currentRevision(EntityPlayer player, long epoch, long tick) {
        update(player, epoch, tick);
        StringBuilder state = new StringBuilder(itemState(player.inventory.getItemStack()));
        for (int index = 0; index < open.container.inventorySlots.size(); index++) {
            state.append('|').append(index).append(':')
                    .append(itemState(open.container.inventorySlots.get(index).getStack()));
        }
        String serialized = state.append(':').append(player.inventory.currentItem).toString();
        if (!serialized.equals(open.lastJson)) {
            open.lastJson = serialized;
            open.revision = ++revision;
        }
        return open.revision;
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

    private String reference(Identity identity, Slot slot, int index) {
        String reference = identity.bySlot.get(slot);
        if (reference == null || identity.references.get(reference).index != index) {
            reference = "slot:" + source + ":" + UUID.randomUUID();
            identity.bySlot.put(slot, reference);
            identity.references.put(reference, new Reference(slot, index));
        }
        return reference;
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
        identity.references.clear();
        identity.bySlot.clear();
        return true;
    }

    private static String itemState(ItemStack stack) {
        return stack.isEmpty() ? "empty" : stack.serializeNBT().toString();
    }

    private static JsonObject item(ItemStack stack) {
        JsonObject data = ItemData.describe(stack);
        // 标签变化也必须出现在差异中；只读序列化结果，不推断模组机制。
        if (!stack.isEmpty()) {
            data.addProperty("serializedNbt", itemState(stack));
        }
        return data;
    }

    private static final class Identity {

        private final Map<String, Reference> references = new HashMap<>();
        private final Map<Slot, String> bySlot = new IdentityHashMap<>();
        private Container container;
        private long epoch = -1;
        private int windowId = -1;
        private String id;
        private String lastJson;
        private long revision;
    }

    private static final class Reference {

        private final Slot slot;
        private final int index;

        private Reference(Slot slot, int index) {
            this.slot = slot;
            this.index = index;
        }
    }
}
