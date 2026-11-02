package com.shiver.zenscriptmcp.game.client;

import com.shiver.zenscriptmcp.model.DomainException;
import java.util.IdentityHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.GameSettings;
import net.minecraft.client.settings.KeyBinding;

/** 目录和输入共用绑定身份；只在客户端线程使用。 */
public final class BindingRegistry {

    private final Map<KeyBinding, String> ids = new IdentityHashMap<>();
    private long sequence;
    private long version;
    private String signature = "";

    public void refresh() {
        StringBuilder current = new StringBuilder();
        for (KeyBinding binding : Minecraft.getMinecraft().gameSettings.keyBindings) {
            if (!ids.containsKey(binding)) {
                ids.put(binding, "binding:" + ++sequence);
            }
            current.append(ids.get(binding)).append(':').append(binding.getKeyCode()).append(':')
                    .append(binding.getKeyModifier()).append(':').append(binding.getKeyConflictContext()).append(';');
        }
        if (!signature.equals(current.toString())) {
            signature = current.toString();
            version++;
        }
    }

    public String id(KeyBinding binding) {
        return ids.get(binding);
    }

    public long version() {
        return version;
    }

    public KeyBinding resolve(String id) {
        refresh();
        GameSettings settings = Minecraft.getMinecraft().gameSettings;
        switch (id) {
            case "forward":
                return settings.keyBindForward;
            case "back":
                return settings.keyBindBack;
            case "left":
                return settings.keyBindLeft;
            case "right":
                return settings.keyBindRight;
            case "jump":
                return settings.keyBindJump;
            case "sprint":
                return settings.keyBindSprint;
            case "sneak":
                return settings.keyBindSneak;
            case "attack":
                return settings.keyBindAttack;
            case "use":
                return settings.keyBindUseItem;
            case "inventory":
                return settings.keyBindInventory;
            case "swap_hands":
                return settings.keyBindSwapHands;
            case "drop":
                return settings.keyBindDrop;
            default:
                for (KeyBinding binding : settings.keyBindings) {
                    if (id.equals(ids.get(binding))) {
                        return binding;
                    }
                }
                return resolveDescription(settings, id);
        }
    }

    private static KeyBinding resolveDescription(GameSettings settings, String description) {
        KeyBinding matched = null;
        for (KeyBinding binding : settings.keyBindings) {
            if (description.equals(binding.getKeyDescription())) {
                if (matched != null) {
                    throw new DomainException("invalid_target", "按键名称重复，请使用 mc_catalog 返回的 binding ID");
                }
                matched = binding;
            }
        }
        if (matched == null) {
            throw new DomainException("stale_reference", "当前目录没有该按键绑定：" + description);
        }
        return matched;
    }
}
