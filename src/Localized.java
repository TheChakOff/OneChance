import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

final class Localized {
    private final Map<String, String> messages = new HashMap<>();

    Localized(Main plugin) {
        String locale = plugin.getConfig().getString("language", "ru").toLowerCase(Locale.ROOT);
        int column = List.of("ru", "en", "uk", "es").indexOf(locale);
        if (column < 0) {
            plugin.getLogger().warning("Unknown language " + locale + ". Using ru.");
            return;
        }
        if (column == 0) return;
        try (var reader = new BufferedReader(new InputStreamReader(
                Objects.requireNonNull(plugin.getResource("translations.txt")), StandardCharsets.UTF_8))) {
            for (String line; (line = reader.readLine()) != null;) {
                String[] fields = line.split("\\|", -1);
                if (fields.length == 4) messages.put(unescape(fields[0]), unescape(fields[column]));
            }
        } catch (IOException e) {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Cannot load translations", e);
        }
    }

    private static String unescape(String text) {
        return text.replace("\\n", "\n").replace("\\s", " ");
    }

    String text(String source) {
        return messages.getOrDefault(source, source);
    }

    static Component colored(String text, NamedTextColor fallback) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(text)
            .colorIfAbsent(fallback).decoration(TextDecoration.ITALIC, false);
    }
}
