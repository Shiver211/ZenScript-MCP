package com.shiver.zenscriptmcp;

import com.shiver.zenscriptmcp.game.client.ClientRuntime;
import java.io.IOException;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPostInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStoppingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** 将 Forge 生命周期事件接入 MCP 运行时。 */
@Mod(
        modid = Tags.MOD_ID,
        name = Tags.MOD_NAME,
        version = Tags.VERSION,
        acceptableRemoteVersions = "*",
        clientSideOnly = true)
public final class ZenScriptMcpMod {

    public static final Logger LOGGER = LogManager.getLogger(Tags.MOD_NAME);

    private ClientRuntime runtime;

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        LOGGER.info("{} {}", Tags.MOD_NAME, Tags.VERSION);

        try {
            runtime = new ClientRuntime();
        } catch (IOException exception) {
            throw new IllegalStateException("MCP 配置读取失败", exception);
        }
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        try {
            runtime.start();
        } catch (IOException exception) {
            throw new IllegalStateException("MCP 端点启动失败；请检查配置和端口占用", exception);
        }
    }

    @Mod.EventHandler
    public void serverStopping(FMLServerStoppingEvent event) {
        runtime.serverStopping();
    }
}
