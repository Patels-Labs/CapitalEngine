package com.patelheet.capitalengine.core.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

/**
 * Runs {@code db/<dialect>/V<n>__*.sql} in order. {@code db/migrations.txt} lists the file names (a jar can't be globbed).
 * Statements are separated by a semicolon at the end of a line; {@code --} comment lines are ignored.
 */
public final class Migrations {
    private Migrations() {}

    public static void run(Database db) throws SQLException, IOException {
        try (Connection c = db.conn()) {
            try (Statement s = c.createStatement()) { s.execute("CREATE TABLE IF NOT EXISTS schema_version (version INT NOT NULL)"); }
            int current = 0;
            try (Statement s = c.createStatement(); ResultSet rs = s.executeQuery("SELECT MAX(version) FROM schema_version")) {
                if (rs.next()) current = rs.getInt(1);
            }
            for (String file : read("db/migrations.txt").lines().map(String::trim).filter(l -> !l.isEmpty()).toList()) {
                int version = Integer.parseInt(file.substring(1, file.indexOf("__")));
                if (version <= current) continue;
                String sql = read("db/" + db.dialect.folder() + "/" + file);
                c.setAutoCommit(false);
                try (Statement s = c.createStatement()) {
                    for (String stmt : statements(sql)) s.execute(stmt);
                    s.execute("INSERT INTO schema_version (version) VALUES (" + version + ")");
                    c.commit();
                } catch (SQLException e) { c.rollback(); throw e; }
                finally { c.setAutoCommit(true); }
            }
        }
    }

    static List<String> statements(String sql) {
        String clean = sql.lines().filter(l -> !l.strip().startsWith("--")).reduce("", (a, b) -> a + b + "\n");
        return java.util.Arrays.stream(clean.split(";\\s*\\n")).map(String::strip).filter(s -> !s.isEmpty()).map(s -> s.endsWith(";") ? s.substring(0, s.length() - 1) : s).toList();
    }

    private static String read(String path) throws IOException {
        try (InputStream in = Migrations.class.getClassLoader().getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing resource " + path);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
