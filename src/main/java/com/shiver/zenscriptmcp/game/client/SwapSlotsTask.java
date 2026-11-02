package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.data.ItemData;
import com.shiver.zenscriptmcp.game.query.ContainerSlots;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Container;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraftforge.items.ItemHandlerHelper;

/** 只经正常 PICKUP 点击交换；记录每次点击的可核对状态，补偿不会覆盖未知变化。 */
public final class SwapSlotsTask implements JobManager.Task {

    private final ActionService service;
    private final GuiInputBridge gui;
    private final JsonObject request;
    private final List<String> references = new ArrayList<>();
    private final List<Slot> slots = new ArrayList<>();
    private final List<Integer> clicks = new ArrayList<>();
    private final List<State> states = new ArrayList<>();
    private QueryContext context;
    private Container container;
    private JobManager.Task click;
    private int completedClicks;
    private long settledTick = -1;
    private boolean recovering;
    private boolean recoveryComplete;
    private boolean recoveryFailed;
    private String recoveryReason;
    private JsonObject result;

    SwapSlotsTask(ActionService service, JsonObject request, GuiInputBridge gui) {
        this.service = service;
        this.request = request;
        this.gui = gui;
    }

    @Override
    public JsonObject step() throws Exception {
        if (context == null) {
            initialize();
        }
        validateReferences();
        if (completedClicks == clicks.size()) {
            result = evidence(clicks.isEmpty() ? "noop" : "swapped");
            return result;
        }
        if (advanceClick(completedClicks, false)) {
            completedClicks++;
            result = evidence(completedClicks == clicks.size() ? "swapped" : "in_progress");
        }
        return completedClicks == clicks.size() ? result : null;
    }

    private void initialize() {
        EntityPlayer player = ActionService.player();
        if (!player.inventory.getItemStack().isEmpty()) {
            throw new DomainException("action_rejected", "交换前必须清空光标");
        }
        if (player.isSpectator()) {
            throw new DomainException("permission_denied", "旁观模式不能交换槽位");
        }
        context = service.context(GameExecutor.Side.CLIENT);
        container = player.openContainer;
        addReference(request.get("first").getAsString());
        addReference(request.get("second").getAsString());
        Slot first = slots.get(0);
        Slot second = slots.get(1);
        ItemStack left = first.getStack().copy();
        ItemStack right = second.getStack().copy();
        if (ContainerSlots.sameStorage(first, second, player)) {
            throw new DomainException("invalid_target", "交换两端必须是不同的实际槽位");
        }
        requirePlacement(first, right, player);
        requirePlacement(second, left, player);
        if (ItemStack.areItemStacksEqual(left, right)) {
            states.add(capture());
            return;
        }
        if (!left.isEmpty() && !right.isEmpty() && ItemHandlerHelper.canItemStacksStack(left, right)) {
            findTemporary(left, player);
            addClicks(0, 2, 1, 0, 2, 1);
        } else if (left.isEmpty()) {
            addClicks(1, 0);
        } else if (right.isEmpty()) {
            addClicks(0, 1);
        } else {
            addClicks(0, 1, 0);
        }
        State state = capture();
        states.add(state);
        for (int target : clicks) {
            state = state.pickup(target);
            states.add(state);
        }
        State expected = states.get(states.size() - 1);
        if (!same(expected.items.get(0), right) || !same(expected.items.get(1), left) || !expected.cursor.isEmpty()) {
            throw new IllegalStateException("交换点击计划无效");
        }
        result = evidence("prepared");
    }

    private void addReference(String reference) {
        JsonObject target = service.containers().operationTarget(
                ActionService.player(), context.epoch(), service.tick(), reference);
        if (target.has("phantom") && target.get("phantom").getAsBoolean()) {
            throw new DomainException("unsupported_capability", "显示/过滤槽不支持实际物品交换");
        }
        references.add(reference);
        slots.add(service.containers().resolve(
                ActionService.player(), context.epoch(), service.tick(), reference, true));
    }

    private void findTemporary(ItemStack stack, EntityPlayer player) {
        for (int index = 0; index < container.inventorySlots.size(); index++) {
            Slot candidate = container.inventorySlots.get(index);
            if (!candidate.getStack().isEmpty() || ContainerSlots.sameStorage(candidate, slots.get(0), player)
                    || ContainerSlots.sameStorage(candidate, slots.get(1), player) || !candidate.canTakeStack(player)
                    || !fits(candidate, stack)) {
                continue;
            }
            JsonObject description = service.containers().describeSlot(context, player, false, index);
            if (description.get("actionable").getAsBoolean()) {
                addReference(description.get("slotRef").getAsString());
                return;
            }
        }
        throw new DomainException("action_rejected", "同类物品交换需要一个可验证且可取放的临时空槽");
    }

    private void addClicks(int... indices) {
        for (int index : indices) {
            clicks.add(index);
        }
    }

    private static boolean fits(Slot slot, ItemStack stack) {
        return stack.isEmpty() || slot.isItemValid(stack)
                && stack.getCount() <= Math.min(stack.getMaxStackSize(), slot.getItemStackLimit(stack));
    }

    private static void requirePlacement(Slot slot, ItemStack stack, EntityPlayer player) {
        if (!slot.canTakeStack(player) || !fits(slot, stack)) {
            throw new DomainException("action_rejected", "槽位不允许取出或放入完整物品栈");
        }
    }

    private void validateReferences() {
        EntityPlayer player = ActionService.player();
        if (player.openContainer != container) {
            throw new DomainException("stale_reference", "交换期间容器发生变化");
        }
        for (int index = 0; index < references.size(); index++) {
            if (service.containers().resolve(player, context.epoch(), service.tick(), references.get(index), true)
                    != slots.get(index)) {
                throw new DomainException("stale_reference", "交换槽位已重建");
            }
        }
    }

    private boolean advanceClick(int index, boolean reverse) throws Exception {
        int beforeIndex = reverse ? index + 1 : index;
        int afterIndex = reverse ? index : index + 1;
        State expected = states.get(afterIndex);
        if (click == null) {
            if (!states.get(beforeIndex).matches(capture())) {
                throw new DomainException("state_conflict", "槽位或光标发生外部变化，停止修改");
            }
            int target = clicks.get(index);
            requirePlacement(slots.get(target), capture().cursor, ActionService.player());
            click = new SlotClickTask(service, Json.object("slotRef", references.get(target),
                    "clickType", "PICKUP", "button", 0), gui);
        }
        if (settledTick < 0) {
            if (click.step() == null) {
                return false;
            }
            click.cleanup();
            settledTick = service.tick();
            return false;
        }
        // 给适配器同步留出两个实际 tick；成功必须核对两端内容及光标，而非只判断发送成功。
        if (service.tick() - settledTick < 2 || !expected.matches(capture())) {
            return false;
        }
        click = null;
        settledTick = -1;
        return true;
    }

    public boolean recover(boolean expired) throws Exception {
        if (recoveryComplete || recoveryFailed) {
            return true;
        }
        if (context == null || states.isEmpty()) {
            recoveryComplete = true;
            return true;
        }
        try {
            if (!recovering) {
                if (click != null) {
                    click.cleanup();
                    click = null;
                }
                settledTick = -1;
                recovering = true;
                State current = capture();
                int matched = -1;
                // 取消可能发生在点击发送后、确认前，匹配最新可识别的中间状态。
                for (int index = 0; index <= Math.min(completedClicks + 1, states.size() - 1); index++) {
                    if (states.get(index).matches(current)) {
                        matched = index;
                    }
                }
                if (matched < 0) {
                    throw new DomainException("state_conflict", "当前内容不属于已记录的交换状态");
                }
                completedClicks = matched;
            }
            validateReferences();
            if (completedClicks == 0) {
                recoveryComplete = states.get(0).matches(capture());
            } else if (!expired && advanceClick(completedClicks - 1, true)) {
                completedClicks--;
                recoveryComplete = completedClicks == 0 && states.get(0).matches(capture());
            }
            if (expired && !recoveryComplete) {
                recoveryFailed = true;
                recoveryReason = "recovery_budget_exceeded";
            }
        } catch (Exception exception) {
            recoveryFailed = true;
            recoveryReason = exception.toString();
        }
        result = evidence(recoveryComplete ? "restored" : "recovery_incomplete");
        return recoveryComplete || recoveryFailed;
    }

    @Override
    public boolean finishStep(String reason, boolean expired) throws Exception {
        return "succeeded".equals(reason) || recover(expired);
    }

    public JsonObject recovery() {
        String status = recoveryFailed ? "failed" : "not_attempted";
        if (recoveryComplete) {
            status = "restored";
        }
        return Json.object("status", status,
                "reason", recoveryReason, "remainingClicks", completedClicks,
                "current", context == null ? null : safeCapture());
    }

    private State capture() {
        List<ItemStack> items = new ArrayList<>();
        for (Slot slot : slots) {
            items.add(slot.getStack().copy());
        }
        return new State(items, ActionService.player().inventory.getItemStack().copy());
    }

    private JsonObject safeCapture() {
        try {
            return capture().json(references);
        } catch (RuntimeException exception) {
            return Json.object("unavailable", exception.toString());
        }
    }

    private JsonObject evidence(String outcome) {
        return context.result(Json.object("outcome", outcome, "confirmation", "observed",
                "before", states.isEmpty() ? null : states.get(0).json(references), "after", safeCapture(),
                "completedClicks", completedClicks, "plannedClicks", clicks.size(),
                "recovery", recovery(), "compensatable", true, "sideEffects", "mod_callbacks_not_reversible"));
    }

    @Override
    public void cleanup() throws Exception {
        service.inputs().clear();
        if (click != null) {
            click.cleanup();
            click = null;
        }
    }

    @Override
    public JsonObject lastResult() {
        return result;
    }

    private static boolean same(ItemStack first, ItemStack second) {
        return ItemStack.areItemStacksEqual(first, second);
    }

    private static final class State {

        private final List<ItemStack> items;
        private final ItemStack cursor;

        private State(List<ItemStack> items, ItemStack cursor) {
            this.items = items;
            this.cursor = cursor;
        }

        private State pickup(int target) {
            List<ItemStack> next = new ArrayList<>(items);
            next.set(target, cursor.copy());
            return new State(next, items.get(target).copy());
        }

        private boolean matches(State other) {
            if (!same(cursor, other.cursor) || items.size() != other.items.size()) {
                return false;
            }
            for (int index = 0; index < items.size(); index++) {
                if (!same(items.get(index), other.items.get(index))) {
                    return false;
                }
            }
            return true;
        }

        private JsonObject json(List<String> references) {
            JsonArray values = new JsonArray();
            for (int index = 0; index < items.size(); index++) {
                JsonObject item = ItemData.describe(items.get(index));
                item.addProperty("serializedNbt", items.get(index).serializeNBT().toString());
                values.add(Json.object("slotRef", references.get(index), "item", item));
            }
            JsonObject carried = ItemData.describe(cursor);
            carried.addProperty("serializedNbt", cursor.serializeNBT().toString());
            return Json.object("slots", values, "cursor", carried);
        }
    }
}
