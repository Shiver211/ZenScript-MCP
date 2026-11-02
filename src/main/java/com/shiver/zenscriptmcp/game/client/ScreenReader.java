package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.data.ItemInspector;
import com.shiver.zenscriptmcp.game.query.ContainerTracker;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.integration.modularui.ModularUiIntegration;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.gui.inventory.GuiContainer;
import net.minecraft.inventory.Slot;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** GUI 引用只在客户端线程保存；初始化和缩放变化都会使旧坐标引用失效。 */
public final class ScreenReader {

    private final EventBuffer events;
    private final String instancePrefix = UUID.randomUUID().toString();
    private GuiScreen screen;
    private GuiScreen initializedScreen;
    private List<GuiButton> buttons = Collections.emptyList();
    private String screenId = instancePrefix + ":screen:0";
    private long sequence;
    private long epoch = -1;
    private int width;
    private int height;
    private ModularUiIntegration modularUi;
    private final Map<GuiButton, String> buttonIds = new IdentityHashMap<>();

    public ScreenReader(EventBuffer events) {
        this.events = events;
    }

    @SubscribeEvent
    public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
        initializedScreen = event.getGui();
        buttons = event.getButtonList();
        screenId = instancePrefix + ":screen:" + ++sequence;
        buttonIds.clear();
    }

    public void setModularUi(ModularUiIntegration integration) {
        modularUi = integration;
    }

    public void update(long worldEpoch, long tick) {
        Minecraft minecraft = Minecraft.getMinecraft();
        ScaledResolution scaled = new ScaledResolution(minecraft);
        boolean changed = screen != minecraft.currentScreen || width != scaled.getScaledWidth()
                || height != scaled.getScaledHeight() || epoch != worldEpoch;
        if (!changed) {
            return;
        }
        screen = minecraft.currentScreen;
        epoch = worldEpoch;
        width = scaled.getScaledWidth();
        height = scaled.getScaledHeight();
        screenId = instancePrefix + ":screen:" + ++sequence;
        buttonIds.clear();
        if (screen != initializedScreen) {
            buttons = Collections.emptyList();
            initializedScreen = null;
        }
        events.add("client", tick, "screen_changed", Json.object("screenId", screenId,
                "class", screen == null ? null : screen.getClass().getName()));
    }

    public JsonObject inspect(QueryContext context, JsonObject arguments, ContainerTracker containers) {
        update(context.epoch(), context.startedTick());
        Minecraft minecraft = Minecraft.getMinecraft();
        refreshWidgets();
        JsonObject data = dimensions();
        data.addProperty("class", screen == null ? "hud" : screen.getClass().getName());
        data.addProperty("open", screen != null);
        context.checkFields(arguments, "buttons", "slots", "title", "widgets", "tooltip", "extensions");
        context.field(data, arguments, "buttons", () -> buttons(context));
        if (screen != null) {
            context.missing("custom_drawn_regions");
            if (QueryContext.wants(arguments, "title")) {
                context.missing("title:not_exposed");
            }
            if (initializedScreen != screen && QueryContext.wants(arguments, "buttons")) {
                context.missing("buttons:init_not_observed");
            }
        }
        if (screen instanceof GuiContainer) {
            GuiContainer gui = (GuiContainer) screen;
            if (minecraft.player != null) {
                containers.update(minecraft.player, context.epoch(), context.startedTick());
                data.addProperty("containerId", containers.currentId());
            }
            data.add("containerBounds", Json.object("x", gui.getGuiLeft(), "y", gui.getGuiTop(),
                    "width", gui.getXSize(), "height", gui.getYSize()));
            context.field(data, arguments, "slots", () -> slots(context, gui, containers));
        }
        context.field(data, arguments, "widgets", () -> widgets(context, containers));
        if (arguments.has("tooltip")) {
            JsonObject target = arguments.getAsJsonObject("tooltip");
            if (target.has("slotRef")) {
                Slot slot = containers.resolve(context.player(Json.object()), context.epoch(), context.startedTick(),
                        target.get("slotRef").getAsString(), false);
                data.add("tooltip", ItemInspector.tooltip(slot.getStack(), false));
                if (modularUi != null) {
                    JsonObject mapped = modularUi.slotTarget(slot, screenId);
                    if (mapped != null) {
                        data.add("widgetTooltip", modularUi.tooltip(mapped.get("widgetId").getAsString(), screenId));
                    }
                }
            } else if (modularUi != null) {
                data.add("tooltip", modularUi.tooltip(target.get("widgetId").getAsString(), screenId));
            } else {
                throw new DomainException("unsupported_capability", "该控件未提供 tooltip 适配器");
            }
        }
        return data;
    }

    public void refreshWidgets() {
        if (modularUi != null) {
            modularUi.scan(screenId);
        }
    }

    public JsonObject slotTarget(Slot slot) {
        return modularUi == null ? null : modularUi.slotTarget(slot, screenId);
    }

    public JsonObject widgetTarget(String widgetId) {
        for (GuiButton button : buttons) {
            if (widgetId.equals(buttonIds.get(button))) {
                if (!button.enabled || !button.visible) {
                    throw new DomainException("action_rejected", "按钮未启用或不可见");
                }
                return Json.object("screenId", screenId, "widgetId", widgetId,
                        "x", button.x + button.width / 2, "y", button.y + button.height / 2);
            }
        }
        if (modularUi == null) {
            throw new DomainException("stale_reference", "控件引用已失效");
        }
        return modularUi.target(widgetId, screenId);
    }

    public void preparePointer(int x, int y, String widgetId) {
        if (modularUi != null) {
            modularUi.preparePointer(x, y, widgetId);
        }
    }

    private JsonArray widgets(QueryContext context, ContainerTracker containers) {
        JsonArray widgets = new JsonArray();
        for (GuiButton button : buttons) {
            String id = buttonIds.computeIfAbsent(button, ignored -> "widget:" + UUID.randomUUID());
            widgets.add(Json.object("widgetId", id, "parentId", null, "type", button.getClass().getName(),
                    "name", String.valueOf(button.id), "text", button.displayString, "visible", button.visible,
                    "enabled", button.enabled, "coordinateSpace", "gui_scaled",
                    "bounds", Json.object("x", button.x, "y", button.y,
                            "width", button.width, "height", button.height)));
        }
        if (modularUi != null) {
            for (ModularUiIntegration.Entry entry : modularUi.scan(screenId)) {
                JsonObject data = entry.describe();
                if (entry.slot() != null && Minecraft.getMinecraft().player != null) {
                    int index = Minecraft.getMinecraft().player.openContainer.inventorySlots.indexOf(entry.slot());
                    if (index >= 0) {
                        JsonObject slot = containers.describeSlot(context, context.player(Json.object()), false, index);
                        data.add("slotRef", slot.get("slotRef"));
                    }
                }
                widgets.add(data);
            }
        }
        return widgets;
    }

    public JsonObject dimensions() {
        Minecraft minecraft = Minecraft.getMinecraft();
        ScaledResolution scaled = new ScaledResolution(minecraft);
        return Json.object("screenId", screenId,
                "guiWidth", scaled.getScaledWidth(), "guiHeight", scaled.getScaledHeight(),
                "framebufferWidth", minecraft.displayWidth, "framebufferHeight", minecraft.displayHeight,
                "guiScale", scaled.getScaleFactor(), "coordinateSpace", "gui_scaled");
    }

    private JsonArray buttons(QueryContext context) {
        JsonArray result = new JsonArray();
        int count = Math.min(buttons.size(), context.settings().maxLimit);
        for (int index = 0; index < count; index++) {
            GuiButton button = buttons.get(index);
            result.add(Json.object("id", button.id, "text", button.displayString, "x", button.x, "y", button.y,
                    "width", button.width, "height", button.height,
                    "enabled", button.enabled, "visible", button.visible));
        }
        if (count < buttons.size()) {
            context.missing("buttons:limit");
        }
        return result;
    }

    private JsonArray slots(QueryContext context, GuiContainer gui, ContainerTracker containers) {
        JsonArray result = new JsonArray();
        if (Minecraft.getMinecraft().player == null) {
            return result;
        }
        for (int index = 0; index < gui.inventorySlots.inventorySlots.size(); index++) {
            Slot slot = gui.inventorySlots.inventorySlots.get(index);
            JsonObject data = containers.describeSlot(context, context.player(Json.object()), false, index);
            data.addProperty("x", gui.getGuiLeft() + slot.xPos);
            data.addProperty("y", gui.getGuiTop() + slot.yPos);
            data.addProperty("width", 16);
            data.addProperty("height", 16);
            data.addProperty("coordinateSpace", "gui_scaled");
            JsonObject target = slotTarget(slot);
            if (target != null) {
                data.add("widgetId", target.get("widgetId"));
                data.add("x", target.get("x"));
                data.add("y", target.get("y"));
                data.addProperty("coordinateAnchor", "center");
            }
            result.add(data);
        }
        return result;
    }
}
