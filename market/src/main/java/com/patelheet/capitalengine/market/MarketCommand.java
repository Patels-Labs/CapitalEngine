package com.patelheet.capitalengine.market;

import com.patelheet.capitalengine.market.maker.BukkitRecipes;
import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

/** /market, /jobs and /economy. Every cost is confirmed with {@code /market yes}; the menus ask with Yes/No screens instead. */
final class MarketCommand implements CommandExecutor, TabCompleter {
    private final CapitalMarketPlugin plugin;
    private final MarketService svc;
    private final Actions a;
    private final Menus menus;
    private final Stalls stalls;
    private final Messages msg;

    MarketCommand(CapitalMarketPlugin plugin, MarketService svc, Actions a, Menus menus, Stalls stalls, Messages msg) {
        this.plugin = plugin; this.svc = svc; this.a = a; this.menus = menus; this.stalls = stalls; this.msg = msg;
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        switch (cmd.getName()) {
            case "jobs" -> jobs(p, args);
            case "economy" -> menus.economy(p);
            default -> market(p, args);
        }
        return true;
    }

    private static String words(String[] a, int from, int to) { return String.join(" ", Arrays.copyOfRange(a, from, to)); }

    /** An item typed as words, or null (having said so). */
    private String item(Player p, String text) {
        String key = Items.parse(text);
        if (key == null) a.say(p, "unknown-item", "item", text);
        return key;
    }

    // ---- /market -----------------------------------------------------------------------------------------------

    private void market(Player p, String[] x) {
        String sub = x.length == 0 ? "" : x[0].toLowerCase();
        UUID id = p.getUniqueId();
        int n = x.length;
        switch (sub) {
            case "" -> menus.main(p);
            case "help", "?" -> a.help(p, n > 1 ? x[1].toLowerCase() : "");
            case "sell" -> {
                String key = a.hand(p);
                if (key == null) return;
                int want = n < 2 ? p.getInventory().getItemInMainHand().getAmount() : x[1].equalsIgnoreCase("all") ? 0 : a.whole(p, x[1]);
                if (n < 2 || want > 0 || x[1].equalsIgnoreCase("all")) a.startSell(p, key, want, a.chat);
            }
            case "buy" -> {
                if (n < 3) { a.say(p, "help"); return; }
                String key = item(p, words(x, 1, n - 1));
                int qty = a.whole(p, x[n - 1]);
                if (key != null && qty > 0) a.startBuy(p, key, qty, a.chat);
            }
            case "list" -> {
                String key = a.hand(p);
                if (key == null || n < 3) { if (key != null) a.say(p, "help"); return; }
                int qty = a.whole(p, x[1]);
                long unit = a.amount(p, x[2]);
                if (qty > 0 && unit > 0) a.list(p, key, qty, unit);
            }
            case "wanted" -> {
                if (n < 4) { a.say(p, "help"); return; }
                String key = item(p, words(x, 1, n - 2));
                int qty = a.whole(p, x[n - 2]);
                long unit = a.amount(p, x[n - 1]);
                if (key != null && qty > 0 && unit > 0) a.startWanted(p, key, qty, unit, a.chat);
            }
            case "orders" -> a.then(p, svc.orders(id), rows -> {
                if (rows.isEmpty()) { a.say(p, "orders-empty"); return; }
                a.say(p, "orders-header");
                rows.forEach(o -> p.sendMessage(msg.get("orders-line", "id", o.id(), "side", o.side().equals("BUY") ? "wanted" : "selling", "left", o.left(), "item", Items.pretty(o.item()), "price", a.coins(o.price()))));
            });
            case "cancel" -> {
                long oid = n > 1 ? a.whole(p, x[1]) : 0;
                if (oid > 0) a.then(p, svc.cancel(id, oid), ok -> a.say(p, ok ? "cancelled" : "cant-cancel", "id", oid));
            }
            case "stash" -> a.then(p, svc.stash(id), m -> {
                if (m.isEmpty()) { a.say(p, "stash-empty"); return; }
                a.say(p, "stash-header");
                m.forEach((key, q) -> p.sendMessage(msg.get("stash-line", "item", Items.pretty(key), "qty", q[0], "locked", q[1])));
            });
            case "take" -> {
                if (n < 2) { a.say(p, "help"); return; }
                boolean hasQty = n > 2 && x[n - 1].chars().allMatch(Character::isDigit);
                String key = item(p, words(x, 1, hasQty ? n - 1 : n));
                int qty = hasQty ? a.whole(p, x[n - 1]) : Integer.MAX_VALUE;
                if (key != null && qty > 0) a.withdraw(p, key, qty, () -> { });
            }
            case "price" -> {
                String key = n > 1 ? item(p, words(x, 1, n)) : a.hand(p);
                if (key == null) return;
                a.then(p, svc.prices(List.of(key)), m -> {
                    var pi = m.get(key);
                    if (pi == null) a.say(p, "price-none", "item", Items.pretty(key));
                    else a.say(p, "price-line", "item", Items.pretty(key), "ask", a.coins(pi.ask()), "bid", a.coins(pi.bid()), "stock", pi.stock(),
                            "trend", trend(key));
                });
            }
            case "yes" -> { if (!a.confirmNow(p)) a.say(p, "nothing-to-confirm"); }
            case "tutorial" -> { if (n > 1 && x[1].equalsIgnoreCase("skip")) { svc.tutorial.skip(id); a.say(p, "tutorial-skipped"); } }
            case "admin" -> admin(p, x);
            default -> a.say(p, "help");
        }
    }

    private String trend(String key) {
        Double t = svc.trend.get(key);
        if (t == null) return "";
        long pct = Math.round((t - 1) * 100);
        return (pct >= 0 ? "▲" : "▼") + Math.abs(pct) + "%";
    }

    private void admin(Player p, String[] x) {
        if (!p.hasPermission("capitalmarket.admin")) return;
        String sub = x.length > 1 ? x[1].toLowerCase() : "";
        int n = x.length;
        switch (sub) {
            case "stock" -> {
                String key = n >= 4 ? item(p, words(x, 2, n - 1)) : null;
                if (key == null) { if (n < 4) a.say(p, "help"); return; }
                long qty = Long.parseLong(x[n - 1]);
                svc.run(() -> { svc.store.setStock(key, qty); return null; });
                a.say(p, "admin-done");
            }
            case "check" -> a.then(p, svc.escrowCheck(), v ->
                    a.say(p, v[0] == v[1] && v[2] == v[3] ? "escrow-ok" : "escrow-bad", "held", a.coins(v[0]), "need", a.coins(v[1]), "jheld", a.coins(v[2]), "jneed", a.coins(v[3])));
            case "revalue" -> a.then(p, svc.revalue(BukkitRecipes.load()), v -> a.say(p, "admin-done"));
            case "reload" -> {
                msg.reload();
                a.then(p, svc.revalue(BukkitRecipes.load()).thenCompose(v -> svc.replan()), v -> a.say(p, "reloaded"));
            }
            case "stall" -> {
                Block b = p.getTargetBlockExact(6);
                if (b == null || n < 3) { a.say(p, "stall-look"); return; }
                boolean remove = x[2].equalsIgnoreCase("remove");
                Stalls.Result r = remove ? stalls.remove(b) : stalls.create(b);
                a.say(p, switch (r) {
                    case CREATED -> "stall-created";
                    case REMOVED -> "stall-removed";
                    case NO_SPACE -> "stall-no-space";
                    case ALREADY -> "stall-already";
                    case NOT_A_STALL -> "stall-not-a-stall";
                });
            }
            case "project" -> project(p, x);
            default -> a.say(p, "help");
        }
    }

    private void project(Player p, String[] x) {
        File f = new File(plugin.getDataFolder(), "projects.yml");
        YamlConfiguration y = YamlConfiguration.loadConfiguration(f);
        String act = x.length > 2 ? x[2].toLowerCase() : "list";
        try {
            switch (act) {
                case "create" -> {   // create <name> <item words...> <qty>
                    if (x.length < 6) { a.say(p, "help"); return; }
                    String key = item(p, words(x, 4, x.length - 1));
                    int qty = a.whole(p, x[x.length - 1]);
                    if (key == null || qty <= 0) return;
                    y.set(x[3] + ".needs." + key, qty);
                }
                case "chest" -> {
                    Block b = p.getTargetBlockExact(6);
                    if (b == null || x.length < 4) { a.say(p, "stall-look"); return; }
                    y.set(x[3] + ".deliver-to", "chest@" + b.getWorld().getName() + "," + b.getX() + "," + b.getY() + "," + b.getZ());
                }
                case "remove" -> { if (x.length > 3) y.set(x[3], null); }
                default -> {
                    if (y.getKeys(false).isEmpty()) a.say(p, "project-none");
                    for (String name : y.getKeys(false)) p.sendMessage(msg.get("project-line", "name", name, "needs", String.valueOf(y.getConfigurationSection(name + ".needs") == null ? "" : y.getConfigurationSection(name + ".needs").getValues(false))));
                    return;
                }
            }
            y.save(f);
            svc.replan();
            a.say(p, "admin-done");
        } catch (IOException e) { plugin.getLogger().warning("Could not save projects.yml: " + e.getMessage()); a.say(p, "err-other"); }
    }

    // ---- /jobs -------------------------------------------------------------------------------------------------

    private void jobs(Player p, String[] x) {
        String sub = x.length == 0 ? "" : x[0].toLowerCase();
        UUID id = p.getUniqueId();
        int n = x.length;
        long jid = n > 1 && x[1].chars().allMatch(Character::isDigit) ? Long.parseLong(x[1]) : 0;
        switch (sub) {
            case "" -> menus.jobs(p);
            case "list" -> a.then(p, svc.jobs.open(), open -> {
                if (open.isEmpty()) { a.say(p, "jobs-empty"); return; }
                a.say(p, "jobs-header");
                open.forEach(j -> p.sendMessage(j.kind().equals("BUILD")
                        ? msg.get("jobs-line-build", "id", j.id(), "note", j.note(), "pay", a.coins(j.pay()), "status", j.status().toLowerCase())
                        : msg.get("jobs-line", "id", j.id(), "who", j.poster() == null ? (j.project() == null ? "The town" : j.project()) : "A player", "left", j.left(), "item", Items.pretty(j.item()), "pay", a.coins(j.pay()))));
            });
            case "post" -> {
                String key = a.hand(p);
                int qty = n > 1 ? a.whole(p, x[1]) : 0;
                long pay = n > 2 ? a.amount(p, x[2]) : 0;
                if (key == null || qty <= 0 || pay <= 0) return;
                a.chat.ask(p, "confirm-job", new Object[]{"qty", qty, "item", Items.pretty(key), "pay", a.coins(pay), "total", a.coins(qty * pay)},
                        () -> a.then(p, svc.jobs.post(id, key, qty, pay), r -> a.say(p, r.error() != null ? r.error() : "job-posted", "id", r.orderId(), "qty", qty, "item", Items.pretty(key), "pay", a.coins(pay))));
            }
            case "build" -> {   // /jobs build <pay> <what to build...>
                long pay = n > 1 ? a.amount(p, x[1]) : 0;
                if (pay <= 0 || n < 3) { if (n < 3) a.say(p, "help"); return; }
                String note = words(x, 2, n);
                a.chat.ask(p, "confirm-build", new Object[]{"note", note, "pay", a.coins(pay)},
                        () -> a.then(p, svc.jobs.postBuild(id, pay, note), r -> a.say(p, r.error() != null ? r.error() : "build-posted", "id", r.orderId(), "pay", a.coins(pay))));
            }
            case "deliver" -> {
                if (jid <= 0) { a.say(p, "help"); return; }
                a.then(p, svc.jobs.get(jid), j -> {
                    if (j == null || j.item() == null) { a.say(p, "job-none", "id", jid); return; }
                    a.deliver(p, jid, j.item(), n > 2 ? Math.max(1, a.whole(p, x[2])) : j.left(), () -> { });
                });
            }
            case "accept" -> { if (jid > 0) a.then(p, svc.jobs.accept(id, jid), r -> a.say(p, r.error() != null ? r.error() : "build-accepted", "id", jid)); }
            case "done" -> { if (jid > 0) a.then(p, svc.jobs.finish(id, jid, true), r -> a.say(p, r.error() != null ? r.error() : "build-paid", "id", jid, "pay", a.coins(r.coins()))); }
            case "problem" -> { if (jid > 0) a.then(p, svc.jobs.finish(id, jid, false), r -> a.say(p, r.error() != null ? r.error() : "build-problem", "id", jid)); }
            case "cancel" -> { if (jid > 0) a.then(p, svc.jobs.cancel(id, jid), r -> a.say(p, r.error() != null ? r.error() : "job-cancelled", "id", jid)); }
            case "admin" -> {
                if (!p.hasPermission("capitalmarket.jobs.admin") || n < 4 || !x[1].equalsIgnoreCase("settle")) return;
                long sid = a.whole(p, x[2]);
                if (sid > 0) a.then(p, svc.jobs.settle(sid, x[3].equalsIgnoreCase("pay")), r -> a.say(p, r.error() != null ? r.error() : "admin-done"));
            }
            default -> a.say(p, "help");
        }
    }

    // ---- tab completion ----------------------------------------------------------------------------------------

    @Override public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] x) {
        if (cmd.getName().equals("market")) {
            if (x.length == 1) return List.of("help", "sell", "buy", "list", "wanted", "orders", "cancel", "stash", "take", "price", "yes", "tutorial").stream().filter(c -> c.startsWith(x[0].toLowerCase())).toList();
            if (x[0].equalsIgnoreCase("help") && x.length == 2) return Actions.HELP_TOPICS.stream().filter(t -> t.startsWith(x[1].toLowerCase())).toList();
            if (List.of("buy", "wanted", "take", "price").contains(x[0].toLowerCase()) && x.length == 2) return svc.items().stream().filter(i -> i.startsWith(x[1].toLowerCase())).limit(40).toList();
        } else if (cmd.getName().equals("jobs") && x.length == 1) {
            return List.of("list", "post", "build", "deliver", "accept", "done", "problem", "cancel").stream().filter(c -> c.startsWith(x[0].toLowerCase())).toList();
        }
        return List.of();
    }
}
