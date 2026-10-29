package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.data.ItemData;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.PageStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.FurnaceRecipes;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.item.crafting.Ingredient;
import net.minecraftforge.fml.common.registry.ForgeRegistries;

/** 只描述注册信息，不调用 matches/getCraftingResult 或消耗材料。 */
public final class RecipeReader {

    private static final int MAX_INGREDIENTS = 256;
    private static final int MAX_ALTERNATIVES = 64;
    private static final Map<ItemStack, String> SMELTING_IDS = new WeakHashMap<>();

    private RecipeReader() {
    }

    public static JobManager.Task task(QueryContext context, JsonObject arguments, PageStore pages) {
        String system = QueryContext.string(arguments, "system", "systems");
        List<JsonObject> systems = new ArrayList<>();
        if ("systems".equals(system)) {
            systems.add(Json.object("id", "minecraft:crafting", "name", "工作台", "coverage", "registered_declarations"));
            systems.add(Json.object("id", "minecraft:smelting", "name", "熔炉", "coverage", "registered_declarations"));
            return new ListQueryTask<>("mc_recipes", context, arguments, systems.iterator(), row -> row,
                    pages, false, Json.object("system", system, "privateSystemsDiscovered", false));
        }
        List<Object> entries = new ArrayList<>();
        if ("minecraft:crafting".equals(system)) {
            ForgeRegistries.RECIPES.forEach(entries::add);
        } else {
            FurnaceRecipes.instance().getSmeltingList().entrySet().forEach(entries::add);
        }
        return new ListQueryTask<>("mc_recipes", context, arguments, entries.iterator(), entry -> {
            JsonObject readFields = arguments.has("query") || arguments.has("output") ? Json.object() : arguments;
            JsonObject data = entry instanceof IRecipe ? crafting(context, (IRecipe) entry, readFields)
                    : smelting(context, entry, readFields);
            String output = QueryContext.string(arguments, "output", "");
            String recipeId = QueryContext.string(arguments, "recipeId", "");
            boolean outputMatches = output.isEmpty() || (data.has("output")
                    && data.getAsJsonObject("output").has("registryName")
                    && output.equals(data.getAsJsonObject("output").get("registryName").getAsString()));
            if (!outputMatches || (!recipeId.isEmpty() && !recipeId.equals(data.get("id").getAsString()))
                    || !CatalogReader.matches(data, arguments)) {
                return null;
            }
            return CatalogReader.project(context, data, arguments);
        }, pages, false, Json.object("system", system, "coverage", "registered_declarations"));
    }

    private static JsonObject crafting(QueryContext context, IRecipe recipe, JsonObject arguments) {
        JsonObject data = Json.object("id", String.valueOf(recipe.getRegistryName()),
                "class", recipe.getClass().getName());
        context.field(data, arguments, "dynamic", recipe::isDynamic);
        context.field(data, arguments, "group", recipe::getGroup);
        context.field(data, arguments, "output", () -> ItemData.describe(recipe.getRecipeOutput()));
        context.field(data, arguments, "ingredients", () -> ingredients(context, recipe));
        if (data.has("dynamic") && data.get("dynamic").getAsBoolean()) {
            data.addProperty("runtimeResultKnown", false);
            context.missing("dynamic_recipe_result");
        }
        return data;
    }

    private static JsonArray ingredients(QueryContext context, IRecipe recipe) {
        JsonArray result = new JsonArray();
        List<Ingredient> ingredients = recipe.getIngredients();
        int count = Math.min(ingredients.size(), MAX_INGREDIENTS);
        for (int index = 0; index < count; index++) {
            ItemStack[] matches = ingredients.get(index).getMatchingStacks();
            JsonArray alternatives = new JsonArray();
            int maximum = Math.min(matches.length, MAX_ALTERNATIVES);
            for (int candidate = 0; candidate < maximum; candidate++) {
                alternatives.add(ItemData.describe(matches[candidate]));
            }
            result.add(Json.object("alternatives", alternatives,
                    "total", matches.length, "truncated", maximum < matches.length));
            if (maximum < matches.length) {
                context.missing("ingredients:alternative_limit");
            }
        }
        if (count < ingredients.size()) {
            context.missing("ingredients:limit");
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static JsonObject smelting(QueryContext context, Object value, JsonObject arguments) {
        Map.Entry<ItemStack, ItemStack> entry = (Map.Entry<ItemStack, ItemStack>) value;
        JsonObject data = Json.object("id", smeltingId(entry.getKey()),
                "syntheticId", true, "idScope", "instance_entry");
        context.field(data, arguments, "input", () -> ItemData.describe(entry.getKey()));
        context.field(data, arguments, "output", () -> ItemData.describe(entry.getValue()));
        context.field(data, arguments, "experience",
                () -> FurnaceRecipes.instance().getSmeltingExperience(entry.getValue()));
        return data;
    }

    private static synchronized String smeltingId(ItemStack input) {
        String id = SMELTING_IDS.get(input);
        if (id == null) {
            id = "minecraft:smelting/" + UUID.randomUUID();
            SMELTING_IDS.put(input, id);
        }
        return id;
    }
}
