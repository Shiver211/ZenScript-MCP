package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.ContainerSlots;
import com.shiver.zenscriptmcp.game.query.ContainerTracker;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import java.util.Collections;
import java.util.List;
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

    public ScreenReader(EventBuffer events) {
        this.events = events;
    }

    @SubscribeEvent
    public void onInit(GuiScreenEvent.InitGuiEvent.Post event) {
        initializedScreen = event.getGui();
        buttons = event.getButtonList();
        screenId = instancePrefix + ":screen:" + ++sequence;
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
        JsonObject data = dimensions();
        data.addProperty("class", screen == null ? "hud" : screen.getClass().getName());
        data.addProperty("open", screen != null);
        context.checkFields(arguments, "buttons", "slots", "title");
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
            context.field(data, arguments, "slots", () -> slots(context, gui));
        }
        return data;
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

    private JsonArray slots(QueryContext context, GuiContainer gui) {
        JsonArray result = new JsonArray();
        int count = Math.min(gui.inventorySlots.inventorySlots.size(), context.settings().maxLimit);
        for (int index = 0; index < count; index++) {
            Slot slot = gui.inventorySlots.inventorySlots.get(index);
            result.add(Json.object("slotId", index,
                    "vanillaSlotId", ContainerSlots.isVanillaSlot(gui.inventorySlots, index) ? index : null,
                    "x", gui.getGuiLeft() + slot.xPos,
                    "y", gui.getGuiTop() + slot.yPos, "width", 16, "height", 16));
        }
        if (count < gui.inventorySlots.inventorySlots.size()) {
            context.missing("slots:limit");
        }
        return result;
    }
}
