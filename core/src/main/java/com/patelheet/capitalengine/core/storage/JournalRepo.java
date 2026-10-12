package com.patelheet.capitalengine.core.storage;

import com.patelheet.capitalengine.api.AccountKind;
import com.patelheet.capitalengine.api.Posting;
import com.patelheet.capitalengine.api.TxRecord;
import com.patelheet.capitalengine.core.ledger.Item;
import com.patelheet.capitalengine.core.ledger.Item.NewAccount;
import com.patelheet.capitalengine.core.ledger.Item.PendingTx;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Accounts, balances, journal, postings and per-day policy counters. Plain JDBC. */
public final class JournalRepo {
    public record AccountRow(UUID id, AccountKind kind, String plugin, String key, String name) {}
    public record RefRow(String plugin, String reference, long txId) {}

    private final Database db;

    public JournalRepo(Database db) { this.db = db; }

    // ---- group commit -----------------------------------------------------------------------------------------

    /** Writes one batch in a single database transaction: accounts, journal, postings, balances, counters. */
    public void write(Connection c, List<Item> items) throws SQLException {
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        try (PreparedStatement acc = c.prepareStatement(db.insertIgnore("accounts", List.of("id", "kind", "plugin", "acct_key", "owner_player", "name", "created_at")));
             PreparedStatement jr = c.prepareStatement(db.insertIgnore("journal", List.of("tx_id", "ts", "type", "memo", "source_plugin", "actor", "reference", "reverses_tx")));
             PreparedStatement po = c.prepareStatement(db.insertIgnore("postings", List.of("tx_id", "account_id", "amount")));
             PreparedStatement ba = c.prepareStatement(db.upsert("balances", List.of("account_id"), List.of("amount", "last_tx")));
             PreparedStatement pd = c.prepareStatement(db.upsert("policy_daily", List.of("day_index", "plugin"),
                     List.of("issued", "destroyed", "treasury_in", "treasury_out", "transferred")))) {
            for (Item it : items) {
                if (it instanceof NewAccount n) {
                    var a = n.acct();
                    acc.setString(1, a.id().toString()); acc.setString(2, a.kind().name()); acc.setString(3, a.plugin()); acc.setString(4, a.key());
                    acc.setString(5, a.ownerPlayer().map(UUID::toString).orElse(null)); acc.setString(6, a.name()); acc.setLong(7, n.createdAt());
                    acc.addBatch();
                } else if (it instanceof PendingTx t) {
                    TxRecord r = t.rec();
                    jr.setLong(1, r.txId()); jr.setLong(2, r.time().toEpochMilli()); jr.setString(3, r.type()); jr.setString(4, r.memo());
                    jr.setString(5, r.sourcePlugin()); jr.setString(6, r.actor() == null ? null : r.actor().toString()); jr.setString(7, r.reference());
                    if (r.reversesTxId() == null) jr.setNull(8, Types.BIGINT); else jr.setLong(8, r.reversesTxId());
                    jr.addBatch();
                    for (Posting p : r.postings()) {
                        po.setLong(1, r.txId()); po.setString(2, p.accountId().toString()); po.setLong(3, p.amount());
                        po.addBatch();
                    }
                    for (var b : t.balances().entrySet()) {
                        ba.setString(1, b.getKey().toString()); ba.setLong(2, b.getValue()); ba.setLong(3, r.txId());
                        ba.addBatch();
                    }
                    pd.setLong(1, t.day()); pd.setString(2, t.plugin());
                    for (int i = 0; i < 5; i++) pd.setLong(3 + i, t.counters()[i]);
                    pd.addBatch();
                }
            }
            acc.executeBatch(); jr.executeBatch(); po.executeBatch(); ba.executeBatch(); pd.executeBatch();
            c.commit();
        } catch (SQLException | RuntimeException e) {
            c.rollback();
            throw e;
        } finally { c.setAutoCommit(auto); }
    }

    // ---- startup loads ----------------------------------------------------------------------------------------

    public List<AccountRow> accounts() throws SQLException {
        List<AccountRow> out = new ArrayList<>();
        query("SELECT id, kind, plugin, acct_key, name FROM accounts", rs -> {
            out.add(new AccountRow(UUID.fromString(rs.getString(1)), AccountKind.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5)));
        });
        return out;
    }

    public Map<UUID, Long> balances() throws SQLException {
        Map<UUID, Long> out = new HashMap<>();
        query("SELECT account_id, amount FROM balances", rs -> out.put(UUID.fromString(rs.getString(1)), rs.getLong(2)));
        return out;
    }

    public long maxTx() throws SQLException {
        long[] m = {0};
        query("SELECT COALESCE(MAX(tx_id), 0) FROM journal", rs -> m[0] = rs.getLong(1));
        return m[0];
    }

    public void references(Consumer<RefRow> sink) throws SQLException {
        query("SELECT source_plugin, reference, tx_id FROM journal WHERE reference IS NOT NULL",
                rs -> sink.accept(new RefRow(rs.getString(1), rs.getString(2), rs.getLong(3))));
    }

    /** Counter rows of one day: lower-case plugin → {display via map key} counters. Keys are the stored (display) names. */
    public Map<String, long[]> counters(long day) throws SQLException {
        Map<String, long[]> out = new LinkedHashMap<>();
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("SELECT plugin, issued, destroyed, treasury_in, treasury_out, transferred FROM policy_daily WHERE day_index=?")) {
            ps.setLong(1, day);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)});
            }
        }
        return out;
    }

    // ---- reads ------------------------------------------------------------------------------------------------

    public Optional<TxRecord> find(long txId) throws SQLException {
        try (Connection c = db.conn()) { return find(c, txId); }
    }

    private Optional<TxRecord> find(Connection c, long txId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT tx_id, ts, type, memo, source_plugin, actor, reference, reverses_tx FROM journal WHERE tx_id=?")) {
            ps.setLong(1, txId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Optional.empty();
                String actor = rs.getString(6);
                long rev = rs.getLong(8);
                Long reverses = rs.wasNull() ? null : rev;
                List<Posting> postings = new ArrayList<>();
                try (PreparedStatement pp = c.prepareStatement("SELECT account_id, amount FROM postings WHERE tx_id=? ORDER BY amount")) {
                    pp.setLong(1, txId);
                    try (ResultSet pr = pp.executeQuery()) { while (pr.next()) postings.add(new Posting(UUID.fromString(pr.getString(1)), pr.getLong(2))); }
                }
                return Optional.of(new TxRecord(rs.getLong(1), Instant.ofEpochMilli(rs.getLong(2)), rs.getString(3), rs.getString(4), rs.getString(5),
                        actor == null ? null : UUID.fromString(actor), rs.getString(7), reverses, postings));
            }
        }
    }

    public List<TxRecord> history(UUID account, int limit, long beforeTx) throws SQLException {
        try (Connection c = db.conn()) {
            List<Long> ids = longs(c, "SELECT tx_id FROM postings WHERE account_id=? AND tx_id<? ORDER BY tx_id DESC LIMIT ?", account.toString(), beforeTx, limit);
            return records(c, ids);
        }
    }

    /** A plugin's transactions since {@code sinceMs}, newest first. */
    public List<TxRecord> byPlugin(String plugin, long sinceMs, int limit, int offset) throws SQLException {
        try (Connection c = db.conn()) {
            List<Long> ids = longs(c, "SELECT tx_id FROM journal WHERE LOWER(source_plugin)=? AND ts>=? ORDER BY tx_id DESC LIMIT ? OFFSET ?",
                    plugin.toLowerCase(), sinceMs, limit, offset);
            return records(c, ids);
        }
    }

    public Optional<Long> reversedBy(long txId) throws SQLException {
        try (Connection c = db.conn()) {
            List<Long> ids = longs(c, "SELECT tx_id FROM journal WHERE reverses_tx=?", txId);
            return ids.stream().findFirst();
        }
    }

    /** Σ(issued, destroyed, treasuryIn, treasuryOut, transferred) per plugin over days {@code > fromDay}. */
    public Map<String, long[]> flows(long fromDay) throws SQLException {
        Map<String, long[]> out = new LinkedHashMap<>();
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement(
                "SELECT plugin, SUM(issued), SUM(destroyed), SUM(treasury_in), SUM(treasury_out), SUM(transferred) FROM policy_daily WHERE day_index>? GROUP BY plugin")) {
            ps.setLong(1, fromDay);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) out.put(rs.getString(1), new long[] {rs.getLong(2), rs.getLong(3), rs.getLong(4), rs.getLong(5), rs.getLong(6)});
            }
        }
        return out;
    }

    public void prune(long beforeDay) throws SQLException {
        try (Connection c = db.conn()) {
            for (String t : List.of("policy_daily", "activity_daily"))
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM " + t + " WHERE day_index<?")) { ps.setLong(1, beforeDay); ps.executeUpdate(); }
        }
    }

    // ---- self-check queries (all limited to tx ≤ maxTx so they match a memory snapshot exactly) ---------------

    public long postingsTotal(long maxTx) throws SQLException {
        long[] v = {0};
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("SELECT COALESCE(SUM(amount), 0) FROM postings WHERE tx_id<=?")) {
            ps.setLong(1, maxTx);
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) v[0] = rs.getLong(1); }
        }
        return v[0];
    }

    public Map<UUID, Long> postingSums(long maxTx) throws SQLException {
        Map<UUID, Long> out = new HashMap<>();
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("SELECT account_id, SUM(amount) FROM postings WHERE tx_id<=? GROUP BY account_id")) {
            ps.setLong(1, maxTx);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.put(UUID.fromString(rs.getString(1)), rs.getLong(2)); }
        }
        return out;
    }

    /** Balance rows whose last_tx ≤ maxTx (untouched since the snapshot): account → {amount}. */
    public Map<UUID, Long> balanceRowsUpTo(long maxTx) throws SQLException {
        Map<UUID, Long> out = new HashMap<>();
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("SELECT account_id, amount FROM balances WHERE last_tx<=?")) {
            ps.setLong(1, maxTx);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.put(UUID.fromString(rs.getString(1)), rs.getLong(2)); }
        }
        return out;
    }

    // ---- helpers ----------------------------------------------------------------------------------------------

    private List<TxRecord> records(Connection c, List<Long> ids) throws SQLException {
        List<TxRecord> out = new ArrayList<>();
        for (long id : ids) find(c, id).ifPresent(out::add);
        return out;
    }

    private static List<Long> longs(Connection c, String sql, Object... args) throws SQLException {
        List<Long> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.add(rs.getLong(1)); }
        }
        return out;
    }

    private interface RowSink { void row(ResultSet rs) throws SQLException; }

    private void query(String sql, RowSink sink) throws SQLException {
        try (Connection c = db.conn(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(sql)) {
            while (rs.next()) sink.row(rs);
        }
    }
}
