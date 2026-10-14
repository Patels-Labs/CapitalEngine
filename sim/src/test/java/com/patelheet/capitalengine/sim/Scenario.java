package com.patelheet.capitalengine.sim;

import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

/** A scenario file (sim/Plan.md §5.4): who plays, for how long, what happens, and what must stay true. */
record Scenario(String name, long seed, int days, Map<String, Integer> players, Map<String, Double> onlineChance,
                Map<String, Map<String, Integer>> production, List<Event> events, Map<String, Object> config,
                Map<String, Object> core, Map<String, Map<String, Integer>> projects, Map<String, Double> bands) {

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
        return new Scenario(y.getString("name", "scenario"), y.getLong("seed", 1), y.getInt("days", 60), players, online, production, events,
                section(y, "config"), section(y, "core"), projects, bands);
    }

    /** A nested section as dotted-path leaves: {@code market.autopilot.x: 1} becomes {@code "autopilot.x" -> 1}. */
    private static Map<String, Object> section(YamlConfiguration y, String name) {
        Map<String, Object> out = new LinkedHashMap<>();
        ConfigurationSection s = y.getConfigurationSection(name);
        if (s != null) s.getValues(true).forEach((k, v) -> { if (!(v instanceof ConfigurationSection)) out.put(k, v); });
        return out;
    }

    Scenario with(int days, long seed, Map<String, Object> extraConfig) {
        Map<String, Object> cfg = new LinkedHashMap<>(config);
        cfg.putAll(extraConfig);
        return new Scenario(name, seed, days, players, onlineChance, production, events, cfg, core, projects, bands);
    }
}
