package com.patelheet.capitalengine.dashboard;

import com.patelheet.capitalengine.api.CapitalApi;
import com.patelheet.capitalengine.api.service.Unit;
import com.patelheet.capitalengine.dashboard.snapshot.Snapshot;
import com.patelheet.capitalengine.dashboard.storage.Store;
import com.patelheet.capitalengine.dashboard.web.JsonApi;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** {@code /cdash [alerts [ack <id|all>]|plugins|web|reload]}. Database reads run on the worker; replies go back on the sender's thread. */
final class CdashCommand implements CommandExecutor, TabCompleter {
    private static final Component SEP = Component.text(" · ");
    private final CapitalDashboardPlugin plugin;
    private final CapitalApi api;
    private final JsonApi data;
    private final Store store;
    private final Messages msg;

    CdashCommand(CapitalDashboardPlugin plugin, CapitalApi api, JsonApi data, Store store, Messages msg) {
        this.plugin = plugin; this.api = api; this.data = data; this.store = store; this.msg = msg;
    }

    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "" -> { if (allowed(sender, "capitaldash.view")) reply(sender, this::summary); }
            case "plugins" -> { if (allowed(sender, "capitaldash.view")) reply(sender, this::plugins); }
            case "alerts" -> {
                if (!allowed(sender, "capitaldash.alerts")) return true;
                if (args.length >= 3 && args[1].equalsIgnoreCase("ack")) ack(sender, args[2]);
                else reply(sender, this::alerts);
            }
            case "web" -> {
                if (!allowed(sender, "capitaldash.web")) return true;
                String link = plugin.loginLink(who(sender));
                if (link == null) { line(sender, "web-disabled"); return true; }
                sender.sendMessage(prefixed(msg.get("web-link", "url", Component.text(link).clickEvent(ClickEvent.openUrl(link))) ));
                sender.sendMessage(msg.get("web-link-plain", "url", link));   // Bedrock and the console can't click
            }
            case "reload" -> {
                if (!allowed(sender, "capitaldash.admin")) return true;
                plugin.reloadAll();
                line(sender, "reloaded");
            }
            default -> sender.sendMessage(msg.get("usage"));
        }
        return true;
    }

    private static String who(CommandSender s) { return s instanceof Player p ? p.getUniqueId().toString() : "console"; }

    private boolean allowed(CommandSender s, String perm) {
        if (s.hasPermission(perm)) return true;
        line(s, "no-permission");
        return false;
    }

    private Component prefixed(Component c) { return msg.get("prefix").append(c); }

    private void line(CommandSender s, String key, Object... kv) { s.sendMessage(prefixed(msg.get(key, kv))); }

    /** Builds the lines on the worker thread (they read the database), then sends them on the sender's own thread. */
    private void reply(CommandSender sender, Supplier<List<Component>> work) {
        CompletableFuture.supplyAsync(work, plugin.worker()).whenComplete((lines, e) -> {
            Runnable send = () -> {
                if (e != null) { line(sender, "error", "reason", String.valueOf(e.getCause() != null ? e.getCause().getMessage() : e.getMessage())); return; }
                lines.forEach(sender::sendMessage);
            };
            if (sender instanceof Player p) p.getScheduler().run(plugin, t -> send.run(), null);
            else plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> send.run());
        });
    }

    // ---- views --------------------------------------------------------------------------------------------------

    private String coins(double v) { return api.currency().format(Math.round(v)); }

    private List<Component> summary() {
        Snapshot s = data.latest();
        if (s == null) return List.of(prefixed(msg.get("summary-waiting")));
        var st = s.stats();
        List<Component> out = new ArrayList<>();
        out.add(msg.get("summary-title"));
        var week = data.supplyWeekChange();
        Component change = week.isEmpty() ? Component.empty()
                : msg.get("summary-change", "arrow", week.getAsDouble() >= 0 ? "▲" : "▼", "percent", String.format(Locale.ROOT, "%.1f", Math.abs(week.getAsDouble())));
        out.add(msg.get("summary-supply", "amount", coins(st.moneySupply()), "change", change));
        out.add(msg.get("summary-flows", "issued", coins(st.issuedToday()), "destroyed", coins(st.destroyedToday()), "treasury", coins(st.treasuryBalance())));
        out.add(msg.get("summary-players", "count", st.activePlayersToday()));
        List<Component> top = new ArrayList<>();
        var byIssued = new ArrayList<>(data.flows(1));
        byIssued.sort((a, b) -> Long.compare(b.v()[0], a.v()[0]));
        for (var f : byIssued) if (f.v()[0] > 0 && top.size() < 3) top.add(msg.get("summary-top-item", "plugin", f.plugin(), "amount", coins(f.v()[0])));
        out.add(top.isEmpty() ? msg.get("summary-top-none") : msg.get("summary-top", "list", Component.join(JoinConfiguration.separator(SEP), top)));
        s.providers().forEach((id, name) -> {
            List<Component> items = new ArrayList<>();
            for (var m : s.metrics()) if (m.provider().equals(id) && items.size() < 3) items.add(msg.get("summary-addon-item", "label", m.label(), "value", value(m.unit(), m.value())));
            if (!items.isEmpty()) out.add(msg.get("summary-addon", "name", name, "list", Component.join(JoinConfiguration.separator(SEP), items)));
        });
        int open = store.openAlerts();
        out.add(open == 0 ? msg.get("summary-alerts-none") : msg.get("summary-alerts", "count", open));
        return out;
    }

    /** How an indicator reads: coins in the currency's format, percentages with one decimal, daily rates as a percentage per day. */
    String value(Unit unit, double v) {
        if (unit == null) unit = Unit.COUNT;
        return switch (unit) {
            case COINS -> coins(v);
            case PERCENT -> String.format(Locale.ROOT, "%.1f%%", v);
            case RATE_PER_DAY -> String.format(Locale.ROOT, "%.2f%% a day", v * 100);
            case INDEX -> String.format(Locale.ROOT, "%.2f", v);
            case COUNT -> v == Math.rint(v) ? String.format(Locale.ROOT, "%,d", (long) v) : String.format(Locale.ROOT, "%,.1f", v);
        };
    }

    private List<Component> plugins() {
        var today = data.flows(1);
        Map<String, Long> net7 = new HashMap<>();
        for (var f : data.flows(7)) net7.put(f.plugin(), f.v()[4]);
        List<Component> out = new ArrayList<>();
        out.add(msg.get("plugins-title"));
        if (today.isEmpty()) out.add(msg.get("plugins-none"));
        for (var f : today) {
            long[] v = f.v();
            out.add(msg.get("plugins-line", "plugin", f.plugin(), "issued", coins(v[0]), "destroyed", coins(v[1]), "tin", coins(v[2]), "tout", coins(v[3]),
                    "net", coins(v[4]), "net7", coins(net7.getOrDefault(f.plugin(), v[4]))));
        }
        return out;
    }

    private List<Component> alerts() {
        int open = store.openAlerts();
        if (open == 0) return List.of(prefixed(msg.get("alerts-none")));
        List<Component> out = new ArrayList<>();
        out.add(msg.get("alerts-title", "count", open));
        var list = store.alerts(true, 10);
        long now = System.currentTimeMillis();
        for (Store.Alert a : list) {
            Component ack = msg.get("alerts-ack-button").clickEvent(ClickEvent.runCommand("/cdash alerts ack " + a.id()));
            out.add(msg.get("alerts-line", "id", a.id(), "severity", msg.get("severity-" + a.severity().name()), "source", a.source(), "message", a.message(),
                    "hits", a.hits(), "ago", ago(now - a.lastTs()), "ack", ack));
        }
        if (open > list.size()) out.add(msg.get("alerts-more", "count", open - list.size()));
        out.add(msg.get("alerts-ack-all-hint"));
        return out;
    }

    private Component ago(long ms) {
        long min = ms / 60_000;
        if (min < 60) return msg.get("ago-minutes", "n", min);
        if (min < 48 * 60) return msg.get("ago-hours", "n", min / 60);
        return msg.get("ago-days", "n", min / 1440);
    }

    private void ack(CommandSender sender, String arg) {
        Long id;
        if (arg.equalsIgnoreCase("all")) id = null;
        else {
            try { id = Long.parseLong(arg.replace("#", "")); } catch (NumberFormatException e) { sender.sendMessage(msg.get("usage")); return; }
        }
        String by = who(sender);
        reply(sender, () -> {
            int n = plugin.ack(id, by);
            return List.of(prefixed(n == 0 ? msg.get("alerts-ack-none") : msg.get("alerts-acked", "count", n)));
        });
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> options = switch (args.length) {
            case 1 -> List.of("alerts", "plugins", "web", "reload");
            case 2 -> args[0].equalsIgnoreCase("alerts") ? List.of("ack") : List.of();
            case 3 -> args[0].equalsIgnoreCase("alerts") && args[1].equalsIgnoreCase("ack") ? List.of("all") : List.of();
            default -> List.of();
        };
        String last = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
        return options.stream().filter(o -> o.startsWith(last)).toList();
    }
}
