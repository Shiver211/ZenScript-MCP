package com.shiver.zenscriptmcp.data;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.game.query.QueryContext;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTBase;
import net.minecraft.nbt.NBTPrimitive;
import net.minecraft.nbt.NBTTagByteArray;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagIntArray;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;

/** 保留 NBT 类型；long 和 long 数组绝不经过 double。 */
public final class NbtReader {

    private static final String[] TYPES = {"end", "byte", "short", "int", "long", "float", "double",
            "byte_array", "string", "list", "compound", "int_array", "long_array"};

    private final McpConfig.QuerySettings settings;
    private int nodes;
    private int characters;
    private boolean truncated;

    public NbtReader(McpConfig.QuerySettings settings) {
        this.settings = settings;
    }

    public JsonObject read(NBTBase root, JsonObject arguments) {
        NBTBase selected = path(root, arguments);
        int offset = arguments.has("offset") ? arguments.get("offset").getAsInt() : 0;
        int limit = arguments.has("limit") ? arguments.get("limit").getAsInt() : settings.defaultLimit;
        JsonObject typed = encode(selected, 0, offset, limit);
        String format = QueryContext.string(arguments, "format", "typed");
        Object value = typed;
        if ("snbt".equals(format)) {
            // 截断后的 SNBT 容易被误认为完整原文；要求缩小路径后再读取。
            value = truncated ? null : selected.toString();
        }
        return Json.object("format", format, "nbt", value, "truncated", truncated,
                "offset", offset, "limit", limit, "visitedNodes", nodes);
    }

    private JsonObject encode(NBTBase tag, int depth, int offset, int limit) {
        int id = tag.getId();
        String type = id >= 0 && id < TYPES.length ? TYPES[id] : "unknown";
        if (++nodes > settings.maxNbtNodes || depth > settings.maxNbtDepth || characters > settings.maxNbtBytes / 4) {
            truncated = true;
            return Json.object("type", type, "unavailable", true);
        }
        JsonObject result = Json.object("type", type);
        if (tag instanceof NBTPrimitive) {
            NBTPrimitive number = (NBTPrimitive) tag;
            if (id == 4) {
                result.addProperty("value", Long.toString(number.getLong()));
            } else if (id == 5 || id == 6) {
                double value = number.getDouble();
                if (Double.isNaN(value) || Double.isInfinite(value)) {
                    result.addProperty("value", Double.toString(value));
                    result.addProperty("nonFinite", true);
                } else {
                    result.addProperty("value", value);
                }
            } else {
                result.addProperty("value", number.getInt());
            }
        } else if (tag instanceof NBTTagString) {
            String value = ((NBTTagString) tag).getString();
            characters += value.length();
            if (characters > settings.maxNbtBytes / 4) {
                result.addProperty("unavailable", true);
                truncated = true;
            } else {
                result.addProperty("value", value);
            }
        } else if (tag instanceof NBTTagCompound) {
            compound(result, (NBTTagCompound) tag, depth, offset, limit);
        } else if (tag instanceof NBTTagList) {
            list(result, (NBTTagList) tag, depth, offset, limit);
        } else if (id == 7 || id == 11 || id == 12) {
            array(result, tag, offset, limit);
        }
        return result;
    }

    private void compound(JsonObject result, NBTTagCompound tag, int depth, int offset, int limit) {
        List<String> keys = new ArrayList<>(tag.getKeySet());
        Collections.sort(keys);
        JsonObject values = new JsonObject();
        int end = Math.min(keys.size(), offset + limit);
        for (int index = offset; index < end && nodes < settings.maxNbtNodes; index++) {
            String key = keys.get(index);
            characters += key.length();
            values.add(key, encode(tag.getTag(key), depth + 1, 0, settings.maxNbtNodes));
        }
        result.add("value", values);
        range(result, keys.size(), offset, values.size());
    }

    private void list(JsonObject result, NBTTagList tag, int depth, int offset, int limit) {
        JsonArray values = new JsonArray();
        int end = Math.min(tag.tagCount(), offset + limit);
        for (int index = offset; index < end && nodes < settings.maxNbtNodes; index++) {
            values.add(encode(tag.get(index), depth + 1, 0, settings.maxNbtNodes));
        }
        result.add("value", values);
        result.addProperty("elementType", tag.getTagType());
        range(result, tag.tagCount(), offset, values.size());
    }

    private void array(JsonObject result, NBTBase tag, int offset, int limit) {
        JsonArray values = new JsonArray();
        long[] longs = tag.getId() == 12 ? longs(tag) : null;
        byte[] bytes = tag instanceof NBTTagByteArray ? ((NBTTagByteArray) tag).getByteArray() : null;
        int[] integers = tag instanceof NBTTagIntArray ? ((NBTTagIntArray) tag).getIntArray() : null;
        int length;
        if (longs != null) {
            length = longs.length;
        } else if (bytes != null) {
            length = bytes.length;
        } else {
            length = integers.length;
        }
        int end = Math.min(length, offset + limit);
        for (int index = offset; index < end && nodes < settings.maxNbtNodes; index++) {
            nodes++;
            if (longs != null) {
                values.add(Long.toString(longs[index]));
            } else if (bytes != null) {
                values.add(bytes[index]);
            } else {
                values.add(integers[index]);
            }
        }
        result.add("value", values);
        range(result, length, offset, values.size());
    }

    private void range(JsonObject result, int total, int offset, int count) {
        boolean partial = offset > 0 || count < total;
        truncated |= partial;
        result.addProperty("total", total);
        result.addProperty("offset", offset);
        result.addProperty("truncated", partial);
        if (offset + count < total) {
            result.addProperty("nextOffset", offset + count);
        }
    }

    private long[] longs(NBTBase tag) {
        // 1.12.2 没有 long[] getter；通过公开写入接口读取原始 64 位整数。
        NBTTagCompound wrapper = new NBTTagCompound();
        wrapper.setTag("value", tag);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        OutputStream bounded = new OutputStream() {
            @Override
            public void write(int value) throws IOException {
                if (bytes.size() >= settings.maxNbtBytes) {
                    throw new IOException("long 数组超过预算");
                }
                bytes.write(value);
            }
        };
        try {
            CompressedStreamTools.write(wrapper, new DataOutputStream(bounded));
            DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes.toByteArray()));
            input.readByte();
            input.readUTF();
            input.readByte();
            input.readUTF();
            long[] result = new long[input.readInt()];
            for (int index = 0; index < result.length; index++) {
                result[index] = input.readLong();
            }
            return result;
        } catch (IOException exception) {
            throw new DomainException("result_too_large", "NBT long 数组超过读取预算");
        }
    }

    private static NBTBase path(NBTBase root, JsonObject arguments) {
        if (!arguments.has("path")) {
            return root;
        }
        NBTBase current = root;
        for (JsonElement segment : arguments.getAsJsonArray("path")) {
            String key = segment.getAsString();
            if (current instanceof NBTTagCompound) {
                current = ((NBTTagCompound) current).getTag(key);
            } else if (current instanceof NBTTagList) {
                try {
                    int index = Integer.parseInt(key);
                    NBTTagList list = (NBTTagList) current;
                    current = index >= 0 && index < list.tagCount() ? list.get(index) : null;
                } catch (NumberFormatException exception) {
                    current = null;
                }
            } else {
                current = null;
            }
            if (current == null) {
                throw new DomainException("target_missing", "NBT 路径不存在");
            }
        }
        return current;
    }
}
