package com.patelheet.capitalengine.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/** Last-fired clock indexes and the single-server lock row. */
public final class ClockRepo {
    private static final int LOCK_ID = 1;
    private final Database db;

    public ClockRepo(Database db) { this.db = db; }

    public Optional<Long> get(String key) throws SQLException {
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("SELECT v FROM clock WHERE k=?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? Optional.of(rs.getLong(1)) : Optional.empty(); }
        }
    }

    public void set(String key, long value) throws SQLException {
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement(db.upsert("clock", List.of("k"), List.of("v")))) {
            ps.setString(1, key); ps.setLong(2, value); ps.executeUpdate();
        }
    }

    /** Takes the lock unless another holder heartbeated within {@code staleMs}. ponytail: not atomic across racing servers; use a row lock if networks ever share a ledger. */
    public boolean tryLock(String holder, long now, long staleMs) throws SQLException {
        try (Connection c = db.conn()) {
            try (PreparedStatement ps = c.prepareStatement("SELECT holder, heartbeat FROM server_lock WHERE id=?")) {
                ps.setInt(1, LOCK_ID);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && !rs.getString(1).equals(holder) && now - rs.getLong(2) < staleMs) return false;
                }
            }
            heartbeat(c, holder, now);
            return true;
        }
    }

    public void heartbeat(String holder, long now) throws SQLException {
        try (Connection c = db.conn()) { heartbeat(c, holder, now); }
    }

    private void heartbeat(Connection c, String holder, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(db.upsert("server_lock", List.of("id"), List.of("holder", "heartbeat")))) {
            ps.setInt(1, LOCK_ID); ps.setString(2, holder); ps.setLong(3, now); ps.executeUpdate();
        }
    }

    public void release(String holder) throws SQLException {
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("DELETE FROM server_lock WHERE id=? AND holder=?")) {
            ps.setInt(1, LOCK_ID); ps.setString(2, holder); ps.executeUpdate();
        }
    }
}
