package com.shiver.zenscriptmcp.data;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import net.minecraft.item.ItemStack;

public final class ItemData {

    private ItemData() {
    }

    public static JsonObject describe(ItemStack stack) {
        if (stack.isEmpty()) {
            return Json.object("empty", true, "count", 0);
        }
        return Json.object(
                "empty", false,
                "registryName", String.valueOf(stack.getItem().getRegistryName()),
                "count", stack.getCount(),
                "metadata", stack.getMetadata(),
                "damage", stack.getItemDamage(),
                "displayName", stack.getDisplayName(),
                "maxStackSize", stack.getMaxStackSize(),
                "hasNbt", stack.hasTagCompound());
    }
}
