package com.patelheet.capitalengine.bank;

import java.util.List;
import java.util.UUID;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /bank. Loans and repayments are confirmed with {@code /bank yes}; the menus ask with Yes/No screens instead. */
final class BankCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("deposit", "withdraw", "loan", "repay", "upkeep", "yes", "help");
    private static final List<String> ADMIN = List.of("loans", "forgive", "unfreeze", "check", "reload");

    private final CapitalBankPlugin plugin;
    private final BankService svc;
    private final Actions a;
    private final Menus menus;
    private final Messages msg;

    BankCommand(CapitalBankPlugin plugin, BankService svc, Actions a, Menus menus, Messages msg) {
        this.plugin = plugin; this.svc = svc; this.a = a; this.menus = menus; this.msg = msg;
    }

    @Override public boolean onCommand(CommandSender sender, Command cmd, String label, String[] x) {
        String sub = x.length == 0 ? "" : x[0].toLowerCase();
        if (sub.equals("admin")) { admin(sender, x); return true; }
        if (!(sender instanceof Player p)) { sender.sendMessage("Players only."); return true; }
        boolean all = x.length > 1 && x[1].equalsIgnoreCase("all");
        switch (sub) {
            case "" -> menus.main(p);
            case "help", "?" -> a.help(p, x.length > 1 ? x[1].toLowerCase() : "");
            case "deposit", "withdraw" -> {
                if (x.length < 2) { a.say(p, "usage-" + sub); return true; }
                long n = all ? 0 : a.amount(p, x[1]);
                if (!all && n <= 0) return true;
                if (sub.equals("deposit")) a.deposit(p, n, () -> { }); else a.withdraw(p, n, () -> { });
            }
            case "loan" -> {
                if (!loans(p)) return true;
                if (x.length < 2) { a.loanInfo(p); return true; }
                long n = a.amount(p, x[1]);
                if (n > 0) a.startBorrow(p, n, a.chat);
            }
            case "repay" -> {
                if (!loans(p)) return true;
                long n = x.length < 2 || all ? 0 : a.amount(p, x[1]);
                if (x.length < 2 || all || n > 0) a.startRepay(p, n, a.chat);
            }
            case "upkeep" -> menus.upkeep(p);
            case "yes" -> { if (!a.confirmNow(p)) a.say(p, "nothing-to-confirm"); }
            default -> a.say(p, "unknown-command");
        }
        return true;
    }

    private boolean loans(Player p) {
        if (p.hasPermission("capitalbank.loans")) return true;
        a.say(p, "no-permission");
        return false;
    }

    // ---- /bank admin -------------------------------------------------------------------------------------------

    /** Replies from the worker hop back to the main thread first. */
    private void reply(CommandSender s, net.kyori.adventure.text.Component c) { plugin.getServer().getGlobalRegionScheduler().run(plugin, t -> s.sendMessage(c)); }

    private void admin(CommandSender s, String[] x) {
        if (!s.hasPermission("capitalbank.admin")) { s.sendMessage(msg.get("no-permission")); return; }
        String sub = x.length < 2 ? "" : x[1].toLowerCase();
        UUID target = x.length < 3 ? null : player(x[2]);
        if (x.length >= 3 && target == null) { s.sendMessage(msg.get("unknown-player", "name", x[2])); return; }
        switch (sub) {
            case "loans" -> svc.run(() -> target == null ? svc.store.loans("ACTIVE", "OVERDUE", "PENDING") : svc.store.loansOf(target)).thenAccept(list -> {
                if (list.isEmpty()) { reply(s, msg.get("admin-no-loans")); return; }
                for (var l : list) reply(s, msg.get("admin-loan-line", "id", l.id(), "name", svc.wallet(l.uuid()).name(), "status", l.status(),
                        "principal", svc.coins(l.principal()), "left", svc.coins(l.left()), "behind", svc.coins(l.arrears())));
            });
            case "forgive" -> {
                if (target == null) { reply(s, msg.get("admin-usage")); return; }
                svc.forgive(target).thenAccept(ok -> reply(s, msg.get(ok ? "admin-forgiven" : "admin-no-loans")));
            }
            case "unfreeze" -> {
                if (target == null) { reply(s, msg.get("admin-usage")); return; }
                svc.unfreeze(target).thenAccept(n -> reply(s, msg.get("admin-unfrozen", "amount", svc.coins(n))));
            }
            case "check" -> svc.check().thenAccept(problems -> {
                if (problems.isEmpty()) reply(s, msg.get("admin-check-ok"));
                else problems.forEach(pr -> reply(s, msg.get("admin-check-bad", "problem", pr)));
            });
            case "reload" -> { msg.reload(); s.sendMessage(msg.get("admin-reloaded")); }
            default -> s.sendMessage(msg.get("admin-usage"));
        }
    }

    /** A player by name, including Bedrock names with Floodgate's prefix. */
    private UUID player(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        OfflinePlayer off = plugin.getServer().getOfflinePlayerIfCached(name);
        return off == null ? null : off.getUniqueId();
    }

    @Override public List<String> onTabComplete(CommandSender s, Command cmd, String label, String[] x) {
        if (x.length == 1) return (s.hasPermission("capitalbank.admin") ? java.util.stream.Stream.concat(SUBS.stream(), java.util.stream.Stream.of("admin")) : SUBS.stream())
                .filter(c -> c.startsWith(x[0].toLowerCase())).toList();
        if (x.length == 2 && x[0].equalsIgnoreCase("admin")) return ADMIN.stream().filter(c -> c.startsWith(x[1].toLowerCase())).toList();
        if (x.length == 2 && x[0].equalsIgnoreCase("help")) return Actions.HELP_TOPICS.stream().filter(c -> c.startsWith(x[1].toLowerCase())).toList();
        if (x.length == 2 && List.of("deposit", "withdraw", "repay").contains(x[0].toLowerCase())) return List.of("all");
        return x.length == 3 && x[0].equalsIgnoreCase("admin") ? null : List.of();
    }
}
