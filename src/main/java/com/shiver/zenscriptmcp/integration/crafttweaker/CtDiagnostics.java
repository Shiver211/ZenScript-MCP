package com.shiver.zenscriptmcp.integration.crafttweaker;

import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.EventBuffer;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 接收器可能由模组后台线程调用；只保存有界 DTO，不访问游戏对象。 */
public final class CtDiagnostics {

    private static final int MAX_BACKFILL_BYTES = 2 * 1024 * 1024;
    private static final int MAX_TEXT_CHARACTERS = 6000;
    private static final Pattern POSITION = Pattern.compile(
            "^(?:\\[[^\\]]*\\]\\s*)*(?:Error parsing )?"
                    + "((?:[A-Za-z]:)?[^\\s:]+\\.zs):(\\d{1,9})(?::(\\d{1,9}))?(?=[: >\\t]|$)");

    private final EventBuffer buffer;
    private final boolean filtersPossible;
    private long errors;
    private long warnings;
    private String backfill = "not_read";
    private boolean active = true;

    public CtDiagnostics(SnapshotStore snapshots, int capacity, boolean filtersPossible) {
        buffer = new EventBuffer(snapshots, capacity);
        this.filtersPossible = filtersPossible;
    }

    public synchronized void record(String severity, String message, Throwable exception, String origin) {
        if (!active) {
            return;
        }
        if ("error".equals(severity)) {
            errors++;
        } else if ("warning".equals(severity)) {
            warnings++;
        }
        String raw = message == null ? "" : message;
        JsonObject data = Json.object("severity", severity, "raw", bounded(raw), "origin", origin,
                "truncated", raw.length() > MAX_TEXT_CHARACTERS);
        Matcher position = POSITION.matcher(raw);
        if (position.find()) {
            data.addProperty("file", position.group(1).trim());
            data.addProperty("line", Integer.parseInt(position.group(2)));
            if (position.group(3) != null) {
                data.addProperty("column", Integer.parseInt(position.group(3)));
            }
        }
        if (exception != null) {
            StringWriter stack = new StringWriter();
            exception.printStackTrace(new PrintWriter(stack));
            data.addProperty("exception", bounded(stack.toString()));
            if (!data.has("file")) {
                addStackPosition(data, exception);
            }
        }
        buffer.add("runtime", 0, "ct_diagnostic", data);
    }

    public void backfill(Path path) {
        if (!Files.isRegularFile(path)) {
            backfill = "file_missing";
            return;
        }
        try (SeekableByteChannel channel = Files.newByteChannel(path)) {
            long size = channel.size();
            long offset = Math.max(0, size - MAX_BACKFILL_BYTES);
            channel.position(offset);
            ByteBuffer bytes = ByteBuffer.allocate((int) Math.min(size, MAX_BACKFILL_BYTES));
            while (bytes.hasRemaining() && channel.read(bytes) > 0) {
                // 启动阶段有界补读；不持续监听或在 HTTP 线程遍历日志。
            }
            bytes.flip();
            String text = StandardCharsets.UTF_8.decode(bytes).toString();
            String[] lines = text.split("\\r?\\n");
            for (int index = offset > 0 ? 1 : 0; index < lines.length; index++) {
                String line = lines[index];
                if (!line.isEmpty()) {
                    String upper = line.toUpperCase(Locale.ROOT);
                    String severity = "unknown";
                    if (upper.contains("[ERROR]") || upper.contains("[FATAL]")) {
                        severity = "error";
                    } else if (upper.contains("[WARNING]") || upper.contains("[WARN]")) {
                        severity = "warning";
                    }
                    record(severity, line, null, "startup_file");
                }
            }
            backfill = offset > 0 ? "tail_only" : "available_file_bytes";
        } catch (IOException exception) {
            backfill = "read_failed";
        }
    }

    public synchronized JsonObject checkpoint() {
        return Json.object("cursor", buffer.cursor(), "errors", errors, "warnings", warnings);
    }

    public JsonObject read(JsonObject arguments) {
        JsonObject result = buffer.read(arguments, false, 100);
        result.add("coverage", coverage());
        return result;
    }

    public JsonObject coverage() {
        return Json.object("scope", "received_messages", "complete", false,
                "filtersMayHideMessages", filtersPossible, "startupBackfill", backfill,
                "startupFileMayBeBuffered", true, "attribution", "time_window_not_causality",
                "runtimeCallbacksRequireExecution", true);
    }

    public synchronized void close() {
        active = false;
    }

    private static String bounded(String text) {
        return text.length() > MAX_TEXT_CHARACTERS ? text.substring(0, MAX_TEXT_CHARACTERS) : text;
    }

    private static void addStackPosition(JsonObject data, Throwable exception) {
        for (StackTraceElement frame : exception.getStackTrace()) {
            if (frame.getFileName() != null && frame.getFileName().endsWith(".zs") && frame.getLineNumber() > 0) {
                data.addProperty("file", frame.getFileName());
                data.addProperty("line", frame.getLineNumber());
                return;
            }
        }
    }
}
