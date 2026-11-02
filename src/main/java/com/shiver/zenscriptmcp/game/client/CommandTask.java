package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.GameExecutor;
import com.shiver.zenscriptmcp.runtime.JobManager;
import net.minecraft.client.Minecraft;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.client.ClientCommandHandler;
import net.minecraftforge.event.ForgeEventFactory;

/** 保留真实发送者和 Forge 客户端优先路由，不用消息包装器替换玩家类型。 */
final class CommandTask implements JobManager.Task {

    private final ActionService service;
    private final String tool;
    private final JsonObject request;
    private final QueryContext context;
    private String cursor;
    private JsonObject data;
    private JsonObject result;

    CommandTask(ActionService service, String tool, JsonObject request, GameExecutor.Side side) {
        this.service = service;
        this.tool = tool;
        this.request = request;
        context = service.context(side);
    }

    @Override
    public JsonObject step() {
        if (data != null) {
            data.addProperty("messageCursorEnd", service.events().cursor());
            data.add("messages", service.events().read(Json.object("cursor", cursor), false, 100));
            result = context.result(data);
            return result;
        }
        cursor = service.events().cursor();
        String mode = QueryContext.string(request, "mode", "player");
        String text = request.get("mc_chat".equals(tool) ? "text" : "command").getAsString();
        if (text.indexOf('\n') >= 0 || text.indexOf('\r') >= 0 || text.trim().isEmpty()) {
            throw new IllegalArgumentException("聊天和命令必须是非空单行文本");
        }
        data = Json.object("mode", mode, "messageCursorStart", cursor,
                "messageAttribution", "time_window_not_causality", "confirmation", "unconfirmed",
                "outcome", "unverified");
        if (mode.startsWith("server_")) {
            MinecraftServer server = context.server();
            ICommandSender sender = "server_console".equals(mode) ? server : context.player(Json.object());
            data.addProperty("senderClass", sender.getClass().getName());
            data.addProperty("sender", sender.getName());
            data.addProperty("route", mode);
            execute(server.getCommandManager(), sender, server, text);
        } else {
            Minecraft minecraft = Minecraft.getMinecraft();
            ActionService.player();
            if ("mc_command".equals(tool) && !text.startsWith("/")) {
                text = "/" + text;
            }
            if (!"client".equals(mode)) {
                text = ForgeEventFactory.onClientSendMessage(text);
                if (text.isEmpty()) {
                    fail("action_rejected", "聊天发送被 Forge 事件取消");
                }
                minecraft.ingameGUI.getChatGUI().addToSentMessages(text);
            }
            data.addProperty("senderClass", minecraft.player.getClass().getName());
            data.addProperty("sender", minecraft.player.getName());
            int returned = executeCaptured(ClientCommandHandler.instance, minecraft.player, text);
            data.addProperty("handlerReturn", returned);
            if (returned != 0 || "client".equals(mode)) {
                data.addProperty("route", "client");
                if (returned <= 0) {
                    fail("command_failed", "客户端命令未成功处理");
                }
                data.addProperty("confirmation", "observed");
                data.addProperty("outcome", "succeeded");
                data.addProperty("confirmationScope", "client_handler_return");
            } else {
                if (text.length() > 256) {
                    fail("invalid_target", "玩家聊天协议最多允许 256 个字符");
                }
                minecraft.player.sendChatMessage(text);
                data.addProperty("route", "server_via_player_connection");
                data.addProperty("executionState", "sent");
                data.addProperty("serverReturnAvailable", false);
                data.addProperty("verificationRequired", true);
            }
        }
        result = context.result(data);
        return null;
    }

    private void execute(ICommandManager manager, ICommandSender sender, MinecraftServer server, String text) {
        String normalized = text.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1);
        }
        String name = normalized.split(" ", 2)[0];
        ICommand command = manager.getCommands().get(name);
        boolean permitted = command != null && command.checkPermission(server, sender);
        int returned = executeCaptured(manager, sender, text);
        data.addProperty("handlerReturn", returned);
        data.addProperty("commandKnown", command != null);
        data.addProperty("permitted", permitted);
        if (returned <= 0) {
            fail(command != null && !permitted ? "permission_denied" : "command_failed", "服务端命令执行失败");
        }
        data.addProperty("confirmation", "observed");
        data.addProperty("outcome", "succeeded");
        data.addProperty("confirmationScope", "synchronous_command_return");
    }

    private int executeCaptured(ICommandManager manager, ICommandSender sender, String text) {
        try (CommandFeedback feedback = new CommandFeedback(sender)) {
            int returned;
            try {
                returned = manager.executeCommand(sender, text);
            } catch (RuntimeException exception) {
                CommandFeedback.exception(exception);
                data.add("feedback", feedback.result());
                fail("command_failed", exception.toString());
                return 0;
            }
            data.add("feedback", feedback.result());
            data.addProperty("handlerReturn", returned);
            if (feedback.failed()) {
                fail("command_failed", "命令处理器捕获到异常；详见原始反馈");
            }
            data.addProperty("executionState", returned > 0 ? "succeeded" : "unhandled_or_failed");
            return returned;
        }
    }

    private void fail(String code, String message) {
        data.addProperty("messageCursorEnd", service.events().cursor());
        data.add("messages", service.events().read(Json.object("cursor", cursor), false, 100));
        data.addProperty("confirmation", "rejected");
        data.addProperty("outcome", "failed");
        result = context.result(data);
        throw new DomainException(code, message, "command", Json.object("feedback", data.get("feedback"),
                "route", data.get("route")));
    }

    @Override
    public JsonObject lastResult() {
        return result;
    }
}
