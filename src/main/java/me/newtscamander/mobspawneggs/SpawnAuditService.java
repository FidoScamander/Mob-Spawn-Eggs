package me.newtscamander.mobspawneggs;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.logging.Level;
import java.util.regex.Pattern;

final class SpawnAuditService {
    private static final Object FILE_LOCK = new Object();
    private static final DateTimeFormatter LINE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Pattern LEGACY_DAILY_LOG = Pattern.compile("(?:spawns|failed-spawns|debug)-\\d{4}-\\d{2}-\\d{2}\\.log");
    private static final String LOG_FILE_NAME = "MobSpawnEggs.log";

    private final MobSpawnEggsPlugin plugin;
    private final Path logsDirectory;
    private final Path logFile;
    private final MobSpawnEggsConfig config;
    private LocalDate lastMaintenanceDate;

    SpawnAuditService(MobSpawnEggsPlugin plugin, Path logsDirectory, MobSpawnEggsConfig config) {
        this.plugin = plugin;
        this.logsDirectory = logsDirectory;
        this.logFile = logsDirectory.resolve(LOG_FILE_NAME);
        this.config = config;
        maintainLog(true);
    }

    void spawn(String entityId, String player, String world, double x, double y, double z) {
        String message = String.format(Locale.ROOT, "SPAWN %s DIRECT by %s %s %.2f/%.2f/%.2f",
                safe(entityId), safe(player), safe(world), x, y, z);
        if (config.consoleEnabled) info(message);
        if (config.spawnLogEnabled) append(message);
    }

    void failSpawn(String entityId, String player, String world, double x, double y, double z, String result) {
        String message = String.format(Locale.ROOT, "FAIL_SPAWN %s DIRECT by %s %s %.2f/%.2f/%.2f result=%s",
                safe(entityId), safe(player), safe(world), x, y, z, safe(result));
        if (config.consoleEnabled) info(message);
        if (config.failSpawnLogEnabled) append(message);
    }

    void debug(String message) {
        if (!config.debugMessages) return;
        info("[MobSpawnEggs] DEBUG " + safe(message));
        append("DEBUG " + safe(message));
    }

    void warning(String message, Throwable error) {
        String text = error == null ? message
                : message + " (" + error.getClass().getSimpleName() + ": " + error.getMessage() + ")";
        if (plugin != null) plugin.getLogger().at(Level.WARNING).log("%s", text);
        else System.err.println(text);
        if (config.debugMessages) append("WARN " + safe(text));
    }

    private void info(String message) {
        if (plugin != null) plugin.getLogger().at(Level.INFO).log("%s", message);
        else System.out.println(message);
    }

    private void append(String message) {
        synchronized (FILE_LOCK) {
            try {
                Files.createDirectories(logsDirectory);
                maintainLogLocked(false);
                String line = LINE_TIME.format(LocalDateTime.now()) + " " + message + System.lineSeparator();
                Files.writeString(logFile, line, StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
            } catch (IOException e) {
                logIoWarning("Unable to write log", e);
            }
        }
    }

    private void maintainLog(boolean force) {
        synchronized (FILE_LOCK) {
            try { maintainLogLocked(force); }
            catch (IOException e) { logIoWarning("Unable to maintain log", e); }
        }
    }

    private void maintainLogLocked(boolean force) throws IOException {
        LocalDate today = LocalDate.now();
        if (!force && today.equals(lastMaintenanceDate)) return;

        Files.createDirectories(logsDirectory);
        List<Path> legacyFiles = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(logsDirectory, "*.log")) {
            for (Path path : stream) {
                if (LEGACY_DAILY_LOG.matcher(path.getFileName().toString()).matches()) legacyFiles.add(path);
            }
        }

        List<String> lines = new ArrayList<>();
        if (Files.exists(logFile)) lines.addAll(Files.readAllLines(logFile, StandardCharsets.UTF_8));
        for (Path legacy : legacyFiles) lines.addAll(Files.readAllLines(legacy, StandardCharsets.UTF_8));

        int originalSize = lines.size();
        if (config.logRetentionDays > 0) {
            LocalDateTime cutoff = LocalDateTime.now().minusDays(config.logRetentionDays);
            lines.removeIf(line -> isOlderThan(line, cutoff));
        }

        boolean changed = !legacyFiles.isEmpty() || lines.size() != originalSize;
        if (!legacyFiles.isEmpty()) Collections.sort(lines);
        if (changed) {
            writeAtomically(lines);
            for (Path legacy : legacyFiles) {
                try { Files.deleteIfExists(legacy); }
                catch (IOException e) { logIoWarning("Unable to delete legacy log " + legacy.getFileName(), e); }
            }
        }
        lastMaintenanceDate = today;
    }

    private void writeAtomically(List<String> lines) throws IOException {
        Path temp = logsDirectory.resolve("MobSpawnEggs.log.tmp");
        Files.write(temp, lines, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
        try {
            Files.move(temp, logFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(temp, logFile, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static boolean isOlderThan(String line, LocalDateTime cutoff) {
        if (line == null || line.length() < 19) return false;
        try { return LocalDateTime.parse(line.substring(0, 19), LINE_TIME).isBefore(cutoff); }
        catch (DateTimeParseException ignored) { return false; }
    }

    private void logIoWarning(String message, IOException error) {
        String text = message + ": " + error.getMessage();
        if (plugin != null) plugin.getLogger().at(Level.WARNING).log("%s", text);
        else System.err.println(text);
    }

    private static String safe(String value) {
        return value == null || value.isBlank() ? "unknown" : value;
    }
}
