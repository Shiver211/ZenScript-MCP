package com.shiver.zenscriptmcp.integration;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.protocol.ToolRegistry;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.PageStore;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.capabilities.ICapabilityProvider;

/** 只在初始化时登记；读取回调在 QueryContext 所属线程执行，结果继承来源与预算。 */
public final class IntegrationContext {

    private final String id;
    private final Map<String, TargetReader> targets = new LinkedHashMap<>();
    private final Map<String, RecipeProvider> recipes = new LinkedHashMap<>();
    private final Map<String, ScreenReader> screens = new LinkedHashMap<>();
    private final Map<String, ToolRegistration> tools = new LinkedHashMap<>();
    private boolean frozen;

    public IntegrationContext(String id) {
        this.id = id;
    }

    public void registerTargetReader(String name, TargetReader reader) {
        put(targets, name, reader);
    }

    public void registerRecipeProvider(String name, RecipeProvider provider) {
        put(recipes, name, provider);
    }

    public void registerScreenReader(String name, ScreenReader reader) {
        put(screens, name, reader);
    }

    public void registerTool(String name, String description, JsonObject schema, boolean readOnly,
            Function<JsonObject, JsonObject> handler) {
        put(tools, name, new ToolRegistration(description, schema, readOnly, handler));
    }

    public String id() {
        return id;
    }

    Map<String, TargetReader> targets() {
        return targets;
    }

    Map<String, RecipeProvider> recipes() {
        return recipes;
    }

    Map<String, ScreenReader> screens() {
        return screens;
    }

    void install(ToolRegistry registry) {
        for (String name : tools.keySet()) {
            if (registry.contains(id + "_" + name)) {
                throw new IllegalArgumentException("集成工具重名：" + id + "_" + name);
            }
        }
        for (Map.Entry<String, ToolRegistration> entry : tools.entrySet()) {
            ToolRegistration tool = entry.getValue();
            registry.register(id + "_" + entry.getKey(), tool.description, tool.schema, tool.readOnly, tool.handler);
        }
        frozen = true;
    }

    private <T> void put(Map<String, T> entries, String name, T value) {
        if (frozen || !name.matches("[a-z][a-z0-9_]*") || entries.containsKey(name) || value == null) {
            throw new IllegalArgumentException("集成注册无效：" + id + ":" + name);
        }
        entries.put(name, value);
    }

    public interface TargetReader {

        /** 不适用当前目标时返回 null；不得修改目标。 */
        JsonObject read(QueryContext context, ICapabilityProvider target, JsonObject arguments);
    }

    public interface RecipeProvider {

        JobManager.Task query(QueryContext context, JsonObject arguments, PageStore pages);
    }

    public interface ScreenReader {

        /** 不适用当前界面时返回 null；只在客户端线程调用。 */
        JsonObject read(QueryContext context, GuiScreen screen, JsonObject arguments);
    }

    private static final class ToolRegistration {

        private final String description;
        private final JsonObject schema;
        private final boolean readOnly;
        private final Function<JsonObject, JsonObject> handler;

        private ToolRegistration(String description, JsonObject schema, boolean readOnly,
                Function<JsonObject, JsonObject> handler) {
            this.description = description;
            this.schema = schema;
            this.readOnly = readOnly;
            this.handler = handler;
        }
    }
}
