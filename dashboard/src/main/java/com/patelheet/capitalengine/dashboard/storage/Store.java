package com.patelheet.capitalengine.dashboard.storage;

import com.patelheet.capitalengine.api.Severity;
import com.patelheet.capitalengine.api.service.Unit;
import com.patelheet.capitalengine.dashboard.snapshot.Downsampler;
import com.patelheet.capitalengine.dashboard.snapshot.Downsampler.Point;
import com.patelheet.capitalengine.dashboard.snapshot.Snapshot;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The dashboard's own database: SQLite (default), MySQL/MariaDB or PostgreSQL, through HikariCP and plain JDBC. Schema comes
 * from {@code dashboard-db/<dialect>/V<n>__*.sql}; an optional table prefix lets it share a database with other plugins.
 * Thread-safe: every call borrows its own pooled connection (the web server, the snapshot worker and event threads all read it).
 */
public final class Store implements AutoCloseable {
    public enum Dialect {
        SQLITE, MYSQL, POSTGRES;
        String folder() { return name().toLowerCase(); }
    }

    public record Alert(long id, long firstTs, long lastTs, String source, String code, Severity severity, String message, int hits,
                        String ackedBy, long ackedTs) {
        public boolean open() { return ackedBy == null; }
    }

    public record MetricInfo(String key, String label, Unit unit, String provider) {}

    private static final Pattern TABLES = Pattern.compile("\\b(metrics|snapshots|daily|alerts|sessions|schema_version)\\b");

    private final Dialect d;
    private final HikariDataSource ds;
    private final String prefix;

    public Store(Dialect d, String jdbcUrl, String user, String pass, int pool, String prefix) throws SQLException {
        this.d = d;
        this.prefix = prefix;
        HikariConfig c = new HikariConfig();
        c.setPoolName("CapitalDashboard");
        c.setJdbcUrl(jdbcUrl);
        if (user != null && !user.isEmpty()) { c.setUsername(user); c.setPassword(pass); }
        c.setMaximumPoolSize(pool);
        if (d == Dialect.SQLITE) {
            c.addDataSourceProperty("journal_mode", "WAL");
            c.addDataSourceProperty("busy_timeout", "10000");
        }
        ds = new HikariDataSource(c);
        try { migrate(); } catch (IOException e) { throw new SQLException(e); }
    }

    /** Runs {@code dashboard-db/<dialect>/V<n>__*.sql} in order; {@code dashboard-db/migrations.txt} lists the file names (a jar can't be globbed). */
    private void migrate() throws SQLException, IOException {
        try (Connection c = ds.getConnection()) {
            try (Statement st = c.createStatement()) { st.execute(q("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)")); }
            int current = 0;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q("SELECT MAX(version) FROM schema_version"))) { if (rs.next()) current = rs.getInt(1); }
            for (String file : resource("dashboard-db/migrations.txt").lines().map(String::trim).filter(l -> !l.isEmpty()).toList()) {
                int version = Integer.parseInt(file.substring(1, file.indexOf("__")));
                if (version <= current) continue;
                String sql = resource("dashboard-db/" + d.folder() + "/" + file).lines().filter(l -> !l.strip().startsWith("--")).collect(Collectors.joining("\n"));
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

    private String upsert(String insert, String keys, String sets) {
        return insert + (d == Dialect.MYSQL ? " ON DUPLICATE KEY UPDATE " : " ON CONFLICT(" + keys + ") DO UPDATE SET ") + sets;
    }

    private String insertIgnore(String insert) {
        return d == Dialect.MYSQL ? insert.replaceFirst("INSERT", "INSERT IGNORE") : insert + " ON CONFLICT DO NOTHING";
    }

    // ---- tiny JDBC helpers -------------------------------------------------------------------------------------

    private interface Sql<T> { T run(Connection c) throws SQLException; }
    private interface Row<T> { T map(ResultSet rs) throws SQLException; }

    private <T> T use(Sql<T> f) {
        try (Connection c = ds.getConnection()) { return f.run(c); }
        catch (SQLException e) { throw new IllegalStateException(e); }
    }

    /** Runs {@code body} on one connection as one database transaction. */
    private <T> T tx(Sql<T> body) {
        return use(c -> {
            c.setAutoCommit(false);
            try { T r = body.run(c); c.commit(); return r; }
            catch (SQLException | RuntimeException e) { c.rollback(); throw e; }
            finally { c.setAutoCommit(true); }
        });
    }

    private <T> List<T> query(Connection c, String sql, Row<T> map, Object... args) throws SQLException {
        try (PreparedStatement ps = prep(c, sql, false, args); ResultSet rs = ps.executeQuery()) {
            List<T> out = new ArrayList<>();
            while (rs.next()) out.add(map.map(rs));
            return out;
        }
    }

    private <T> List<T> query(String sql, Row<T> map, Object... args) { return use(c -> query(c, sql, map, args)); }

    private int exec(Connection c, String sql, Object... args) throws SQLException {
        try (PreparedStatement ps = prep(c, sql, false, args)) { return ps.executeUpdate(); }
    }

    private int exec(String sql, Object... args) { return use(c -> exec(c, sql, args)); }

    private PreparedStatement prep(Connection c, String sql, boolean keys, Object... args) throws SQLException {
        PreparedStatement ps = keys ? c.prepareStatement(q(sql), Statement.RETURN_GENERATED_KEYS) : c.prepareStatement(q(sql));
        for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]);
        return ps;
    }

    private static Point point(ResultSet rs) throws SQLException { return new Point(rs.getString("metric"), rs.getLong("ts"), rs.getDouble("value")); }

    // ---- snapshots ---------------------------------------------------------------------------------------------

    /** Writes one 5-minute point per metric and keeps the catalogue (label, unit, provider) current. */
    public void saveSnapshot(Snapshot s) {
        tx(c -> {
            String meta = upsert("INSERT INTO metrics (metric, label, unit, provider) VALUES (?,?,?,?)", "metric", "label = ?, unit = ?, provider = ?");
            String point = insertIgnore("INSERT INTO snapshots (ts, metric, value, res) VALUES (?,?,?,'m5')");
            try (PreparedStatement pm = prep(c, meta, false); PreparedStatement pp = prep(c, point, false)) {
                for (Snapshot.Metric m : s.metrics()) {
                    if (!Double.isFinite(m.value())) continue;
                    String unit = m.unit() == null ? Unit.COUNT.name() : m.unit().name();
                    set(pm, m.key(), m.label(), unit, m.provider(), m.label(), unit, m.provider());
                    pm.addBatch();
                    set(pp, s.ts(), m.key(), m.value());
                    pp.addBatch();
                }
                pm.executeBatch();
                pp.executeBatch();
            }
            return null;
        });
    }

    private static void set(PreparedStatement ps, Object... args) throws SQLException { for (int i = 0; i < args.length; i++) ps.setObject(i + 1, args[i]); }

    /** Day totals ({@code CapitalDayEvent.endedDay()}); a day already stored is left as it is. */
    public void saveDaily(long day, Map<String, Double> values) {
        tx(c -> {
            try (PreparedStatement ps = prep(c, insertIgnore("INSERT INTO daily (day_index, metric, value) VALUES (?,?,?)"), false)) {
                for (var e : values.entrySet()) { if (!Double.isFinite(e.getValue())) continue; set(ps, day, e.getKey(), e.getValue()); ps.addBatch(); }
                ps.executeBatch();
            }
            return null;
        });
    }

    /**
     * 5-minute points older than 7 days become hourly; hourly points older than 90 days become daily. Cutoffs sit on bucket
     * boundaries so every bucket written is complete, and each step's insert and delete are one transaction. Returns points written.
     */
    public int downsample(long now) {
        return step("m5", "h1", Downsampler.align(now - Downsampler.KEEP_M5, Downsampler.H1), Downsampler.H1)
                + step("h1", "d1", Downsampler.align(now - Downsampler.KEEP_H1, Downsampler.D1), Downsampler.D1);
    }

    private int step(String from, String to, long cutoff, long bucket) {
        return tx(c -> {
            List<Point> old = query(c, "SELECT metric, ts, value FROM snapshots WHERE res=? AND ts<?", Store::point, from, cutoff);
            if (old.isEmpty()) return 0;
            List<Point> merged = Downsampler.bucket(old, bucket);
            try (PreparedStatement ps = prep(c, insertIgnore("INSERT INTO snapshots (ts, metric, value, res) VALUES (?,?,?,?)"), false)) {
                for (Point p : merged) { set(ps, p.ts(), p.metric(), p.value(), to); ps.addBatch(); }
                ps.executeBatch();
            }
            exec(c, "DELETE FROM snapshots WHERE res=? AND ts<?", from, cutoff);
            return merged.size();
        });
    }

    /** Every stored point of a metric since {@code from}, whatever its resolution, oldest first. */
    public List<Point> series(String metric, long from) {
        return query("SELECT metric, ts, value FROM snapshots WHERE metric=? AND ts>=? ORDER BY ts", Store::point, metric, from);
    }

    /** {day_index, value} pairs since {@code fromDay}, oldest first. */
    public List<double[]> dailySeries(String metric, long fromDay) {
        return query("SELECT day_index, value FROM daily WHERE metric=? AND day_index>=? ORDER BY day_index", rs -> new double[]{rs.getLong(1), rs.getDouble(2)}, metric, fromDay);
    }

    public OptionalDouble daily(String metric, long day) {
        List<Double> r = query("SELECT value FROM daily WHERE metric=? AND day_index=?", rs -> rs.getDouble(1), metric, day);
        return r.isEmpty() ? OptionalDouble.empty() : OptionalDouble.of(r.get(0));
    }

    /** Sums of every {@code core.flow.*} metric over the days {@code fromDay}..{@code toDay}. */
    public Map<String, Double> flowSums(long fromDay, long toDay) {
        Map<String, Double> m = new LinkedHashMap<>();
        query("SELECT metric, SUM(value) FROM daily WHERE metric LIKE 'core.flow.%' AND day_index>=? AND day_index<=? GROUP BY metric ORDER BY metric",
                rs -> m.put(rs.getString(1), rs.getDouble(2)), fromDay, toDay);
        return m;
    }

    /** Whether the metric was 0 in every 5-minute point of the last {@code span} ms, with points going back at least that far. */
    public boolean zeroSince(String metric, long now, long span) {
        return use(c -> {
            long oldest = query(c, "SELECT MIN(ts) FROM snapshots WHERE metric=? AND res='m5'", rs -> rs.getLong(1), metric).get(0);
            if (oldest == 0 || oldest > now - span) return false;
            List<double[]> r = query(c, "SELECT COUNT(*), MAX(ABS(value)) FROM snapshots WHERE metric=? AND res='m5' AND ts>=?", rs -> new double[]{rs.getLong(1), rs.getDouble(2)}, metric, now - span);
            return r.get(0)[0] > 0 && r.get(0)[1] == 0;
        });
    }

    public List<MetricInfo> catalogue() {
        return query("SELECT metric, label, unit, provider FROM metrics ORDER BY provider, metric", rs -> new MetricInfo(rs.getString(1), rs.getString(2), unit(rs.getString(3)), rs.getString(4)));
    }

    private static Unit unit(String s) { try { return Unit.valueOf(s); } catch (RuntimeException e) { return Unit.COUNT; } }

    /** Plugins that have ever had flows (so {@code dash.unknown_plugin} fires once per plugin, across restarts). */
    public Set<String> knownPlugins() {
        Set<String> out = new TreeSet<>();
        for (String k : query("SELECT metric FROM metrics WHERE metric LIKE 'core.flow.%'", rs -> rs.getString(1))) {
            String rest = k.substring(Snapshot.FLOW.length());
            int dot = rest.lastIndexOf('.');
            if (dot > 0) out.add(rest.substring(0, dot));
        }
        return out;
    }

    // ---- alerts ------------------------------------------------------------------------------------------------

    private static Alert alert(ResultSet rs) throws SQLException {
        long acked = rs.getLong("acked_ts");
        Severity sev;
        try { sev = Severity.valueOf(rs.getString("severity")); } catch (RuntimeException e) { sev = Severity.INFO; }
        return new Alert(rs.getLong("id"), rs.getLong("first_ts"), rs.getLong("last_ts"), rs.getString("source"), rs.getString("code"), sev,
                rs.getString("message"), rs.getInt("hits"), rs.getString("acked_by"), acked);
    }

    /** The newest alert with this source and code seen since {@code since}, or null. */
    public Alert recent(String source, String code, long since) {
        return query("SELECT * FROM alerts WHERE source=? AND code=? AND last_ts>=? ORDER BY last_ts DESC", Store::alert, source, code, since).stream().findFirst().orElse(null);
    }

    public long insertAlert(long ts, String source, String code, Severity sev, String message) {
        return use(c -> {
            try (PreparedStatement ps = prep(c, "INSERT INTO alerts (first_ts, last_ts, source, code, severity, message, hits) VALUES (?,?,?,?,?,?,1)", true, ts, ts, source, code, sev.name(), message)) {
                ps.executeUpdate();
                try (ResultSet k = ps.getGeneratedKeys()) { return k.next() ? k.getLong(1) : 0L; }
            }
        });
    }

    /** Counts a repeat: newer time and message, one more hit. */
    public void bumpAlert(long id, long ts, String message) { exec("UPDATE alerts SET hits = hits + 1, last_ts=?, message=? WHERE id=?", ts, message, id); }

    public List<Alert> alerts(boolean openOnly, int limit) {
        return query("SELECT * FROM alerts" + (openOnly ? " WHERE acked_by IS NULL" : "") + " ORDER BY last_ts DESC, id DESC", Store::alert).stream().limit(limit).toList();
    }

    public Alert alert(long id) { return query("SELECT * FROM alerts WHERE id=?", Store::alert, id).stream().findFirst().orElse(null); }

    public int openAlerts() { return query("SELECT COUNT(*) FROM alerts WHERE acked_by IS NULL", rs -> rs.getInt(1)).get(0); }

    /** Acknowledges one open alert, or every open alert when {@code id} is null. Returns how many changed. */
    public int ack(Long id, String by, long ts) {
        return id == null ? exec("UPDATE alerts SET acked_by=?, acked_ts=? WHERE acked_by IS NULL", by, ts)
                : exec("UPDATE alerts SET acked_by=?, acked_ts=? WHERE id=? AND acked_by IS NULL", by, ts, id);
    }

    // ---- web sessions (only the token's hash is stored) --------------------------------------------------------

    public void saveSession(String tokenHash, String player, long created, long expires, String ip) {
        exec("INSERT INTO sessions (token_hash, player_uuid, created, expires, last_ip) VALUES (?,?,?,?,?)", tokenHash, player, created, expires, ip);
    }

    /** Who owns a live session, or null. */
    public String session(String tokenHash, long now) {
        return query("SELECT player_uuid FROM sessions WHERE token_hash=? AND expires>?", rs -> rs.getString(1), tokenHash, now).stream().findFirst().orElse(null);
    }

    public int deleteExpiredSessions(long now) { return exec("DELETE FROM sessions WHERE expires<=?", now); }

    @Override public void close() { ds.close(); }
}
