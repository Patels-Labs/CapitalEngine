package com.patelheet.capitalengine.bank.storage;

import com.patelheet.capitalengine.bank.math.BankMath;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The bank's own database: SQLite (default), MySQL/MariaDB or PostgreSQL, through HikariCP and plain JDBC. Schema comes from
 * {@code bank-db/<dialect>/V<n>__*.sql}; an optional table prefix lets it share a database with other plugins.
 * Savings balances are not here: they are core accounts. Not thread-safe on purpose: {@code BankService} runs every call on its one worker.
 */
public final class Store implements AutoCloseable {
    public enum Dialect {
        SQLITE, MYSQL, POSTGRES;
        String folder() { return name().toLowerCase(); }
    }

    /** status: PENDING (payout in flight), ACTIVE, OVERDUE, PAID, WRITTEN_OFF, FAILED (the payout never happened). */
    public record Loan(long id, UUID uuid, long principal, long interest, long instalment, int termDays, long startDay, long paidPrincipal,
                       long paidInterest, long arrears, String status, int overdueDays, boolean missed, long lastDay, Long closedDay) {
        public long principalLeft() { return principal - paidPrincipal; }
        public long interestLeft() { return interest - paidInterest; }
        public long left() { return principalLeft() + interestLeft(); }
        public boolean open() { return status.equals("ACTIVE") || status.equals("OVERDUE"); }
    }

    /** kind: DAY (the daily instalment) or PAY (early repayment and coins taken as they arrive). status: PENDING or DONE. */
    public record Payment(long id, long loanId, long day, String kind, long due, long principal, long interest, String reference, String status) {}

    public record Credit(UUID uuid, long limit, int cleanRepayments, Long pausedUntilDay) {}

    /** {@code pending} is the amount of an upkeep collection in flight under reference {@code upk:<uuid>:<seq>}, or 0. */
    public record Upkeep(UUID uuid, long area, long due, long lastChargedDay, boolean frozen, long lastWarnedDay, long seq, long pending) {}

    public record DayRun(long day, boolean catchUp, boolean done, long interestOwed, long interestPaid, double scale, long upkeepCollected) {}

    private static final Pattern TABLES = Pattern.compile("\\b(loans|loan_payments|credit|upkeep|players|day_runs|schema_version)\\b");

    private final Dialect d;
    private final HikariDataSource ds;
    private final String prefix;
    private Connection txConn;   // set while inside tx()

    public Store(Dialect d, String jdbcUrl, String user, String pass, int pool, String prefix) throws SQLException {
        this.d = d;
        this.prefix = prefix;
        HikariConfig c = new HikariConfig();
        c.setPoolName("CapitalBank");
        c.setJdbcUrl(jdbcUrl);
        if (user != null && !user.isEmpty()) { c.setUsername(user); c.setPassword(pass); }
        c.setMaximumPoolSize(pool);
        if (d == Dialect.SQLITE) {
            c.addDataSourceProperty("journal_mode", "WAL");   // synchronous stays FULL: a PENDING row must survive power loss before its money moves
            c.addDataSourceProperty("busy_timeout", "10000");
        }
        ds = new HikariDataSource(c);
        try { migrate(); } catch (IOException e) { throw new SQLException(e); }
    }

    /** Runs {@code bank-db/<dialect>/V<n>__*.sql} in order; {@code bank-db/migrations.txt} lists the file names (a jar can't be globbed). */
    private void migrate() throws SQLException, IOException {
        try (Connection c = ds.getConnection()) {
            try (Statement st = c.createStatement()) { st.execute(q("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)")); }
            int current = 0;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q("SELECT MAX(version) FROM schema_version"))) { if (rs.next()) current = rs.getInt(1); }
            for (String file : resource("bank-db/migrations.txt").lines().map(String::trim).filter(l -> !l.isEmpty()).toList()) {
                int version = Integer.parseInt(file.substring(1, file.indexOf("__")));
                if (version <= current) continue;
                String sql = resource("bank-db/" + d.folder() + "/" + file).lines().filter(l -> !l.strip().startsWith("--")).collect(Collectors.joining("\n"));
                c.setAutoCommit(false);
                try (Statement st = c.createStatement()) {
                    for (String stmt : sql.split(";\\s*\\n")) if (!stmt.isBlank()) st.execute(q(stmt.strip().replaceAll(";$", "")));
                    st.execute(q("INSERT INTO schema_version (version) VALUES (" + version + ")"));
                    c.commit();
                } catch (SQLException e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(true); }
            }
        }
    }

    private static String resource(String path) throws IOException {
        try (InputStream in = Store.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** Adds the table prefix to every table name in a statement. */
    private String q(String sql) { return prefix.isEmpty() ? sql : TABLES.matcher(sql).replaceAll(m -> prefix + m.group()); }

    // ---- the few places SQL differs between databases ------------------------------------------------------------

    /** {@code INSERT ...} plus "if the key exists, apply these changes". */
    private String upsert(String insert, String keys, String sets) {
        return insert + (d == Dialect.MYSQL ? " ON DUPLICATE KEY UPDATE " : " ON CONFLICT(" + keys + ") DO UPDATE SET ") + sets;
    }

    /** {@code INSERT ...} that silently skips an existing key. */
    private String insertIgnore(String insert) {
        return d == Dialect.MYSQL ? insert.replaceFirst("INSERT", "INSERT IGNORE") : insert + " ON CONFLICT DO NOTHING";
    }

    // ---- tiny JDBC helpers -------------------------------------------------------------------------------------

    private interface Sql<T> { T run(Connection c) throws SQLException; }
    private interface Row<T> { T map(ResultSet rs) throws SQLException; }

    private <T> T use(Sql<T> f) {
        try {
            if (txConn != null) return f.run(txConn);
            try (Connection c = ds.getConnection()) { return f.run(c); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private <T> List<T> query(String sql, Row<T> map, Object... args) {
        return use(c -> {
            try (PreparedStatement ps = prep(c, sql, false, args); ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(map.map(rs));
                return out;
            }
        });
    }

    private <T> T first(String sql, Row<T> map, Object... args) { List<T> r = query(sql, map, args); return r.isEmpty() ? null : r.get(0); }

    private long one(String sql, Object... args) { Long v = first(sql, rs -> rs.getLong(1), args); return v == null ? 0 : v; }

    private void exec(String sql, Object... args) {
        use(c -> { try (PreparedStatement ps = prep(c, sql, false, args)) { ps.executeUpdate(); } return null; });
    }

    private long insert(String sql, Object... args) {
        return use(c -> {
            try (PreparedStatement ps = prep(c, sql, true, args)) {
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getLong(1) : 0L; }
            }
        });
    }

    private PreparedStatement prep(Connection c, String sql, boolean keys, Object... args) throws SQLException {
        PreparedStatement ps = keys ? c.prepareStatement(q(sql), Statement.RETURN_GENERATED_KEYS) : c.prepareStatement(q(sql));
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
        return ps;
    }

    /** Runs {@code body} as one database transaction (a nested call joins the outer one). */
    public void tx(Runnable body) {
        if (txConn != null) { body.run(); return; }
        try (Connection c = ds.getConnection()) {
            c.setAutoCommit(false);
            txConn = c;
            try { body.run(); c.commit(); } catch (RuntimeException e) { c.rollback(); throw e; } finally { txConn = null; }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private static Long nullableLong(ResultSet rs, String col) throws SQLException { long v = rs.getLong(col); return rs.wasNull() ? null : v; }

    // ---- loans -------------------------------------------------------------------------------------------------

    private static Loan loan(ResultSet rs) throws SQLException {
        return new Loan(rs.getLong("id"), UUID.fromString(rs.getString("uuid").trim()), rs.getLong("principal"), rs.getLong("interest"), rs.getLong("instalment"),
                rs.getInt("term_days"), rs.getLong("start_day"), rs.getLong("paid_principal"), rs.getLong("paid_interest"), rs.getLong("arrears"),
                rs.getString("status"), rs.getInt("overdue_days"), rs.getInt("missed") != 0, rs.getLong("last_day"), nullableLong(rs, "closed_day"));
    }

    /** A new loan. {@code last_day} starts at the borrowing day, so the first instalment is taken the next day. */
    public long insertLoan(UUID p, BankMath.Terms t, long day, String status) {
        return insert("INSERT INTO loans (uuid, principal, interest, instalment, term_days, start_day, last_day, status) VALUES (?,?,?,?,?,?,?,?)",
                p.toString(), t.principal(), t.interest(), t.instalment(), t.days(), day, day, status);
    }

    public void loanStatus(long id, String status) { exec("UPDATE loans SET status=? WHERE id=?", status, id); }

    public void updateLoan(Loan l) {
        exec("UPDATE loans SET paid_principal=?, paid_interest=?, arrears=?, status=?, overdue_days=?, missed=?, last_day=?, closed_day=? WHERE id=?",
                l.paidPrincipal(), l.paidInterest(), l.arrears(), l.status(), l.overdueDays(), l.missed() ? 1 : 0, l.lastDay(), l.closedDay(), l.id());
    }

    public Loan loan(long id) { return first("SELECT * FROM loans WHERE id=?", Store::loan, id); }

    /** The player's one ACTIVE or OVERDUE loan, or null. */
    public Loan openLoan(UUID p) { return first("SELECT * FROM loans WHERE uuid=? AND status IN ('ACTIVE','OVERDUE') ORDER BY id DESC", Store::loan, p.toString()); }

    /** Whether the player has a loan that is open or whose payout is in flight. */
    public boolean hasLoan(UUID p) { return one("SELECT COUNT(*) FROM loans WHERE uuid=? AND status IN ('ACTIVE','OVERDUE','PENDING')", p.toString()) > 0; }

    public List<Loan> loans(String... statuses) {
        String in = java.util.Arrays.stream(statuses).map(s -> "'" + s + "'").collect(Collectors.joining(","));
        return query("SELECT * FROM loans WHERE status IN (" + in + ") ORDER BY id", Store::loan);
    }

    public List<Loan> loansOf(UUID p) { return query("SELECT * FROM loans WHERE uuid=? AND status<>'FAILED' ORDER BY id DESC", Store::loan, p.toString()); }

    /** Principal lent out and not yet repaid: the bank-wide cap is checked against this. */
    public long outstandingPrincipal() { return one("SELECT COALESCE(SUM(principal - paid_principal), 0) FROM loans WHERE status IN ('ACTIVE','OVERDUE','PENDING')"); }

    public long countLoans(String status) { return one("SELECT COUNT(*) FROM loans WHERE status=?", status); }

    public long writtenOffSince(long day) { return one("SELECT COUNT(*) FROM loans WHERE status='WRITTEN_OFF' AND closed_day>=?", day); }

    /** Loans that were open at some point since {@code day}. */
    public long loansSeenSince(long day) { return one("SELECT COUNT(*) FROM loans WHERE status IN ('ACTIVE','OVERDUE') OR (status IN ('PAID','WRITTEN_OFF') AND closed_day>=?)", day); }

    // ---- loan payments -----------------------------------------------------------------------------------------

    private static Payment payment(ResultSet rs) throws SQLException {
        return new Payment(rs.getLong("id"), rs.getLong("loan_id"), rs.getLong("day_index"), rs.getString("kind"), rs.getLong("due"),
                rs.getLong("principal"), rs.getLong("interest"), rs.getString("reference"), rs.getString("status"));
    }

    public long insertPayment(long loanId, long day, String kind, long due, long principal, long interest, String reference) {
        return insert("INSERT INTO loan_payments (loan_id, day_index, kind, due, principal, interest, reference, status) VALUES (?,?,?,?,?,?,?,'PENDING')",
                loanId, day, kind, due, principal, interest, reference);
    }

    public Payment payment(long id) { return first("SELECT * FROM loan_payments WHERE id=?", Store::payment, id); }

    public void paymentDone(long id) { exec("UPDATE loan_payments SET status='DONE' WHERE id=?", id); }

    /** A payment whose money never moved leaves no trace, so its reference can be used again. */
    public void deletePayment(long id) { exec("DELETE FROM loan_payments WHERE id=?", id); }

    public List<Payment> pendingPayments() { return query("SELECT * FROM loan_payments WHERE status='PENDING' ORDER BY id", Store::payment); }

    public List<Payment> donePayments(long loanId) { return query("SELECT * FROM loan_payments WHERE loan_id=? AND status='DONE' ORDER BY id", Store::payment, loanId); }

    public long paymentCount(long loanId) { return one("SELECT COUNT(*) FROM loan_payments WHERE loan_id=?", loanId); }

    // ---- credit ------------------------------------------------------------------------------------------------

    public Credit credit(UUID p, long startLimit) {
        Credit c = first("SELECT * FROM credit WHERE uuid=?", rs -> new Credit(p, rs.getLong("credit_limit"), rs.getInt("clean_repayments"), nullableLong(rs, "paused_until_day")), p.toString());
        return c != null ? c : new Credit(p, startLimit, 0, null);
    }

    public void saveCredit(Credit c) {
        exec(upsert("INSERT INTO credit (uuid, credit_limit, clean_repayments, paused_until_day) VALUES (?,?,?,?)", "uuid",
                "credit_limit = ?, clean_repayments = ?, paused_until_day = ?"),
                c.uuid().toString(), c.limit(), c.cleanRepayments(), c.pausedUntilDay(), c.limit(), c.cleanRepayments(), c.pausedUntilDay());
    }

    // ---- upkeep ------------------------------------------------------------------------------------------------

    private static Upkeep upkeep(ResultSet rs) throws SQLException {
        return new Upkeep(UUID.fromString(rs.getString("uuid").trim()), rs.getLong("area"), rs.getLong("due"), rs.getLong("last_charged_day"),
                rs.getInt("frozen") != 0, rs.getLong("last_warned_day"), rs.getLong("seq"), rs.getLong("pending"));
    }

    public Upkeep upkeep(UUID p) { return first("SELECT * FROM upkeep WHERE uuid=?", Store::upkeep, p.toString()); }

    /** Everyone who owns land, owes upkeep or is frozen. */
    public List<Upkeep> upkeepRows() { return query("SELECT * FROM upkeep WHERE area>0 OR due>0 OR frozen<>0 OR pending>0", Store::upkeep); }

    public void saveUpkeep(Upkeep u) {
        exec(upsert("INSERT INTO upkeep (uuid, area, due, last_charged_day, frozen, last_warned_day, seq, pending) VALUES (?,?,?,?,?,?,?,?)", "uuid",
                        "area = ?, due = ?, last_charged_day = ?, frozen = ?, last_warned_day = ?, seq = ?, pending = ?"),
                u.uuid().toString(), u.area(), u.due(), u.lastChargedDay(), u.frozen() ? 1 : 0, u.lastWarnedDay(), u.seq(), u.pending(),
                u.area(), u.due(), u.lastChargedDay(), u.frozen() ? 1 : 0, u.lastWarnedDay(), u.seq(), u.pending());
    }

    public long totalUpkeepDue() { return one("SELECT COALESCE(SUM(due), 0) FROM upkeep"); }

    // ---- players -----------------------------------------------------------------------------------------------

    /** Records that the player was here on {@code day} (join, quit and each Day event for those online). */
    public void seen(UUID p, long day) {
        exec(upsert("INSERT INTO players (uuid, last_seen_day) VALUES (?,?)", "uuid", "last_seen_day = ?"), p.toString(), day, day);
    }

    /** The last day the player was seen, or null if the bank has never seen them. */
    public Long lastSeen(UUID p) { return first("SELECT last_seen_day FROM players WHERE uuid=?", rs -> rs.getLong(1), p.toString()); }

    public void markSaver(UUID p, long day) {
        exec(insertIgnore("INSERT INTO players (uuid, last_seen_day) VALUES (?,?)"), p.toString(), day);
        exec("UPDATE players SET saver=1 WHERE uuid=?", p.toString());
    }

    /** Everyone who ever used savings, so the interest run knows whose accounts to look at. */
    public List<UUID> savers() { return query("SELECT uuid FROM players WHERE saver<>0 ORDER BY uuid", rs -> UUID.fromString(rs.getString(1).trim())); }

    // ---- day runs ----------------------------------------------------------------------------------------------

    private static DayRun dayRun(ResultSet rs) throws SQLException {
        return new DayRun(rs.getLong("day_index"), rs.getInt("catch_up") != 0, rs.getInt("done") != 0, rs.getLong("interest_owed"),
                rs.getLong("interest_paid"), rs.getDouble("scale"), rs.getLong("upkeep_collected"));
    }

    /** Durable "this day's run has started" marker, written before any money moves. */
    public void startDay(long day, boolean catchUp) {
        exec(insertIgnore("INSERT INTO day_runs (day_index, catch_up, done) VALUES (?,?,0)"), day, catchUp ? 1 : 0);
    }

    public void finishDay(long day) { exec("UPDATE day_runs SET done=1 WHERE day_index=?", day); }

    public DayRun dayRun(long day) { return first("SELECT * FROM day_runs WHERE day_index=?", Store::dayRun, day); }

    public List<DayRun> unfinishedDays() { return query("SELECT * FROM day_runs WHERE done=0 ORDER BY day_index", Store::dayRun); }

    public void saveInterest(long day, long owed, long paid, double scale) {
        exec("UPDATE day_runs SET interest_owed=?, interest_paid=?, scale=? WHERE day_index=?", owed, paid, scale, day);
    }

    public void addUpkeepCollected(long day, long n) {
        exec(insertIgnore("INSERT INTO day_runs (day_index, catch_up, done) VALUES (?,0,1)"), day);
        exec("UPDATE day_runs SET upkeep_collected = upkeep_collected + ? WHERE day_index=?", n, day);
    }

    @Override public void close() { ds.close(); }
}
