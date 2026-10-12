package com.patelheet.capitalengine.core.storage;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Players and per-day activity minutes. */
public final class PlayerRepo {
    public record Row(UUID uuid, String name, long lastSeen, boolean starterPaid) {}

    private final Database db;

    public PlayerRepo(Database db) { this.db = db; }

    public List<Row> all() throws SQLException {
        List<Row> out = new java.util.ArrayList<>();
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("SELECT uuid, name, last_seen, starter_paid FROM players"); ResultSet rs = ps.executeQuery()) {
            while (rs.next()) out.add(new Row(UUID.fromString(rs.getString(1)), rs.getString(2), rs.getLong(3), rs.getBoolean(4)));
        }
        return out;
    }

    public void join(UUID id, String name, long now) throws SQLException {
        try (Connection c = db.conn()) {
            try (PreparedStatement ps = c.prepareStatement(db.insertIgnore("players", List.of("uuid", "name", "first_seen", "last_seen", "starter_paid")))) {
                ps.setString(1, id.toString()); ps.setString(2, name); ps.setLong(3, now); ps.setLong(4, now); ps.setBoolean(5, false);
                ps.executeUpdate();
            }
            seen(c, id, name, now);
        }
    }

    public void quit(UUID id, long now) throws SQLException {
        try (Connection c = db.conn()) { seen(c, id, null, now); }
    }

    private static void seen(Connection c, UUID id, String name, long now) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(name == null ? "UPDATE players SET last_seen=? WHERE uuid=?" : "UPDATE players SET last_seen=?, name=? WHERE uuid=?")) {
            ps.setLong(1, now);
            if (name == null) ps.setString(2, id.toString()); else { ps.setString(2, name); ps.setString(3, id.toString()); }
            ps.executeUpdate();
        }
    }

    public void starterPaid(UUID id) throws SQLException {
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("UPDATE players SET starter_paid=? WHERE uuid=?")) {
            ps.setBoolean(1, true); ps.setString(2, id.toString()); ps.executeUpdate();
        }
    }

    public Map<UUID, Integer> activity(long day) throws SQLException {
        Map<UUID, Integer> out = new HashMap<>();
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("SELECT uuid, minutes FROM activity_daily WHERE day_index=?")) {
            ps.setLong(1, day);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.put(UUID.fromString(rs.getString(1)), rs.getInt(2)); }
        }
        return out;
    }

    public void saveActivity(long day, Map<UUID, Integer> minutes) throws SQLException {
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement(db.upsert("activity_daily", List.of("day_index", "uuid"), List.of("minutes")))) {
            c.setAutoCommit(false);
            for (var e : minutes.entrySet()) { ps.setLong(1, day); ps.setString(2, e.getKey().toString()); ps.setInt(3, e.getValue()); ps.addBatch(); }
            ps.executeBatch();
            c.commit();
        }
    }

    /** Active players per day (players with at least {@code minMinutes}) for days {@code > fromDay}. */
    public Map<Long, Integer> activePlayersPerDay(long fromDay, int minMinutes) throws SQLException {
        Map<Long, Integer> out = new HashMap<>();
        try (Connection c = db.conn(); PreparedStatement ps = c.prepareStatement("SELECT day_index, COUNT(*) FROM activity_daily WHERE day_index>? AND minutes>=? GROUP BY day_index")) {
            ps.setLong(1, fromDay); ps.setInt(2, minMinutes);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) out.put(rs.getLong(1), rs.getInt(2)); }
        }
        return out;
    }
}
