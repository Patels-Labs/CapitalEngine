package com.patelheet.capitalengine.core;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Upgrading must add what is new and never change what the owner set. */
class FileUpdaterTest {
    @TempDir Path dir;

    private File f(String n) { return dir.resolve(n).toFile(); }
    private String read(String n) throws Exception { return Files.readString(f(n).toPath()); }
    private YamlConfiguration yaml(String n) { return YamlConfiguration.loadConfiguration(f(n)); }

    private static final String V1 = "prefix: \"[Market] \"\nhello: \"Hello\"\n";
    private static final String V2 = "prefix: \"[Market] \"\nhello: \"Welcome\"\ngoodbye: \"Goodbye <name>\"\n";

    @Test void aMissingFileIsWrittenWithAPristineCopy() throws Exception {
        FileUpdater.sync(dir.toFile(), "messages.yml", V1, "1.0.0");
        assertEquals(V1, read("messages.yml"));
        assertEquals(V1, read(".defaults/messages.yml"));
    }

    @Test void aFileTheOwnerNeverEditedIsReplacedWholeSoNewWordingArrives() throws Exception {
        FileUpdater.sync(dir.toFile(), "messages.yml", V1, "1.0.0");
        FileUpdater.sync(dir.toFile(), "messages.yml", V2, "1.1.0");
        assertEquals(V2, read("messages.yml"));
        assertEquals(V2, read(".defaults/messages.yml"));
    }

    @Test void anEditedFileKeepsEveryEditAndOnlyGainsTheNewKeys() throws Exception {
        FileUpdater.sync(dir.toFile(), "messages.yml", V1, "1.0.0");
        Files.writeString(f("messages.yml").toPath(), read("messages.yml").replace("Hello", "Bonjour"));
        FileUpdater.sync(dir.toFile(), "messages.yml", V2, "1.1.0");
        var y = yaml("messages.yml");
        assertEquals("Bonjour", y.getString("hello"), "the owner's edit survives, even though the new version words it differently");
        assertEquals("Goodbye <name>", y.getString("goodbye"), "the new key arrives");
        assertTrue(read("messages.yml").startsWith("prefix: \"[Market] \"\nhello: \"Bonjour\"\n"), "existing lines are untouched, byte for byte");
        assertTrue(read("messages.yml").contains("# Added in 1.1.0:"));

        String once = read("messages.yml");
        FileUpdater.sync(dir.toFile(), "messages.yml", V2, "1.1.0");
        assertEquals(once, read("messages.yml"), "running again changes nothing");
    }

    @Test void aFileWithNothingMissingIsNotTouched() throws Exception {
        FileUpdater.sync(dir.toFile(), "messages.yml", V2, "1.1.0");
        String custom = V2.replace("Welcome", "Hi") + "# my own note\n";
        Files.writeString(f("messages.yml").toPath(), custom);
        FileUpdater.sync(dir.toFile(), "messages.yml", V2, "1.2.0");
        assertEquals(custom, read("messages.yml"));
    }

    @Test void aFileFromBeforeThePristineCopyExistedOnlyEverGainsKeys() throws Exception {
        Files.writeString(f("messages.yml").toPath(), V1.replace("Hello", "Hola"));   // an old install: no .defaults folder
        FileUpdater.sync(dir.toFile(), "messages.yml", V2, "1.1.0");
        assertEquals("Hola", yaml("messages.yml").getString("hello"));
        assertEquals("Goodbye <name>", yaml("messages.yml").getString("goodbye"));
        assertFalse(f(".defaults/messages.yml").exists(), "we can't prove it was never edited, so it is never replaced whole");
    }

    @Test void nestedConfigGainsNewSettingsAndSectionsWithTheirCommentsAndKeepsTheOwnersValues() throws Exception {
        String v1 = "# Settings\nmaker:\n  k: 0.5\n  rate: 0.02\n";
        String v2 = "# Settings\nmaker:\n  k: 0.5\n  rate: 0.02\n  # how many a day\n  daily: 1280\n# New in this version\nsearch:\n  enabled: true\n";
        FileUpdater.sync(dir.toFile(), "config.yml", v1, "1.0.0");
        Files.writeString(f("config.yml").toPath(), read("config.yml").replace("k: 0.5", "k: 0.9"));
        FileUpdater.sync(dir.toFile(), "config.yml", v2, "1.1.0");
        var y = yaml("config.yml");
        assertEquals(0.9, y.getDouble("maker.k"), "the owner's value survives");
        assertEquals(1280, y.getInt("maker.daily"), "a new setting inside an existing section arrives");
        assertTrue(y.getBoolean("search.enabled"), "a whole new section arrives");
        assertTrue(y.getComments("maker.daily").stream().anyMatch(c -> c != null && c.contains("how many a day")), "with its comment");
    }
}
