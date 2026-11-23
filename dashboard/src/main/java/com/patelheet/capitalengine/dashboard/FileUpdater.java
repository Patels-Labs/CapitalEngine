package com.patelheet.capitalengine.dashboard;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Brings a plugin's {@code config.yml} and {@code messages.yml} up to date after an upgrade without changing anything the owner set.
 * <ul>
 *   <li>Missing file: written, and a pristine copy is kept in {@code .defaults/}.</li>
 *   <li>Unedited since we wrote it (it still equals the pristine copy): replaced whole, so new wording and defaults arrive.</li>
 *   <li>Edited: every existing line stays. Keys the new version added are appended (flat files) or merged with their comments.</li>
 *   <li>Nothing missing: the file is not touched.</li>
 * </ul>
 * A file from before the pristine copy existed counts as edited, so it only ever gains keys.
 */
final class FileUpdater {
    private FileUpdater() {}

    /** Syncs {@code name} in the plugin's data folder with the copy inside the jar. */
    static void sync(JavaPlugin plugin, String name) {
        try (InputStream in = plugin.getResource(name)) {
            if (in == null) return;
            sync(plugin.getDataFolder(), name, new String(in.readAllBytes(), StandardCharsets.UTF_8), plugin.getPluginMeta().getVersion());
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("Could not update " + name + " (" + e.getMessage() + "); keeping the file as it is.");
        }
    }

    static void sync(File dir, String name, String jar, String version) throws IOException {
        File file = new File(dir, name), pristine = new File(dir, ".defaults/" + name);
        if (!file.exists()) { write(file, jar); write(pristine, jar); return; }
        String have = Files.readString(file.toPath());
        if (same(have, jar)) { write(pristine, jar); return; }
        if (pristine.exists() && same(Files.readString(pristine.toPath()), have)) { write(file, jar); write(pristine, jar); return; }
        addMissing(file, have, jar, version);
    }

    private static void addMissing(File file, String have, String jar, String version) throws IOException {
        YamlConfiguration mine = YamlConfiguration.loadConfiguration(new java.io.StringReader(have));
        YamlConfiguration theirs = YamlConfiguration.loadConfiguration(new java.io.StringReader(jar));
        List<String> missing = theirs.getKeys(true).stream().filter(k -> !theirs.isConfigurationSection(k) && !mine.contains(k, true)).toList();
        if (missing.isEmpty()) return;
        List<String> lines = jar.lines().toList();
        // new top-level entries that are one line each can be appended as they are, which leaves the owner's file byte for byte intact
        boolean appendable = missing.stream().allMatch(k -> !k.contains(".") && lineOf(lines, k) != null);
        if (appendable) {
            StringBuilder sb = new StringBuilder(have.endsWith("\n") ? have : have + "\n").append("\n# Added in ").append(version).append(":\n");
            missing.forEach(k -> sb.append(lineOf(lines, k)).append('\n'));
            Files.writeString(file.toPath(), sb.toString());
            return;
        }
        for (String k : missing) {
            mine.set(k, theirs.get(k));
            mine.setComments(k, theirs.getComments(k));
            mine.setInlineComments(k, theirs.getInlineComments(k));
        }
        mine.save(file);
    }

    /** The jar's single line for a top-level key ({@code key: value}), or null when the entry spans several lines. */
    private static String lineOf(List<String> lines, String key) {
        for (int i = 0; i < lines.size(); i++) {
            if (!lines.get(i).startsWith(key + ":")) continue;
            boolean continues = i + 1 < lines.size() && (lines.get(i + 1).startsWith(" ") || lines.get(i + 1).startsWith("\t"));
            return lines.get(i).substring(key.length() + 1).isBlank() || continues ? null : lines.get(i);
        }
        return null;
    }

    private static boolean same(String a, String b) { return a.replace("\r\n", "\n").strip().equals(b.replace("\r\n", "\n").strip()); }

    private static void write(File f, String text) throws IOException {
        f.getParentFile().mkdirs();
        Files.writeString(f.toPath(), text);
    }
}
