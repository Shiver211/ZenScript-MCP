package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.PageStore;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.block.Block;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.potion.Potion;
import net.minecraft.world.DimensionType;
import net.minecraft.world.biome.Biome;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;
import net.minecraftforge.fml.common.registry.EntityEntry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/** 从本实例注册表发现内容；按键 ID 绑定对象身份，改键只改变目录版本。 */
public final class CatalogReader {

    private final Map<KeyBinding, String> bindingIds = new IdentityHashMap<>();
    private final SnapshotStore snapshots;
    private String bindingSignature = "";
    private long bindingSequence;
    private long bindingVersion;

    public CatalogReader(SnapshotStore snapshots) {
        this.snapshots = snapshots;
    }

    public JobManager.Task task(QueryContext context, JsonObject arguments, PageStore pages) {
        String kind = arguments.get("kind").getAsString();
        List<Object> entries = new ArrayList<>();
        switch (kind) {
            case "mods":
                entries.addAll(Loader.instance().getActiveModList());
                break;
            case "items":
                ForgeRegistries.ITEMS.forEach(entries::add);
                break;
            case "blocks":
                ForgeRegistries.BLOCKS.forEach(entries::add);
                break;
            case "entities":
                ForgeRegistries.ENTITIES.forEach(entries::add);
                break;
            case "effects":
                ForgeRegistries.POTIONS.forEach(entries::add);
                break;
            case "biomes":
                ForgeRegistries.BIOMES.forEach(entries::add);
                break;
            case "dimensions":
                entries.addAll(Arrays.asList(DimensionManager.getStaticDimensionIDs()));
                break;
            case "keybindings":
                entries.addAll(Arrays.asList(Minecraft.getMinecraft().gameSettings.keyBindings));
                updateBindings(entries);
                break;
            default:
                throw new IllegalArgumentException("未知目录类型");
        }
        JsonObject metadata = Json.object("kind", kind, "entryKind", "registered");
        if ("keybindings".equals(kind)) {
            metadata.addProperty("catalogVersion", bindingVersion);
        }
        return new ListQueryTask<>("mc_catalog", context, arguments, entries.iterator(), entry -> {
            JsonObject readFields = arguments.has("query") ? Json.object() : arguments;
            JsonObject data = describe(context, entry, readFields);
            return matches(data, arguments) ? project(context, data, arguments) : null;
        }, pages, false, metadata);
    }

    private JsonObject describe(QueryContext context, Object entry, JsonObject arguments) {
        JsonObject result = Json.object("class", entry.getClass().getName());
        if (entry instanceof ModContainer) {
            ModContainer mod = (ModContainer) entry;
            result.addProperty("id", mod.getModId());
            result.addProperty("name", mod.getName());
            result.addProperty("version", mod.getVersion());
        } else if (entry instanceof Item) {
            Item item = (Item) entry;
            result.addProperty("registryName", String.valueOf(item.getRegistryName()));
            context.field(result, arguments, "name", () -> new ItemStack(item).getDisplayName());
        } else if (entry instanceof Block) {
            Block block = (Block) entry;
            result.addProperty("registryName", String.valueOf(block.getRegistryName()));
            context.field(result, arguments, "name", block::getLocalizedName);
        } else if (entry instanceof EntityEntry) {
            EntityEntry entity = (EntityEntry) entry;
            result.addProperty("registryName", String.valueOf(entity.getRegistryName()));
            result.addProperty("name", entity.getName());
            result.addProperty("entityClass", entity.getEntityClass().getName());
        } else if (entry instanceof Potion) {
            Potion effect = (Potion) entry;
            result.addProperty("registryName", String.valueOf(effect.getRegistryName()));
            result.addProperty("translationKey", effect.getName());
        } else if (entry instanceof Biome) {
            Biome biome = (Biome) entry;
            result.addProperty("registryName", String.valueOf(biome.getRegistryName()));
            result.addProperty("name", biome.getBiomeName());
        } else if (entry instanceof Integer) {
            int dimension = (Integer) entry;
            DimensionType type = DimensionManager.getProviderType(dimension);
            result.addProperty("dimension", dimension);
            result.addProperty("name", type.getName());
            result.addProperty("providerType", type.getId());
            JsonObject server = snapshots.status().getAsJsonObject("server");
            boolean known = server.has("loadedDimensions");
            result.addProperty("loadedKnown", known);
            if (known) {
                boolean loaded = false;
                for (JsonElement value : server.getAsJsonArray("loadedDimensions")) {
                    loaded |= value.getAsInt() == dimension;
                }
                result.addProperty("loaded", loaded);
                result.add("loadedObservedAt", server.get("capturedAt"));
            } else if (QueryContext.wants(arguments, "loaded")) {
                context.missing("loaded_dimensions:server_unavailable");
            }
        } else if (entry instanceof KeyBinding) {
            KeyBinding binding = (KeyBinding) entry;
            result.addProperty("id", bindingIds.get(binding));
            result.addProperty("name", binding.getKeyDescription());
            result.addProperty("category", binding.getKeyCategory());
            result.addProperty("keyCode", binding.getKeyCode());
            result.addProperty("defaultKeyCode", binding.getKeyCodeDefault());
            result.addProperty("modifier", binding.getKeyModifier().name());
            result.addProperty("context", binding.getKeyConflictContext().toString());
            context.field(result, arguments, "active", () -> binding.getKeyConflictContext().isActive());
            context.field(result, arguments, "displayName", binding::getDisplayName);
        }
        return result;
    }

    private void updateBindings(List<Object> entries) {
        StringBuilder signature = new StringBuilder();
        for (Object entry : entries) {
            KeyBinding binding = (KeyBinding) entry;
            if (!bindingIds.containsKey(binding)) {
                bindingIds.put(binding, "binding:" + ++bindingSequence);
            }
            signature.append(bindingIds.get(binding)).append(':').append(binding.getKeyCode())
                    .append(':').append(binding.getKeyModifier()).append(':').append(binding.getKeyConflictContext())
                    .append(':').append(binding.getKeyDescription())
                    .append(':').append(binding.getKeyCategory()).append(';');
        }
        if (!bindingSignature.equals(signature.toString())) {
            bindingSignature = signature.toString();
            bindingVersion++;
        }
    }

    public static boolean matches(JsonObject data, JsonObject arguments) {
        String namespace = QueryContext.string(arguments, "namespace", "");
        String query = QueryContext.string(arguments, "query", "").toLowerCase(Locale.ROOT);
        String id = QueryContext.string(data, "registryName", QueryContext.string(data, "id", ""));
        return (namespace.isEmpty() || id.startsWith(namespace + ":") || id.equals(namespace))
                && (query.isEmpty() || Json.GSON.toJson(data).toLowerCase(Locale.ROOT).contains(query));
    }

    public static JsonObject project(QueryContext context, JsonObject data, JsonObject arguments) {
        if (!arguments.has("fields")) {
            return data;
        }
        JsonObject projected = Json.object();
        for (String identity : Arrays.asList("registryName", "id", "dimension")) {
            if (data.has(identity)) {
                projected.add(identity, data.get(identity));
            }
        }
        arguments.getAsJsonArray("fields").forEach(field -> {
            String name = field.getAsString();
            if (data.has(name)) {
                projected.add(name, data.get(name));
            } else {
                context.missing(name);
            }
        });
        return projected;
    }
}
