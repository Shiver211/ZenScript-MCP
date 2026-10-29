package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.game.query.WorldReader;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/** 一条有界时间线；视角先于输入更新，完成结果报告实际位移。 */
final class InputTask implements JobManager.Task {

    private final ActionService service;
    private final String tool;
    private final JsonObject request;
    private final JsonArray timeline;
    private final int duration;
    private final QueryContext context;
    private long startedTick = -1;
    private int nextStep;
    private GuiScreen initialScreen;
    private JsonObject before;
    private JsonObject result;
    private float initialYaw;
    private float initialPitch;
    private boolean usedItemInput;
    private boolean usedAttackInput;

    InputTask(ActionService service, String tool, JsonObject request) {
        this.service = service;
        this.tool = tool;
        this.request = request;
        context = service.context(GameExecutor.Side.CLIENT);
        duration = request.has("durationTicks") ? request.get("durationTicks").getAsInt() : 0;
        timeline = request.has("timeline") ? request.getAsJsonArray("timeline") : new JsonArray();
        int previousTick = -1;
        for (JsonElement element : timeline) {
            int tick = element.getAsJsonObject().get("atTick").getAsInt();
            if (tick < previousTick || tick > duration) {
                throw new IllegalArgumentException("时间线必须按 atTick 升序排列且不超过 durationTicks");
            }
            previousTick = tick;
        }
    }

    @Override
    public JsonObject step() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = ActionService.player();
        if (startedTick < 0) {
            if (minecraft.currentScreen != null) {
                throw new DomainException("action_rejected", "世界输入需要关闭当前 GUI");
            }
            startedTick = service.tick();
            initialScreen = minecraft.currentScreen;
            initialYaw = player.rotationYaw;
            initialPitch = player.rotationPitch;
            before = ActionObservation.capture(context, Json.object());
            if ("mc_move".equals(tool)) {
                service.inputs().axes(number(request, "forward", 0), number(request, "strafe", 0));
                for (String name : new String[] {"jump", "sprint", "sneak"}) {
                    if (request.has(name) && request.get(name).getAsBoolean()) {
                        service.inputs().press(name, true);
                    }
                }
            }
        }
        if (minecraft.currentScreen != initialScreen) {
            return finish("screen_changed");
        }
        service.inputs().validate();
        if (minecraft.isGamePaused()) {
            return null;
        }
        long elapsed = service.tick() - startedTick;
        if ("mc_look".equals(tool) || request.has("look")) {
            JsonObject look = "mc_look".equals(tool) ? request : request.getAsJsonObject("look");
            float amount = duration == 0 ? 1 : Math.min(1, (float) elapsed / duration);
            look(player, look, initialYaw, initialPitch, amount);
        }
        while (nextStep < timeline.size()
                && timeline.get(nextStep).getAsJsonObject().get("atTick").getAsLong() <= elapsed) {
            JsonObject step = timeline.get(nextStep++).getAsJsonObject();
            if (step.has("look")) {
                look(player, step.getAsJsonObject("look"), player.rotationYaw, player.rotationPitch, 1);
            }
            keys(step, "release", false);
            keys(step, "press", true);
        }
        result = evidence("running");
        return elapsed >= duration ? finish("duration_reached") : null;
    }

    static void look(EntityPlayerSP player, JsonObject look, float fromYaw, float fromPitch, float amount) {
        float yaw;
        float pitch;
        if (look.has("position")) {
            Vec3d delta = WorldReader.vector(look.getAsJsonObject("position")).subtract(player.getPositionEyes(1));
            yaw = (float) Math.toDegrees(Math.atan2(-delta.x, delta.z));
            pitch = (float) -Math.toDegrees(Math.atan2(delta.y, Math.sqrt(delta.x * delta.x + delta.z * delta.z)));
        } else {
            yaw = look.get("yaw").getAsFloat();
            pitch = look.get("pitch").getAsFloat();
        }
        player.rotationYaw = fromYaw + MathHelper.wrapDegrees(yaw - fromYaw) * amount;
        player.rotationPitch = MathHelper.clamp(fromPitch + (pitch - fromPitch) * amount, -90, 90);
        Minecraft.getMinecraft().entityRenderer.getMouseOver(1);
    }

    private void keys(JsonObject step, String field, boolean down) {
        if (!step.has(field)) {
            return;
        }
        for (JsonElement key : step.getAsJsonArray(field)) {
            if (down) {
                service.inputs().press(key.getAsString(), true);
                usedItemInput |= service.inputs().owns("use");
                usedAttackInput |= service.inputs().owns("attack");
            } else {
                service.inputs().release(key.getAsString());
            }
        }
    }

    private JsonObject evidence(String reason) {
        JsonObject data = Json.object("before", before, "after", ActionObservation.capture(context, Json.object()),
                "ticks", service.tick() - startedTick, "endReason", reason,
                "confirmation", "observed", "confirmationSource", "client", "requestedInput", request);
        data.add("raycast", WorldReader.raycast(context, Json.object("distance", 6)));
        return context.result(data);
    }

    private JsonObject finish(String reason) {
        result = evidence(reason);
        return result;
    }

    @Override
    public void cleanup() {
        service.inputs().clear();
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player != null && minecraft.playerController != null) {
            // 时间线可能已释放最后一个按键，仍需在报告清理完成前停止相应控制器动作。
            if (usedItemInput && minecraft.player.isHandActive()) {
                minecraft.playerController.onStoppedUsingItem(minecraft.player);
            }
            if (usedAttackInput) {
                minecraft.playerController.resetBlockRemoving();
            }
        }
    }

    @Override
    public JsonObject lastResult() {
        return result;
    }

    private static float number(JsonObject arguments, String name, float fallback) {
        return arguments.has(name) ? arguments.get(name).getAsFloat() : fallback;
    }
}
