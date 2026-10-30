package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.data.ItemData;
import com.shiver.zenscriptmcp.game.query.ContainerSlots;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.game.query.TargetResolver;
import com.shiver.zenscriptmcp.game.query.WorldReader;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.inventory.Slot;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/** 两端使用相同字段采集；对象不离开其所属线程。 */
final class ActionObservation {

    private ActionObservation() {
    }

    static JsonObject capture(QueryContext context, JsonObject arguments) {
        EntityPlayer player = context.player(Json.object());
        JsonObject data = Json.object("position", WorldReader.vector(player.getPositionVector()),
                "yaw", player.rotationYaw, "pitch", player.rotationPitch, "dimension", player.dimension,
                "health", player.getHealth(), "selectedHotbar", player.inventory.currentItem,
                "mainHand", item(player.getHeldItemMainhand()), "offHand", item(player.getHeldItemOffhand()),
                "usingItem", player.isHandActive(), "windowId", player.openContainer.windowId,
                "containerClass", player.openContainer.getClass().getName(),
                "riding", player.getRidingEntity() == null ? null : player.getRidingEntity().getUniqueID().toString());
        if (arguments.has("target")) {
            captureTarget(context, arguments.getAsJsonObject("target"), data);
        }
        return context.result(data);
    }

    static JsonObject container(EntityPlayer player) {
        JsonArray slots = new JsonArray();
        for (int index = 0; index < player.openContainer.inventorySlots.size(); index++) {
            if (ContainerSlots.isVanillaSlot(player.openContainer, index)) {
                Slot slot = player.openContainer.inventorySlots.get(index);
                slots.add(Json.object("slotId", index, "item", item(slot.getStack())));
            }
        }
        return Json.object("windowId", player.openContainer.windowId,
                "class", player.openContainer.getClass().getName(), "slotScope", "vanilla", "slots", slots,
                "cursor", item(player.inventory.getItemStack()), "selectedHotbar", player.inventory.currentItem);
    }

    static JsonObject item(ItemStack stack) {
        JsonObject data = ItemData.describe(stack);
        // 非空物品必须比较 NBT；空栈的底层计数可能不同，但都表示没有物品。
        if (!stack.isEmpty()) {
            data.addProperty("snbt", stack.serializeNBT().toString());
        }
        return data;
    }

    private static void captureTarget(QueryContext context, JsonObject target, JsonObject data) {
        String kind = QueryContext.string(target, "kind", "");
        if ("block".equals(kind)) {
            World world = context.world(target);
            BlockPos position = WorldReader.blockPosition(target.getAsJsonObject("position"));
            WorldReader.requireChunk(world, position);
            data.add("block", block(world, position));
            JsonArray adjacent = new JsonArray();
            for (BlockPos neighbor : new BlockPos[] {position.up(), position.down(), position.north(),
                    position.south(), position.east(), position.west()}) {
                if (world.isBlockLoaded(neighbor, false)) {
                    adjacent.add(block(world, neighbor));
                }
            }
            data.add("adjacent", adjacent);
        } else if ("entity".equals(kind)) {
            try {
                Entity entity = TargetResolver.entity(context, target);
                data.add("entity", Json.object("uuid", entity.getUniqueID().toString(),
                        "position", WorldReader.vector(entity.getPositionVector()), "dead", entity.isDead,
                        "health", entity instanceof EntityLivingBase ? ((EntityLivingBase) entity).getHealth() : null));
            } catch (DomainException exception) {
                if (!"target_missing".equals(exception.getCode())) {
                    throw exception;
                }
                data.add("entity", Json.object("missing", true));
            }
        }
    }

    private static JsonObject block(World world, BlockPos position) {
        return Json.object("position", Json.object("x", position.getX(), "y", position.getY(), "z", position.getZ()),
                "state", world.getBlockState(position).toString());
    }
}
