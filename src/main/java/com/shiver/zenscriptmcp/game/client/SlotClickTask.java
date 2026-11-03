package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.data.ItemData;
import com.shiver.zenscriptmcp.game.query.ContainerSlots;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;

/** 槽位引用在执行线程解析；适配槽绝不转为原版协议编号。 */
public final class SlotClickTask implements JobManager.Task {

    private final ActionService service;
    private final JsonObject request;
    private final GuiInputBridge gui;
    private JobManager.Task delegate;
    private Slot slot;
    private ItemStack before;
    private ItemStack cursorBefore;
    private JsonObject result;
    private long dispatchedTick = -1;
    private boolean adapted;
    private JobManager.Task cursorRecovery;
    private JsonObject recovery;

    SlotClickTask(ActionService service, JsonObject request, GuiInputBridge gui) {
        this.service = service;
        this.request = request;
        this.gui = gui;
    }

    @Override
    public JsonObject step() throws Exception {
        EntityPlayer player = ActionService.player();
        if (delegate == null) {
            JsonObject resolved = Json.copy(request);
            resolved.addProperty("containerId", service.containers().currentId());
            if (request.has("slotRefs")) {
                JsonArray indices = new JsonArray();
                for (JsonElement reference : request.getAsJsonArray("slotRefs")) {
                    Slot target = resolve(reference.getAsString());
                    int index = player.openContainer.inventorySlots.indexOf(target);
                    if (!ContainerSlots.isVanillaSlot(player.openContainer, index)) {
                        throw new DomainException("unsupported_capability", "适配槽拖放请使用 mc_ui_action");
                    }
                    indices.add(index);
                }
                resolved.add("slots", indices);
            } else {
                String reference = request.get("slotRef").getAsString();
                slot = resolve(reference);
                before = slot.getStack().copy();
                cursorBefore = player.inventory.getItemStack().copy();
                JsonObject target = service.containers().operationTarget(player,
                        service.context(GameExecutor.Side.CLIENT).epoch(),
                        service.tick(), reference);
                adapted = "modularui".equals(target.get("path").getAsString());
                if (adapted) {
                    if (!"PICKUP".equals(request.get("clickType").getAsString())) {
                        throw new DomainException("unsupported_capability", "适配槽当前支持正常 PICKUP 点击");
                    }
                    before = slot.getStack().copy();
                    cursorBefore = player.inventory.getItemStack().copy();
                    target.addProperty("action", "click");
                    target.add("button", request.get("button"));
                    delegate = gui.task(target);
                } else {
                    resolved.addProperty("slotId", player.openContainer.inventorySlots.indexOf(slot));
                }
            }
            if (delegate == null) {
                delegate = new ContainerActionTask(service, "mc_click_slot", resolved);
            }
        }
        if (request.has("slotRef") && resolve(request.get("slotRef").getAsString()) != slot) {
            throw new DomainException("stale_reference", "执行期间槽位映射发生变化");
        }
        if (!adapted) {
            result = delegate.step();
            if (result != null && request.has("slotRef")) {
                result.getAsJsonObject("data").add("slotRef", request.get("slotRef"));
            }
            return result;
        }
        if (dispatchedTick < 0) {
            result = delegate.step();
            if (result != null) {
                dispatchedTick = service.tick();
            }
            return null;
        }
        if (service.tick() <= dispatchedTick) {
            return null;
        }
        boolean changed = !ItemStack.areItemStacksEqual(before, slot.getStack())
                || !ItemStack.areItemStacksEqual(cursorBefore, player.inventory.getItemStack());
        result.getAsJsonObject("data").addProperty("confirmation", changed ? "observed" : "unconfirmed");
        result.getAsJsonObject("data").addProperty("verificationRequired", !changed);
        result.getAsJsonObject("data").addProperty("confirmationScope", "client_slot_observation");
        result.getAsJsonObject("data").add("slotRef", request.get("slotRef"));
        result.getAsJsonObject("data").add("item", ItemData.describe(slot.getStack()));
        result.getAsJsonObject("data").add("cursor", ItemData.describe(player.inventory.getItemStack()));
        return result;
    }

    private Slot resolve(String reference) {
        return service.containers().resolve(ActionService.player(),
                service.context(GameExecutor.Side.CLIENT).epoch(),
                service.tick(), reference, true);
    }

    public boolean recoverCursor(boolean expired) throws Exception {
        try {
            EntityPlayer player = ActionService.player();
            if (cursorRecovery == null && player.inventory.getItemStack().isEmpty()) {
                recovery = Json.object("status", "cursor_empty");
                return true;
            }
            if (expired) {
                recovery = Json.object("status", "incomplete", "reason", "recovery_budget_exceeded");
                return true;
            }
            if (cursorRecovery == null) {
                if (slot == null || before == null || before.isEmpty() || !cursorBefore.isEmpty()
                        || !"PICKUP".equals(request.get("clickType").getAsString())
                        || request.get("button").getAsInt() != 0 || !slot.getStack().isEmpty()
                        || !ItemStack.areItemStacksEqual(before, player.inventory.getItemStack())) {
                    recovery = Json.object("status", "incomplete", "reason", "cursor_origin_not_verified");
                    return true;
                }
                resolve(request.get("slotRef").getAsString());
                cursorRecovery = new SlotClickTask(service, Json.object("slotRef", request.get("slotRef"),
                        "clickType", "PICKUP", "button", 0), gui);
            }
            if (cursorRecovery.step() == null) {
                return false;
            }
            boolean restored = player.inventory.getItemStack().isEmpty() && ItemStack.areItemStacksEqual(before,
                    slot.getStack());
            recovery = Json.object("status", restored ? "restored" : "incomplete",
                    "slotRef", request.get("slotRef"));
            cursorRecovery.cleanup();
            cursorRecovery = null;
        } catch (Exception exception) {
            recovery = Json.object("status", "incomplete", "reason", exception.toString());
        }
        return true;
    }

    public JsonObject cursorRecovery() {
        return recovery;
    }

    @Override
    public void cleanup() throws Exception {
        if (delegate != null) {
            delegate.cleanup();
        }
        if (cursorRecovery != null) {
            cursorRecovery.cleanup();
        }
    }

    @Override
    public JsonObject lastResult() {
        if (result != null) {
            return result;
        }
        return delegate == null ? null : delegate.lastResult();
    }
}
