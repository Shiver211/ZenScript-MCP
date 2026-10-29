package com.shiver.zenscriptmcp.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.Json;
import java.util.Arrays;
import java.util.List;
import net.minecraft.inventory.IInventory;
import net.minecraft.inventory.ISidedInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.util.EnumFacing;
import net.minecraftforge.common.capabilities.ICapabilityProvider;
import net.minecraftforge.energy.CapabilityEnergy;
import net.minecraftforge.energy.IEnergyStorage;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fluids.capability.CapabilityFluidHandler;
import net.minecraftforge.fluids.capability.IFluidHandler;
import net.minecraftforge.fluids.capability.IFluidTankProperties;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;

public final class CapabilityReader {

    private static final List<String> STANDARD_CAPABILITIES = Arrays.asList("items", "fluids", "energy");

    private CapabilityReader() {
    }

    public static JsonObject read(QueryContext context, ICapabilityProvider provider, JsonObject arguments) {
        String faceName = QueryContext.string(arguments, "face", "internal");
        EnumFacing face = "internal".equals(faceName) ? null : EnumFacing.byName(faceName);
        JsonObject result = Json.object("face", faceName);
        JsonObject values = new JsonObject();
        JsonArray supported = new JsonArray();
        JsonArray unsupported = new JsonArray();
        JsonArray requested = arguments.has("capabilities") ? arguments.getAsJsonArray("capabilities")
                : Json.GSON.toJsonTree(STANDARD_CAPABILITIES).getAsJsonArray();
        for (JsonElement entry : requested) {
            String capability = entry.getAsString();
            context.field(values, Json.object(), capability,
                    () -> readOne(context, provider, face, capability, arguments));
            if (values.has(capability) && !values.get(capability).isJsonNull()) {
                supported.add(capability);
            } else {
                unsupported.add(capability);
                context.missing(capability + ":unsupported_or_unavailable");
            }
        }
        result.add("supported", supported);
        result.add("unavailable", unsupported);
        result.add("values", values);
        return result;
    }

    private static JsonObject readOne(QueryContext context, ICapabilityProvider provider,
            EnumFacing face, String name, JsonObject arguments) {
        switch (name) {
            case "items":
                IItemHandler items = provider.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, face);
                if (items != null) {
                    return items(context, items, arguments);
                }
                return provider instanceof IInventory
                        ? inventory(context, (IInventory) provider, face, arguments) : null;
            case "fluids":
                IFluidHandler fluids = provider.getCapability(CapabilityFluidHandler.FLUID_HANDLER_CAPABILITY, face);
                if (fluids == null && provider instanceof ItemStack) {
                    fluids = provider.getCapability(CapabilityFluidHandler.FLUID_HANDLER_ITEM_CAPABILITY, face);
                }
                return fluids == null ? null : fluids(context, fluids, arguments);
            case "energy":
                IEnergyStorage energy = provider.getCapability(CapabilityEnergy.ENERGY, face);
                return energy == null ? null : Json.object("unit", "FE", "stored", energy.getEnergyStored(),
                        "capacity", energy.getMaxEnergyStored(), "canReceive", energy.canReceive(),
                        "canExtract", energy.canExtract());
            default:
                return null;
        }
    }

    private static JsonObject items(QueryContext context, IItemHandler handler, JsonObject arguments) {
        JsonArray slots = new JsonArray();
        int total = handler.getSlots();
        int offset = offset(arguments);
        int end = Math.min(total, offset + limit(context, arguments));
        for (int index = offset; index < end; index++) {
            final int slot = index;
            JsonObject data = Json.object("slot", index);
            context.field(data, Json.object(), "item", () -> ItemData.describe(handler.getStackInSlot(slot)));
            context.field(data, Json.object(), "limit", () -> handler.getSlotLimit(slot));
            slots.add(data);
        }
        JsonObject result = pagination(offset, slots.size(), total);
        result.addProperty("interface", "IItemHandler");
        result.add("slots", slots);
        return result;
    }

    private static JsonObject inventory(QueryContext context, IInventory inventory,
            EnumFacing face, JsonObject arguments) {
        int[] indices = null;
        if (face != null && inventory instanceof ISidedInventory) {
            indices = ((ISidedInventory) inventory).getSlotsForFace(face);
        }
        int total = indices == null ? inventory.getSizeInventory() : indices.length;
        JsonArray slots = new JsonArray();
        int offset = offset(arguments);
        int end = Math.min(total, offset + limit(context, arguments));
        for (int index = offset; index < end; index++) {
            int slot = indices == null ? index : indices[index];
            JsonObject data = Json.object("slot", slot);
            context.field(data, Json.object(), "item", () -> ItemData.describe(inventory.getStackInSlot(slot)));
            slots.add(data);
        }
        JsonObject result = pagination(offset, slots.size(), total);
        result.addProperty("interface", "IInventory");
        result.addProperty("limit", inventory.getInventoryStackLimit());
        result.add("slots", slots);
        return result;
    }

    private static JsonObject fluids(QueryContext context, IFluidHandler handler, JsonObject arguments) {
        IFluidTankProperties[] properties = handler.getTankProperties();
        JsonArray tanks = new JsonArray();
        int offset = offset(arguments);
        int end = Math.min(properties.length, offset + limit(context, arguments));
        for (int index = offset; index < end; index++) {
            IFluidTankProperties property = properties[index];
            FluidStack fluid = property.getContents();
            tanks.add(Json.object("tank", index, "unit", "mB", "capacity", property.getCapacity(),
                    "canFill", property.canFill(), "canDrain", property.canDrain(),
                    "fluid", fluid == null ? null : Json.object(
                            "registryName", fluid.getFluid().getName(),
                            "amount", fluid.amount, "hasNbt", fluid.tag != null)));
        }
        JsonObject result = pagination(offset, tanks.size(), properties.length);
        result.add("tanks", tanks);
        return result;
    }

    private static int offset(JsonObject arguments) {
        return arguments.has("offset") ? arguments.get("offset").getAsInt() : 0;
    }

    private static int limit(QueryContext context, JsonObject arguments) {
        return arguments.has("limit") ? arguments.get("limit").getAsInt() : context.settings().defaultLimit;
    }

    private static JsonObject pagination(int offset, int count, int total) {
        return Json.object("offset", offset, "total", total, "truncated", offset + count < total,
                "nextOffset", offset + count < total ? offset + count : null);
    }
}
