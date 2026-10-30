package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.gui.GuiSlot;
import net.minecraft.client.gui.GuiTextField;
import net.minecraftforge.client.event.GuiScreenEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/** 使用实际 GUI 输入入口及 Forge 前后事件；虚拟设备状态仅在回调/渲染窗口内有效。 */
public final class GuiInputBridge {

    private static final int CHARACTERS_PER_TICK = 32;

    private enum DispatchPath {
        PRE_EVENT_CANCELLED,
        GUI_CALLBACK
    }

    private final ScreenReader screens;
    private final SnapshotStore snapshots;
    private GuiScreen dragScreen;
    private String dragScreenId;
    private int dragButton = -1;
    private int pointerX;
    private int pointerY;
    private NativeInputScope renderScope;

    public GuiInputBridge(ScreenReader screens, SnapshotStore snapshots) {
        this.screens = screens;
        this.snapshots = snapshots;
    }

    public JobManager.Task task(JsonObject request) {
        return new GuiTask(request);
    }

    private long tick() {
        return snapshots.status().getAsJsonObject("client").get("tick").getAsLong();
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.END) {
            if (renderScope != null) {
                renderScope.close();
                renderScope = null;
            }
        } else if (dragButton >= 0 && Minecraft.getMinecraft().currentScreen == dragScreen
                && dragScreenId.equals(screens.dimensions().get("screenId").getAsString())) {
            renderScope = new NativeInputScope();
            try {
                renderScope.pointer(rawX(pointerX), rawY(pointerY), dragButton);
            } catch (RuntimeException exception) {
                renderScope.close();
                renderScope = null;
            }
        }
    }

    private DispatchPath mouse(GuiScreen screen, int x, int y, int button, boolean down, int wheel) throws IOException {
        requireCoordinates(x, y);
        pointerX = x;
        pointerY = y;
        updateLists(screen, x, y);
        try (NativeInputScope input = new NativeInputScope()) {
            input.mouse(rawX(x), rawY(y), button, down, wheel, down ? button : dragButton);
            if (button >= 0 && !down) {
                input.keyDown(button - 100, false);
            }
            Field handled = InputAccess.field(GuiScreen.class, "mouseHandled");
            InputAccess.set(handled, screen, false);
            if (MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.MouseInputEvent.Pre(screen))) {
                // 取消只阻止原版回调；模组可能已经消费输入，不能推断为动作拒绝。
                return DispatchPath.PRE_EVENT_CANCELLED;
            }
            screen.handleMouseInput();
            if (screen == Minecraft.getMinecraft().currentScreen && !(Boolean) InputAccess.get(handled, screen)) {
                MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.MouseInputEvent.Post(screen));
            }
        }
        return DispatchPath.GUI_CALLBACK;
    }

    private DispatchPath keyboard(GuiScreen screen, int key, char character, boolean down,
            JsonObject request) throws IOException {
        try (NativeInputScope input = new NativeInputScope()) {
            input.keyboard(key, character, down);
            if (request.has("modifiers")) {
                for (JsonElement modifier : request.getAsJsonArray("modifiers")) {
                    String value = modifier.getAsString();
                    int code = "shift".equals(value) ? Keyboard.KEY_LSHIFT
                            : "control".equals(value) ? Keyboard.KEY_LCONTROL : Keyboard.KEY_LMENU;
                    input.keyDown(code, true);
                }
            }
            Field handled = InputAccess.field(GuiScreen.class, "keyHandled");
            InputAccess.set(handled, screen, false);
            if (MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.KeyboardInputEvent.Pre(screen))) {
                return DispatchPath.PRE_EVENT_CANCELLED;
            }
            screen.handleKeyboardInput();
            if (screen == Minecraft.getMinecraft().currentScreen && !(Boolean) InputAccess.get(handled, screen)) {
                MinecraftForge.EVENT_BUS.post(new GuiScreenEvent.KeyboardInputEvent.Post(screen));
            }
        }
        return DispatchPath.GUI_CALLBACK;
    }

    private int rawX(int x) {
        JsonObject size = screens.dimensions();
        return (int) Math.ceil((double) x * size.get("framebufferWidth").getAsInt() / size.get("guiWidth").getAsInt());
    }

    private int rawY(int y) {
        JsonObject size = screens.dimensions();
        return (int) Math.ceil((double) (size.get("guiHeight").getAsInt() - y - 1)
                * size.get("framebufferHeight").getAsInt() / size.get("guiHeight").getAsInt());
    }

    private void requireCoordinates(int x, int y) {
        JsonObject size = screens.dimensions();
        if (x < 0 || y < 0 || x >= size.get("guiWidth").getAsInt() || y >= size.get("guiHeight").getAsInt()) {
            throw new DomainException("invalid_target", "坐标超出当前 GUI 缩放尺寸");
        }
    }

    private static void updateLists(GuiScreen screen, int x, int y) {
        // GuiSlot 使用最近一次 drawScreen 的坐标；只更新标准列表的指针缓存。
        for (Class<?> type = screen.getClass(); type != null && GuiScreen.class.isAssignableFrom(type);
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && GuiSlot.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    Object list = InputAccess.get(field, screen);
                    if (list != null) {
                        InputAccess.set(InputAccess.field(GuiSlot.class, "mouseX", "field_148150_g"), list, x);
                        InputAccess.set(InputAccess.field(GuiSlot.class, "mouseY", "field_148162_h"), list, y);
                    }
                }
            }
        }
    }

    private static JsonArray texts(GuiScreen screen) {
        JsonArray result = new JsonArray();
        for (Class<?> type = screen.getClass(); type != null && GuiScreen.class.isAssignableFrom(type);
                type = type.getSuperclass()) {
            for (Field field : type.getDeclaredFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && GuiTextField.class.isAssignableFrom(field.getType())) {
                    field.setAccessible(true);
                    GuiTextField text = (GuiTextField) InputAccess.get(field, screen);
                    if (text != null && text.isFocused()) {
                        result.add(Json.object("id", text.getId(), "text", text.getText()));
                    }
                }
            }
        }
        return result;
    }

    private final class GuiTask implements JobManager.Task {

        private final JsonObject request;
        private final String screenId;
        private final String action;
        private GuiScreen screen;
        private JsonObject before;
        private JsonObject result;
        private int step;
        private int textOffset;
        private int preEventCancelledCount;
        private boolean pressed;
        private long startedTick;

        private GuiTask(JsonObject request) {
            this.request = request;
            screenId = request.get("screenId").getAsString();
            action = request.get("action").getAsString();
        }

        @Override
        public JsonObject step() throws IOException {
            Minecraft minecraft = Minecraft.getMinecraft();
            screens.update(snapshots.worldEpoch(), tick());
            if (before == null) {
                if (!screenId.equals(screens.dimensions().get("screenId").getAsString())) {
                    throw new DomainException("stale_reference", "GUI 引用已经失效");
                }
                screen = minecraft.currentScreen;
                before = screens.dimensions();
                startedTick = tick();
            } else if (!screenId.equals(screens.dimensions().get("screenId").getAsString())) {
                throw new DomainException("stale_reference", "输入过程中 GUI 或缩放发生变化");
            }
            if (screen == null) {
                if ("key".equals(action) && request.get("keyCode").getAsInt() == Keyboard.KEY_ESCAPE) {
                    minecraft.displayInGameMenu();
                    return finish();
                }
                throw new DomainException("unsupported_capability", "HUD 仅支持 Escape 打开暂停界面");
            }
            switch (action) {
                case "click":
                    int button = request.get("button").getAsInt();
                    int x = request.get("x").getAsInt();
                    int y = request.get("y").getAsInt();
                    recordDispatch(mouse(screen, x, y, button, true, 0));
                    if (minecraft.currentScreen == screen) {
                        recordDispatch(mouse(screen, x, y, button, false, 0));
                    }
                    return finish();
                case "scroll":
                    recordDispatch(mouse(screen, request.get("x").getAsInt(), request.get("y").getAsInt(),
                            -1, false, request.get("delta").getAsInt()));
                    return finish();
                case "drag":
                    return drag();
                case "text":
                    String text = request.get("text").getAsString();
                    int end = Math.min(text.length(), textOffset + CHARACTERS_PER_TICK);
                    while (textOffset < end) {
                        recordDispatch(keyboard(screen, 0, text.charAt(textOffset++), true, Json.object()));
                        if (minecraft.currentScreen != screen) {
                            return finish();
                        }
                    }
                    result = observe();
                    return textOffset >= text.length() ? result : null;
                case "key":
                case "close":
                    int key = "close".equals(action) ? Keyboard.KEY_ESCAPE : request.get("keyCode").getAsInt();
                    recordDispatch(keyboard(screen, key, '\0', true, request));
                    if (minecraft.currentScreen == screen) {
                        recordDispatch(keyboard(screen, key, '\0', false, request));
                    }
                    return finish();
                default:
                    throw new IllegalArgumentException("未知 GUI 输入");
            }
        }

        private JsonObject drag() throws IOException {
            JsonArray points = request.getAsJsonArray("points");
            JsonObject point = points.get(Math.min(step, points.size() - 1)).getAsJsonObject();
            int x = point.get("x").getAsInt();
            int y = point.get("y").getAsInt();
            if (!pressed) {
                dragScreen = screen;
                dragScreenId = screenId;
                dragButton = request.get("button").getAsInt();
                pressed = true;
                recordDispatch(mouse(screen, x, y, dragButton, true, 0));
            } else if (step < points.size()) {
                recordDispatch(mouse(screen, x, y, -1, false, 0));
            } else {
                recordDispatch(mouse(screen, x, y, dragButton, false, 0));
                pressed = false;
                dragButton = -1;
                return finish();
            }
            step++;
            result = observe();
            return null;
        }

        private void recordDispatch(DispatchPath path) {
            if (path == DispatchPath.PRE_EVENT_CANCELLED) {
                preEventCancelledCount++;
            }
        }

        private JsonObject finish() {
            result = observe();
            return result;
        }

        private JsonObject observe() {
            screens.update(snapshots.worldEpoch(), tick());
            JsonObject after = screens.dimensions();
            JsonArray text = screen == null ? new JsonArray() : texts(screen);
            boolean changed = !before.get("screenId").equals(after.get("screenId"));
            return Json.object("observation", snapshots.observation("client", startedTick, new JsonArray()),
                    "data", Json.object("before", before, "after", after,
                    "delivered", true, "screenChanged", changed, "focusedText", text,
                    "confirmation", "unconfirmed", "preEventCancelledCount", preEventCancelledCount,
                    "coverage", "standard_gui_and_lwjgl2", "textCharactersDispatched", textOffset));
        }

        @Override
        public void cleanup() throws IOException {
            try {
                if (pressed && Minecraft.getMinecraft().currentScreen == screen
                        && screenId.equals(screens.dimensions().get("screenId").getAsString())) {
                    mouse(screen, pointerX, pointerY, dragButton, false, 0);
                }
            } finally {
                pressed = false;
                dragButton = -1;
                dragScreen = null;
                if (renderScope != null) {
                    renderScope.close();
                    renderScope = null;
                }
            }
        }

        @Override
        public JsonObject lastResult() {
            return result;
        }
    }
}
