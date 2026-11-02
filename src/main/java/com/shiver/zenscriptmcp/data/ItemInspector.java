package com.shiver.zenscriptmcp.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.ContainerTracker;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.util.ITooltipFlag;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.oredict.OreDictionary;

/** 只在客户端线程读取实际公开数据；单字段失败不丢弃其他字段。 */
public final class ItemInspector {

    private ItemInspector() {
    }

    public static JsonObject inspect(QueryContext context, ContainerTracker containers, JsonObject arguments) {
        ItemStack stack;
        if (arguments.has("slotRef")) {
            stack = containers.item(context, context.player(Json.object()), arguments);
        } else {
            ResourceLocation name = new ResourceLocation(arguments.get("registryName").getAsString());
            if (!Item.REGISTRY.containsKey(name)) {
                throw new DomainException("target_missing", "物品未注册：" + name);
            }
            int metadata = arguments.has("metadata") ? arguments.get("metadata").getAsInt() : 0;
            stack = new ItemStack(Item.REGISTRY.getObject(name), 1, metadata);
        }
        JsonObject result = Json.object("source", "client", "stackSource",
                arguments.has("slotRef") ? "runtime_slot" : "default_stack", "empty", stack.isEmpty());
        if (arguments.has("slotRef")) {
            result.add("slotRef", arguments.get("slotRef"));
        }
        JsonObject errors = new JsonObject();
        read(context, arguments, result, errors, "item", () -> ItemData.describe(stack));
        read(context, arguments, result, errors, "durability", () -> durability(stack));
        read(context, arguments, result, errors, "enchantments", () -> enchantments(stack));
        read(context, arguments, result, errors, "attributes", () -> attributes(stack));
        read(context, arguments, result, errors, "oreDictionary", () -> ores(stack));
        read(context, arguments, result, errors, "nbt", () -> stack.serializeNBT().toString());
        read(context, arguments, result, errors, "capabilities",
                () -> CapabilityReader.read(context, stack, Json.object()));
        read(context, arguments, result, errors, "tooltip", () -> tooltip(stack, false));
        read(context, arguments, result, errors, "advancedTooltip", () -> tooltip(stack, true));
        result.add("fieldErrors", errors);
        return result;
    }

    private static void read(QueryContext context, JsonObject arguments, JsonObject result, JsonObject errors,
            String field, Supplier<?> reader) {
        if (!QueryContext.wants(arguments, field)) {
            return;
        }
        try {
            result.add(field, Json.GSON.toJsonTree(reader.get()));
        } catch (RuntimeException | LinkageError exception) {
            context.missing(field);
            errors.add(field, Json.object("code", "field_read_failed", "message", exception.toString(),
                    "stage", "item_inspect", "details", Json.object("field", field)));
        }
    }

    private static JsonObject durability(ItemStack stack) {
        boolean damageable = stack.isItemStackDamageable();
        int damage = damageable ? stack.getItemDamage() : 0;
        int maximum = damageable ? stack.getMaxDamage() : 0;
        return Json.object("damageable", damageable, "damage", damage, "maximum", maximum,
                "remaining", Math.max(0, maximum - damage), "metadataDamage", stack.getItemDamage());
    }

    private static JsonArray enchantments(ItemStack stack) {
        JsonArray result = new JsonArray();
        for (Map.Entry<Enchantment, Integer> entry : EnchantmentHelper.getEnchantments(stack).entrySet()) {
            result.add(Json.object("registryName", String.valueOf(entry.getKey().getRegistryName()),
                    "level", entry.getValue(), "name", entry.getKey().getTranslatedName(entry.getValue())));
        }
        return result;
    }

    private static JsonArray attributes(ItemStack stack) {
        JsonArray result = new JsonArray();
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            for (Map.Entry<String, AttributeModifier> entry : stack.getAttributeModifiers(slot).entries()) {
                AttributeModifier modifier = entry.getValue();
                result.add(Json.object("equipmentSlot", slot.getName(), "attribute", entry.getKey(),
                        "uuid", modifier.getID().toString(), "name", modifier.getName(),
                        "amount", modifier.getAmount(), "operation", modifier.getOperation()));
            }
        }
        return result;
    }

    private static JsonArray ores(ItemStack stack) {
        JsonArray result = new JsonArray();
        for (int id : OreDictionary.getOreIDs(stack)) {
            result.add(OreDictionary.getOreName(id));
        }
        return result;
    }

    public static JsonObject tooltip(ItemStack stack, boolean advanced) {
        EntityPlayer player = Minecraft.getMinecraft().player;
        return Json.object("lines", stack.getTooltip(player,
                advanced ? ITooltipFlag.TooltipFlags.ADVANCED : ITooltipFlag.TooltipFlags.NORMAL),
                "advanced", advanced, "source", "ItemStack.getTooltip",
                "playerUuid", player == null ? null : player.getUniqueID().toString(),
                "playerContext", player == null ? "absent" : "client_player");
    }
}
