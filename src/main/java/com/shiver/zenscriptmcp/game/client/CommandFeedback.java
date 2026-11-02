package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.text.ITextComponent;

/** 只在一次同步命令调用的线程局部范围内记录；不替换真实玩家或命令发送者。 */
public final class CommandFeedback implements AutoCloseable {

    private static final int MAX_ENTRIES = 64;
    private static final ThreadLocal<CommandFeedback> CURRENT = new ThreadLocal<>();

    private final CommandFeedback previous;
    private final Object sender;
    private final JsonArray messages = new JsonArray();
    private final JsonArray exceptions = new JsonArray();
    private boolean truncated;

    public CommandFeedback(ICommandSender sender) {
        this.sender = sender;
        previous = CURRENT.get();
        CURRENT.set(this);
    }

    public static void message(Object recipient, Object component) {
        CommandFeedback current = CURRENT.get();
        if (current == null || recipient != current.sender || !(component instanceof ITextComponent)) {
            return;
        }
        try {
            ITextComponent text = (ITextComponent) component;
            current.append(current.messages, Json.object("text", text.getUnformattedText(),
                    "formattedText", text.getFormattedText(),
                    "component", Json.parse(ITextComponent.Serializer.componentToJson(text))));
        } catch (RuntimeException exception) {
            current.truncated = true;
        }
    }

    public static void exception(Throwable exception) {
        CommandFeedback current = CURRENT.get();
        if (current != null) {
            current.append(current.exceptions, Json.object("class", exception.getClass().getName(),
                    "message", exception.getMessage(), "text", exception.toString()));
        }
    }

    private void append(JsonArray array, JsonObject entry) {
        if (array.size() >= MAX_ENTRIES) {
            truncated = true;
        } else {
            array.add(entry);
        }
    }

    public boolean failed() {
        return exceptions.size() > 0;
    }

    public JsonObject result() {
        return Json.object("attribution", "synchronous_thread_scope", "messages", messages,
                "exceptions", exceptions, "truncated", truncated);
    }

    @Override
    public void close() {
        if (previous == null) {
            CURRENT.remove();
        } else {
            CURRENT.set(previous);
        }
    }
}
