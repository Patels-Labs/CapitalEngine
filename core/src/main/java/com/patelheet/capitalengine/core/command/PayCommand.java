package com.patelheet.capitalengine.core.command;

import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.core.CoreConfig;
import com.patelheet.capitalengine.core.Messages;
import com.patelheet.capitalengine.core.activity.ActivityTracker;
import com.patelheet.capitalengine.core.api.ApiImpl;
import com.patelheet.capitalengine.core.api.CurrencyImpl;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * {@code /pay <player> <amount>}. Big payments ask first: a clickable prompt for Java, and the same text says
 * "or type /pay confirm" because chat clicks don't work through Geyser.
 * Bedrock players on a server with Floodgate get a yes/no form ({@link FloodgateForms}); everyone else gets the chat prompt.
 */
public final class PayCommand implements CommandExecutor, TabCompleter {
    private static final long CONFIRM_MS = 30_000;

    private record Pending(UUID to, long amount, long expires) {}

    private final Plugin plugin;
    private final ApiImpl api;
    private final ActivityTracker players;
    private final CurrencyImpl currency;
    private final Messages msgs;
    private final Supplier<CoreConfig> cfg;
    private final Map<UUID, Pending> pending = new ConcurrentHashMap<>();

    public PayCommand(Plugin plugin, ApiImpl api, ActivityTracker players, CurrencyImpl currency, Messages msgs, Supplier<CoreConfig> cfg) {
        this.plugin = plugin; this.api = api; this.players = players; this.currency = currency; this.msgs = msgs; this.cfg = cfg;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player p)) { msgs.send(sender, "pay.console"); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("confirm")) { confirm(p); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            msgs.send(p, pending.remove(p.getUniqueId()) != null ? "pay.cancelled" : "pay.nothing-pending");
            return true;
        }
        if (args.length != 2) { msgs.send(p, "pay.usage"); return true; }

        UUID to = players.uuidOf(args[0]);
        if (to == null) { Player online = Bukkit.getPlayerExact(args[0]); to = online == null ? null : online.getUniqueId(); }
        if (to == null) { msgs.send(p, "unknown-player", "name", args[0]); return true; }
        if (to.equals(p.getUniqueId())) { msgs.send(p, "pay.self"); return true; }
        long amount;
        try { amount = currency.parse(args[1]); } catch (IllegalArgumentException e) { msgs.send(p, "pay.invalid-amount"); return true; }
        CoreConfig c = cfg.get();
        if (amount <= 0 || amount < c.payMin()) { msgs.send(p, "pay.too-small", "min", currency.format(c.payMin())); return true; }
        long have = api.accounts().player(p.getUniqueId()).balance();
        if (amount > have) { msgs.send(p, "pay.not-enough", "balance", currency.format(have)); return true; }

        if (amount >= c.payConfirmAbove()) {
            pending.put(p.getUniqueId(), new Pending(to, amount, System.currentTimeMillis() + CONFIRM_MS));
            if (!askWithForm(p, players.nameOf(to), amount)) {
                msgs.send(p, "pay.confirm", "name", players.nameOf(to), "amount", currency.format(amount));
                msgs.send(p, "pay.confirm-typed");
            }
        } else pay(p, to, amount);
        return true;
    }

    /** Bedrock players with Floodgate get a pop-up instead of clickable chat. */
    private boolean askWithForm(Player p, String toName, long amount) {
        if (Bukkit.getPluginManager().getPlugin("floodgate") == null) return false;
        String amt = currency.format(amount);
        return FloodgateForms.askYesNo(p.getUniqueId(),
                msgs.plain("pay.form-title", "name", toName), msgs.plain("pay.form-content", "name", toName, "amount", amt),
                msgs.plain("pay.form-yes"), msgs.plain("pay.form-no"),
                () -> p.getScheduler().run(plugin, t -> confirm(p), null),
                () -> p.getScheduler().run(plugin, t -> msgs.send(p, pending.remove(p.getUniqueId()) != null ? "pay.cancelled" : "pay.nothing-pending"), null));
    }

    private void confirm(Player p) {
        Pending pe = pending.remove(p.getUniqueId());
        if (pe == null) { msgs.send(p, "pay.nothing-pending"); return; }
        if (System.currentTimeMillis() > pe.expires()) { msgs.send(p, "pay.expired"); return; }
        pay(p, pe.to(), pe.amount());
    }

    private void pay(Player p, UUID to, long amount) {
        String toName = players.nameOf(to);
        var from = api.accounts().player(p.getUniqueId());
        api.transfer(plugin, from, api.accounts().player(to), amount, "core.pay", msgs.plain("memo.pay", "from", p.getName(), "to", toName)).thenAccept(r ->
                p.getScheduler().run(plugin, t -> {
                    if (!r.success()) {
                        msgs.send(p, "pay.failed", "reason", r.failure() == Failure.INSUFFICIENT_FUNDS ? msgs.plain("pay.failed-funds") : r.message());
                        return;
                    }
                    msgs.send(p, "pay.sent", "name", toName, "amount", currency.format(amount));
                    Player target = Bukkit.getPlayer(to);
                    if (target != null) target.getScheduler().run(plugin, t2 -> msgs.send(target, "pay.received", "name", p.getName(), "amount", currency.format(amount)), null);
                }, null));
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> !n.equals(sender.getName()) && n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
