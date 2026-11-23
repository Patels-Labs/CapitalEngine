package com.patelheet.capitalengine.trade.storage;

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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * CapitalTrade's own database: SQLite (default), MySQL/MariaDB or PostgreSQL, through HikariCP and plain JDBC. Schema comes from
 * {@code trade-db/<dialect>/V<n>__*.sql}; an optional table prefix lets it share a database with other plugins.
 * Not thread-safe on purpose: {@code TradeService} runs every call on its one worker thread.
 */
public final class Store implements AutoCloseable {
    public enum Dialect {
        SQLITE, MYSQL, POSTGRES;
        String folder() { return name().toLowerCase(); }
    }

    /** One export or import. {@code direction} is EXPORT or IMPORT; {@code status} PENDING, DONE or FAILED. */
    public record Shipment(long id, String uuid, String item, String direction, int qty, long total, long tariff, long day, String status) {}

    /** {@code kind} is BLOCK or NPC; an NPC port remembers the entity's UUID and where it stood. */
    public record Port(long id, String name, String world, int x, int y, int z, String kind, String npcId) {}

    /** A world price: today's and yesterday's, and the day it was last moved. */
    public record Price(long day, long price, long prev) {}

    private static final Pattern TABLES = Pattern.compile("\\b(world_price|shipments|booms|ports|cargo|schema_version)\\b");

    private final Dialect d;
    private final HikariDataSource ds;
    private final String prefix;
    private Connection txConn;   // set while inside tx()

    public Store(Dialect d, String jdbcUrl, String user, String pass, int pool, String prefix) throws SQLException {
        this.d = d;
        this.prefix = prefix;
        HikariConfig c = new HikariConfig();
        c.setPoolName("CapitalTrade");
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

    /** Runs {@code trade-db/<dialect>/V<n>__*.sql} in order; {@code trade-db/migrations.txt} lists the file names (a jar can't be globbed). */
    private void migrate() throws SQLException, IOException {
        try (Connection c = ds.getConnection()) {
            try (Statement st = c.createStatement()) { st.execute(q("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)")); }
            int current = 0;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q("SELECT MAX(version) FROM schema_version"))) { if (rs.next()) current = rs.getInt(1); }
            for (String file : resource("trade-db/migrations.txt").lines().map(String::trim).filter(l -> !l.isEmpty()).toList()) {
                int version = Integer.parseInt(file.substring(1, file.indexOf("__")));
                if (version <= current) continue;
                String sql = resource("trade-db/" + d.folder() + "/" + file).lines().filter(l -> !l.strip().startsWith("--")).collect(Collectors.joining("\n"));
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

    /** {@code INSERT ...} plus "if the key exists, apply these changes". */
    private String upsert(String insert, String keys, String sets) {
        return insert + (d == Dialect.MYSQL ? " ON DUPLICATE KEY UPDATE " : " ON CONFLICT(" + keys + ") DO UPDATE SET ") + sets;
    }

    // ---- tiny JDBC helpers -------------------------------------------------------------------------------------

    private interface Sql<T> { T run(Connection c) throws SQLException; }

    private <T> T use(Sql<T> f) {
        try {
            if (txConn != null) return f.run(txConn);
            try (Connection c = ds.getConnection()) { return f.run(c); }
        } catch (SQLException e) { throw new IllegalStateException(e); }
    }

    private <T> List<T> query(String sql, Function<ResultSet, T> map, Object... args) {
        return use(c -> {
            try (PreparedStatement ps = prep(c, sql, false, args); ResultSet rs = ps.executeQuery()) {
                List<T> out = new ArrayList<>();
                while (rs.next()) out.add(map.apply(rs));
                return out;
            }
        });
    }

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

    private static String s(ResultSet rs, String col) { try { return rs.getString(col); } catch (SQLException e) { throw new IllegalStateException(e); } }
    private static long l(ResultSet rs, String col) { try { return rs.getLong(col); } catch (SQLException e) { throw new IllegalStateException(e); } }
    private static double dbl(ResultSet rs, String col) { try { return rs.getDouble(col); } catch (SQLException e) { throw new IllegalStateException(e); } }

    // ---- world prices and booms --------------------------------------------------------------------------------

    public Map<String, Price> prices() {
        Map<String, Price> m = new HashMap<>();
        query("SELECT * FROM world_price", rs -> m.put(s(rs, "item"), new Price(l(rs, "day"), l(rs, "price"), l(rs, "prev"))));
        return m;
    }

    public void savePrice(String item, Price p) {
        exec(upsert("INSERT INTO world_price (item, day, price, prev) VALUES (?,?,?,?)", "item",
                d == Dialect.MYSQL ? "day = VALUES(day), price = VALUES(price), prev = VALUES(prev)" : "day = excluded.day, price = excluded.price, prev = excluded.prev"),
                item, p.day(), p.price(), p.prev());
    }

    /** {item, multiplier} for a week, or null. */
    public Object[] boom(long week) {
        List<Object[]> r = query("SELECT item, multiplier FROM booms WHERE week=?", rs -> new Object[]{s(rs, "item"), dbl(rs, "multiplier")}, week);
        return r.isEmpty() ? null : r.get(0);
    }

    public void saveBoom(long week, String item, double mult) {
        exec(upsert("INSERT INTO booms (week, item, multiplier) VALUES (?,?,?)", "week",
                d == Dialect.MYSQL ? "item = VALUES(item), multiplier = VALUES(multiplier)" : "item = excluded.item, multiplier = excluded.multiplier"), week, item, mult);
    }

    // ---- shipments ---------------------------------------------------------------------------------------------

    public long insertShipment(UUID p, String item, String dir, int qty, long total, long tariff, long day, long ts) {
        return insert("INSERT INTO shipments (uuid, item, direction, qty, total, tariff, day, ts, status) VALUES (?,?,?,?,?,?,?,?,'PENDING')",
                p.toString(), item, dir, qty, total, tariff, day, ts);
    }

    public void status(long id, String status) { exec("UPDATE shipments SET status=? WHERE id=?", status, id); }

    public List<Shipment> pending() { return shipments("SELECT * FROM shipments WHERE status='PENDING'"); }

    public Shipment shipment(long id) { return shipments("SELECT * FROM shipments WHERE id=?", id).stream().findFirst().orElse(null); }

    private List<Shipment> shipments(String sql, Object... args) {
        return query(sql, rs -> new Shipment(l(rs, "id"), s(rs, "uuid"), s(rs, "item"), s(rs, "direction"), (int) l(rs, "qty"), l(rs, "total"),
                l(rs, "tariff"), l(rs, "day"), s(rs, "status")), args);
    }

    /** "item\tDIRECTION" -> {qty, total, tariff} for a day, counting PENDING rows too so a slow commit can't be sold twice. */
    public Map<String, long[]> dayTotals(long day) {
        Map<String, long[]> m = new HashMap<>();
        query("SELECT item, direction, SUM(qty) AS q, SUM(total) AS t, SUM(tariff) AS f FROM shipments WHERE day=? AND status IN ('PENDING','DONE') GROUP BY item, direction",
                rs -> m.put(s(rs, "item") + "\t" + s(rs, "direction"), new long[]{l(rs, "q"), l(rs, "t"), l(rs, "f")}), day);
        return m;
    }

    // ---- cargo -------------------------------------------------------------------------------------------------

    public void addCargo(String uuid, String item, int n) {
        exec(upsert("INSERT INTO cargo (uuid, item, qty) VALUES (?,?,?)", "uuid, item", "qty = cargo.qty + ?"), uuid, item, n, n);
    }

    /** item -> qty, A to Z. */
    public Map<String, Integer> cargo(UUID p) {
        Map<String, Integer> m = new TreeMap<>();
        query("SELECT item, qty FROM cargo WHERE uuid=? AND qty>0", rs -> m.put(s(rs, "item"), (int) l(rs, "qty")), p.toString());
        return m;
    }

    /** Takes everything out of a player's cargo and returns what it was. */
    public Map<String, Integer> takeCargo(UUID p) {
        Map<String, Integer> out = new TreeMap<>();
        tx(() -> { out.putAll(cargo(p)); exec("DELETE FROM cargo WHERE uuid=?", p.toString()); });
        return out;
    }

    // ---- ports -------------------------------------------------------------------------------------------------

    public List<Port> ports() {
        return query("SELECT * FROM ports ORDER BY id", rs -> new Port(l(rs, "id"), s(rs, "name"), s(rs, "world"), (int) l(rs, "x"), (int) l(rs, "y"), (int) l(rs, "z"),
                s(rs, "kind"), s(rs, "npc_id")));
    }

    public long addPort(Port p) {
        return insert("INSERT INTO ports (name, world, x, y, z, kind, npc_id) VALUES (?,?,?,?,?,?,?)", p.name(), p.world(), p.x(), p.y(), p.z(), p.kind(), p.npcId());
    }

    public void removePort(String name) { exec("DELETE FROM ports WHERE name=?", name); }

    @Override public void close() { ds.close(); }
}
