package com.patelheet.capitalengine.sim;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** A scenario file (sim/Plan.md §5.4): who plays, for how long, what happens, and what must stay true. */
record Scenario(String name, long seed, int days, List<String> plugins, Map<String, Integer> players, Map<String, Double> onlineChance,
                Map<String, Map<String, Integer>> production, List<Event> events, Map<String, Object> config,
                Map<String, Object> core, Map<String, Object> bank, Map<String, Object> trade, Map<String, Long> land,
                Map<String, Object> imports, Map<String, Map<String, Integer>> projects, Map<String, Double> bands) {

    /** Plugins besides CapitalEngine; every scenario has CapitalMarket, which the agents trade through. */
    static final List<String> ADD_ONS = List.of("market", "bank", "trade");

    boolean has(String plugin) { return plugins.contains(plugin); }

    /** {@code share} is for go-dormant, {@code x} for production-multiplier. */
    record Event(int day, String type, String role, double x, double share) {}

    static final Map<String, Double> DEFAULT_BANDS = Map.of(
            "weekly-inflation-abs-after-warmup", 0.03,
            "max-monthly-growth", 0.15,
            "min-monthly-growth", -0.10,
            "max-unmet-needs", 0.05);

    static Scenario parse(Reader r) {
        YamlConfiguration y = YamlConfiguration.loadConfiguration(r);
        Map<String, Integer> players = new LinkedHashMap<>();
        section(y, "players").forEach((k, v) -> players.put(k, ((Number) v).intValue()));
        Map<String, Double> online = new LinkedHashMap<>();
        section(y, "online-chance").forEach((k, v) -> online.put(k, ((Number) v).doubleValue()));
        Map<String, Map<String, Integer>> production = new LinkedHashMap<>();
        ConfigurationSection prod = y.getConfigurationSection("production");
        if (prod != null) for (String role : prod.getKeys(false)) {
            Map<String, Integer> m = new LinkedHashMap<>();
            prod.getConfigurationSection(role).getValues(false).forEach((k, v) -> m.put(k, ((Number) v).intValue()));
            production.put(role, m);
        }
        Map<String, Map<String, Integer>> projects = new LinkedHashMap<>();
        ConfigurationSection proj = y.getConfigurationSection("projects");
        if (proj != null) for (String name : proj.getKeys(false)) {
            Map<String, Integer> m = new LinkedHashMap<>();
            proj.getConfigurationSection(name).getValues(false).forEach((k, v) -> m.put(k, ((Number) v).intValue()));
            projects.put(name, m);
        }
        List<Event> events = new ArrayList<>();
        for (Map<?, ?> e : y.getMapList("events"))
            events.add(new Event(((Number) e.get("day")).intValue(), (String) e.get("type"), (String) e.get("role"),
                    e.get("x") == null ? 1 : ((Number) e.get("x")).doubleValue(), e.get("share") == null ? 0 : ((Number) e.get("share")).doubleValue()));
        Map<String, Double> bands = new LinkedHashMap<>(DEFAULT_BANDS);
        section(y, "bands").forEach((k, v) -> bands.put(k, ((Number) v).doubleValue()));
        Map<String, Long> land = new LinkedHashMap<>();
        section(y, "land").forEach((k, v) -> land.put(k, ((Number) v).longValue()));
        List<String> plugins = y.isList("plugins") ? y.getStringList("plugins") : List.of("market");
        for (String p : plugins) if (!ADD_ONS.contains(p)) throw new IllegalArgumentException("Unknown plugin " + p + " (use " + ADD_ONS + ")");
        if (!plugins.contains("market")) throw new IllegalArgumentException("The agents trade through CapitalMarket, so plugins must include market");
        ConfigurationSection imp = y.getConfigurationSection("trade-imports");
        return new Scenario(y.getString("name", "scenario"), y.getLong("seed", 1), y.getInt("days", 60), List.copyOf(plugins), players, online, production, events,
                section(y, "config"), section(y, "core"), section(y, "bank"), section(y, "trade"), land, imp == null ? Map.of() : imp.getValues(true), projects, bands);
    }

    /** A nested section as dotted-path leaves: {@code market.autopilot.x: 1} becomes {@code "autopilot.x" -> 1}. */
    private static Map<String, Object> section(YamlConfiguration y, String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        ConfigurationSection s = y.getConfigurationSection(name);
        if (s != null) s.getValues(true).forEach((k, v) -> { if (!(v instanceof ConfigurationSection)) out.put(k, v); });
        return out;
    }

    /**
     * The same scenario with other settings. Keys name the module whose config they go into: {@code market.maker.k},
     * {@code bank.savings.interest-cap}, {@code trade.exports.daily-budget}, {@code core.starter-coins}; {@code sim.*} keys steer the agents.
     * A key without a module prefix is a market key (as in the scenario's {@code config:} section).
     */
    Scenario with(int days, long seed, Map<String, Object> extra) {
        Map<String, Object> cfg = new LinkedHashMap<>(config), c = new LinkedHashMap<>(core), b = new LinkedHashMap<>(bank), t = new LinkedHashMap<>(trade);
        extra.forEach((k, v) -> {
            if (k.startsWith("bank.")) b.put(k.substring(5), v);
            else if (k.startsWith("trade.")) t.put(k.substring(6), v);
            else if (k.startsWith("core.")) c.put(k.substring(5), v);
            else cfg.put(k.startsWith("market.") ? k.substring(7) : k, v);
        });
        return new Scenario(name, seed, days, plugins, players, onlineChance, production, events, cfg, c, b, t, land, imports, projects, bands);
    }

    Scenario withPlugins(List<String> p) {
        return new Scenario(name, seed, days, List.copyOf(p), players, onlineChance, production, events, config, core, bank, trade, land, imports, projects, bands);
    }
}
