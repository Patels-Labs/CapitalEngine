package com.patelheet.capitalengine.core.audit;

import com.patelheet.capitalengine.api.Posting;
import com.patelheet.capitalengine.api.TxRecord;
import com.patelheet.capitalengine.core.Messages;
import com.patelheet.capitalengine.core.activity.ActivityTracker;
import com.patelheet.capitalengine.core.api.CurrencyImpl;
import com.patelheet.capitalengine.core.ledger.Acct;
import com.patelheet.capitalengine.core.ledger.Ledger;
import com.patelheet.capitalengine.core.storage.JournalRepo;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executor;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;

/** The read side of {@code /ce audit}: every coin movement, searchable. Runs on the async pool and replies from there. */
public final class AuditView {
    private static final int PAGE = 10;

    private final Ledger ledger;
    private final JournalRepo repo;
    private final ActivityTracker players;
    private final CurrencyImpl currency;
    private final Messages msgs;
    private final Executor async;

    public AuditView(Ledger ledger, JournalRepo repo, ActivityTracker players, CurrencyImpl currency, Messages msgs, Executor async) {
        this.ledger = ledger; this.repo = repo; this.players = players; this.currency = currency; this.msgs = msgs; this.async = async;
    }

    private interface Job { void run() throws SQLException; }

    private void later(CommandSender to, Job job) {
        ledger.barrier().thenRunAsync(() -> {
            try { job.run(); } catch (SQLException e) { msgs.send(to, "audit.error", "error", e.getMessage()); }
        }, async);
    }

    public void player(CommandSender to, String name, int page) {
        UUID id = players.uuidOf(name);
        if (id == null) { msgs.send(to, "unknown-player", "name", name); return; }
        later(to, () -> {
            long before = Long.MAX_VALUE;
            List<TxRecord> list = repo.history(id, PAGE * Math.max(1, page), before);
            List<TxRecord> shown = list.subList(Math.min(list.size(), PAGE * (Math.max(1, page) - 1)), list.size());
            msgs.send(to, "audit.player-header", "name", players.nameOf(id), "page", Math.max(1, page));
            if (shown.isEmpty()) msgs.send(to, "audit.empty");
            for (TxRecord r : shown) {
                long mine = r.postings().stream().filter(p -> p.accountId().equals(id)).mapToLong(Posting::amount).sum();
                line(to, r, mine);
            }
        });
    }

    public void plugin(CommandSender to, String name, int days) {
        later(to, () -> {
            long fromDay = ledger.dayIndex() - days;
            long since = System.currentTimeMillis() - days * 86_400_000L;
            long[] f = repo.flows(fromDay).entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(new long[5]);
            msgs.send(to, "audit.plugin-header", "name", name, "days", days);
            msgs.send(to, "audit.plugin-flows", "issued", currency.format(f[0]), "destroyed", currency.format(f[1]), "tin", currency.format(f[2]),
                    "tout", currency.format(f[3]), "net", currency.format(f[0] - f[1]));
            List<TxRecord> list = repo.byPlugin(name, since, PAGE, 0);
            if (list.isEmpty()) msgs.send(to, "audit.empty");
            for (TxRecord r : list) line(to, r, r.postings().stream().mapToLong(Posting::amount).filter(a -> a > 0).sum());
        });
    }

    public void tx(CommandSender to, long id) {
        later(to, () -> {
            var found = repo.find(id);
            if (found.isEmpty()) { msgs.send(to, "audit.tx-not-found", "id", id); return; }
            TxRecord r = found.get();
            msgs.send(to, "audit.tx-header", "id", r.txId(), "type", r.type(), "plugin", r.sourcePlugin(), "time", Instant.ofEpochMilli(r.time().toEpochMilli()).toString());
            msgs.send(to, "audit.tx-memo", "memo", r.memo());
            for (Posting p : r.postings()) {
                Acct a = ledger.byId(p.accountId());
                msgs.send(to, "audit.tx-posting", "account", a == null ? p.accountId().toString() : a.name(), "amount", amount(p.amount()));
            }
            if (r.reference() != null) msgs.send(to, "audit.tx-reference", "reference", r.reference());
            if (r.reversesTxId() != null) msgs.send(to, "audit.tx-reverses", "id", r.reversesTxId());
            repo.reversedBy(id).ifPresent(by -> msgs.send(to, "audit.tx-reversed", "id", by));
        });
    }

    public void top(CommandSender to, int days) {
        later(to, () -> {
            List<Map.Entry<String, long[]>> rows = new ArrayList<>(repo.flows(ledger.dayIndex() - days).entrySet());
            rows.sort(Comparator.comparingLong((Map.Entry<String, long[]> e) -> e.getValue()[0] - e.getValue()[1]).reversed());
            msgs.send(to, "audit.top-header", "days", days);
            if (rows.isEmpty()) msgs.send(to, "audit.empty");
            int rank = 1;
            for (var e : rows) {
                long[] v = e.getValue();
                msgs.send(to, "audit.top-line", "rank", rank++, "name", e.getKey(), "net", amount(v[0] - v[1]), "issued", currency.format(v[0]), "destroyed", currency.format(v[1]));
            }
        });
    }

    private void line(CommandSender to, TxRecord r, long delta) {
        msgs.send(to, "audit.line", "id", r.txId(), "amount", amount(delta), "memo", r.memo(), "type", r.type(), "plugin", r.sourcePlugin());
    }

    private Component amount(long delta) {
        return Component.text((delta >= 0 ? "+" : "-") + currency.format(Math.abs(delta)), delta >= 0 ? NamedTextColor.GREEN : NamedTextColor.RED);
    }
}
