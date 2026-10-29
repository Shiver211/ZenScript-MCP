package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.data.ItemData;
import com.shiver.zenscriptmcp.model.Json;
import net.minecraft.client.entity.EntityOtherPlayerMP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.EntityEquipmentSlot;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.AxisAlignedBB;

public final class EntityReader {

    private EntityReader() {
    }

    public static JsonObject describe(QueryContext context, Entity entity, JsonObject arguments) {
        context.checkFields(arguments, "registryName", "name", "position", "rotation", "motion", "bounds",
                "state", "health", "equipment", "effects", "riding", "passengers", "food", "experience", "mode");
        JsonObject data = Json.object("entityRef", reference(context, entity), "entityId", entity.getEntityId());
        context.field(data, arguments, "registryName", () -> {
            ResourceLocation id = EntityList.getKey(entity);
            return id == null ? null : id.toString();
        });
        context.field(data, arguments, "name", entity::getName);
        context.field(data, arguments, "position", () -> position(entity.posX, entity.posY, entity.posZ));
        context.field(data, arguments, "rotation",
                () -> Json.object("yaw", entity.rotationYaw, "pitch", entity.rotationPitch));
        context.field(data, arguments, "motion", () -> position(entity.motionX, entity.motionY, entity.motionZ));
        context.field(data, arguments, "bounds", () -> bounds(entity.getEntityBoundingBox()));
        context.field(data, arguments, "state", () -> Json.object(
                "alive", entity.isEntityAlive(), "onGround", entity.onGround, "inWater", entity.isInWater(),
                "burning", entity.isBurning(), "sneaking", entity.isSneaking(), "sprinting", entity.isSprinting()));
        context.field(data, arguments, "riding", () -> entity.getRidingEntity() == null
                ? null : reference(context, entity.getRidingEntity()));
        context.field(data, arguments, "passengers", () -> passengers(context, entity));
        if (entity instanceof EntityLivingBase) {
            EntityLivingBase living = (EntityLivingBase) entity;
            context.field(data, arguments, "health", () -> Json.object(
                    "current", living.getHealth(), "maximum", living.getMaxHealth(),
                    "absorption", living.getAbsorptionAmount(), "armor", living.getTotalArmorValue()));
            context.field(data, arguments, "equipment", () -> equipment(living));
            context.field(data, arguments, "effects", () -> effects(living));
        }
        if (entity instanceof EntityPlayer && !(entity instanceof EntityOtherPlayerMP)) {
            EntityPlayer player = (EntityPlayer) entity;
            context.field(data, arguments, "food", () -> Json.object(
                    "level", player.getFoodStats().getFoodLevel(),
                    "saturation", player.getFoodStats().getSaturationLevel()));
            context.field(data, arguments, "experience", () -> Json.object(
                    "level", player.experienceLevel, "progress", player.experience, "total", player.experienceTotal));
            context.field(data, arguments, "mode", () -> mode(player));
        }
        if (entity instanceof EntityOtherPlayerMP) {
            for (String field : new String[] {"food", "experience", "mode"}) {
                if (QueryContext.wants(arguments, field)) {
                    context.missing(field + ":not_synced");
                }
            }
        }
        if (arguments.has("fields")) {
            for (JsonElement field : arguments.getAsJsonArray("fields")) {
                if (!data.has(field.getAsString())) {
                    context.missing(field.getAsString() + ":not_applicable");
                }
            }
        }
        return data;
    }

    public static JsonObject reference(QueryContext context, Entity entity) {
        return Json.object("kind", "entity", "instanceId", context.instanceId(), "worldEpoch", context.epoch(),
                "dimension", entity.dimension, "uuid", entity.getUniqueID().toString());
    }

    public static JsonObject position(double x, double y, double z) {
        return Json.object("x", x, "y", y, "z", z);
    }

    public static JsonObject bounds(AxisAlignedBB box) {
        return Json.object("min", position(box.minX, box.minY, box.minZ),
                "max", position(box.maxX, box.maxY, box.maxZ));
    }

    private static JsonObject mode(EntityPlayer player) {
        JsonObject result = Json.object("creative", player.isCreative(), "spectator", player.isSpectator(),
                "allowFlying", player.capabilities.allowFlying, "flying", player.capabilities.isFlying,
                "invulnerable", player.capabilities.disableDamage);
        if (player instanceof EntityPlayerMP) {
            result.addProperty("gameType", ((EntityPlayerMP) player).interactionManager.getGameType().getName());
        }
        return result;
    }

    private static JsonArray passengers(QueryContext context, Entity entity) {
        JsonArray result = new JsonArray();
        for (Entity passenger : entity.getPassengers()) {
            result.add(reference(context, passenger));
        }
        return result;
    }

    private static JsonArray equipment(EntityLivingBase entity) {
        JsonArray result = new JsonArray();
        for (EntityEquipmentSlot slot : EntityEquipmentSlot.values()) {
            result.add(Json.object("slot", slot.getName(),
                    "item", ItemData.describe(entity.getItemStackFromSlot(slot))));
        }
        return result;
    }

    private static JsonArray effects(EntityLivingBase entity) {
        JsonArray result = new JsonArray();
        for (PotionEffect effect : entity.getActivePotionEffects()) {
            ResourceLocation id = effect.getPotion().getRegistryName();
            result.add(Json.object("registryName", id == null ? null : id.toString(),
                    "amplifier", effect.getAmplifier(), "durationTicks", effect.getDuration(),
                    "ambient", effect.getIsAmbient(), "showParticles", effect.doesShowParticles()));
        }
        return result;
    }
}
