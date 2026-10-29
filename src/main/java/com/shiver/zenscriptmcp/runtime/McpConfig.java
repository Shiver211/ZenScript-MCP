package com.shiver.zenscriptmcp.runtime;

import com.google.gson.GsonBuilder;
import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/** Gson 配置模型；字段名称对应已有配置文件的 JSON 键。 */
public final class McpConfig {

    private static final String CONFIG_FILE = "config/zenscriptmcp.json";
    private static final int MAX_PORT = 65_535;
    private static final int MIN_REQUEST_BYTES = 1_024;
    private static final int MAX_REQUEST_BYTES = 1_048_576;
    private static final int TOKEN_RANDOM_BYTES = 32;
    private static final int MIN_TOKEN_LENGTH = 32;

    public boolean enabled = true;
    public HttpSettings http = new HttpSettings();
    public RuntimeSettings runtime = new RuntimeSettings();
    public QuerySettings queries = new QuerySettings();
    public EventSettings events = new EventSettings();
    public ScreenshotSettings screenshots = new ScreenshotSettings();

    public static McpConfig load(Path gameDirectory) throws IOException {
        Path configPath = gameDirectory.resolve(CONFIG_FILE);
        Files.createDirectories(configPath.getParent());

        McpConfig config;
        if (Files.exists(configPath)) {
            config = readConfig(configPath);
        } else {
            config = new McpConfig();
            writeConfig(configPath, config);
        }

        if (config == null || config.http == null || config.runtime == null
                || config.queries == null || config.events == null || config.screenshots == null) {
            throw new IOException("MCP 配置不能为空");
        }

        config.validate();
        return config;
    }

    public void validate() throws IOException {
        validateHttpSettings();
        validateRuntimeSettings();
        if (queries.defaultLimit < 1 || queries.defaultLimit > queries.maxLimit || queries.maxLimit > 1000
                || queries.maxBlocksPerTick < 1 || queries.maxScanBlocks < 1 || queries.maxResults < 1
                || queries.maxPageBytes < 4096 || queries.maxPageBytes > 524_288
                || queries.retainedPages < 1 || queries.maxCacheBytes < queries.maxPageBytes
                || queries.maxNbtNodes < 1 || queries.maxNbtBytes < 1024 || queries.maxNbtDepth < 1
                || events.capacity < 1 || screenshots.maxWidth < 1 || screenshots.maxWidth > 4096
                || screenshots.maxHeight < 1 || screenshots.maxHeight > 4096) {
            throw new IOException("查询、事件或截图预算无效");
        }
    }

    public String loadToken(Path gameDirectory) throws IOException {
        Path tokenPath = gameDirectory.resolve(http.tokenFile).normalize();
        Files.createDirectories(tokenPath.toAbsolutePath().getParent());

        if (!Files.exists(tokenPath)) {
            createTokenFile(tokenPath);
        }

        String token = new String(Files.readAllBytes(tokenPath), StandardCharsets.UTF_8).trim();
        if (token.length() < MIN_TOKEN_LENGTH || !token.matches("[A-Za-z0-9_-]+")) {
            throw new IOException("令牌文件必须包含至少 32 位 URL 安全字符");
        }

        return token;
    }

    private static McpConfig readConfig(Path configPath) throws IOException {
        try (Reader reader = Files.newBufferedReader(configPath, StandardCharsets.UTF_8)) {
            return new GsonBuilder().create().fromJson(reader, McpConfig.class);
        }
    }

    private static void writeConfig(Path configPath, McpConfig config) throws IOException {
        try (Writer writer = Files.newBufferedWriter(configPath, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(config, writer);
        }
    }

    private void validateHttpSettings() throws IOException {
        if (http.host == null || !InetAddress.getByName(http.host).isLoopbackAddress()) {
            throw new IOException("仅支持回环地址");
        }

        boolean invalidPort = http.port < 1 || http.port > MAX_PORT;
        boolean invalidRequestLimit = http.maxRequestBytes < MIN_REQUEST_BYTES
                || http.maxRequestBytes > MAX_REQUEST_BYTES;
        boolean invalidTokenFile = http.tokenFile == null || http.tokenFile.trim().isEmpty();

        if (invalidPort || invalidRequestLimit || http.allowedOrigins == null || invalidTokenFile) {
            throw new IOException("MCP 配置数值无效");
        }
    }

    private void validateRuntimeSettings() throws IOException {
        if (runtime.maxReadTasksPerTick < 1
                || runtime.readBudgetMillis < 1
                || runtime.defaultTimeoutMillis < 1
                || runtime.completedJobsRetained < 1) {
            throw new IOException("MCP 配置数值无效");
        }
    }

    private static void createTokenFile(Path tokenPath) throws IOException {
        byte[] randomBytes = new byte[TOKEN_RANDOM_BYTES];
        new SecureRandom().nextBytes(randomBytes);

        byte[] encodedToken = Base64.getUrlEncoder().withoutPadding().encode(randomBytes);
        Files.write(tokenPath, encodedToken, StandardOpenOption.CREATE_NEW);
    }

    public static final class HttpSettings {

        public String host = "127.0.0.1";
        public int port = 8765;
        public String tokenFile = "config/zenscriptmcp.token";
        public List<String> allowedOrigins = new ArrayList<>();
        public int maxRequestBytes = 262_144;
    }

    public static final class RuntimeSettings {

        public int maxReadTasksPerTick = 16;
        public int readBudgetMillis = 2;
        public int defaultTimeoutMillis = 15_000;
        public int completedJobsRetained = 128;
    }

    public static final class QuerySettings {

        public int defaultLimit = 100;
        public int maxLimit = 1000;
        public int maxBlocksPerTick = 2048;
        public int maxScanBlocks = 262_144;
        public int maxResults = 10_000;
        public int maxPageBytes = 262_144;
        public int retainedPages = 32;
        public int maxCacheBytes = 16_777_216;
        public int maxNbtNodes = 4096;
        public int maxNbtBytes = 131_072;
        public int maxNbtDepth = 32;
    }

    public static final class EventSettings {

        public int capacity = 5000;
    }

    public static final class ScreenshotSettings {

        public int maxWidth = 1280;
        public int maxHeight = 720;
    }
}
