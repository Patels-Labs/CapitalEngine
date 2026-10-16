package com.patelheet.capitalengine.core;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

/** Every player-facing string lives in {@code messages.yml} (MiniMessage). Missing keys fall back to the jar's copy. */
public final class Messages {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private final JavaPlugin plugin;
    private YamlConfiguration yaml;

    public Messages(JavaPlugin plugin) { this.plugin = plugin; reload(); }

    public void reload() {
        FileUpdater.sync(plugin, "messages.yml");   // adds new entries after an upgrade, never changes what the owner edited
        File f = new File(plugin.getDataFolder(), "messages.yml");
        yaml = YamlConfiguration.loadConfiguration(f);
        var in = plugin.getResource("messages.yml");
        if (in != null) yaml.setDefaults(YamlConfiguration.loadConfiguration(new InputStreamReader(in, StandardCharsets.UTF_8)));
    }

    /** {@code kv} is name, value, name, value... Values that are Components are inserted as they are; everything else is plain text (never parsed). */
    public Component get(String key, Object... kv) {
        TagResolver.Builder b = TagResolver.builder();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            String name = String.valueOf(kv[i]);
            b.resolver(kv[i + 1] instanceof Component c ? Placeholder.component(name, c) : Placeholder.unparsed(name, String.valueOf(kv[i + 1])));
        }
        String raw = yaml.getString(key);   // no explicit default here, so a key missing from the owner's file falls back to the jar's text
        return MM.deserialize(raw != null ? raw : "<red>Missing message: " + key + "</red>", b.build());
    }

    /** Same, as plain text (for memos that are stored in the ledger). */
    public String plain(String key, Object... kv) { return PlainTextComponentSerializer.plainText().serialize(get(key, kv)); }

    public void send(CommandSender to, String key, Object... kv) { to.sendMessage(get(key, kv)); }
}
