package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
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
import net.minecraft.item.crafting.ShapelessRecipes;
import net.minecraftforge.common.crafting.IShapedRecipe;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.oredict.ShapelessOreRecipe;

/** 只描述注册信息，不调用 matches/getCraftingResult 或消耗材料。 */
public final class RecipeReader {

    private static final int MAX_INGREDIENTS = 256;
    private static final int MAX_ALTERNATIVES = 64;
    private static final Map<ItemStack, String> SMELTING_IDS = new WeakHashMap<>();

    private RecipeReader() {
    }

    public static JobManager.Task task(QueryContext context, JsonObject arguments, PageStore pages,
            JsonArray extensions) {
        String system = system(arguments);
        List<JsonObject> systems = new ArrayList<>();
        if ("systems".equals(system)) {
            systems.add(Json.object("id", "minecraft:crafting", "name", "工作台", "coverage", "registered_declarations"));
            systems.add(Json.object("id", "minecraft:smelting", "name", "熔炉", "coverage", "registered_declarations"));
            for (JsonElement extension : extensions) {
                systems.add(extension.getAsJsonObject());
            }
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
            // 先按轻量输出信息筛选，避免无关配方的原料展开拖慢查询并污染覆盖结论。
            JsonObject candidate = candidate(entry);
            String output = QueryContext.string(arguments, "output", "");
            String recipeId = QueryContext.string(arguments, "recipeId", "");
            boolean outputMatches = output.isEmpty() || (candidate.getAsJsonObject("output").has("registryName")
                    && output.equals(candidate.getAsJsonObject("output").get("registryName").getAsString()));
            if (!outputMatches || (!recipeId.isEmpty() && !recipeId.equals(candidate.get("id").getAsString()))
                    || !CatalogReader.matches(candidate, arguments)) {
                return null;
            }
            JsonObject data = entry instanceof IRecipe ? crafting(context, (IRecipe) entry, arguments)
                    : smelting(context, entry, arguments);
            data.addProperty("system", system);
            return CatalogReader.project(context, data, arguments);
        }, pages, false, Json.object("system", system, "coverage", "registered_declarations"));
    }

    public static String system(JsonObject arguments) {
        boolean searching = arguments.has("query") || arguments.has("output") || arguments.has("recipeId");
        return QueryContext.string(arguments, "system", searching ? "minecraft:crafting" : "systems");
    }

    @SuppressWarnings("unchecked")
    private static JsonObject candidate(Object entry) {
        if (entry instanceof IRecipe) {
            IRecipe recipe = (IRecipe) entry;
            return Json.object("id", String.valueOf(recipe.getRegistryName()),
                    "output", ItemData.describe(recipe.getRecipeOutput()));
        }
        Map.Entry<ItemStack, ItemStack> recipe = (Map.Entry<ItemStack, ItemStack>) entry;
        return Json.object("id", smeltingId(recipe.getKey()), "output", ItemData.describe(recipe.getValue()));
    }

    private static JsonObject crafting(QueryContext context, IRecipe recipe, JsonObject arguments) {
        JsonObject data = Json.object("id", String.valueOf(recipe.getRegistryName()),
                "class", recipe.getClass().getName());
        context.field(data, arguments, "dynamic", recipe::isDynamic);
        context.field(data, arguments, "group", recipe::getGroup);
        context.field(data, arguments, "output", () -> ItemData.describe(recipe.getRecipeOutput()));
        context.field(data, arguments, "layout", () -> layout(recipe));
        context.field(data, arguments, "ingredients", () -> ingredients(context, recipe));
        if (data.has("dynamic") && data.get("dynamic").getAsBoolean()) {
            data.addProperty("runtimeResultKnown", false);
            context.missing("dynamic_recipe_result");
        }
        return data;
    }

    private static JsonObject layout(IRecipe recipe) {
        if (recipe instanceof IShapedRecipe) {
            IShapedRecipe shaped = (IShapedRecipe) recipe;
            return Json.object("type", "shaped", "width", shaped.getRecipeWidth(),
                    "height", shaped.getRecipeHeight(), "coordinateBase", 0);
        }
        boolean shapeless = recipe instanceof ShapelessRecipes || recipe instanceof ShapelessOreRecipe;
        return Json.object("type", shapeless ? "shapeless" : "unknown");
    }

    private static JsonArray ingredients(QueryContext context, IRecipe recipe) {
        JsonArray result = new JsonArray();
        List<Ingredient> ingredients = recipe.getIngredients();
        int count = Math.min(ingredients.size(), MAX_INGREDIENTS);
        int width = recipe instanceof IShapedRecipe ? ((IShapedRecipe) recipe).getRecipeWidth() : 0;
        for (int index = 0; index < count; index++) {
            ItemStack[] matches = ingredients.get(index).getMatchingStacks();
            JsonArray alternatives = new JsonArray();
            int maximum = Math.min(matches.length, MAX_ALTERNATIVES);
            for (int candidate = 0; candidate < maximum; candidate++) {
                alternatives.add(ItemData.describe(matches[candidate]));
            }
            JsonObject ingredient = Json.object("index", index, "empty", ingredients.get(index) == Ingredient.EMPTY,
                    "alternatives", alternatives, "total", matches.length, "truncated", maximum < matches.length);
            if (width > 0) {
                ingredient.addProperty("row", index / width);
                ingredient.addProperty("col", index % width);
            }
            result.add(ingredient);
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
