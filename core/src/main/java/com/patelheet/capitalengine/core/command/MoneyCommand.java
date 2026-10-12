package com.patelheet.capitalengine.core.command;

import com.patelheet.capitalengine.core.Messages;
import com.patelheet.capitalengine.core.activity.ActivityTracker;
import com.patelheet.capitalengine.core.api.CurrencyImpl;
import com.patelheet.capitalengine.core.gui.MoneyMenu;
import com.patelheet.capitalengine.core.ledger.Acct;
import com.patelheet.capitalengine.core.ledger.Ledger;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

/** {@code /money}: your balance as a menu, or {@code /money <player>} (permission {@code capitalengine.money.others}). */
public final class MoneyCommand implements CommandExecutor, TabCompleter {
    private final MoneyMenu menu;
    private final Ledger ledger;
    private final ActivityTracker players;
    private final CurrencyImpl currency;
    private final Messages msgs;

    public MoneyCommand(MoneyMenu menu, Ledger ledger, ActivityTracker players, CurrencyImpl currency, Messages msgs) {
        this.menu = menu; this.ledger = ledger; this.players = players; this.currency = currency; this.msgs = msgs;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) menu.open(p); else msgs.send(sender, "money.console-usage");
            return true;
        }
        if (!sender.hasPermission("capitalengine.money.others") && !(sender instanceof Player p && p.getName().equalsIgnoreCase(args[0]))) {
            msgs.send(sender, "no-permission");
            return true;
        }
        UUID id = players.uuidOf(args[0]);
        Acct a = id == null ? null : ledger.byId(id);
        if (a == null) { msgs.send(sender, "unknown-player", "name", args[0]); return true; }
        msgs.send(sender, "money.balance-other", "name", a.name(), "amount", currency.format(a.balance()));
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command cmd, @NotNull String alias, @NotNull String[] args) {
        if (args.length != 1 || !sender.hasPermission("capitalengine.money.others")) return List.of();
        String prefix = args[0].toLowerCase(Locale.ROOT);
        return Bukkit.getOnlinePlayers().stream().map(Player::getName).filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).toList();
    }
}
