package com.shiver.zenscriptmcp.integration.modularui;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.client.ScreenReader;
import com.shiver.zenscriptmcp.integration.GameIntegration;
import com.shiver.zenscriptmcp.integration.IntegrationContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.client.Minecraft;
import net.minecraft.inventory.Slot;

/** 仅调用 3.1.6 已核对的公开 API；反射隔离可选依赖，不访问私有字段或模组资源。 */
public final class ModularUiIntegration implements GameIntegration {

    private static final String ROOT = "com.cleanroommc.modularui.";
    private static final int MAX_WIDGETS = 10000;
    private final ScreenReader screens;
    private final Class<?> screenType = type("screen.ModularScreen");
    private final Class<?> viewportType = type("api.layout.IViewport");
    private final Class<?> itemSlotType = type("widgets.slot.ItemSlot");
    private final Map<Object, Entry> byWidget = new IdentityHashMap<>();
    private final Map<String, Entry> byId = new LinkedHashMap<>();
    private String screenId;
    private Object modularScreen;

    public ModularUiIntegration(ScreenReader screens) {
        this.screens = screens;
    }

    @Override
    public void register(IntegrationContext context) {
        context.registerScreenReader("status", (query, screen, arguments) ->
                current() == null ? null : Json.object("version", "3.1.6", "api", "public"));
        screens.setModularUi(this);
    }

    public List<Entry> scan(String currentScreenId) {
        Object current = current();
        if (!currentScreenId.equals(screenId) || current != modularScreen) {
            byWidget.clear();
            byId.clear();
        }
        screenId = currentScreenId;
        modularScreen = current;
        List<Entry> entries = new ArrayList<>();
        if (current == null) {
            return entries;
        }
        Object stack = construct("screen.viewport.GuiViewportStack");
        Object screenArea = call(current, "getScreenArea");
        call(stack, "pushViewport", null, screenArea);
        Bounds clip = new Bounds(0, 0, number(screenArea, "w"), number(screenArea, "h"));
        Map<Object, Entry> next = new IdentityHashMap<>();
        for (Object panel : (Iterable<?>) call(call(current, "getPanelManager"), "getOpenPanels")) {
            collect(panel, null, stack, clip, true, entries, next);
        }
        byWidget.clear();
        byWidget.putAll(next);
        byId.clear();
        for (Entry entry : entries) {
            byId.put(entry.id, entry);
        }
        return entries;
    }

    private void collect(Object widget, Entry parent, Object stack, Bounds clip, boolean parentEnabled,
            List<Entry> entries, Map<Object, Entry> next) {
        if (entries.size() >= MAX_WIDGETS) {
            throw new DomainException("result_too_large", "控件树超过采集预算");
        }
        call(stack, "pushMatrix");
        try {
            call(widget, "transform", stack);
            Object area = call(widget, "getArea");
            Bounds bounds = bounds(stack, number(area, "w"), number(area, "h"));
            Bounds visible = bounds.intersect(clip);
            boolean enabled = parentEnabled && (Boolean) call(widget, "isEnabled")
                    && (Boolean) call(widget, "isValid");
            Slot slot = itemSlotType.isInstance(widget) ? (Slot) call(widget, "getVanillaSlot") : null;
            String parentId = parent == null ? null : parent.id;
            String signature = bounds.toString() + ":" + visible + ":" + parentId + ":" + enabled;
            Entry old = byWidget.get(widget);
            String id = old != null && signature.equals(old.signature) && old.slot == slot
                    ? old.id : "widget:" + UUID.randomUUID();
            Entry entry = new Entry(widget, slot, id, parentId, signature, bounds, visible, enabled);
            entries.add(entry);
            next.put(widget, entry);
            if (viewportType.isInstance(widget)) {
                // 父视口的滚动只作用于后代；当前控件的矩形保留未滚动位置。
                call(widget, "transformChildren", stack);
                clip = visible;
            }
            for (Object child : (Iterable<?>) call(widget, "getChildren")) {
                collect(child, entry, stack, clip, enabled, entries, next);
            }
        } finally {
            call(stack, "popMatrix");
        }
    }

    public JsonObject target(String id, String currentScreenId) {
        scan(currentScreenId);
        Entry entry = byId.get(id);
        if (entry == null) {
            throw new DomainException("stale_reference", "控件已重建、移动、裁剪或缩放，请重新读取界面");
        }
        if (!entry.enabled || entry.visible.width <= 0 || entry.visible.height <= 0) {
            throw new DomainException("action_rejected", "控件不可见或未启用");
        }
        return Json.object("screenId", currentScreenId, "widgetId", entry.id, "path", "modularui",
                "x", entry.visible.x + entry.visible.width / 2, "y", entry.visible.y + entry.visible.height / 2);
    }

    public JsonObject slotTarget(Slot slot, String currentScreenId) {
        for (Entry entry : byId.values()) {
            if (entry.slot == slot && entry.enabled && entry.visible.width > 0 && entry.visible.height > 0) {
                return Json.object("screenId", currentScreenId, "widgetId", entry.id, "path", "modularui",
                        "phantom", call(slot, "isPhantom"),
                        "x", entry.visible.x + entry.visible.width / 2,
                        "y", entry.visible.y + entry.visible.height / 2);
            }
        }
        return null;
    }

    public JsonObject tooltip(String id, String currentScreenId) {
        target(id, currentScreenId);
        Entry entry = byId.get(id);
        Object tooltip = call(entry.widget, "getTooltip");
        if (tooltip == null) {
            return Json.object("lines", new JsonArray(), "source", "ModularUI.getTooltip");
        }
        call(tooltip, "buildTooltip");
        return Json.object("lines", call(call(tooltip, "getRichText"), "getAsStrings"),
                "source", "ModularUI.getTooltip", "nonTextContent", "unknown");
    }

    public void preparePointer(int x, int y, String expectedWidgetId) {
        Object current = current();
        if (current == null) {
            return;
        }
        Object context = call(current, "getContext");
        // MUI 的输入分发使用最近帧的悬停列表；先按同一公开帧更新路径重算。
        call(context, "updateState", x, y, 0.0f);
        call(current, "onFrameUpdate");
        if (expectedWidgetId != null) {
            Entry entry = byId.get(expectedWidgetId);
            Object top = call(call(current, "getPanelManager"), "getTopWidget");
            while (entry != null && top != null && top != entry.widget
                    && !type("api.widget.Interactable").isInstance(top) && (Boolean) call(top, "hasParent")) {
                top = call(top, "getParent");
            }
            if (entry == null || top != entry.widget) {
                throw new DomainException("action_rejected", "目标控件被其他控件遮挡或输入区域已经变化");
            }
        }
    }

    private Object current() {
        Object current = call(screenType, "getCurrent");
        return current != null && call(current, "getScreenWrapper") == Minecraft.getMinecraft().currentScreen
                ? current : null;
    }

    private static Bounds bounds(Object stack, int width, int height) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int x : new int[] {0, width}) {
            for (int y : new int[] {0, height}) {
                int mappedX = (Integer) call(stack, "transformX", (float) x, (float) y);
                int mappedY = (Integer) call(stack, "transformY", (float) x, (float) y);
                minX = Math.min(minX, mappedX);
                minY = Math.min(minY, mappedY);
                maxX = Math.max(maxX, mappedX);
                maxY = Math.max(maxY, mappedY);
            }
        }
        return new Bounds(minX, minY, maxX - minX, maxY - minY);
    }

    private static int number(Object target, String method) {
        return ((Number) call(target, method)).intValue();
    }

    private static Class<?> type(String name) {
        try {
            return Class.forName(ROOT + name);
        } catch (ClassNotFoundException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Object construct(String name) {
        try {
            return type(name).getConstructor().newInstance();
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static Object call(Object target, String name, Object... arguments) {
        Class<?> owner = target instanceof Class<?> ? (Class<?>) target : target.getClass();
        for (Method method : owner.getMethods()) {
            if (!method.getName().equals(name) || method.getParameterCount() != arguments.length
                    || !matches(method.getParameterTypes(), arguments)) {
                continue;
            }
            try {
                return method.invoke(target instanceof Class<?> ? null : target, arguments);
            } catch (InvocationTargetException exception) {
                throw new IllegalStateException("ModularUI API：" + name, exception.getCause());
            } catch (IllegalAccessException exception) {
                throw new IllegalStateException(exception);
            }
        }
        throw new IllegalStateException("ModularUI 3.1.6 API 不可用：" + owner.getName() + "." + name);
    }

    private static boolean matches(Class<?>[] parameters, Object[] arguments) {
        for (int index = 0; index < parameters.length; index++) {
            Class<?> parameter = parameters[index];
            Object value = arguments[index];
            if (value == null) {
                if (parameter.isPrimitive()) {
                    return false;
                }
            } else if (!parameter.isInstance(value) && !(parameter == int.class && value instanceof Integer)
                    && !(parameter == float.class && value instanceof Float)) {
                return false;
            }
        }
        return true;
    }

    public static final class Entry {

        private final Object widget;
        private final Slot slot;
        private final String id;
        private final String parentId;
        private final String signature;
        private final Bounds bounds;
        private final Bounds visible;
        private final boolean enabled;

        private Entry(Object widget, Slot slot, String id, String parentId, String signature,
                Bounds bounds, Bounds visible, boolean enabled) {
            this.widget = widget;
            this.slot = slot;
            this.id = id;
            this.parentId = parentId;
            this.signature = signature;
            this.bounds = bounds;
            this.visible = visible;
            this.enabled = enabled;
        }

        public Slot slot() {
            return slot;
        }

        public JsonObject describe() {
            JsonObject result = Json.object("widgetId", id, "parentId", parentId,
                    "type", widget.getClass().getName(), "name", call(widget, "getName"),
                    "enabled", enabled, "visible", enabled && visible.width > 0 && visible.height > 0,
                    "bounds", bounds.json(), "visibleBounds", visible.json(), "coordinateSpace", "gui_scaled");
            Object text = text(widget);
            result.add("text", Json.GSON.toJsonTree(text));
            result.addProperty("textCoverage", text == null ? "unknown" : "public_text");
            return result;
        }

        private static Object text(Object widget) {
            if (type("widgets.TextWidget").isInstance(widget)) {
                return call(call(widget, "getKey"), "get");
            }
            if (type("widgets.RichTextWidget").isInstance(widget)) {
                return call(call(widget, "getRichText"), "getAsStrings");
            }
            if (type("widgets.textfield.TextFieldWidget").isInstance(widget)) {
                return call(widget, "getText");
            }
            if (type("widgets.textfield.BaseTextFieldWidget").isInstance(widget)) {
                return call(widget, "getLastText");
            }
            if (type("widget.Widget").isInstance(widget)) {
                Object overlay = call(widget, "getOverlay");
                if (overlay != null && type("api.drawable.IKey").isInstance(overlay)) {
                    return call(overlay, "get");
                }
            }
            return null;
        }
    }

    private static final class Bounds {

        private final int x;
        private final int y;
        private final int width;
        private final int height;

        private Bounds(int x, int y, int width, int height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        private Bounds intersect(Bounds other) {
            int left = Math.max(x, other.x);
            int top = Math.max(y, other.y);
            return new Bounds(left, top, Math.max(0, Math.min(x + width, other.x + other.width) - left),
                    Math.max(0, Math.min(y + height, other.y + other.height) - top));
        }

        private JsonObject json() {
            return Json.object("x", x, "y", y, "width", width, "height", height);
        }

        @Override
        public String toString() {
            return x + ":" + y + ":" + width + ":" + height;
        }
    }
}
