package com.patelheet.capitalengine.core.importer;

import com.patelheet.capitalengine.api.Failure;
import com.patelheet.capitalengine.core.Messages;
import com.patelheet.capitalengine.core.activity.ActivityTracker;
import com.patelheet.capitalengine.core.api.CurrencyImpl;
import com.patelheet.capitalengine.core.ledger.Leg;
import com.patelheet.capitalengine.core.ledger.Ledger;
import com.patelheet.capitalengine.core.ledger.Req;
import java.util.List;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;

/**
 * {@code /ce import <provider>}: reads every balance from the old economy's own Vault provider and posts one
 * {@code core.import} issue per player with the reference {@code import:<provider>:<uuid>}, so a second run is a no-op.
 * ponytail: runs in one go on the main thread (Vault providers expect that); chunk it across ticks for 100k+ players.
 */
public final class Importer {
    private final Plugin core;
    private final Ledger ledger;
    private final ActivityTracker players;
    private final CurrencyImpl currency;
    private final Messages msgs;

    public Importer(Plugin core, Ledger ledger, ActivityTracker players, CurrencyImpl currency, Messages msgs) {
        this.core = core; this.ledger = ledger; this.players = players; this.currency = currency; this.msgs = msgs;
    }

    public void run(CommandSender to, String providerName, boolean dryRun) {
        Economy old = find(providerName);
        if (old == null) {
            msgs.send(to, "import.no-provider", "name", providerName, "found", String.join(", ", others()));
            return;
        }
        int imported = 0, already = 0, skipped = 0, zero = 0;
        long total = 0;
        for (OfflinePlayer op : Bukkit.getOfflinePlayers()) {
            double b = old.getBalance(op);
            long units;
            if (Double.isNaN(b) || Double.isInfinite(b) || b < 0) { skipped++; continue; }
            try { units = currency.fromDouble(b); } catch (IllegalArgumentException e) { skipped++; continue; }
            if (units == 0) { zero++; continue; }
            players.discover(op.getUniqueId(), op.getName(), op.getLastPlayed());
            if (dryRun) { imported++; total += units; continue; }
            var r = ledger.apply(new Req(Ledger.ADMIN, "core.import", msgs.plain("memo.import", "provider", providerName), null,
                    "import:" + providerName + ":" + op.getUniqueId(),
                    List.of(new Leg(Leg.Kind.ISSUE, null, ledger.player(op.getUniqueId(), op.getName()), units)), true)).result();
            if (r.success()) { imported++; total += units; }
            else if (r.failure() == Failure.DUPLICATE_REFERENCE) already++;
            else skipped++;
        }
        msgs.send(to, dryRun ? "import.dry-run" : "import.done", "count", imported, "total", currency.format(total), "already", already, "skipped", skipped, "zero", zero);
        if (!dryRun) msgs.send(to, "import.next-steps");
    }

    private Economy find(String name) {
        for (RegisteredServiceProvider<Economy> rsp : Bukkit.getServicesManager().getRegistrations(Economy.class)) {
            if (rsp.getPlugin() == core) continue;
            if (rsp.getPlugin().getName().equalsIgnoreCase(name) || rsp.getProvider().getName().equalsIgnoreCase(name)) return rsp.getProvider();
        }
        return null;
    }

    public List<String> others() {
        return Bukkit.getServicesManager().getRegistrations(Economy.class).stream().filter(r -> r.getPlugin() != core).map(r -> r.getPlugin().getName()).distinct().toList();
    }
}
