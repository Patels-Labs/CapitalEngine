package com.patelheet.capitalengine.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/** Every message key the code asks for exists, and every message is valid MiniMessage with legal placeholder names. */
class MessagesTest {
    @Test void everyKeyUsedInCodeIsDefined() throws IOException {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/messages.yml").toFile());
        Pattern use = Pattern.compile("(?:msgs|messages)\\.(?:send|get|plain)\\([^\"\\n]*\"([a-z0-9.-]+)\"|\\bsend\\(\\w+, \"([a-z0-9.-]+)\"");
        Set<String> missing = new HashSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher m = use.matcher(Files.readString(f));
                while (m.find()) {
                    String key = m.group(1) != null ? m.group(1) : m.group(2);
                    if (!yaml.contains(key)) missing.add(key + " (" + f.getFileName() + ")");
                }
            }
        }
        assertEquals(Set.of(), missing);
    }

    @Test void placeholdersAreLowerCaseSoMiniMessageAcceptsThem() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/messages.yml").toFile());
        Pattern tag = Pattern.compile("<([A-Za-z][A-Za-z0-9_-]*)>");
        for (String key : yaml.getKeys(true)) {
            String v = yaml.getString(key);
            if (v == null || yaml.isConfigurationSection(key)) continue;
            Matcher m = tag.matcher(v);
            while (m.find()) assertEquals(m.group(1).toLowerCase(), m.group(1), key + " has a placeholder MiniMessage would reject");
        }
    }

    @Test void playerFacingTextAvoidsEconomistJargon() {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(Path.of("src/main/resources/messages.yml").toFile());
        for (String banned : new String[] {"CPI", "M1", "VWAP", "Taylor", "inflation", "ledger"}) {
            for (String key : yaml.getKeys(true)) {
                if (key.startsWith("ce.") || key.startsWith("audit.") || key.startsWith("import.")) continue;   // owner tools may say "ledger"
                String v = yaml.getString(key);
                assertTrue(v == null || !v.contains(banned), key + " mentions " + banned);
            }
        }
    }
}
