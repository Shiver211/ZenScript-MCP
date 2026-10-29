package com.shiver.zenscriptmcp.game.client;

import com.shiver.zenscriptmcp.model.DomainException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.MovementInput;
import net.minecraftforge.client.event.InputUpdateEvent;
import net.minecraftforge.client.settings.KeyModifier;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

/** 客户端独占作业持有输入；不调用全局 unPressAllKeys。 */
public final class InputController {

    private static final Field PRESSED = InputAccess.field(KeyBinding.class, "pressed", "field_74513_e");
    private static final Field PRESS_TIME = InputAccess.field(KeyBinding.class, "pressTime", "field_151474_i");

    private final BindingRegistry bindings;
    private final Map<KeyBinding, HeldBinding> held = new LinkedHashMap<>();
    private NativeInputScope scope;
    private boolean virtualFocus;
    private boolean previousFocus;
    private boolean axesActive;
    private float forward;
    private float strafe;

    public InputController(BindingRegistry bindings) {
        this.bindings = bindings;
    }

    public void beginTick() {
        restoreScope();
        for (HeldBinding entry : held.values()) {
            InputAccess.set(PRESSED, entry.binding, true);
        }
        applyScope();
    }

    public void endTick() {
        restoreScope();
        for (HeldBinding entry : held.values()) {
            // 保留一次按下给下一 Tick 的监听器；释放时再清除仍未消费的计数。
            int count = (Integer) InputAccess.get(PRESS_TIME, entry.binding);
            if (count <= entry.previousPresses) {
                entry.pendingPress = false;
            }
        }
    }

    public void press(String id, boolean edge) {
        KeyBinding binding = bindings.resolve(id);
        if (held.containsKey(binding)) {
            return;
        }
        if (!binding.getKeyConflictContext().isActive() || binding.getKeyCode() == Keyboard.KEY_NONE) {
            throw new DomainException("action_rejected", "按键未绑定或当前冲突上下文不允许输入：" + id);
        }
        restoreScope();
        HeldBinding entry = new HeldBinding(binding);
        held.put(binding, entry);
        applyScope();
        InputAccess.set(PRESSED, binding, true);
        if (edge) {
            entry.previousPresses = (Integer) InputAccess.get(PRESS_TIME, binding);
            InputAccess.set(PRESS_TIME, binding, entry.previousPresses + 1);
            entry.pendingPress = true;
            try (NativeInputScope event = new NativeInputScope()) {
                if (entry.code > 0) {
                    event.keyboard(entry.code, '\0', true);
                    FMLCommonHandler.instance().fireKeyInput();
                } else {
                    event.mouse(Mouse.getX(), Mouse.getY(), entry.code + 100, true, 0, entry.code + 100);
                    FMLCommonHandler.instance().fireMouseInput();
                }
            }
        }
    }

    public void release(String id) {
        release(bindings.resolve(id));
    }

    public void axes(float forward, float strafe) {
        axesActive = true;
        this.forward = forward;
        this.strafe = strafe;
    }

    public void validate() {
        for (HeldBinding entry : held.values()) {
            if (entry.code != entry.binding.getKeyCode() || entry.modifier != entry.binding.getKeyModifier()
                    || !entry.binding.getKeyConflictContext().isActive()) {
                throw new DomainException("stale_reference", "输入期间按键绑定或冲突上下文发生变化");
            }
        }
    }

    public void clear() {
        axesActive = false;
        for (KeyBinding binding : new ArrayList<>(held.keySet())) {
            release(binding);
        }
        restoreScope();
    }

    public boolean owns(String id) {
        return held.containsKey(bindings.resolve(id));
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onMovement(InputUpdateEvent event) {
        if (!axesActive || event.getEntityPlayer() != Minecraft.getMinecraft().player) {
            return;
        }
        MovementInput movement = event.getMovementInput();
        float scale = movement.sneak ? 0.3F : 1.0F;
        movement.moveForward = forward * scale;
        movement.moveStrafe = strafe * scale;
        movement.forwardKeyDown = forward > 0;
        movement.backKeyDown = forward < 0;
        movement.leftKeyDown = strafe > 0;
        movement.rightKeyDown = strafe < 0;
    }

    private void release(KeyBinding binding) {
        restoreScope();
        HeldBinding entry = held.remove(binding);
        if (entry != null) {
            InputAccess.set(PRESSED, binding, physicalDown(entry.code));
            int count = (Integer) InputAccess.get(PRESS_TIME, binding);
            if (entry.pendingPress && count > entry.previousPresses) {
                InputAccess.set(PRESS_TIME, binding, count - 1);
            }
        }
        applyScope();
    }

    private void applyScope() {
        scope = new NativeInputScope();
        try {
            for (HeldBinding entry : held.values()) {
                scope.keyDown(entry.code, true);
                if (entry.modifier == KeyModifier.SHIFT) {
                    scope.keyDown(Keyboard.KEY_LSHIFT, true);
                } else if (entry.modifier == KeyModifier.CONTROL) {
                    scope.keyDown(Keyboard.KEY_LCONTROL, true);
                } else if (entry.modifier == KeyModifier.ALT) {
                    scope.keyDown(Keyboard.KEY_LMENU, true);
                }
            }
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.currentScreen == null && held.containsKey(minecraft.gameSettings.keyBindAttack)) {
                // 原版持续破坏检查游戏焦点；仅在本 Tick 提供该意图，不抓取系统鼠标。
                previousFocus = minecraft.inGameHasFocus;
                virtualFocus = true;
                minecraft.inGameHasFocus = true;
            }
        } catch (RuntimeException exception) {
            restoreScope();
            throw exception;
        }
    }

    private void restoreScope() {
        if (virtualFocus) {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (minecraft.currentScreen == null) {
                minecraft.inGameHasFocus = previousFocus;
            }
            virtualFocus = false;
        }
        if (scope != null) {
            scope.close();
            scope = null;
        }
    }

    private static boolean physicalDown(int code) {
        if (code > 0 && code < Keyboard.KEYBOARD_SIZE) {
            return Keyboard.isCreated() && Keyboard.isKeyDown(code);
        }
        return code < 0 && Mouse.isCreated() && Mouse.isButtonDown(code + 100);
    }

    private static final class HeldBinding {

        private final KeyBinding binding;
        private final int code;
        private final KeyModifier modifier;
        private boolean pendingPress;
        private int previousPresses;

        private HeldBinding(KeyBinding binding) {
            this.binding = binding;
            code = binding.getKeyCode();
            modifier = binding.getKeyModifier();
        }
    }
}
