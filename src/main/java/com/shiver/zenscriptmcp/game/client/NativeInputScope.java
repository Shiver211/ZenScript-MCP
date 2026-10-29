package com.shiver.zenscriptmcp.game.client;

import com.shiver.zenscriptmcp.model.DomainException;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** LWJGL 2 事件窗口：不写系统输入队列；在 finally 中恢复原始事件和轮询状态。 */
final class NativeInputScope implements AutoCloseable {

    private final List<Runnable> restorations = new ArrayList<>();

    void keyDown(int key, boolean down) {
        if (key > 0 && key < Keyboard.KEYBOARD_SIZE) {
            buffer(Keyboard.class, "keyDownBuffer", key, down);
        } else if (key < 0) {
            buffer(Mouse.class, "buttons", key + 100, down);
        }
    }

    void mouse(int x, int y, int button, boolean down, int wheel, int heldButton) {
        if (!Mouse.isCreated()) {
            throw new DomainException("source_unavailable", "鼠标输入设备尚未初始化");
        }
        set(Mouse.class, null, "event_x", x);
        set(Mouse.class, null, "event_y", y);
        set(Mouse.class, null, "event_dx", 0);
        set(Mouse.class, null, "event_dy", 0);
        set(Mouse.class, null, "event_dwheel", wheel);
        set(Mouse.class, null, "eventButton", button);
        set(Mouse.class, null, "eventState", down);
        set(Mouse.class, null, "event_nanos", System.nanoTime());
        pointer(x, y, heldButton);
    }

    void pointer(int x, int y, int heldButton) {
        set(Mouse.class, null, "x", x);
        set(Mouse.class, null, "y", y);
        if (heldButton >= 0) {
            buffer(Mouse.class, "buttons", heldButton, true);
        }
    }

    void keyboard(int key, char character, boolean down) {
        if (!Keyboard.isCreated()) {
            throw new DomainException("source_unavailable", "键盘输入设备尚未初始化");
        }
        Object event = InputAccess.get(InputAccess.field(Keyboard.class, "current_event"), null);
        set(event.getClass(), event, "key", key);
        set(event.getClass(), event, "character", (int) character);
        set(event.getClass(), event, "state", down);
        set(event.getClass(), event, "nanos", System.nanoTime());
        set(event.getClass(), event, "repeat", false);
        keyDown(key, down);
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

    @Override
    public void close() {
        for (int index = restorations.size() - 1; index >= 0; index--) {
            restorations.get(index).run();
        }
        restorations.clear();
    }
}
