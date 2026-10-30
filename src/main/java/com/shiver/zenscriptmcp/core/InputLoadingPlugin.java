package com.shiver.zenscriptmcp.core;

import java.util.Map;
import net.minecraftforge.fml.relauncher.IFMLLoadingPlugin;

/** 启动时注册轮询调用转换；不提前加载游戏或输入设备类。 */
@IFMLLoadingPlugin.MCVersion("1.12.2")
@IFMLLoadingPlugin.Name("ZenScript MCP Input")
@IFMLLoadingPlugin.TransformerExclusions("com.shiver.zenscriptmcp.core.")
public final class InputLoadingPlugin implements IFMLLoadingPlugin {

    @Override
    public String[] getASMTransformerClass() {
        return new String[] {"com.shiver.zenscriptmcp.core.InputPollingTransformer"};
    }

    @Override
    public String getModContainerClass() {
        return null;
    }

    @Override
    public String getSetupClass() {
        return null;
    }

    @Override
    public void injectData(Map<String, Object> data) {
    }

    @Override
    public String getAccessTransformerClass() {
        return null;
    }
}
