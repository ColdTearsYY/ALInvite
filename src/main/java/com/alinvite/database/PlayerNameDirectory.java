package com.alinvite.database;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Shared name-to-UUID index for players seen on any backend in the network. */
public final class PlayerNameDirectory {
    @FunctionalInterface
    public interface ConnectionProvider {
        Connection open() throws SQLException;
    }

    public record PlayerIdentity(UUID uuid, String currentName) { }

    private final ConnectionProvider connections;
    private final String prefix;
    private final boolean mysql;

    public PlayerNameDirectory(ConnectionProvider connections, String prefix, boolean mysql) {
        this.connections = connections;
        this.prefix = prefix == null ? "" : prefix;
        this.mysql = mysql;
    }

    public void ensureSchema() throws SQLException {
        String ddl = mysql
            ? "CREATE TABLE IF NOT EXISTS " + table() + " (name_lower VARCHAR(16) PRIMARY KEY,"
                + " player_uuid VARCHAR(36) NOT NULL, display_name VARCHAR(16) NOT NULL,"
                + " updated_at BIGINT NOT NULL, INDEX idx_" + prefix + "player_names_uuid (player_uuid)) ENGINE=InnoDB"
            : "CREATE TABLE IF NOT EXISTS " + table() + " (name_lower VARCHAR(16) PRIMARY KEY,"
                + " player_uuid VARCHAR(36) NOT NULL, display_name VARCHAR(16) NOT NULL, updated_at BIGINT NOT NULL)";
        try (Connection conn = connections.open(); Statement stmt = conn.createStatement()) {
            stmt.execute(ddl);
            if (!mysql) stmt.execute("CREATE INDEX IF NOT EXISTS idx_" + prefix + "player_names_uuid ON " + table() + " (player_uuid)");
        }
    }

    public void upsert(UUID uuid, String name) throws SQLException {
        if (uuid == null || name == null || name.isBlank()) return;
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement delete = conn.prepareStatement("DELETE FROM " + table() + " WHERE player_uuid = ?")) {
                    delete.setString(1, uuid.toString());
                    delete.executeUpdate();
                }
                upsertOne(conn, uuid, name, System.currentTimeMillis());
                conn.commit();
            } catch (SQLException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }

    private void upsertOne(Connection conn, UUID uuid, String name, long updatedAt) throws SQLException {
        String sql = mysql
            ? "INSERT INTO " + table() + " (name_lower, player_uuid, display_name, updated_at) VALUES (?, ?, ?, ?)"
                + " ON DUPLICATE KEY UPDATE player_uuid=VALUES(player_uuid), display_name=VALUES(display_name), updated_at=VALUES(updated_at)"
            : "INSERT OR REPLACE INTO " + table() + " (name_lower, player_uuid, display_name, updated_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, normalize(name));
            stmt.setString(2, uuid.toString());
            stmt.setString(3, name);
            stmt.setLong(4, updatedAt);
            stmt.executeUpdate();
        }
    }

    public void upsertAll(List<PlayerIdentity> identities) throws SQLException {
        if (identities == null || identities.isEmpty()) return;
        try (Connection conn = connections.open()) {
            conn.setAutoCommit(false);
            try {
                long now = System.currentTimeMillis();
                try (PreparedStatement delete = conn.prepareStatement("DELETE FROM " + table() + " WHERE player_uuid = ?")) {
                    for (PlayerIdentity identity : identities) {
                        if (identity == null || identity.uuid() == null || identity.currentName() == null || identity.currentName().isBlank()) continue;
                        delete.setString(1, identity.uuid().toString());
                        delete.addBatch();
                    }
                    delete.executeBatch();
                }
                for (PlayerIdentity identity : identities) {
                    if (identity != null && identity.uuid() != null && identity.currentName() != null && !identity.currentName().isBlank()) {
                        upsertOne(conn, identity.uuid(), identity.currentName(), now);
                    }
                }
                conn.commit();
            } catch (SQLException error) {
                conn.rollback();
                throw error;
            } finally {
                conn.setAutoCommit(true);
            }
        }
    }


    public PlayerIdentity find(String name) throws SQLException {
        if (name == null || name.isBlank()) return null;
        UUID uuid = null;
        String requested = name.trim();
        try (Connection conn = connections.open();
             PreparedStatement stmt = conn.prepareStatement("SELECT player_uuid FROM " + table() + " WHERE name_lower = ?")) {
            stmt.setString(1, normalize(requested));
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) uuid = UUID.fromString(rs.getString(1));
            }
        }
        if (uuid == null) return null;
        String current = currentName(uuid, requested);
        return current == null ? null : new PlayerIdentity(uuid, current);
    }

    private String currentName(UUID uuid, String fallback) throws SQLException {
        String sql = "SELECT display_name FROM " + table() + " WHERE player_uuid = ? ORDER BY updated_at DESC LIMIT 1";
        try (Connection conn = connections.open(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String current = rs.getString(1);
                    return current.equalsIgnoreCase(fallback) ? current : null;
                }
            }
        }
        return null;
    }

    public List<String> listNames() throws SQLException {
        List<String> result = new ArrayList<>();
        String sql = "SELECT display_name FROM " + table() + " ORDER BY display_name LIMIT 10000";
        try (Connection conn = connections.open(); Statement stmt = conn.createStatement(); ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString(1);
                if (name != null && !name.isBlank()) result.add(name);
            }
        }
        return result;
    }

    private String table() { return prefix + "player_names"; }
    private static String normalize(String name) { return name.trim().toLowerCase(Locale.ROOT); }
}
