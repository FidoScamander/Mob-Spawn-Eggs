package me.newtscamander.mobspawneggs;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.Interaction;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MobSpawnEggsPlugin extends JavaPlugin {
    private static final Pattern COLOR_TAG = Pattern.compile(
            "<color\\s+is=\"([^\"]+)\">(.*?)</color>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Set<String> BUNDLED = Set.of(
            "en-US", "it-IT", "de-DE", "es-ES", "fr-FR", "pt-BR", "ru-RU", "tr-TR");

    private static volatile MobSpawnEggsPlugin instance;

    private MobSpawnEggsConfig config = MobSpawnEggsConfig.defaults();
    private SpawnAuditService auditService;

    public MobSpawnEggsPlugin(JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        instance = this;
        initializeFiles();
        getCodecRegistry(Interaction.CODEC).register(
                "MSESpawnNPCDirect",
                SpawnNPCDirectInteraction.class,
                SpawnNPCDirectInteraction.CODEC);
        debug("Registered interaction: MSESpawnNPCDirect");
    }

    private void initializeFiles() {
        try {
            Path data = getDataDirectory();
            Path parent = data == null ? null : data.getParent();
            Path root = parent != null ? parent.resolve("MobSpawnEggs") : data;
            if (root == null) root = Path.of("mods", "MobSpawnEggs");

            Files.createDirectories(root);
            config = MobSpawnEggsConfig.load(root.resolve("config.json"));
            Path logs = root.resolve("logs");
            if (config.hasAnyFileLogging()) Files.createDirectories(logs);
            auditService = new SpawnAuditService(this, logs, config);

            debug("Data directory: " + root.toAbsolutePath());
            debug("Config: blacklistedWorlds=" + config.blacklistedWorlds
                    + ", debug=" + config.debugMessages
                    + ", console=" + config.consoleEnabled
                    + ", spawnLog=" + config.spawnLogEnabled
                    + ", failSpawnLog=" + config.failSpawnLogEnabled);
            debug("Bundled locales: " + BUNDLED);
        } catch (Throwable error) {
            try {
                getLogger().at(Level.WARNING).log("%s", "MobSpawnEggs initialization failed: " + error.getMessage());
            } catch (Throwable ignored) {
            }
            config = MobSpawnEggsConfig.defaults();
            auditService = new SpawnAuditService(this, Path.of("mods", "MobSpawnEggs", "logs"), config);
        }
    }

    static boolean isWorldBlacklisted(String worldName) {
        MobSpawnEggsPlugin plugin = instance;
        return plugin != null && plugin.config != null && plugin.config.isWorldBlacklisted(worldName);
    }

    static void auditSpawn(String entityId, String player, String world, double x, double y, double z) {
        MobSpawnEggsPlugin plugin = instance;
        if (plugin != null && plugin.auditService != null) {
            plugin.auditService.spawn(entityId, player, world, x, y, z);
        }
    }

    static void auditFailSpawn(String entityId, String player, String world,
                               double x, double y, double z, String result) {
        MobSpawnEggsPlugin plugin = instance;
        if (plugin != null && plugin.auditService != null) {
            plugin.auditService.failSpawn(entityId, player, world, x, y, z, result);
        }
    }

    static void debug(String message) {
        MobSpawnEggsPlugin plugin = instance;
        if (plugin != null && plugin.auditService != null) plugin.auditService.debug(message);
    }

    static void warning(String message, Throwable error) {
        MobSpawnEggsPlugin plugin = instance;
        if (plugin != null && plugin.auditService != null) plugin.auditService.warning(message, error);
    }

    static void sendTranslated(PlayerRef player, String key) {
        if (player == null) return;
        try {
            String locale = resolveLocale(player.getLanguage());
            String value = lookup(locale, key);
            if (value == null && !"en-US".equals(locale)) value = lookup("en-US", key);
            player.sendMessage(value == null ? Message.translation(key) : parseLocalizedFormatting(value));
        } catch (Throwable error) {
            debug("Translation lookup failed for " + key + " ("
                    + error.getClass().getSimpleName() + ": " + error.getMessage() + ")");
            try { player.sendMessage(Message.translation(key)); }
            catch (Throwable ignored) { }
        }
    }

    static String resolveLocale(String language) {
        if (language == null || language.isBlank()) return "en-US";
        String canonical = canonicalizeLocale(language.trim().replace('_', '-'));
        String normalized = canonical.toLowerCase(Locale.ROOT);
        if (normalized.equals("it") || normalized.startsWith("it-")) return "it-IT";
        if (normalized.equals("de") || normalized.startsWith("de-")) return "de-DE";
        if (normalized.equals("fr") || normalized.startsWith("fr-")) return "fr-FR";
        if (normalized.equals("en") || normalized.startsWith("en-")) return "en-US";
        if (normalized.equals("es") || normalized.startsWith("es-")) return "es-ES";
        if (normalized.equals("pt") || normalized.startsWith("pt-")) return "pt-BR";
        if (normalized.equals("ru") || normalized.startsWith("ru-")) return "ru-RU";
        if (normalized.equals("tr") || normalized.startsWith("tr-")) return "tr-TR";
        return canonical;
    }

    private static String canonicalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) return "en-US";
        String[] parts = locale.split("-");
        List<String> normalized = new ArrayList<>(parts.length);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (i == 0) normalized.add(part.toLowerCase(Locale.ROOT));
            else if (part.length() == 2 && part.chars().allMatch(Character::isLetter))
                normalized.add(part.toUpperCase(Locale.ROOT));
            else if (part.length() == 4 && part.chars().allMatch(Character::isLetter))
                normalized.add(Character.toUpperCase(part.charAt(0)) + part.substring(1).toLowerCase(Locale.ROOT));
            else normalized.add(part);
        }
        return String.join("-", normalized);
    }

    private static String lookup(String locale, String key) {
        I18nModule i18n = I18nModule.get();
        if (i18n == null) return null;
        String value = i18n.getMessage(locale, key);
        if (value == null && key != null && key.startsWith("server."))
            value = i18n.getMessage(locale, key.substring("server.".length()));
        return value;
    }

    private static Message parseLocalizedFormatting(String input) {
        if (input == null || input.isEmpty()) return Message.empty();
        Matcher matcher = COLOR_TAG.matcher(input);
        Message output = Message.empty();
        int cursor = 0;
        boolean formatted = false;
        while (matcher.find()) {
            if (matcher.start() > cursor) output.insert(Message.raw(input.substring(cursor, matcher.start())));
            output.insert(Message.raw(matcher.group(2)).color(matcher.group(1)));
            cursor = matcher.end();
            formatted = true;
        }
        if (!formatted) return Message.raw(input);
        if (cursor < input.length()) output.insert(Message.raw(input.substring(cursor)));
        return output;
    }
}
