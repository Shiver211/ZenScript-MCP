package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import java.util.UUID;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.common.capabilities.ICapabilityProvider;

public final class TargetResolver {

    private TargetResolver() {
    }

    public static Entity entity(QueryContext context, JsonObject target) {
        checkEpoch(context, target);
        World world = context.world(target);
        UUID uuid = QueryContext.uuid(target.get("uuid").getAsString());
        for (Entity entity : world.loadedEntityList) {
            if (entity.getUniqueID().equals(uuid) && !entity.isDead) {
                return entity;
            }
        }
        throw new DomainException("target_missing", "实体不存在或不在已加载实体中");
    }

    public static ICapabilityProvider provider(QueryContext context, JsonObject target, ContainerTracker containers) {
        checkEpoch(context, target);
        String kind = target.get("kind").getAsString();
        switch (kind) {
            case "block":
                World world = context.world(target);
                BlockPos position = WorldReader.blockPosition(target.getAsJsonObject("position"));
                TileEntity tile = WorldReader.requireChunk(world, position).getTileEntityMap().get(position);
                if (tile == null) {
                    throw new DomainException("unsupported_capability", "该方块没有已存在的 TileEntity 数据提供者");
                }
                return tile;
            case "entity":
                return entity(context, target);
            case "player":
                return context.player(target);
            case "item":
                ItemStack stack = containers.item(context, context.player(Json.object()), target);
                if (stack.isEmpty()) {
                    throw new DomainException("target_missing", "槽位为空");
                }
                return stack;
            default:
                throw new DomainException("invalid_target", "未知目标类型");
        }
    }

    public static NBTTagCompound nbt(ICapabilityProvider provider) {
        if (provider instanceof TileEntity) {
            return ((TileEntity) provider).writeToNBT(new NBTTagCompound());
        }
        if (provider instanceof Entity) {
            return ((Entity) provider).writeToNBT(new NBTTagCompound());
        }
        if (provider instanceof ItemStack) {
            return ((ItemStack) provider).writeToNBT(new NBTTagCompound());
        }
        throw new DomainException("unsupported_capability", "目标没有通用 NBT 读取接口");
    }

    public static void checkEpoch(QueryContext context, JsonObject target) {
        if (target.has("instanceId") && !context.instanceId().equals(target.get("instanceId").getAsString())) {
            throw new DomainException("stale_reference", "目标引用来自其他游戏实例");
        }
        if (target.has("worldEpoch") && target.get("worldEpoch").getAsLong() != context.epoch()) {
            throw new DomainException("stale_reference", "目标引用来自旧世界");
        }
    }
}
