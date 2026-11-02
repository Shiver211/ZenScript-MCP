package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.Json;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiListWorldSelection;
import net.minecraft.client.gui.GuiListWorldSelectionEntry;
import net.minecraft.client.gui.GuiWorldSelection;
import net.minecraft.world.storage.WorldSummary;

/** 读取当前界面已经加载的列表，不重新扫描存档，也不加载世界。 */
final class WorldSelectionReader {

    private static final int LIST_INSET = 4;
    private static final Field SELECTION_LIST = InputAccess.field(
            GuiWorldSelection.class, "selectionList", "field_184866_u");
    private static final Field ENTRIES = InputAccess.field(
            GuiListWorldSelection.class, "entries", "field_186799_w");
    private static final Field SUMMARY = InputAccess.field(
            GuiListWorldSelectionEntry.class, "worldSummary", "field_186786_g");

    private WorldSelectionReader() {
    }

    @SuppressWarnings("unchecked")
    static JsonArray read(GuiWorldSelection screen, String screenId, QueryContext context) {
        GuiListWorldSelection list = (GuiListWorldSelection) InputAccess.get(SELECTION_LIST, screen);
        List<GuiListWorldSelectionEntry> entries =
                (List<GuiListWorldSelectionEntry>) InputAccess.get(ENTRIES, list);
        JsonArray result = new JsonArray();
        int count = Math.min(entries.size(), context.settings().maxResults);
        for (int index = 0; index < count; index++) {
            GuiListWorldSelectionEntry entry = entries.get(index);
            WorldSummary summary = (WorldSummary) InputAccess.get(SUMMARY, entry);
            JsonObject world = describe(summary);
            world.addProperty("directory", Minecraft.getMinecraft().getSaveLoader()
                    .getFile(summary.getFileName(), "level.dat").getParentFile().getAbsolutePath());
            world.addProperty("screenId", screenId);
            world.addProperty("entryIndex", index);
            world.addProperty("selected", entry == list.getSelectedWorld());
            int x = list.left + list.width / 2 - list.getListWidth() / 2 + 2;
            int y = list.top + LIST_INSET - list.getAmountScrolled() + index * list.slotHeight + list.headerPadding;
            int bottom = Math.min(list.bottom, y + list.slotHeight - LIST_INSET);
            int top = Math.max(list.top, y);
            boolean visible = top < bottom;
            world.addProperty("visible", visible);
            world.addProperty("coordinateSpace", "gui_scaled");
            world.add("bounds", Json.object("x", x, "y", y, "width", list.getListWidth(),
                    "height", list.slotHeight - LIST_INSET));
            if (visible) {
                // 单击行中部只选中；避开左侧会立即进入世界的图标区域。
                world.add("selectionPoint", Json.object("x", x + list.getListWidth() / 2,
                        "y", (top + bottom) / 2));
            }
            result.add(world);
        }
        if (count < entries.size()) {
            context.missing("worlds:result_limit");
        }
        return result;
    }

    private static JsonObject describe(WorldSummary summary) {
        return Json.object("displayName", summary.getDisplayName(), "folderName", summary.getFileName(),
                "versionName", summary.getVersionName(), "lastPlayedAt",
                Instant.ofEpochMilli(summary.getLastTimePlayed()).toString(),
                "lastPlayedEpochMillis", Long.toString(summary.getLastTimePlayed()),
                "gameMode", summary.getEnumGameType().getName(), "hardcore", summary.isHardcoreModeEnabled(),
                "cheatsEnabled", summary.getCheatsEnabled(), "requiresConversion", summary.requiresConversion(),
                "requiresVersionConfirmation", summary.askToOpenWorld());
    }
}
