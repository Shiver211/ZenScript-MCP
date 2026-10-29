package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.game.query.TargetResolver;
import com.shiver.zenscriptmcp.game.query.WorldReader;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.util.EnumActionResult;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraftforge.common.ForgeHooks;

/** 玩家交互状态机；服务端快照确认与动作停止分开推进。 */
final class InteractionTask implements JobManager.Task {

    private final ActionService service;
    private final String tool;
    private final JsonObject request;
    private final QueryContext context;
    private final String action;
    private final int duration;
    private JsonObject before;
    private JsonObject serverBefore;
    private JsonObject result;
    private PeerObservation peer;
    private long actionTick = -1;
    private long stoppedTick;
    private boolean stopped;
    private boolean startedUsing;
    private String endReason = "dispatched";
    private String callbackResult = "not_available";

    InteractionTask(ActionService service, String tool, JsonObject request) {
        this.service = service;
        this.tool = tool;
        this.request = request;
        context = service.context(GameExecutor.Side.CLIENT);
        action = QueryContext.string(request, "action", "use");
        duration = request.has("durationTicks") ? request.get("durationTicks").getAsInt() : 1;
    }

    @Override
    public JsonObject step() {
        Minecraft minecraft = Minecraft.getMinecraft();
        EntityPlayerSP player = ActionService.player();
        if (actionTick < 0) {
            if (before == null) {
                requireTarget(player);
                before = ActionObservation.capture(context, request);
                if (service.serverAvailable()) {
                    peer = service.peer(request, false);
                }
            }
            if (peer != null) {
                serverBefore = peer.poll();
                if (serverBefore == null) {
                    return null;
                }
                peer.close();
                peer = null;
            }
            if (minecraft.isGamePaused()) {
                return null;
            }
            requireTarget(player);
            if (player.isHandActive()) {
                throw new DomainException("action_rejected", "玩家已经在持续使用物品，请先结束当前使用");
            }
            actionTick = service.tick();
            dispatch(player);
            startedUsing = player.isHandActive();
            result = evidence(null, "unconfirmed");
            return null;
        }
        if (!stopped) {
            long ticks = service.tick() - actionTick;
            if (minecraft.currentScreen != null) {
                endReason = "screen_changed";
                stop();
            } else if ("break".equals(action) && blockChanged()) {
                endReason = "target_changed";
                stop();
            } else if (startedUsing && !player.isHandActive()) {
                endReason = "item_use_ended";
                stop();
            } else if (ticks >= duration) {
                endReason = "duration_reached";
                stop();
            } else if (!minecraft.isGamePaused()) {
                if ("break".equals(action)) {
                    requireTarget(player);
                }
                if ("hold".equals(action)) {
                    setUseDelay();
                }
                service.inputs().validate();
            }
            result = evidence(null, "unconfirmed");
            if (!stopped) {
                return null;
            }
        }
        if (serverBefore == null) {
            result = evidence(null, "unconfirmed");
            return result;
        }
        if (peer == null) {
            peer = service.peer(request, false);
            return null;
        }
        JsonObject serverAfter = peer.poll();
        if (serverAfter == null) {
            return null;
        }
        boolean changed = meaningfulChange(serverBefore.getAsJsonObject("data"), serverAfter.getAsJsonObject("data"));
        result = evidence(serverAfter, changed ? "observed" : "unconfirmed");
        return result;
    }

    private void dispatch(EntityPlayerSP player) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if ("break".equals(action)) {
            service.inputs().press("attack", true);
        } else if ("attack".equals(action)) {
            InputAccess.invoke(minecraft, Minecraft.class, "clickMouse", "func_147116_af", new Class<?>[0]);
        } else {
            if (minecraft.playerController.getIsHittingBlock() || player.isRowingBoat()) {
                throw new DomainException("action_rejected", "当前玩家状态不允许右键使用");
            }
            String handName = QueryContext.string(request, "hand", "auto");
            if ("auto".equals(handName)) {
                InputAccess.invoke(minecraft, Minecraft.class, "rightClickMouse", "func_147121_ag", new Class<?>[0]);
            } else {
                EnumHand hand = "off".equals(handName) ? EnumHand.OFF_HAND : EnumHand.MAIN_HAND;
                EnumActionResult outcome = useHand(player, hand);
                callbackResult = outcome.name().toLowerCase(Locale.ROOT);
                if (outcome == EnumActionResult.FAIL) {
                    result = evidence(null, "rejected");
                    throw new DomainException("action_rejected", "玩家交互入口返回 FAIL");
                }
            }
            setUseDelay();
            if ("hold".equals(action)) {
                service.inputs().press("use", false);
            }
        }
    }

    private EnumActionResult useHand(EntityPlayerSP player, EnumHand hand) {
        Minecraft minecraft = Minecraft.getMinecraft();
        RayTraceResult hit = minecraft.objectMouseOver;
        EnumActionResult outcome = EnumActionResult.PASS;
        if (hit != null && hit.typeOfHit == RayTraceResult.Type.BLOCK) {
            outcome = minecraft.playerController.processRightClickBlock(player, minecraft.world,
                    hit.getBlockPos(), hit.sideHit, hit.hitVec, hand);
        } else if (hit != null && hit.typeOfHit == RayTraceResult.Type.ENTITY) {
            outcome = minecraft.playerController.interactWithEntity(player, hit.entityHit, hit, hand);
            if (outcome == EnumActionResult.PASS) {
                outcome = minecraft.playerController.interactWithEntity(player, hit.entityHit, hand);
            }
        } else if (player.getHeldItem(hand).isEmpty()) {
            ForgeHooks.onEmptyClick(player, hand);
        }
        if (outcome == EnumActionResult.PASS && !player.getHeldItem(hand).isEmpty()) {
            outcome = minecraft.playerController.processRightClick(player, minecraft.world, hand);
        }
        if (outcome == EnumActionResult.SUCCESS) {
            player.swingArm(hand);
            minecraft.entityRenderer.itemRenderer.resetEquippedProgress(hand);
        }
        return outcome;
    }

    private void requireTarget(EntityPlayerSP player) {
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.currentScreen != null) {
            throw new DomainException("action_rejected", "交互需要关闭当前 GUI");
        }
        if (!request.has("target")) {
            minecraft.entityRenderer.getMouseOver(1);
            return;
        }
        JsonObject target = request.getAsJsonObject("target");
        TargetResolver.checkEpoch(context, target);
        context.world(target);
        Vec3d point;
        Entity entity = null;
        BlockPos position = null;
        if ("mc_entity_interact".equals(tool)) {
            entity = TargetResolver.entity(context, target);
            point = entity.getEntityBoundingBox().getCenter();
        } else {
            position = WorldReader.blockPosition(target.getAsJsonObject("position"));
            WorldReader.requireChunk(minecraft.world, position);
            point = new Vec3d(position).add(0.5, 0.5, 0.5);
            if (request.has("face")) {
                EnumFacing face = EnumFacing.byName(request.get("face").getAsString());
                point = point.add(new Vec3d(face.getDirectionVec()).scale(0.5));
            }
        }
        if (request.has("hitPoint")) {
            point = WorldReader.vector(request.getAsJsonObject("hitPoint"));
        }
        if (request.has("lookAt") && request.get("lookAt").getAsBoolean()) {
            InputTask.look(player, Json.object("position", WorldReader.vector(point)),
                    player.rotationYaw, player.rotationPitch, 1);
        }
        double reach = minecraft.playerController.getBlockReachDistance();
        if (point.distanceTo(player.getPositionEyes(1)) > reach + 0.5) {
            throw new DomainException("out_of_reach", "目标超过玩家交互距离");
        }
        minecraft.entityRenderer.getMouseOver(1);
        RayTraceResult hit = minecraft.objectMouseOver;
        boolean matches = hit != null && (entity != null ? hit.entityHit == entity
                : hit.typeOfHit == RayTraceResult.Type.BLOCK && position.equals(hit.getBlockPos()));
        boolean faceMatches = !request.has("face") || hit != null
                && hit.sideHit == EnumFacing.byName(request.get("face").getAsString());
        if (!matches || !faceMatches) {
            throw new DomainException("target_mismatch", "实际准星命中与指定目标或面不一致");
        }
        if (request.has("hitPoint") && hit.hitVec.distanceTo(point) > 0.15) {
            throw new DomainException("target_mismatch", "实际命中点与请求不一致");
        }
    }

    private boolean blockChanged() {
        JsonObject after = ActionObservation.capture(context, request).getAsJsonObject("data");
        return !before.getAsJsonObject("data").get("block").equals(after.get("block"));
    }

    private static boolean meaningfulChange(JsonObject before, JsonObject after) {
        for (String field : new String[] {"block", "adjacent", "entity", "mainHand", "offHand",
                "windowId", "containerClass", "riding", "health"}) {
            if (before.has(field) && !before.get(field).equals(after.get(field))) {
                return true;
            }
        }
        return false;
    }

    private JsonObject evidence(JsonObject serverAfter, String confirmation) {
        return context.result(Json.object("before", before,
                "after", ActionObservation.capture(context, request), "serverBefore", serverBefore,
                "serverAfter", serverAfter, "confirmation", confirmation, "callbackResult", callbackResult,
                "endReason", endReason, "ticks", actionTick < 0 ? 0
                        : (stopped ? stoppedTick : service.tick()) - actionTick));
    }

    private static void setUseDelay() {
        InputAccess.set(InputAccess.field(Minecraft.class, "rightClickDelayTimer", "field_71467_ac"),
                Minecraft.getMinecraft(), 4);
    }

    private void stop() {
        if (!stopped) {
            stoppedTick = service.tick();
        }
        stopped = true;
        service.inputs().clear();
        Minecraft minecraft = Minecraft.getMinecraft();
        if (minecraft.player != null && minecraft.playerController != null) {
            if ("hold".equals(action) && minecraft.player.isHandActive()) {
                minecraft.playerController.onStoppedUsingItem(minecraft.player);
            }
            if ("break".equals(action)) {
                minecraft.playerController.resetBlockRemoving();
            }
        }
    }

    @Override
    public void cleanup() {
        stop();
        if (peer != null) {
            peer.close();
        }
    }

    @Override
    public JsonObject lastResult() {
        return result;
    }
}
