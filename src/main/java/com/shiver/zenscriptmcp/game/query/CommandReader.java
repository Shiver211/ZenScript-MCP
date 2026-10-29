package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.PageStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandManager;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.client.ClientCommandHandler;

public final class CommandReader {

    private CommandReader() {
    }

    public static JobManager.Task task(QueryContext context, JsonObject arguments, PageStore pages) {
        String mode = QueryContext.string(arguments, "mode", "player");
        String action = QueryContext.string(arguments, "action", "list");
        boolean client = "client".equals(context.source());
        ICommandManager manager = client ? ClientCommandHandler.instance : context.server().getCommandManager();
        MinecraftServer server = client ? null : context.server();
        ICommandSender sender = null;
        try {
            sender = "server_console".equals(mode) ? server : context.player(Json.object());
        } catch (DomainException exception) {
            context.missing("permission:player_unavailable");
        }
        final ICommandSender actualSender = sender;
        JsonObject metadata = Json.object("mode", mode, "scope", client ? "client_commands" : "server_commands");
        if (client && "player".equals(mode)) {
            context.missing("server_commands:query_server_player_mode");
        }
        if ("complete".equals(action)) {
            if (sender == null) {
                throw new DomainException("player_unavailable", "补全需要实际命令发送者");
            }
            String prefix = QueryContext.string(arguments, "prefix", "");
            if (prefix.startsWith("/")) {
                prefix = prefix.substring(1);
            }
            List<String> completions = manager.getTabCompletions(sender, prefix, sender.getPosition());
            return new ListQueryTask<>("mc_commands", context, arguments, completions.iterator(),
                    value -> Json.object("text", value), pages, true, metadata);
        }
        List<Map.Entry<String, ICommand>> commands = new ArrayList<>(manager.getCommands().entrySet());
        commands.sort(Map.Entry.comparingByKey());
        String requested = QueryContext.string(arguments, "command", "");
        if ("help".equals(action) && requested.isEmpty()) {
            throw new DomainException("invalid_target", "help 需要 command 名称");
        }
        if (!requested.isEmpty() && !manager.getCommands().containsKey(requested)) {
            throw new DomainException("target_missing", "当前来源没有该命令");
        }
        return new ListQueryTask<>("mc_commands", context, arguments, commands.iterator(), entry -> {
            if (!requested.isEmpty() && !entry.getKey().equals(requested)) {
                return null;
            }
            ICommand command = entry.getValue();
            JsonObject data = Json.object("name", entry.getKey(), "canonicalName", command.getName(),
                    "class", command.getClass().getName(), "aliases", command.getAliases());
            if (actualSender != null) {
                context.field(data, Json.object(), "permitted", () -> command.checkPermission(server, actualSender));
                context.field(data, Json.object(), "usage", () -> command.getUsage(actualSender));
            }
            if (!CatalogReader.matches(data, arguments)) {
                return null;
            }
            return data;
        }, pages, !client || actualSender != null, metadata);
    }
}
