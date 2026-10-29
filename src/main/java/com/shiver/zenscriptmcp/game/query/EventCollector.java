package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.util.text.ITextComponent;
import net.minecraftforge.client.event.ClientChatReceivedEvent;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

/** 只采集少量有意义事件；LOWEST 的取消值仍只是该监听时刻的观察。 */
public final class EventCollector {

    private final SnapshotStore snapshots;
    private final EventBuffer events;

    public EventCollector(SnapshotStore snapshots, EventBuffer events) {
        this.snapshots = snapshots;
        this.events = events;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onChat(ClientChatReceivedEvent event) {
        events.add("client", tick("client"), "chat", Json.object(
                "text", event.getMessage().getUnformattedText(),
                "componentJson", ITextComponent.Serializer.componentToJson(event.getMessage()),
                "messageType", event.getType(), "cancelledAtObservation", event.isCanceled(),
                "observationPhase", "LOWEST"));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onInteraction(PlayerInteractEvent event) {
        if (event instanceof PlayerInteractEvent.RightClickEmpty
                || event instanceof PlayerInteractEvent.LeftClickEmpty) {
            return;
        }
        EntityPlayer player = event.getEntityPlayer();
        String source = player.world.isRemote ? "client" : "server";
        JsonObject target = Json.object("kind", "block", "dimension", player.dimension,
                "instanceId", snapshots.context().get("instanceId"),
                "worldEpoch", snapshots.worldEpoch(), "position", WorldReader.blockPosition(event.getPos()));
        if (event instanceof PlayerInteractEvent.EntityInteract) {
            target = entityTarget(((PlayerInteractEvent.EntityInteract) event).getTarget());
        } else if (event instanceof PlayerInteractEvent.EntityInteractSpecific) {
            target = entityTarget(((PlayerInteractEvent.EntityInteractSpecific) event).getTarget());
        } else if (event instanceof PlayerInteractEvent.RightClickItem) {
            target = Json.object("kind", "player", "uuid", player.getUniqueID().toString(),
                    "instanceId", snapshots.context().get("instanceId"), "worldEpoch", snapshots.worldEpoch());
        }
        events.add(source, tick(source), "interaction", Json.object("event", event.getClass().getSimpleName(),
                "playerUuid", player.getUniqueID().toString(), "target", target,
                "cancelledAtObservation", event.isCancelable() && event.isCanceled(), "observationPhase", "LOWEST"));
    }

    @SubscribeEvent(priority = EventPriority.LOWEST, receiveCanceled = true)
    public void onCommand(CommandEvent event) {
        String source = event.getSender().getEntityWorld() != null && event.getSender().getEntityWorld().isRemote
                ? "client" : "server";
        events.add(source, tick(source), "command", Json.object("name", event.getCommand().getName(),
                "sender", event.getSender().getName(), "phase", "before_execution",
                "cancelledAtObservation", event.isCanceled(), "observationPhase", "LOWEST"));
    }

    private JsonObject entityTarget(Entity entity) {
        return Json.object("kind", "entity", "instanceId", snapshots.context().get("instanceId"),
                "dimension", entity.dimension,
                "worldEpoch", snapshots.worldEpoch(), "uuid", entity.getUniqueID().toString());
    }

    private long tick(String source) {
        JsonObject snapshot = snapshots.status().getAsJsonObject(source);
        return snapshot.has("tick") ? snapshot.get("tick").getAsLong() : 0;
    }
}
