package com.shiver.zenscriptmcp.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.protocol.ToolRegistry;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.PageStore;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiScreen;
import net.minecraftforge.common.capabilities.ICapabilityProvider;

/** 工厂只允许项目显式登记；依赖检查失败时绝不求值工厂。启动后注册表只读。 */
public final class IntegrationRegistry implements AutoCloseable {

    private final List<GameIntegration> loaded = new ArrayList<>();
    private final Map<String, IntegrationContext> contexts = new LinkedHashMap<>();
    private final JsonObject status = new JsonObject();

    public void load(String id, String version, boolean enabled, boolean supported,
            Supplier<GameIntegration> factory, ToolRegistry tools) {
        if (status.has(id)) {
            throw new IllegalArgumentException("集成重复登记：" + id);
        }
        if (!enabled || version == null || !supported) {
            String state = !enabled ? "disabled" : "dependency_missing";
            if (enabled && version != null) {
                state = "unsupported_version";
            }
            status.add(id, Json.object("state", state, "version", version));
            return;
        }
        GameIntegration integration = null;
        try {
            integration = factory.get();
            IntegrationContext context = new IntegrationContext(id);
            integration.register(context);
            context.install(tools);
            contexts.put(id, context);
            loaded.add(integration);
            status.add(id, Json.object("state", "available", "version", version));
        } catch (RuntimeException | LinkageError exception) {
            if (integration != null) {
                integration.close();
            }
            status.add(id, Json.object("state", "load_failed", "version", version,
                    "reason", exception.toString()));
        }
    }

    public JsonObject status() {
        return Json.copy(status);
    }

    public JsonArray recipeSystems() {
        JsonArray systems = new JsonArray();
        for (IntegrationContext context : contexts.values()) {
            for (String name : context.recipes().keySet()) {
                systems.add(Json.object("id", context.id() + ":" + name,
                        "integration", context.id(), "coverage", "adapter_defined"));
            }
        }
        return systems;
    }

    public JobManager.Task recipes(QueryContext query, JsonObject arguments, PageStore pages) {
        String system = arguments.get("system").getAsString();
        for (IntegrationContext context : contexts.values()) {
            for (Map.Entry<String, IntegrationContext.RecipeProvider> entry : context.recipes().entrySet()) {
                if (system.equals(context.id() + ":" + entry.getKey())) {
                    return entry.getValue().query(query, arguments, pages);
                }
            }
        }
        throw new DomainException("unsupported_capability", "未登记配方系统：" + system);
    }

    public JsonObject readTarget(QueryContext query, ICapabilityProvider target, JsonObject arguments) {
        JsonObject result = new JsonObject();
        for (IntegrationContext context : contexts.values()) {
            for (Map.Entry<String, IntegrationContext.TargetReader> entry : context.targets().entrySet()) {
                append(query, result, context.id() + ":" + entry.getKey(),
                        () -> entry.getValue().read(query, target, arguments));
            }
        }
        return result;
    }

    public JsonObject readScreen(QueryContext query, GuiScreen screen, JsonObject arguments) {
        JsonObject result = new JsonObject();
        for (IntegrationContext context : contexts.values()) {
            for (Map.Entry<String, IntegrationContext.ScreenReader> entry : context.screens().entrySet()) {
                append(query, result, context.id() + ":" + entry.getKey(),
                        () -> entry.getValue().read(query, screen, arguments));
            }
        }
        return result;
    }

    private void append(QueryContext query, JsonObject result, String id, Supplier<JsonObject> reader) {
        try {
            JsonObject value = reader.get();
            if (value != null) {
                if (PageStore.byteSize(Json.GSON.toJson(value)) > query.settings().maxPageBytes) {
                    query.missing("extensions:" + id + ":result_too_large");
                } else {
                    result.add(id, Json.copy(value));
                }
            }
        } catch (RuntimeException | LinkageError exception) {
            query.failure("extensions:" + id, new IllegalStateException(exception));
        }
    }

    @Override
    public void close() {
        for (GameIntegration integration : loaded) {
            integration.close();
        }
        loaded.clear();
    }
}
