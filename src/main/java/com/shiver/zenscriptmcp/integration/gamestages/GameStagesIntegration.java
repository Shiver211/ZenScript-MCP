package com.shiver.zenscriptmcp.integration.gamestages;

import com.google.gson.JsonArray;
import com.shiver.zenscriptmcp.integration.GameIntegration;
import com.shiver.zenscriptmcp.integration.IntegrationContext;
import com.shiver.zenscriptmcp.model.Json;
import java.lang.reflect.Method;
import java.util.Collection;
import net.minecraft.entity.player.EntityPlayer;

/** 只读取已解锁阶段；阶段授予、同步和事件仍由原模组负责。 */
public final class GameStagesIntegration implements GameIntegration {

    private final Method getPlayerData;
    private final Method getStages;

    public GameStagesIntegration() {
        try {
            Class<?> helper = Class.forName("net.darkhax.gamestages.GameStageHelper");
            Class<?> data = Class.forName("net.darkhax.gamestages.data.IStageData");
            getPlayerData = helper.getMethod("getPlayerData", EntityPlayer.class);
            getStages = data.getMethod("getStages");
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("GameStages API 与固定版本不符", exception);
        }
    }

    @Override
    public void register(IntegrationContext context) {
        context.registerTargetReader("stages", (query, target, arguments) -> {
            if (!(target instanceof EntityPlayer)) {
                return null;
            }
            try {
                Object data = getPlayerData.invoke(null, target);
                Collection<?> values = (Collection<?>) getStages.invoke(data);
                JsonArray stages = new JsonArray();
                for (Object value : values) {
                    if (stages.size() >= query.settings().maxLimit) {
                        query.missing("gamestages:stages:truncated");
                        break;
                    }
                    stages.add((String) value);
                }
                return Json.object("stages", stages, "total", values.size(),
                        "truncated", stages.size() < values.size());
            } catch (ReflectiveOperationException exception) {
                throw new IllegalStateException("读取 GameStages 阶段失败", exception);
            }
        });
    }
}
