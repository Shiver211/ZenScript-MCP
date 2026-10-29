package com.shiver.zenscriptmcp.game.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.shiver.zenscriptmcp.model.DomainException;
import com.shiver.zenscriptmcp.model.Json;
import com.shiver.zenscriptmcp.runtime.JobManager;
import com.shiver.zenscriptmcp.runtime.McpConfig;
import com.shiver.zenscriptmcp.runtime.SnapshotStore;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.imageio.ImageIO;
import net.minecraft.client.Minecraft;
import net.minecraft.client.shader.Framebuffer;
import net.minecraft.util.ScreenShotHelper;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.opengl.Display;

/** 渲染事件中复制像素；后台只接触独立 BufferedImage 和 JSON DTO。 */
public final class ScreenshotService implements AutoCloseable {

    private static final int MAX_CAPTURE_PIXELS = 16_777_216;
    private static final int MAX_RETAINED_IMAGES = 4;
    private static final int MAX_RETAINED_CHARACTERS = 16_777_216;
    private static final long RETENTION_NANOS = TimeUnit.MINUTES.toNanos(5);

    private final McpConfig.ScreenshotSettings settings;
    private final ScreenReader screens;
    private final Path gameDirectory;
    private final SnapshotStore snapshots;
    private final Map<String, ImageEntry> images = new LinkedHashMap<>();
    private final ThreadPoolExecutor encoder = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(1), runnable -> {
                Thread thread = new Thread(runnable, "zenscriptmcp-image");
                thread.setDaemon(true);
                return thread;
            });
    private Request pending;
    private int retainedCharacters;

    public ScreenshotService(McpConfig.ScreenshotSettings settings, ScreenReader screens,
            Path gameDirectory, SnapshotStore snapshots) {
        this.settings = settings;
        this.screens = screens;
        this.gameDirectory = gameDirectory;
        this.snapshots = snapshots;
    }

    public JobManager.Task task(JsonObject arguments) {
        return new JobManager.Task() {
            private Request request;
            private boolean completed;

            @Override
            public JsonObject step() {
                if (request == null) {
                    if (pending != null) {
                        throw new DomainException("busy", "已有截图正在采集或编码");
                    }
                    request = new Request(Json.copy(arguments));
                    pending = request;
                }
                if (request.error != null) {
                    throw request.error;
                }
                if (request.result == null) {
                    return null;
                }
                completed = true;
                return Json.parse(request.result);
            }

            @Override
            public void cleanup() {
                if (request != null) {
                    request.cancelled = !completed;
                    if (!completed && request.encoding != null) {
                        request.encoding.cancel(true);
                    }
                    if (pending == request) {
                        pending = null;
                    }
                }
            }
        };
    }

    @SubscribeEvent
    public void onRender(TickEvent.RenderTickEvent event) {
        Request request = pending;
        if (event.phase != TickEvent.Phase.END || request == null || request.captured || request.cancelled) {
            return;
        }
        request.captured = true;
        try {
            Minecraft minecraft = Minecraft.getMinecraft();
            if (!Display.isCreated() || !Display.isVisible()
                    || minecraft.displayWidth < 1 || minecraft.displayHeight < 1) {
                throw new DomainException("render_unavailable", "窗口最小化或渲染上下文不可用");
            }
            Framebuffer framebuffer = minecraft.getFramebuffer();
            long pixels = (long) framebuffer.framebufferTextureWidth * framebuffer.framebufferTextureHeight;
            if (pixels > MAX_CAPTURE_PIXELS) {
                throw new DomainException("result_too_large", "原始 framebuffer 超过像素预算");
            }
            BufferedImage image = ScreenShotHelper.createScreenshot(
                    minecraft.displayWidth, minecraft.displayHeight, framebuffer);
            if (image.getWidth() > minecraft.displayWidth || image.getHeight() > minecraft.displayHeight) {
                image = image.getSubimage(0, image.getHeight() - minecraft.displayHeight,
                        minecraft.displayWidth, minecraft.displayHeight);
            }
            JsonObject client = snapshots.status().getAsJsonObject("client");
            long tick = client.has("tick") ? client.get("tick").getAsLong() : 0;
            screens.update(snapshots.worldEpoch(), tick);
            JsonObject data = screens.dimensions();
            data.addProperty("capturedAt", Instant.now().toString());
            data.addProperty("focused", Display.isActive());
            data.addProperty("visible", Display.isVisible());
            data.addProperty("originalWidth", image.getWidth());
            data.addProperty("originalHeight", image.getHeight());
            JsonObject observation = snapshots.observation("client", tick, new JsonArray());
            BufferedImage capturedImage = image;
            request.encoding = encoder.submit(() -> encode(request, capturedImage, data, observation));
        } catch (RuntimeException exception) {
            request.error = exception instanceof DomainException ? (DomainException) exception
                    : new DomainException("render_unavailable", exception.toString());
        }
    }

    public synchronized JsonArray content(JsonObject domainResult) {
        JsonArray content = new JsonArray();
        JsonObject payload = domainResult;
        if (domainResult.has("job")) {
            JsonObject job = domainResult.getAsJsonObject("job");
            if (!job.has("result") || !job.get("result").isJsonObject()) {
                return content;
            }
            payload = job.getAsJsonObject("result");
        }
        if (!payload.has("data") || !payload.getAsJsonObject("data").has("imageId")) {
            return content;
        }
        JsonObject data = payload.getAsJsonObject("data");
        String id = data.get("imageId").getAsString();
        expire();
        ImageEntry image = images.get(id);
        data.addProperty("imageAvailable", image != null);
        if (image != null) {
            content.add(Json.object("type", "image", "mimeType", "image/png", "data", image.base64));
        }
        return content;
    }

    private void encode(Request request, BufferedImage image, JsonObject data, JsonObject observation) {
        try {
            int widthLimit = request.arguments.has("maxWidth")
                    ? Math.min(settings.maxWidth, request.arguments.get("maxWidth").getAsInt()) : settings.maxWidth;
            int heightLimit = request.arguments.has("maxHeight")
                    ? Math.min(settings.maxHeight, request.arguments.get("maxHeight").getAsInt()) : settings.maxHeight;
            double scale = Math.min(1, Math.min((double) widthLimit / image.getWidth(),
                    (double) heightLimit / image.getHeight()));
            int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
            int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
            BufferedImage output = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = output.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                graphics.drawImage(image, 0, 0, width, height, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            ImageIO.write(output, "png", bytes);
            if (request.cancelled || Thread.currentThread().isInterrupted()) {
                return;
            }
            String id = UUID.randomUUID().toString();
            byte[] png = bytes.toByteArray();
            if (request.arguments.has("keepFile") && request.arguments.get("keepFile").getAsBoolean()) {
                Path path = gameDirectory.resolve("screenshots/zenscriptmcp/" + id + ".png");
                Files.createDirectories(path.getParent());
                Files.write(path, png);
                data.addProperty("path", path.toAbsolutePath().toString());
            }
            retain(id, Base64.getEncoder().encodeToString(png));
            data.addProperty("imageId", id);
            data.addProperty("width", width);
            data.addProperty("height", height);
            data.addProperty("guiToImageX", (double) width / data.get("guiWidth").getAsInt());
            data.addProperty("guiToImageY", (double) height / data.get("guiHeight").getAsInt());
            request.result = Json.GSON.toJson(Json.object("observation", observation, "data", data));
        } catch (IOException | RuntimeException exception) {
            request.error = new DomainException("screenshot_failed", exception.toString());
        }
    }

    private synchronized void retain(String id, String image) {
        expire();
        if (image.length() > MAX_RETAINED_CHARACTERS) {
            throw new DomainException("result_too_large", "PNG 超过图片缓存预算");
        }
        while (!images.isEmpty() && (images.size() >= MAX_RETAINED_IMAGES
                || retainedCharacters + image.length() > MAX_RETAINED_CHARACTERS)) {
            Iterator<ImageEntry> iterator = images.values().iterator();
            retainedCharacters -= iterator.next().base64.length();
            iterator.remove();
        }
        images.put(id, new ImageEntry(image));
        retainedCharacters += image.length();
    }

    private void expire() {
        Iterator<ImageEntry> iterator = images.values().iterator();
        while (iterator.hasNext()) {
            ImageEntry image = iterator.next();
            if (System.nanoTime() - image.createdNanos > RETENTION_NANOS) {
                retainedCharacters -= image.base64.length();
                iterator.remove();
            }
        }
    }

    @Override
    public void close() {
        encoder.shutdownNow();
        synchronized (this) {
            images.clear();
            retainedCharacters = 0;
        }
    }

    private static final class Request {

        private final JsonObject arguments;
        private boolean captured;
        private volatile boolean cancelled;
        private volatile String result;
        private volatile DomainException error;
        private Future<?> encoding;

        private Request(JsonObject arguments) {
            this.arguments = arguments;
        }
    }

    private static final class ImageEntry {

        private final String base64;
        private final long createdNanos = System.nanoTime();

        private ImageEntry(String base64) {
            this.base64 = base64;
        }
    }
}
