package com.patelheet.capitalengine.market.storage;

import com.patelheet.capitalengine.market.autopilot.Autopilot;
import com.patelheet.capitalengine.market.autopilot.PriceIndex;
import com.patelheet.capitalengine.market.book.Matcher;
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
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The market's own database: SQLite (default), MySQL/MariaDB or PostgreSQL, through HikariCP and plain JDBC. Schema comes from
 * {@code market-db/<dialect>/V<n>__*.sql}; an optional table prefix lets it share a database with other plugins.
 * Not thread-safe on purpose: {@code MarketService} runs every call on its one worker thread.
 */
public final class Store implements AutoCloseable {
    public enum Dialect {
        SQLITE, MYSQL, POSTGRES;
        String folder() { return name().toLowerCase(); }
    }

    public record Trade(long id, String item, String buyer, String seller, String serverSide, long price, int qty, long fee,
                        long day, long week, long orderId, String orderSide) {
        public long gross() { return price * qty; }
        public Trade withId(long id) { return new Trade(id, item, buyer, seller, serverSide, price, qty, fee, day, week, orderId, orderSide); }
    }

    public record OrderRow(long id, String item, String side, String owner, long price, int qty, int filled, long expires, String status) {
        public int left() { return qty - filled; }
    }

    /** kind is DELIVERY or BUILD (a build job's note says what to build, and {@code item} is null). */
    public record Job(long id, String poster, String item, int total, int done, long pay, String project, long expires, String status,
                      String kind, String assignee, String note) {
        public int left() { return total - done; }
    }

    private static final Pattern TABLES = Pattern.compile("\\b(stash|orders|trades|server_stock|item_values|day_stats|policy_state|jobs|job_deliveries|stalls|tutorial|flood_stats|schema_version)\\b");

    private final Dialect d;
    private final HikariDataSource ds;
    private final String prefix;
    private Connection txConn;   // set while inside tx()

    public Store(Dialect d, String jdbcUrl, String user, String pass, int pool, String prefix) throws SQLException {
        this.d = d;
        this.prefix = prefix;
        HikariConfig c = new HikariConfig();
        c.setPoolName("CapitalMarket");
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

    /** Runs {@code market-db/<dialect>/V<n>__*.sql} in order; {@code market-db/migrations.txt} lists the file names (a jar can't be globbed). */
    private void migrate() throws SQLException, IOException {
        try (Connection c = ds.getConnection()) {
            try (Statement st = c.createStatement()) { st.execute(q("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)")); }
            int current = 0;
            try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(q("SELECT MAX(version) FROM schema_version"))) { if (rs.next()) current = rs.getInt(1); }
            for (String file : resource("market-db/migrations.txt").lines().map(String::trim).filter(l -> !l.isEmpty()).toList()) {
                int version = Integer.parseInt(file.substring(1, file.indexOf("__")));
                if (version <= current) continue;
                String sql = resource("market-db/" + d.folder() + "/" + file).lines().filter(l -> !l.strip().startsWith("--")).collect(Collectors.joining("\n"));
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

    /** {@code INSERT ...} plus "if the key exists, apply these changes". Write the changes with the table name in front of columns. */
    private String upsert(String insert, String keys, String sets) {
        return insert + (d == Dialect.MYSQL ? " ON DUPLICATE KEY UPDATE " : " ON CONFLICT(" + keys + ") DO UPDATE SET ") + sets;
    }

    /** {@code INSERT ...} that silently skips an existing key. */
    private String insertIgnore(String insert) {
        return d == Dialect.MYSQL ? insert.replaceFirst("INSERT", "INSERT IGNORE") : insert + " ON CONFLICT DO NOTHING";
    }

    private String greatest(String a, String b) { return (d == Dialect.SQLITE ? "MAX(" : "GREATEST(") + a + ", " + b + ")"; }

    // ---- tiny JDBC helpers -------------------------------------------------------------------------------------

    private interface Sql<T> { T run(Connection c) throws SQLException; }

    /** Runs on the transaction's connection, or borrows one from the pool for just this statement. */
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

    private long one(String sql, Object... args) {
        List<Long> r = query(sql, rs -> { try { return rs.getLong(1); } catch (SQLException e) { throw new IllegalStateException(e); } }, args);
        return r.isEmpty() ? 0 : r.get(0);
    }

    private void exec(String sql, Object... args) {
        use(c -> { try (PreparedStatement ps = prep(c, sql, false, args)) { ps.executeUpdate(); } return null; });
    }

    /** INSERT that returns the new row's id. */
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

    // ---- stash -------------------------------------------------------------------------------------------------

    public void deposit(UUID p, String item, int n) {
        exec(upsert("INSERT INTO stash (uuid, item, qty, locked) VALUES (?,?,?,0)", "uuid, item", "qty = stash.qty + ?"), p.toString(), item, n, n);
    }

    public int stashFree(UUID p, String item) { return (int) one("SELECT qty - locked FROM stash WHERE uuid=? AND item=?", p.toString(), item); }

    /** item -> {qty, locked} */
    public Map<String, int[]> stash(UUID p) {
        Map<String, int[]> m = new java.util.TreeMap<>();
        query("SELECT item, qty, locked FROM stash WHERE uuid=? AND qty>0", rs -> m.put(s(rs, "item"), new int[]{(int) l(rs, "qty"), (int) l(rs, "locked")}), p.toString());
        return m;
    }

    /** Removes free (unlocked) items; false if there aren't enough. */
    public boolean withdraw(UUID p, String item, int n) {
        if (stashFree(p, item) < n) return false;
        exec("UPDATE stash SET qty = qty - ? WHERE uuid=? AND item=?", n, p.toString(), item);
        return true;
    }

    public void lock(UUID p, String item, int delta) { exec("UPDATE stash SET locked = locked + ? WHERE uuid=? AND item=?", delta, p.toString(), item); }

    /** Stash stacks in use, counting each item as ceil(qty / stackSize). */
    public int stashStacks(UUID p, Function<String, Integer> stackSize) {
        int stacks = 0;
        for (var e : stash(p).entrySet()) stacks += (e.getValue()[0] + stackSize.apply(e.getKey()) - 1) / stackSize.apply(e.getKey());
        return stacks;
    }

    // ---- orders ------------------------------------------------------------------------------------------------

    /** Open orders on one side, best first, without {@code exclude}'s own. */
    public List<Matcher.Order> resting(String item, String side, UUID exclude) {
        String order = side.equals("BUY") ? "unit_price DESC, id ASC" : "unit_price ASC, id ASC";
        return query("SELECT id, owner, unit_price, qty - filled AS remaining FROM orders WHERE item=? AND side=? AND status='OPEN' AND owner<>? ORDER BY " + order,
                rs -> new Matcher.Order(l(rs, "id"), s(rs, "owner"), l(rs, "unit_price"), (int) l(rs, "remaining")), item, side, exclude == null ? "" : exclude.toString());
    }

    /** Inserts a PENDING order (money not yet confirmed) and returns its id. */
    public long insertOrder(String item, String side, UUID owner, long price, int qty, long now, long expires, String status) {
        return insert("INSERT INTO orders (item, side, owner, unit_price, qty, created, expires, status) VALUES (?,?,?,?,?,?,?,?)", item, side, owner.toString(), price, qty, now, expires, status);
    }

    public void orderStatus(long id, String status) { exec("UPDATE orders SET status=? WHERE id=?", status, id); }

    public OrderRow order(long id) { return orders("SELECT * FROM orders WHERE id=?", id).stream().findFirst().orElse(null); }

    public List<OrderRow> openOrders(UUID owner) { return orders("SELECT * FROM orders WHERE owner=? AND status='OPEN' ORDER BY id", owner.toString()); }

    public List<OrderRow> orders(String sql, Object... args) {
        return query(sql, rs -> new OrderRow(l(rs, "id"), s(rs, "item"), s(rs, "side"), s(rs, "owner"), l(rs, "unit_price"), (int) l(rs, "qty"), (int) l(rs, "filled"), l(rs, "expires"), s(rs, "status")), args);
    }

    /** Sum of (qty - filled) × price over open buy orders: what the escrow account must hold. */
    public long buyEscrow() { return one("SELECT COALESCE(SUM((qty - filled) * unit_price), 0) FROM orders WHERE side='BUY' AND status='OPEN'"); }

    // ---- trades ------------------------------------------------------------------------------------------------

    public long insertPending(Trade t, long now) {
        return insert("INSERT INTO trades (item, buyer, seller, server_side, unit_price, qty, fee, day, week, order_id, order_side, ts, status) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'PENDING')",
                t.item(), t.buyer(), t.seller(), t.serverSide(), t.price(), t.qty(), t.fee(), t.day(), t.week(), t.orderId(), t.orderSide(), now);
    }

    public void failTrade(long id) { exec("UPDATE trades SET status='FAILED' WHERE id=?", id); }

    public List<Trade> pendingTrades() {
        return query("SELECT * FROM trades WHERE status='PENDING'", rs -> new Trade(l(rs, "id"), s(rs, "item"), s(rs, "buyer"), s(rs, "seller"), s(rs, "server_side"),
                l(rs, "unit_price"), (int) l(rs, "qty"), l(rs, "fee"), l(rs, "day"), l(rs, "week"), l(rs, "order_id"), s(rs, "order_side")));
    }

    /** Applies a confirmed trade's item effects and marks it DONE, all in one transaction. Recovery uses this too. */
    public void finishTrade(Trade t) {
        tx(() -> {
            int n = t.qty();
            if (t.seller() != null) {
                exec("UPDATE stash SET qty = qty - ?, locked = locked - ? WHERE uuid=? AND item=?", n, "SELL".equals(t.orderSide()) ? n : 0, t.seller(), t.item());
            }
            if (t.buyer() != null) deposit(UUID.fromString(t.buyer()), t.item(), n);
            if ("BUY".equals(t.serverSide())) addStock(t.item(), n);
            if ("SELL".equals(t.serverSide())) addStock(t.item(), -n);
            if (t.orderId() > 0) exec("UPDATE orders SET status = CASE WHEN filled + ? >= qty THEN 'DONE' ELSE status END, filled = filled + ? WHERE id=?", n, n, t.orderId());
            exec("UPDATE trades SET status='DONE' WHERE id=?", t.id());
        });
    }

    /** Coins the server has paid for items today, counting trades still in flight. */
    public long serverSpentToday(long day) { return one("SELECT COALESCE(SUM(unit_price * qty), 0) FROM trades WHERE server_side='BUY' AND day=? AND status<>'FAILED'", day); }

    public Map<String, Integer> serverBoughtByItem(long day) {
        Map<String, Integer> m = new HashMap<>();
        query("SELECT item, SUM(qty) AS q FROM trades WHERE server_side='BUY' AND day=? AND status<>'FAILED' GROUP BY item", rs -> m.put(s(rs, "item"), (int) l(rs, "q")), day);
        return m;
    }

    /** Forgets old trades: only the last few weeks feed prices. */
    public void pruneTrades(long beforeWeek) { exec("DELETE FROM trades WHERE week<? AND status<>'PENDING'", beforeWeek); }

    /** Player-to-player trades since {@code fromWeek}, per item. */
    public Map<String, List<PriceIndex.Pt>> playerTrades(long fromWeek) {
        Map<String, List<PriceIndex.Pt>> m = new HashMap<>();
        query("SELECT item, week, unit_price, qty FROM trades WHERE status='DONE' AND server_side IS NULL AND week>=?",
                rs -> m.computeIfAbsent(s(rs, "item"), k -> new ArrayList<>()).add(new PriceIndex.Pt(l(rs, "week"), l(rs, "unit_price"), (int) l(rs, "qty"))), fromWeek);
        return m;
    }

    /** Traded value per item (players and server) since {@code fromWeek}. */
    public Map<String, Long> tradedValue(long fromWeek) {
        Map<String, Long> m = new HashMap<>();
        query("SELECT item, SUM(unit_price * qty) AS v FROM trades WHERE status='DONE' AND week>=? GROUP BY item", rs -> m.put(s(rs, "item"), l(rs, "v")), fromWeek);
        return m;
    }

    /** Share of recent trade value that was player to player. */
    public double playerShare(long fromWeek) {
        long all = one("SELECT COALESCE(SUM(unit_price * qty), 0) FROM trades WHERE status='DONE' AND week>=?", fromWeek);
        long players = one("SELECT COALESCE(SUM(unit_price * qty), 0) FROM trades WHERE status='DONE' AND server_side IS NULL AND week>=?", fromWeek);
        return all == 0 ? 0 : (double) players / all;
    }

    public int playerTradeCount(String item, long fromWeek) { return (int) one("SELECT COUNT(*) FROM trades WHERE status='DONE' AND server_side IS NULL AND item=? AND week>=?", item, fromWeek); }

    // ---- server stock and values -------------------------------------------------------------------------------

    public long stock(String item) { return one("SELECT qty FROM server_stock WHERE item=?", item); }

    public void addStock(String item, long delta) {
        exec(upsert("INSERT INTO server_stock (item, qty) VALUES (?, " + greatest("0", "?") + ")", "item", "qty = " + greatest("0", "server_stock.qty + ?")), item, delta, delta);
    }

    public void setStock(String item, long qty) { exec(upsert("INSERT INTO server_stock (item, qty) VALUES (?,?)", "item", "qty = ?"), item, qty, qty); }

    public Map<String, Long> allStock() {
        Map<String, Long> m = new HashMap<>();
        query("SELECT item, qty FROM server_stock", rs -> m.put(s(rs, "item"), l(rs, "qty")));
        return m;
    }

    /** The server uses up a share of every stock each day (rounded up so small stocks drain). */
    public void useUpStock(double rate) {
        tx(() -> allStock().forEach((item, qty) -> { if (qty > 0) exec("UPDATE server_stock SET qty = ? WHERE item=?", Math.max(0, qty - (long) Math.ceil(qty * rate)), item); }));
    }

    /** Prices that real player trades have moved away from the recipe or config value. */
    public Map<String, Long> drifted() {
        Map<String, Long> m = new HashMap<>();
        query("SELECT item, base FROM item_values WHERE source='drift'", rs -> m.put(s(rs, "item"), l(rs, "base")));
        return m;
    }

    public void clearDrift() { exec("UPDATE item_values SET source='recipe' WHERE source='drift'"); }

    public Map<String, Long> lastRefs() {
        Map<String, Long> m = new HashMap<>();
        query("SELECT item, last_ref FROM item_values WHERE last_ref IS NOT NULL", rs -> m.put(s(rs, "item"), l(rs, "last_ref")));
        return m;
    }

    public void saveValue(String item, long base, long lastRef, String source) {
        exec(upsert("INSERT INTO item_values (item, base, last_ref, source) VALUES (?,?,?,?)", "item", "base = ?, last_ref = ?, source = ?"), item, base, lastRef, source, base, lastRef, source);
    }

    // ---- days and policy ---------------------------------------------------------------------------------------

    public void saveDay(long day, long treasuryIn, long activePlayers) {
        exec(upsert("INSERT INTO day_stats (day, treasury_in, active_players) VALUES (?,?,?)", "day", "treasury_in = ?, active_players = ?"), day, treasuryIn, activePlayers, treasuryIn, activePlayers);
    }

    public long avgTreasuryIn7d() { return one("SELECT COALESCE(AVG(treasury_in), 0) FROM (SELECT treasury_in FROM day_stats ORDER BY day DESC LIMIT 7) recent"); }

    /** Average active players over the last 7 recorded days, or -1 before any day has been recorded. */
    public double avgActive7d() {
        List<Double> r = query("SELECT AVG(active_players) AS a, COUNT(*) AS n FROM (SELECT active_players FROM day_stats ORDER BY day DESC LIMIT 7) recent",
                rs -> { try { return rs.getLong("n") == 0 ? -1.0 : rs.getDouble("a"); } catch (SQLException e) { throw new IllegalStateException(e); } });
        return r.isEmpty() ? -1 : r.get(0);
    }

    public void savePolicy(Autopilot.State s) {
        exec(upsert("INSERT INTO policy_state (week, rate, savings_budget, jobs_budget, buyer_budget, spread, idx, inflation, new_money) VALUES (?,?,?,?,?,?,?,?,?)", "week",
                "rate = ?, savings_budget = ?, jobs_budget = ?, buyer_budget = ?, spread = ?, idx = ?, inflation = ?, new_money = ?"),
                s.week(), s.rate(), s.savings(), s.jobs(), s.buyer(), s.spread(), s.index(), s.inflation(), s.newMoney(),
                s.rate(), s.savings(), s.jobs(), s.buyer(), s.spread(), s.index(), s.inflation(), s.newMoney());
    }

    public Autopilot.State latestPolicy() {
        return query("SELECT * FROM policy_state ORDER BY week DESC LIMIT 1", rs -> {
            try { return new Autopilot.State(l(rs, "week"), rs.getDouble("rate"), l(rs, "savings_budget"), l(rs, "jobs_budget"), l(rs, "buyer_budget"), rs.getDouble("spread"), rs.getDouble("idx"), rs.getDouble("inflation"), l(rs, "new_money")); }
            catch (SQLException e) { throw new IllegalStateException(e); }
        }).stream().findFirst().orElse(null);
    }

    public int weeksRun() { return (int) one("SELECT COUNT(*) FROM policy_state"); }

    // ---- jobs --------------------------------------------------------------------------------------------------

    public long insertJob(String poster, String item, int total, long pay, String project, long now, long expires, String status) {
        return insert("INSERT INTO jobs (poster, item, qty_total, pay_unit, project, created, expires, status) VALUES (?,?,?,?,?,?,?,?)", poster, item, total, pay, project, now, expires, status);
    }

    public long insertBuildJob(String poster, long pay, String note, long now, long expires, String status) {
        return insert("INSERT INTO jobs (poster, qty_total, pay_unit, created, expires, status, kind, note) VALUES (?,1,?,?,?,?,'BUILD',?)", poster, pay, now, expires, status, note);
    }

    public void assign(long id, String player) { exec("UPDATE jobs SET assignee=?, status='ACCEPTED' WHERE id=?", player, id); }

    public void jobStatus(long id, String status) { exec("UPDATE jobs SET status=? WHERE id=?", status, id); }

    public Job job(long id) { return jobs("SELECT * FROM jobs WHERE id=?", id).stream().findFirst().orElse(null); }

    public List<Job> openJobs() { return jobs("SELECT * FROM jobs WHERE status IN ('OPEN','ACCEPTED','DISPUTE') ORDER BY id"); }

    public List<Job> jobs(String sql, Object... args) {
        return query(sql, rs -> new Job(l(rs, "id"), s(rs, "poster"), s(rs, "item"), (int) l(rs, "qty_total"), (int) l(rs, "qty_done"), l(rs, "pay_unit"), s(rs, "project"), l(rs, "expires"), s(rs, "status"), s(rs, "kind"), s(rs, "assignee"), s(rs, "note")), args);
    }

    public boolean serverJobOpen(String item) { return one("SELECT COUNT(*) FROM jobs WHERE poster IS NULL AND project IS NULL AND item=? AND status='OPEN'", item) > 0; }

    public boolean projectJobExists(String project, String item) { return one("SELECT COUNT(*) FROM jobs WHERE project=? AND item=?", project, item) > 0; }

    /** Sum of unpaid pay on open player jobs: what the jobs escrow account must hold. */
    public long jobsEscrow() { return one("SELECT COALESCE(SUM((qty_total - qty_done) * pay_unit), 0) FROM jobs WHERE poster IS NOT NULL AND status IN ('OPEN','ACCEPTED','DISPUTE')"); }

    public long insertDelivery(long jobId, UUID p, int qty, long paid, long day, boolean server) {
        return insert("INSERT INTO job_deliveries (job_id, uuid, qty, paid, day, server, status) VALUES (?,?,?,?,?,?,'PENDING')", jobId, p.toString(), qty, paid, day, server ? 1 : 0);
    }

    public void failDelivery(long id) { exec("UPDATE job_deliveries SET status='FAILED' WHERE id=?", id); }

    /** Takes the delivered items from the deliverer's stash, adds them to the job (and the poster's stash), closes a finished job. */
    public void finishDelivery(long deliveryId, long jobId, UUID p, int qty) {
        tx(() -> {
            Job j = job(jobId);
            exec("UPDATE stash SET qty = qty - ? WHERE uuid=? AND item=?", qty, p.toString(), j.item());
            if (j.poster() != null) deposit(UUID.fromString(j.poster()), j.item(), qty);
            exec("UPDATE jobs SET status = CASE WHEN qty_done + ? >= qty_total THEN 'DONE' ELSE status END, qty_done = qty_done + ? WHERE id=?", qty, qty, jobId);   // status first: MySQL applies SET left to right
            exec("UPDATE job_deliveries SET status='DONE' WHERE id=?", deliveryId);
        });
    }

    public record Delivery(long id, long jobId, UUID player, int qty) {}

    /** Deliveries that were in flight at a crash. */
    public List<Delivery> pendingDeliveries() {
        return query("SELECT id, job_id, uuid, qty FROM job_deliveries WHERE status='PENDING'", rs -> new Delivery(l(rs, "id"), l(rs, "job_id"), UUID.fromString(s(rs, "uuid")), (int) l(rs, "qty")));
    }

    public long serverJobPaidToday(long day) { return one("SELECT COALESCE(SUM(paid), 0) FROM job_deliveries WHERE server=1 AND day=? AND status<>'FAILED'", day); }

    // ---- stalls, tutorial, flood stats -------------------------------------------------------------------------

    public record Stall(String world, int x, int y, int z) {}

    public List<Stall> stalls() { return query("SELECT * FROM stalls", rs -> new Stall(s(rs, "world"), (int) l(rs, "x"), (int) l(rs, "y"), (int) l(rs, "z"))); }

    public void addStall(Stall st) { exec(insertIgnore("INSERT INTO stalls (world, x, y, z) VALUES (?,?,?,?)"), st.world(), st.x(), st.y(), st.z()); }

    public void removeStall(Stall st) { exec("DELETE FROM stalls WHERE world=? AND x=? AND y=? AND z=?", st.world(), st.x(), st.y(), st.z()); }

    /** The step a player is on (1-based; past the last step means done), starting them at 1 if new. */
    public int tutorialStep(UUID p) {
        exec(insertIgnore("INSERT INTO tutorial (uuid, step) VALUES (?, 1)"), p.toString());
        return (int) one("SELECT step FROM tutorial WHERE uuid=?", p.toString());
    }

    public void setTutorialStep(UUID p, int step) { exec("UPDATE tutorial SET step=? WHERE uuid=?", step, p.toString()); }

    /** "who\titem" -> average units sold per day. */
    public Map<String, Double> floodAll() {
        Map<String, Double> m = new HashMap<>();
        query("SELECT who, item, avg FROM flood_stats", rs -> { try { return m.put(s(rs, "who") + "\t" + s(rs, "item"), rs.getDouble("avg")); } catch (SQLException e) { throw new IllegalStateException(e); } });
        return m;
    }

    public void saveFlood(String who, String item, double avg) {
        exec(upsert("INSERT INTO flood_stats (who, item, avg) VALUES (?,?,?)", "who, item", "avg = ?"), who, item, avg, avg);
    }

    @Override public void close() { ds.close(); }
}
