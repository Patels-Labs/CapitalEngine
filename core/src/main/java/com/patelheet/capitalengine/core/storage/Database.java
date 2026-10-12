package com.patelheet.capitalengine.core.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.stream.Collectors;

/** HikariCP pool plus the few places SQL differs between dialects. */
public final class Database implements AutoCloseable {
    public enum Dialect {
        SQLITE, MYSQL, POSTGRES;
        public String folder() { return name().toLowerCase(); }
    }

    public final Dialect dialect;
    private final HikariDataSource ds;

    public Database(Dialect dialect, String jdbcUrl, String user, String pass, int poolSize) {
        this.dialect = dialect;
        HikariConfig c = new HikariConfig();
        c.setPoolName("CapitalEngine");
        c.setJdbcUrl(jdbcUrl);
        if (user != null && !user.isEmpty()) { c.setUsername(user); c.setPassword(pass); }
        c.setMaximumPoolSize(poolSize);
        if (dialect == Dialect.SQLITE) {
            c.addDataSourceProperty("journal_mode", "WAL");
            c.addDataSourceProperty("busy_timeout", "10000");
            c.addDataSourceProperty("foreign_keys", "true");
        }
        this.ds = new HikariDataSource(c);
    }

    public Connection conn() throws SQLException { return ds.getConnection(); }

    /** INSERT that overwrites the value columns when the key columns already exist (absolute values only). */
    public String upsert(String table, List<String> keys, List<String> vals) {
        List<String> all = new java.util.ArrayList<>(keys);
        all.addAll(vals);
        String insert = "INSERT INTO " + table + " (" + String.join(",", all) + ") VALUES (" + marks(all.size()) + ")";
        if (dialect == Dialect.MYSQL)
            return insert + " ON DUPLICATE KEY UPDATE " + vals.stream().map(v -> v + "=VALUES(" + v + ")").collect(Collectors.joining(","));
        return insert + " ON CONFLICT(" + String.join(",", keys) + ") DO UPDATE SET "
                + vals.stream().map(v -> v + "=EXCLUDED." + v).collect(Collectors.joining(","));
    }

    /** INSERT that silently skips rows whose primary/unique key already exists. */
    public String insertIgnore(String table, List<String> cols) {
        String insert = "INTO " + table + " (" + String.join(",", cols) + ") VALUES (" + marks(cols.size()) + ")";
        return dialect == Dialect.MYSQL ? "INSERT IGNORE " + insert : "INSERT " + insert + " ON CONFLICT DO NOTHING";
    }

    private static String marks(int n) { return "?" + ",?".repeat(n - 1); }

    @Override public void close() { ds.close(); }
}
