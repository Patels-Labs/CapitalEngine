package com.patelheet.capitalengine.trade;

import java.util.Arrays;
import java.util.List;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;

/** /trade. Every shipment is confirmed with {@code /trade yes}; the menus ask with Yes/No screens instead. */
final class TradeCommand implements CommandExecutor, TabCompleter {
    private final CapitalTradePlugin plugin;
    private final TradeService svc;
    private final Actions a;
    private final Menus menus;
    private final Ports ports;

    TradeCommand(CapitalTradePlugin plugin, TradeService svc, Actions a, Menus menus, Ports ports) {
        this.plugin = plugin; this.svc = svc; this.a = a; this.menus = menus; this.ports = ports;
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] x) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        String sub = x.length == 0 ? "" : x[0].toLowerCase();
        int n = x.length;
        switch (sub) {
            case "" -> menus.main(p);
            case "help", "?" -> a.help(p, n > 1 ? x[1].toLowerCase() : "");
            case "sell", "export" -> {
                String key = a.hand(p);
                if (key == null) return true;
                int want = n < 2 ? p.getInventory().getItemInMainHand().getAmount() : x[1].equalsIgnoreCase("all") ? 0 : a.whole(p, x[1]);
                if (n < 2 || want > 0 || x[1].equalsIgnoreCase("all")) a.startExport(p, key, want, a.chat);
            }
            case "buy", "import" -> {
                if (svc.imports.isEmpty()) { a.say(p, "import-off"); return true; }
                if (n < 3) { a.say(p, "help"); return true; }
                String key = Items.parse(String.join(" ", Arrays.copyOfRange(x, 1, n - 1)));
                int qty = a.whole(p, x[n - 1]);
                if (key == null || !svc.imports.containsKey(key)) { a.say(p, "import-unknown"); return true; }
                if (qty > 0) a.startImport(p, key, qty, a.chat);
            }
            case "cargo" -> { if (a.atPort(p)) a.collect(p, false); }
            case "news" -> menus.news(p);
            case "yes", "confirm" -> { if (!a.confirmNow(p)) a.say(p, "nothing-to-confirm"); }
            case "admin" -> { if (p.hasPermission("capitaltrade.admin")) admin(p, x); else a.say(p, "no-permission"); }
            default -> a.say(p, "help");
        }
        return true;
    }

    private void admin(Player p, String[] x) {
        String what = x.length < 2 ? "" : x[1].toLowerCase() + (x.length > 2 && x[1].equalsIgnoreCase("port") ? " " + x[2].toLowerCase() : "");
        switch (what) {
            case "port create" -> {
                String name = x.length > 3 ? String.join("_", Arrays.copyOfRange(x, 3, x.length)) : "port" + (ports.list().size() + 1);
                Entity e = p.getTargetEntity(5);
                Block b = p.getTargetBlockExact(5);
                Ports.Result r = e != null ? ports.create(e, name) : b != null ? ports.create(b, name) : null;
                a.say(p, r == null ? "port-look" : "port-" + r.name().toLowerCase().replace('_', '-'), "name", name);
            }
            case "port remove" -> {
                if (x.length < 4) { a.say(p, "help"); return; }
                a.say(p, "port-" + ports.remove(x[3]).name().toLowerCase().replace('_', '-'), "name", x[3]);
            }
            case "port list" -> {
                a.say(p, "port-list-header");
                ports.list().forEach(s -> a.say(p, "port-list-line", "name", s.name(), "kind", s.kind(), "world", s.world(), "x", s.x(), "y", s.y(), "z", s.z()));
            }
            case "boom" -> {
                String key = x.length > 2 ? Items.parse(x[2]) : null;
                if (key == null || !svc.exports.containsKey(key)) { a.say(p, "boom-unknown"); return; }
                double mult;
                try { mult = x.length > 3 ? Double.parseDouble(x[3]) : 1.3; } catch (NumberFormatException ex) { a.say(p, "bad-number"); return; }
                if (mult <= 0 || mult > 10) { a.say(p, "bad-number"); return; }
                svc.boom(key, mult);
            }
            case "reload" -> { plugin.reload(); a.say(p, "reloaded"); }
            default -> a.say(p, "admin-help");
        }
    }

    @Override public List<String> onTabComplete(CommandSender sender, Command cmd, String alias, String[] x) {
        List<String> subs = sender.hasPermission("capitaltrade.admin")
                ? List.of("help", "sell", "buy", "cargo", "news", "yes", "admin") : List.of("help", "sell", "buy", "cargo", "news", "yes");
        if (x.length == 1) return subs.stream().filter(s -> s.startsWith(x[0].toLowerCase())).toList();
        if (x.length == 2 && x[0].equalsIgnoreCase("help")) return Actions.HELP_TOPICS.stream().filter(s -> s.startsWith(x[1].toLowerCase())).toList();
        if (x.length == 2 && x[0].equalsIgnoreCase("buy")) return Actions.tabItems(svc.imports, x[1]);
        if (x.length == 2 && x[0].equalsIgnoreCase("admin") && sender.hasPermission("capitaltrade.admin"))
            return List.of("port", "boom", "reload").stream().filter(s -> s.startsWith(x[1].toLowerCase())).toList();
        if (x.length == 3 && x[0].equalsIgnoreCase("admin") && x[1].equalsIgnoreCase("port")) return List.of("create", "remove", "list");
        if (x.length == 3 && x[0].equalsIgnoreCase("admin") && x[1].equalsIgnoreCase("boom")) return Actions.tabItems(svc.exports, x[2]);
        return List.of();
    }
}
