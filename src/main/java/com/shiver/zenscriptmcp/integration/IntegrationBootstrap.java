package com.shiver.zenscriptmcp.integration;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.integration.crafttweaker.CraftTweakerIntegration;
import com.shiver.zenscriptmcp.integration.gamestages.GameStagesIntegration;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.protocol.ToolRegistry;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import net.minecraftforge.fml.common.Loader;
import net.minecraftforge.fml.common.ModContainer;

/** 固定版本与工厂，不扫描 classpath；陌生版本保留核心功能并报告不支持。 */
public final class IntegrationBootstrap {

    private IntegrationBootstrap() {
    }

    public static void load(IntegrationRegistry registry, ToolRegistry tools, Path gameDirectory,
            SnapshotStore snapshots, JobManager jobs, McpConfig config, EventBuffer events) {
        String craftTweaker = version("crafttweaker");
        String zenUtils = version("zenutils");
        boolean supported = "1.12-4.1.20.698".equals(craftTweaker)
                && (zenUtils == null || "1.27.1".equals(zenUtils));
        registry.load("ct", craftTweaker, config.integrations.crafttweaker, supported,
                () -> new CraftTweakerIntegration(gameDirectory, snapshots, jobs, config, events, zenUtils), tools);
        String gameStages = version("gamestages");
        registry.load("gamestages", gameStages, config.integrations.gameStages, "2.0.123".equals(gameStages),
                GameStagesIntegration::new, tools);
    }

    private static String version(String id) {
        ModContainer mod = Loader.instance().getIndexedModList().get(id);
        if (mod == null) {
            return null;
        }
        // CT 的 @Mod 只报告 4.1.20，完整构建号保存在 JAR 的 mcmod.info 中。
        if (mod.getSource() != null && mod.getSource().isFile()) {
            try (JarFile jar = new JarFile(mod.getSource())) {
                JarEntry entry = jar.getJarEntry("mcmod.info");
                if (entry != null) {
                    try (Reader reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                        JsonArray metadata = Json.GSON.fromJson(reader, JsonArray.class);
                        for (JsonElement value : metadata) {
                            JsonObject definition = value.getAsJsonObject();
                            if (id.equals(definition.get("modid").getAsString()) && definition.has("version")) {
                                return definition.get("version").getAsString();
                            }
                        }
                    }
                }
            } catch (IOException | RuntimeException exception) {
                return mod.getVersion();
            }
        }
        return mod.getVersion();
    }
}
