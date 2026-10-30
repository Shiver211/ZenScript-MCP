package com.shiver.zenscriptmcp.game.client;

import com.shiver.zenscriptmcp.model.DomainException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** 客户端线程的同步事件窗口：兼容 LWJGL 2/lwjglxx，不推进真实队列，关闭时恢复事件和轮询状态。 */
final class NativeInputScope implements AutoCloseable {

    private static final int MOUSE_KEY_OFFSET = 100;
    private static final boolean LWJGLX_MOUSE = InputAccess.hasMethod(Mouse.class,
            "addMoveEvent", double.class, double.class);
    private static final boolean LWJGLX_KEYBOARD = InputAccess.hasMethod(Keyboard.class,
            "addKeyEvent", int.class, boolean.class);

    private final List<Runnable> restorations = new ArrayList<>();

    void keyDown(int key, boolean down) {
        if (key > 0 && key < Keyboard.KEYBOARD_SIZE) {
            if (LWJGLX_KEYBOARD) {
                overrideDown(key, down);
            } else {
                buffer(Keyboard.class, "keyDownBuffer", key, down);
            }
        } else if (key < 0) {
            if (LWJGLX_MOUSE) {
                overrideDown(key, down);
            } else {
                buffer(Mouse.class, "buttons", key + MOUSE_KEY_OFFSET, down);
            }
        }
    }

    void mouse(int x, int y, int button, boolean down, int wheel, int heldButton) {
        if (!Mouse.isCreated()) {
            throw new DomainException("source_unavailable", "鼠标输入设备尚未初始化");
        }
        if (LWJGLX_MOUSE) {
            lwjglxMouse(x, y, button, down, wheel);
        } else {
            lwjgl2Mouse(x, y, button, down, wheel);
        }
        pointer(x, y, heldButton);
    }

    private void lwjgl2Mouse(int x, int y, int button, boolean down, int wheel) {
        set(Mouse.class, null, "event_x", x);
        set(Mouse.class, null, "event_y", y);
        set(Mouse.class, null, "event_dx", 0);
        set(Mouse.class, null, "event_dy", 0);
        set(Mouse.class, null, "event_dwheel", wheel);
        set(Mouse.class, null, "eventButton", button);
        set(Mouse.class, null, "eventState", down);
        set(Mouse.class, null, "event_nanos", System.nanoTime());
    }

    private void lwjglxMouse(int x, int y, int button, boolean down, int wheel) {
        Object queue = InputAccess.get(InputAccess.field(Mouse.class, "queue"), null);
        int index = (Integer) InputAccess.get(InputAccess.field(queue.getClass(), "readEventPos"), queue);
        // 只替换当前槽；addMoveEvent 会转换坐标，入队还会导致同步回调之后再次消费。
        element(Mouse.class, "xEvents", index, x);
        element(Mouse.class, "yEvents", index, y);
        element(Mouse.class, "lastxEvents", index, x);
        element(Mouse.class, "lastyEvents", index, y);
        element(Mouse.class, "wheelEvents", index, wheel);
        element(Mouse.class, "buttonEvents", index, button);
        element(Mouse.class, "buttonEventStates", index, down);
        element(Mouse.class, "nanoTimeEvents", index, System.nanoTime());
    }

    void pointer(int x, int y, int heldButton) {
        set(Mouse.class, null, "x", x);
        set(Mouse.class, null, "y", y);
        if (heldButton >= 0) {
            keyDown(heldButton - MOUSE_KEY_OFFSET, true);
        }
    }

    void keyboard(int key, char character, boolean down) {
        if (!Keyboard.isCreated()) {
            throw new DomainException("source_unavailable", "键盘输入设备尚未初始化");
        }
        if (LWJGLX_KEYBOARD) {
            lwjglxKeyboard(key, character, down);
        } else {
            lwjgl2Keyboard(key, character, down);
        }
        keyDown(key, down);
    }

    private void lwjgl2Keyboard(int key, char character, boolean down) {
        Object event = InputAccess.get(InputAccess.field(Keyboard.class, "current_event"), null);
        set(event.getClass(), event, "key", key);
        set(event.getClass(), event, "character", (int) character);
        set(event.getClass(), event, "state", down);
        set(event.getClass(), event, "nanos", System.nanoTime());
        set(event.getClass(), event, "repeat", false);
    }

    private void lwjglxKeyboard(int key, char character, boolean down) {
        Queue<?> queue = (Queue<?>) InputAccess.get(InputAccess.field(Keyboard.class, "queue"), null);
        // lwjglxx 始终保留队首作为当前事件，next() 才移除它；不替换队列或推进指针。
        Object event = queue.peek();
        if (event == null) {
            throw new DomainException("unsupported_capability", "键盘输入缺少当前事件槽");
        }
        Field state = InputAccess.field(event.getClass(), "state");
        Object keyState = InputAccess.get(InputAccess.field(state.getType(), down ? "PRESS" : "RELEASE"), null);
        set(event.getClass(), event, "key", key);
        set(event.getClass(), event, "aChar", character);
        set(event.getClass(), event, "state", keyState);
        set(event.getClass(), event, "nano", System.nanoTime());
    }

    private void overrideDown(int key, boolean down) {
        Boolean previous = InputPolling.overrideDown(key, down);
        restorations.add(() -> InputPolling.restoreDown(key, previous));
    }

    private void set(Class<?> type, Object owner, String name, Object value) {
        Field field = InputAccess.field(type, name);
        Object previous = InputAccess.get(field, owner);
        restorations.add(() -> InputAccess.set(field, owner, previous));
        InputAccess.set(field, owner, value);
    }

    private void buffer(Class<?> type, String name, int index, boolean down) {
        ByteBuffer buffer = (ByteBuffer) InputAccess.get(InputAccess.field(type, name), null);
        if (buffer == null || index < 0 || index >= buffer.limit()) {
            throw new DomainException("unsupported_capability", "输入设备不支持该按键");
        }
        byte previous = buffer.get(index);
        restorations.add(() -> buffer.put(index, previous));
        buffer.put(index, down ? (byte) 1 : (byte) 0);
    }

    private void element(Class<?> type, String name, int index, Object value) {
        Object array = InputAccess.get(InputAccess.field(type, name), null);
        Object previous = Array.get(array, index);
        restorations.add(() -> Array.set(array, index, previous));
        Array.set(array, index, value);
    }

    @Override
    public void close() {
        for (int index = restorations.size() - 1; index >= 0; index--) {
            restorations.get(index).run();
        }
        restorations.clear();
    }
}
