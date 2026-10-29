package com.shiver.zenscriptmcp.game.query;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.PageStore;
import java.util.Locale;
import java.util.Map;
import net.minecraft.block.properties.IProperty;
import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.Entity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.world.chunk.Chunk;

public final class WorldReader {

    private static final int MAX_RAY_STEPS = 1024;

    private WorldReader() {
    }

    public static Chunk loadedChunk(World world, BlockPos position) {
        int x = position.getX() >> 4;
        int z = position.getZ() >> 4;
        if (world instanceof WorldServer) {
            // getLoadedChunk 会撤销卸载标记；读取公开映射保持查询无副作用。
            return ((WorldServer) world).getChunkProvider().loadedChunks.get(ChunkPos.asLong(x, z));
        }
        return world.getChunkProvider().getLoadedChunk(x, z);
    }

    public static Chunk requireChunk(World world, BlockPos position) {
        Chunk chunk = loadedChunk(world, position);
        if (chunk == null) {
            throw new DomainException("target_unloaded", "目标区块未加载");
        }
        return chunk;
    }

    public static JsonObject environment(QueryContext context, JsonObject arguments) {
        World world = context.world(arguments);
        BlockPos position = arguments.has("position") ? blockPosition(arguments.getAsJsonObject("position"))
                : context.player(Json.object()).getPosition();
        context.checkFields(arguments, "time", "weather", "biome", "light", "spawn", "difficulty");
        JsonObject data = Json.object("dimension", world.provider.getDimension());
        context.field(data, arguments, "time", () -> Json.object("dayTime", Long.toString(world.getWorldTime()),
                "totalTime", Long.toString(world.getTotalWorldTime())));
        context.field(data, arguments, "weather", () -> Json.object("raining", world.isRaining(),
                "thundering", world.isThundering(), "rainStrength", world.getRainStrength(1)));
        context.field(data, arguments, "spawn", () -> blockPosition(world.getSpawnPoint()));
        context.field(data, arguments, "difficulty", () -> world.getDifficulty().name().toLowerCase(Locale.ROOT));
        Chunk chunk = loadedChunk(world, position);
        if (chunk == null) {
            if (QueryContext.wants(arguments, "biome") || QueryContext.wants(arguments, "light")) {
                context.missing("position:target_unloaded");
            }
        } else {
            context.field(data, arguments, "biome", () -> String.valueOf(
                    chunk.getBiome(position, world.getBiomeProvider()).getRegistryName()));
            context.field(data, arguments, "light", () -> light(chunk, position));
        }
        return data;
    }

    public static JsonObject block(QueryContext context, World world, BlockPos position, JsonObject arguments) {
        Chunk chunk = requireChunk(world, position);
        IBlockState state = chunk.getBlockState(position);
        context.checkFields(arguments, "registryName", "properties", "metadata", "light", "collision", "tileEntity");
        JsonObject target = Json.object("kind", "block", "dimension", world.provider.getDimension(),
                "instanceId", context.instanceId(), "worldEpoch", context.epoch(), "position", blockPosition(position));
        JsonObject result = Json.object("target", target);
        context.field(result, arguments, "registryName", () -> String.valueOf(state.getBlock().getRegistryName()));
        context.field(result, arguments, "properties", () -> properties(state));
        context.field(result, arguments, "metadata", () -> state.getBlock().getMetaFromState(state));
        context.field(result, arguments, "light", () -> light(chunk, position));
        context.field(result, arguments, "tileEntity", () -> tileSummary(chunk.getTileEntityMap().get(position)));
        if (QueryContext.wants(arguments, "collision")) {
            if (world.isAreaLoaded(position, 1)) {
                context.field(result, arguments, "collision", () -> {
                    AxisAlignedBB box = state.getCollisionBoundingBox(world, position);
                    return box == null ? null : EntityReader.bounds(box.offset(position));
                });
            } else {
                context.missing("collision:neighbor_unloaded");
            }
        }
        return result;
    }

    public static JsonObject raycast(QueryContext context, JsonObject arguments) {
        World world = context.world(arguments);
        Entity viewer = null;
        Vec3d start;
        Vec3d end;
        if (arguments.has("start") || arguments.has("end")) {
            if (!arguments.has("start") || !arguments.has("end")) {
                throw new DomainException("invalid_range", "start/end 必须同时提供");
            }
            start = vector(arguments.getAsJsonObject("start"));
            end = vector(arguments.getAsJsonObject("end"));
        } else {
            viewer = context.player(Json.object());
            start = viewer.getPositionEyes(1);
            double distance = arguments.has("distance") ? arguments.get("distance").getAsDouble() : 64;
            end = start.add(viewer.getLook(1).scale(distance));
        }
        if (start.distanceTo(end) > 256) {
            throw new DomainException("invalid_range", "射线长度不能超过 256 格");
        }
        boolean includeBlocks = !arguments.has("blocks") || arguments.get("blocks").getAsBoolean();
        boolean includeEntities = !arguments.has("entities") || arguments.get("entities").getAsBoolean();
        RayTraceResult hit = blockRay(world, start, end, includeBlocks,
                arguments.has("stopOnLiquid") && arguments.get("stopOnLiquid").getAsBoolean());
        double nearest = hit == null ? start.distanceTo(end) : start.distanceTo(hit.hitVec);
        if (includeEntities) {
            AxisAlignedBB bounds = new AxisAlignedBB(start.x, start.y, start.z, end.x, end.y, end.z).grow(1);
            for (Entity entity : world.loadedEntityList) {
                if (entity == viewer || entity.isDead || !entity.canBeCollidedWith()
                        || !entity.getEntityBoundingBox().intersects(bounds)) {
                    continue;
                }
                AxisAlignedBB box = entity.getEntityBoundingBox().grow(entity.getCollisionBorderSize());
                RayTraceResult candidate = box.calculateIntercept(start, end);
                Vec3d point = candidate == null ? null : candidate.hitVec;
                if (box.contains(start)) {
                    point = start;
                }
                if (point != null && (start.distanceTo(point) < nearest
                        || (hit == null && start.distanceTo(point) == nearest))) {
                    nearest = start.distanceTo(point);
                    hit = new RayTraceResult(entity, point);
                }
            }
        }
        JsonObject result = Json.object("rule", "collision", "dimension", world.provider.getDimension(),
                "start", vector(start), "end", vector(end), "hit", null);
        if (hit != null) {
            JsonObject target = hit.entityHit != null ? EntityReader.reference(context, hit.entityHit)
                    : Json.object("kind", "block", "dimension", world.provider.getDimension(),
                            "instanceId", context.instanceId(), "worldEpoch", context.epoch(),
                            "position", blockPosition(hit.getBlockPos()));
            result.add("hit", Json.object("target", target, "position", vector(hit.hitVec), "distance", nearest,
                    "face", hit.sideHit == null ? null : hit.sideHit.getName()));
        }
        return result;
    }

    private static RayTraceResult blockRay(World world, Vec3d start, Vec3d end, boolean blocks, boolean liquids) {
        Vec3d delta = end.subtract(start);
        BlockPos current = new BlockPos(start);
        BlockPos last = new BlockPos(end);
        int stepX = Double.compare(delta.x, 0);
        int stepY = Double.compare(delta.y, 0);
        int stepZ = Double.compare(delta.z, 0);
        double nextX = boundary(start.x, current.getX(), delta.x);
        double nextY = boundary(start.y, current.getY(), delta.y);
        double nextZ = boundary(start.z, current.getZ(), delta.z);
        for (int index = 0; index < MAX_RAY_STEPS; index++) {
            Chunk chunk = requireChunk(world, current);
            if (blocks) {
                IBlockState state = chunk.getBlockState(current);
                if (!world.isAreaLoaded(current, 1)) {
                    throw new DomainException("target_unloaded", "射线路径邻接区块未加载");
                }
                if (state.getBlock().canCollideCheck(state, liquids)) {
                    RayTraceResult hit = state.collisionRayTrace(world, current, start, end);
                    if (hit != null) {
                        return hit;
                    }
                }
            }
            if (current.equals(last)) {
                return null;
            }
            if (nextX <= nextY && nextX <= nextZ) {
                current = current.add(stepX, 0, 0);
                nextX += Math.abs(1 / delta.x);
            } else if (nextY <= nextZ) {
                current = current.add(0, stepY, 0);
                nextY += Math.abs(1 / delta.y);
            } else {
                current = current.add(0, 0, stepZ);
                nextZ += Math.abs(1 / delta.z);
            }
        }
        throw new DomainException("invalid_range", "射线路径超过步数预算");
    }

    private static double boundary(double start, int block, double delta) {
        if (delta == 0) {
            return Double.POSITIVE_INFINITY;
        }
        return ((delta > 0 ? block + 1 : block) - start) / delta;
    }

    public static BlockPos blockPosition(JsonObject value) {
        return new BlockPos(value.get("x").getAsInt(), value.get("y").getAsInt(), value.get("z").getAsInt());
    }

    public static JsonObject blockPosition(BlockPos value) {
        return Json.object("x", value.getX(), "y", value.getY(), "z", value.getZ());
    }

    public static Vec3d vector(JsonObject value) {
        return new Vec3d(value.get("x").getAsDouble(), value.get("y").getAsDouble(), value.get("z").getAsDouble());
    }

    public static JsonObject vector(Vec3d value) {
        return EntityReader.position(value.x, value.y, value.z);
    }

    private static JsonObject light(Chunk chunk, BlockPos position) {
        return Json.object("sky", chunk.getLightFor(EnumSkyBlock.SKY, position),
                "block", chunk.getLightFor(EnumSkyBlock.BLOCK, position));
    }

    private static JsonObject properties(IBlockState state) {
        JsonObject values = new JsonObject();
        for (Map.Entry<IProperty<?>, Comparable<?>> entry : state.getProperties().entrySet()) {
            values.addProperty(entry.getKey().getName(), propertyValue(entry.getKey(), entry.getValue()));
        }
        return values;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static String propertyValue(IProperty property, Comparable value) {
        return property.getName(value);
    }

    private static JsonObject tileSummary(TileEntity tile) {
        return tile == null ? Json.object("present", false)
                : Json.object("present", true, "class", tile.getClass().getName(),
                        "registryName", String.valueOf(TileEntity.getKey(tile.getClass())));
    }

    public static final class Scan implements JobManager.Task {

        private final QueryContext context;
        private final JsonObject arguments;
        private final PageStore pages;
        private final World world;
        private final BlockPos minimum;
        private final BlockPos maximum;
        private final JsonArray rows = new JsonArray();
        private final long volume;
        private long offset;
        private int unloaded;
        private int bytes;

        public Scan(QueryContext context, JsonObject arguments, PageStore pages) {
            this.context = context;
            this.arguments = arguments;
            this.pages = pages;
            world = context.world(arguments);
            if (arguments.has("min") && arguments.has("max")) {
                minimum = blockPosition(arguments.getAsJsonObject("min"));
                maximum = blockPosition(arguments.getAsJsonObject("max"));
            } else if (arguments.has("min") || arguments.has("max")) {
                throw new DomainException("invalid_range", "min/max 必须同时提供");
            } else {
                BlockPos center = arguments.has("center") ? blockPosition(arguments.getAsJsonObject("center"))
                        : context.player(Json.object()).getPosition();
                int radius = arguments.has("radius") ? arguments.get("radius").getAsInt() : 8;
                minimum = new BlockPos(center.getX() - radius,
                        Math.max(0, center.getY() - radius), center.getZ() - radius);
                maximum = new BlockPos(center.getX() + radius,
                        Math.min(255, center.getY() + radius), center.getZ() + radius);
            }
            volume = (long) (maximum.getX() - minimum.getX() + 1)
                    * (maximum.getY() - minimum.getY() + 1) * (maximum.getZ() - minimum.getZ() + 1);
            if (minimum.getX() > maximum.getX() || minimum.getY() > maximum.getY()
                    || minimum.getZ() > maximum.getZ() || volume > context.settings().maxScanBlocks || volume < 1) {
                throw new DomainException("invalid_range", "区域无效或超过 maxScanBlocks 预算");
            }
        }

        @Override
        public JsonObject step() {
            long deadline = context.stepDeadline();
            int processed = 0;
            while (offset < volume && processed++ < context.settings().maxBlocksPerTick) {
                BlockPos position = position(offset++);
                Chunk chunk = loadedChunk(world, position);
                if (chunk == null) {
                    unloaded++;
                } else if (matches(chunk.getBlockState(position))) {
                    JsonObject data = block(context, world, position, arguments);
                    int rowBytes = PageStore.byteSize(Json.GSON.toJson(data));
                    if (bytes + rowBytes > context.settings().maxCacheBytes
                            || rows.size() >= context.settings().maxResults) {
                        return finish(true);
                    }
                    rows.add(data);
                    bytes += rowBytes;
                }
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            return offset < volume ? null : finish(false);
        }

        private JsonObject finish(boolean truncated) {
            if (unloaded > 0) {
                context.missing("unloaded_blocks");
            }
            if (truncated) {
                context.missing("scan:result_budget");
            }
            JsonObject metadata = Json.object("dimension", world.provider.getDimension(),
                    "min", blockPosition(minimum), "max", blockPosition(maximum),
                    "rangeShape", "box", "scannedBlocks", offset, "requestedBlocks", volume,
                    "unloadedBlocks", unloaded, "scanTruncated", truncated, "loadedOnly", true);
            return pages.create("mc_world_query", arguments, rows, metadata, context.observation(), true);
        }

        private BlockPos position(long index) {
            int width = maximum.getX() - minimum.getX() + 1;
            int height = maximum.getY() - minimum.getY() + 1;
            return minimum.add((int) (index % width), (int) ((index / width) % height),
                    (int) (index / width / height));
        }

        private boolean matches(IBlockState state) {
            String id = String.valueOf(state.getBlock().getRegistryName());
            if (!arguments.has("includeAir") || !arguments.get("includeAir").getAsBoolean()) {
                if ("minecraft:air".equals(id)) {
                    return false;
                }
            }
            String namespace = QueryContext.string(arguments, "namespace", "");
            String registryName = QueryContext.string(arguments, "registryName", "");
            return (namespace.isEmpty() || id.startsWith(namespace + ":"))
                    && (registryName.isEmpty() || registryName.equals(id));
        }
    }
}
