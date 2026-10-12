package com.patelheet.capitalengine.core.command;

import com.patelheet.capitalengine.core.CapitalEnginePlugin;
import com.patelheet.capitalengine.core.Messages;
import com.patelheet.capitalengine.core.activity.ActivityTracker;
import com.patelheet.capitalengine.core.api.ApiImpl;
import com.patelheet.capitalengine.core.api.CurrencyImpl;
import com.patelheet.capitalengine.core.audit.Auditor;
import com.patelheet.capitalengine.core.audit.AuditView;
import com.patelheet.capitalengine.core.importer.Importer;
import com.patelheet.capitalengine.core.ledger.Acct;
import com.patelheet.capitalengine.core.ledger.Leg;
import com.patelheet.capitalengine.core.ledger.Ledger;
import com.patelheet.capitalengine.core.ledger.Req;
import com.patelheet.capitalengine.core.policy.PolicyManager;
import com.patelheet.capitalengine.core.policy.PolicyManager.Limit;
import com.patelheet.capitalengine.core.storage.JournalRepo;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /ce ...}: the owner's tools. Each sub-command has its own permission {@code capitalengine.admin.<name>}. */
public final class CeCommand implements CommandExecutor, TabCompleter {
    private static final List<String> SUBS = List.of("audit", "reverse", "give", "take", "import", "policy", "check", "reload", "prune");
    private static final List<String> POLICY_KEYS = List.of("vault-mode", "issue-per-day", "treasury-per-day");

    private final CapitalEnginePlugin plugin;
    private final Ledger ledger;
    private final ApiImpl api;
    private final JournalRepo repo;
    private final ActivityTracker players;
    private final CurrencyImpl currency;
    private final Messages msgs;
    private final AuditView audit;
    private final Auditor auditor;
    private final Importer importer;
    private final Executor async;

    public CeCommand(CapitalEnginePlugin plugin, Ledger ledger, ApiImpl api, JournalRepo repo, ActivityTracker players, CurrencyImpl currency,
                     Messages msgs, AuditView audit, Auditor auditor, Importer importer, Executor async) {
        this.plugin = plugin; this.ledger = ledger; this.api = api; this.repo = repo; this.players = players; this.currency = currency;
        this.msgs = msgs; this.audit = audit; this.auditor = auditor; this.importer = importer; this.async = async;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String label, @NotNull String[] a) {
        if (a.length == 0) { msgs.send(s, "ce.help"); return true; }
        String sub = a[0].toLowerCase(Locale.ROOT);
        String perm = switch (sub) { case "take" -> "give"; case "check" -> "audit"; case "prune" -> "reload"; default -> sub; };
        if (!SUBS.contains(sub)) { msgs.send(s, "ce.help"); return true; }
        if (!s.hasPermission("capitalengine.admin." + perm)) { msgs.send(s, "no-permission"); return true; }
        String[] r = Arrays.copyOfRange(a, 1, a.length);
        switch (sub) {
            case "audit" -> audit(s, r);
            case "reverse" -> reverse(s, r);
            case "give", "take" -> giveTake(s, r, sub.equals("give"));
            case "import" -> {
                if (r.length < 1) { msgs.send(s, "ce.import-usage", "found", String.join(", ", importer.others())); break; }
                importer.run(s, r[0], Arrays.stream(r).anyMatch(x -> x.equalsIgnoreCase("--dry-run")));
            }
            case "policy" -> policy(s, r);
            case "check" -> {
                msgs.send(s, "ce.check-running");
                auditor.run().thenAccept(problems -> {
                    if (problems.isEmpty()) msgs.send(s, "ce.check-ok");
                    else { msgs.send(s, "ce.check-bad", "count", problems.size()); problems.forEach(p -> msgs.send(s, "ce.check-problem", "problem", p)); }
                });
            }
            case "reload" -> { plugin.reloadSettings(); msgs.send(s, "ce.reloaded"); }
            case "prune" -> async.execute(() -> {
                try { repo.prune(ledger.dayIndex() - 400); msgs.send(s, "ce.pruned"); }
                catch (SQLException e) { msgs.send(s, "audit.error", "error", e.getMessage()); }
            });
            default -> { }
        }
        return true;
    }

    private void audit(CommandSender s, String[] r) {
        if (r.length < 1) { msgs.send(s, "ce.audit-usage"); return; }
        try {
            switch (r[0].toLowerCase(Locale.ROOT)) {
                case "player" -> audit.player(s, r.length > 1 ? r[1] : "", r.length > 2 ? Integer.parseInt(r[2]) : 1);
                case "plugin" -> audit.plugin(s, r.length > 1 ? r[1] : "", r.length > 2 ? Integer.parseInt(r[2]) : 1);
                case "tx" -> audit.tx(s, Long.parseLong(r.length > 1 ? r[1] : "x"));
                case "top" -> audit.top(s, r.length > 1 ? Integer.parseInt(r[1]) : 1);
                default -> msgs.send(s, "ce.audit-usage");
            }
        } catch (NumberFormatException e) { msgs.send(s, "ce.audit-usage"); }
    }

    private void reverse(CommandSender s, String[] r) {
        long id;
        try { id = Long.parseLong(r[0]); } catch (RuntimeException e) { msgs.send(s, "ce.reverse-usage"); return; }
        String reason = String.join(" ", Arrays.copyOfRange(r, 1, r.length));
        UUID actor = s instanceof Player p ? p.getUniqueId() : null;
        api.tx(id).thenAccept(found -> {
            if (found.isEmpty()) { msgs.send(s, "audit.tx-not-found", "id", id); return; }
            var res = ledger.reverse(found.get(), actor, reason).result();
            if (res.success()) msgs.send(s, "ce.reversed", "id", id, "by", res.txId());
            else msgs.send(s, "ce.reverse-failed", "id", id, "reason", res.message());
        });
    }

    private void giveTake(CommandSender s, String[] r, boolean give) {
        if (r.length < 2) { msgs.send(s, give ? "ce.give-usage" : "ce.take-usage"); return; }
        UUID id = players.uuidOf(r[0]);
        if (id == null) { msgs.send(s, "unknown-player", "name", r[0]); return; }
        long amount;
        try { amount = currency.parse(r[1]); } catch (IllegalArgumentException e) { msgs.send(s, "pay.invalid-amount"); return; }
        String reason = String.join(" ", Arrays.copyOfRange(r, 2, r.length));
        Acct acct = ledger.player(id, players.nameOf(id));
        String type = give ? "core.give" : "core.take";
        String memo = msgs.plain(give ? "memo.give" : "memo.take") + (reason.isBlank() ? "" : ": " + reason);
        var res = ledger.apply(new Req(Ledger.ADMIN, type, memo, s instanceof Player p ? p.getUniqueId() : null, null,
                List.of(give ? new Leg(Leg.Kind.ISSUE, null, acct, amount) : new Leg(Leg.Kind.DESTROY, acct, null, amount)), true)).result();
        if (res.success()) msgs.send(s, give ? "ce.gave" : "ce.took", "name", acct.name(), "amount", currency.format(amount), "id", res.txId());
        else msgs.send(s, "ce.failed", "reason", res.message());
    }

    private void policy(CommandSender s, String[] r) {
        PolicyManager pm = ledger.policy();
        if (r.length >= 1 && r[0].equalsIgnoreCase("set")) {
            if (r.length != 4 || !POLICY_KEYS.contains(r[2])) { msgs.send(s, "ce.policy-usage"); return; }
            String error = plugin.setPolicy(r[1], r[2], r[3]);
            if (error != null) msgs.send(s, "ce.failed", "reason", error); else msgs.send(s, "ce.policy-set", "plugin", r[1], "key", r[2], "value", r[3]);
            return;
        }
        List<String> names = new ArrayList<>(plugin.settings().plugins().keySet());
        var stats = ledger.stats();
        stats.flowsToday().keySet().stream().filter(n -> names.stream().noneMatch(x -> x.equalsIgnoreCase(n))).forEach(names::add);
        if (r.length == 1) { names.clear(); names.add(r[0]); }
        for (String n : names) {
            Limit l = pm.limit(n.toLowerCase(Locale.ROOT));
            var st = ledger.policyStatus(n);
            msgs.send(s, "ce.policy-line", "plugin", n, "mode", l.mode().name().toLowerCase(Locale.ROOT),
                    "issue", limit(l.issuePerDay()), "treasury", limit(l.treasuryPerDay()),
                    "issue-left", limit(st.issueRemainingToday()), "treasury-left", limit(st.treasuryRemainingToday()));
        }
    }

    private String limit(long v) { return v == Long.MAX_VALUE ? msgs.plain("ce.unlimited") : currency.format(v); }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender s, @NotNull Command cmd, @NotNull String alias, @NotNull String[] a) {
        String prefix = a[a.length - 1].toLowerCase(Locale.ROOT);
        List<String> options = switch (a.length) {
            case 1 -> SUBS;
            case 2 -> switch (a[0].toLowerCase(Locale.ROOT)) {
                case "audit" -> List.of("player", "plugin", "tx", "top");
                case "give", "take" -> Bukkit.getOnlinePlayers().stream().map(Player::getName).toList();
                case "import" -> importer.others();
                case "policy" -> List.of("set");
                default -> List.of();
            };
            case 3 -> a[0].equalsIgnoreCase("audit") && a[1].equalsIgnoreCase("player") ? Bukkit.getOnlinePlayers().stream().map(Player::getName).toList()
                    : a[0].equalsIgnoreCase("policy") && a[1].equalsIgnoreCase("set") ? new ArrayList<>(plugin.settings().plugins().keySet()) : List.of();
            case 4 -> a[0].equalsIgnoreCase("policy") && a[1].equalsIgnoreCase("set") ? POLICY_KEYS : List.of();
            default -> List.of();
        };
        return options.stream().filter(o -> o.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
