package com.shiver.zenscriptmcp.game.client;

import java.util.HashMap;
import java.util.Map;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** lwjglxx 直接轮询 GLFW；转换后的调用仅在当前线程有虚拟输入时覆盖真实设备状态。 */
public final class InputPolling {

    private static final int MOUSE_KEY_OFFSET = 100;
    private static final ThreadLocal<Map<Integer, Boolean>> OVERRIDES = new ThreadLocal<>();

    private InputPolling() {
    }

    public static boolean isKeyDown(int key) {
        Boolean down = downState(key);
        return down == null ? Keyboard.isKeyDown(key) : down;
    }

    public static boolean isButtonDown(int button) {
        Boolean down = downState(button - MOUSE_KEY_OFFSET);
        return down == null ? Mouse.isButtonDown(button) : down;
    }

    static Boolean overrideDown(int key, boolean down) {
        Map<Integer, Boolean> overrides = OVERRIDES.get();
        if (overrides == null) {
            overrides = new HashMap<>();
            OVERRIDES.set(overrides);
        }
        return overrides.put(key, down);
    }

    static void restoreDown(int key, Boolean previous) {
        Map<Integer, Boolean> overrides = OVERRIDES.get();
        if (previous == null) {
            overrides.remove(key);
        } else {
            overrides.put(key, previous);
        }
        if (overrides.isEmpty()) {
            OVERRIDES.remove();
        }
    }

    private static Boolean downState(int key) {
        Map<Integer, Boolean> overrides = OVERRIDES.get();
        return overrides == null ? null : overrides.get(key);
    }
}
