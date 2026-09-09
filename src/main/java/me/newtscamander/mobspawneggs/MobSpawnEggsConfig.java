package me.newtscamander.mobspawneggs;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class MobSpawnEggsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter BACKUP_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int DEFAULT_LOG_RETENTION_DAYS = 15;

    static final String DEFAULT_JSON = """
            {
              "blacklistedWorlds": [],
              "debug-messages": false,
              "console-enabled": false,
              "spawn-log-enabled": true,
              "fail-spawn-log-enabled": true,
              "log-retention-days": 15
            }
            """;

    final Set<String> blacklistedWorlds;
    final boolean debugMessages;
    final boolean consoleEnabled;
    final boolean spawnLogEnabled;
    final boolean failSpawnLogEnabled;
    final int logRetentionDays;

    private MobSpawnEggsConfig(Set<String> blacklistedWorlds, boolean debugMessages,
                               boolean consoleEnabled, boolean spawnLogEnabled,
                               boolean failSpawnLogEnabled, int logRetentionDays) {
        this.blacklistedWorlds = Collections.unmodifiableSet(new LinkedHashSet<>(blacklistedWorlds));
        this.debugMessages = debugMessages;
        this.consoleEnabled = consoleEnabled;
        this.spawnLogEnabled = spawnLogEnabled;
        this.failSpawnLogEnabled = failSpawnLogEnabled;
        this.logRetentionDays = Math.max(0, logRetentionDays);
    }

    static MobSpawnEggsConfig defaults() {
        return new MobSpawnEggsConfig(Set.of(), false, false, true, true, DEFAULT_LOG_RETENTION_DAYS);
    }

    static MobSpawnEggsConfig load(Path path) throws IOException {
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
            Files.writeString(path, DEFAULT_JSON, StandardCharsets.UTF_8);
            return defaults();
        }

        try {
            JsonElement parsed = JsonParser.parseString(Files.readString(path, StandardCharsets.UTF_8));
            if (!parsed.isJsonObject()) throw new IllegalArgumentException("config root is not an object");

            JsonObject root = parsed.getAsJsonObject();
            Set<String> worlds = new LinkedHashSet<>();
            JsonArray worldArray = root.has("blacklistedWorlds") && root.get("blacklistedWorlds").isJsonArray()
                    ? root.getAsJsonArray("blacklistedWorlds") : new JsonArray();
            for (JsonElement value : worldArray) {
                if (!value.isJsonPrimitive()) continue;
                String world = normalizeWorld(value.getAsString());
                if (!world.isBlank()) worlds.add(world);
            }

            boolean legacy = root.has("usage") || (root.has("logging") && root.get("logging").isJsonObject()
                    && (root.getAsJsonObject("logging").has("direct") || root.getAsJsonObject("logging").has("throw")));
            JsonObject logging = root.has("logging") && root.get("logging").isJsonObject()
                    ? root.getAsJsonObject("logging") : null;
            JsonObject direct = logging != null && logging.has("direct") && logging.get("direct").isJsonObject()
                    ? logging.getAsJsonObject("direct") : null;

            boolean debug = root.has("debug-messages") ? bool(root, "debug-messages", false)
                    : logging != null && bool(logging, "debug-messages", false);
            boolean console = root.has("console-enabled") ? bool(root, "console-enabled", false)
                    : direct != null && bool(direct, "console-enabled", false);
            boolean spawnLog = root.has("spawn-log-enabled") ? bool(root, "spawn-log-enabled", true)
                    : direct == null || bool(direct, "spawn-log-enabled", true);
            boolean failLog = root.has("fail-spawn-log-enabled") ? bool(root, "fail-spawn-log-enabled", true)
                    : direct == null || bool(direct, "fail-spawn-log-enabled", true);

            boolean retentionValid = true;
            int retention = integer(root, "log-retention-days", DEFAULT_LOG_RETENTION_DAYS);
            if (!root.has("log-retention-days") || retention < 0) {
                retention = DEFAULT_LOG_RETENTION_DAYS;
                retentionValid = false;
            } else {
                try { root.get("log-retention-days").getAsInt(); }
                catch (Throwable ignored) { retention = DEFAULT_LOG_RETENTION_DAYS; retentionValid = false; }
            }

            MobSpawnEggsConfig config = new MobSpawnEggsConfig(worlds, debug, console, spawnLog, failLog, retention);
            if (legacy) {
                try {
                    Path backup = path.resolveSibling("config.full-backup-" + BACKUP_TIME.format(LocalDateTime.now()) + ".json");
                    Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
                } catch (Throwable ignored) {
                }
            }
            if (legacy || !retentionValid) Files.writeString(path, config.toPrettyJson(), StandardCharsets.UTF_8);
            return config;
        } catch (Throwable invalidConfig) {
            try {
                Path invalid = path.resolveSibling("config.invalid-" + BACKUP_TIME.format(LocalDateTime.now()) + ".json");
                Files.move(path, invalid, StandardCopyOption.REPLACE_EXISTING);
            } catch (Throwable ignored) {
            }
            Files.writeString(path, DEFAULT_JSON, StandardCharsets.UTF_8);
            return defaults();
        }
    }

    boolean isWorldBlacklisted(String worldName) {
        return blacklistedWorlds.contains(normalizeWorld(worldName));
    }

    boolean hasAnyFileLogging() {
        return debugMessages || spawnLogEnabled || failSpawnLogEnabled;
    }

    private String toPrettyJson() {
        JsonObject root = new JsonObject();
        JsonArray worlds = new JsonArray();
        blacklistedWorlds.forEach(worlds::add);
        root.add("blacklistedWorlds", worlds);
        root.addProperty("debug-messages", debugMessages);
        root.addProperty("console-enabled", consoleEnabled);
        root.addProperty("spawn-log-enabled", spawnLogEnabled);
        root.addProperty("fail-spawn-log-enabled", failSpawnLogEnabled);
        root.addProperty("log-retention-days", logRetentionDays);
        return GSON.toJson(root) + System.lineSeparator();
    }

    private static boolean bool(JsonObject object, String key, boolean fallback) {
        try { return object.has(key) ? object.get(key).getAsBoolean() : fallback; }
        catch (Throwable ignored) { return fallback; }
    }

    private static int integer(JsonObject object, String key, int fallback) {
        try { return object.has(key) ? object.get(key).getAsInt() : fallback; }
        catch (Throwable ignored) { return fallback; }
    }

    private static String normalizeWorld(String worldName) {
        return worldName == null ? "" : worldName.trim().toLowerCase(Locale.ROOT);
    }
}
