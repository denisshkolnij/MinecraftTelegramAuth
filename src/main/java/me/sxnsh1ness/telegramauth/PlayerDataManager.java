package me.sxnsh1ness.telegramauth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.mindrot.jbcrypt.BCrypt;

import java.nio.file.Path;
import java.sql.*;
import java.util.Objects;
import java.util.UUID;

public class PlayerDataManager {
    private final Connection conn;
    private final java.util.Map<java.util.UUID, Boolean> loggedIn = new java.util.HashMap<>();

    public PlayerDataManager(Path dataFolder) throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite:" + dataFolder.resolve("users.db"));
        try (Statement s = conn.createStatement()) {
            s.execute("""
                CREATE TABLE IF NOT EXISTS users(
                    uuid TEXT PRIMARY KEY,
                    nickname TEXT NOT NULL,
                    password_hash TEXT NOT NULL,
                    telegram_chat_id INTEGER DEFAULT NULL,
                    pending_login INTEGER DEFAULT 0
                )""");
        }
    }

    public boolean register(Player p, String pass) {
        return updatePassword(p.getUniqueId(), pass) && login(p, pass);
    }

    public boolean login(Player p, String pass) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT password_hash FROM users WHERE uuid = ?")) {
            ps.setString(1, p.getUniqueId().toString());
            ResultSet rs = ps.executeQuery();
            if (rs.next() && BCrypt.checkpw(pass, rs.getString(1))) {
                updateNickname(p);
                return true;
            }
        } catch (SQLException ignored) {}
        return false;
    }

    private boolean updatePassword(UUID uuid, String pass) {
        String hash = BCrypt.hashpw(pass, BCrypt.gensalt());
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR REPLACE INTO users(uuid, nickname, password_hash) VALUES(?, ?, ?)")) {
            Player p = org.bukkit.Bukkit.getPlayer(uuid);
            ps.setString(1, uuid.toString());
            ps.setString(2, p != null ? p.getName() : "unknown");
            ps.setString(3, hash);
            ps.executeUpdate();
            return true;
        } catch (SQLException e) { return false; }
    }

    public boolean changePassword(UUID uuid, String oldPass, String newPass) {
        if (!login(Objects.requireNonNull(Bukkit.getOfflinePlayer(uuid).getPlayer()), oldPass)) return false;
        return updatePassword(uuid, newPass);
    }

    public Long getTelegramId(UUID uuid) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT telegram_chat_id FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() && rs.getLong(1) != 0 ? rs.getLong(1) : null;
        } catch (SQLException e) {
            return null;
        }
    }

    public void setTelegramId(UUID uuid, Long chatId) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET telegram_chat_id = ? WHERE uuid = ?")) {
            ps.setObject(1, chatId);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public boolean isPending(UUID uuid) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT pending_login FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeQuery().next() && ps.getResultSet().getInt(1) == 1;
        } catch (SQLException e) { return false; }
    }

    public void setPending(UUID uuid, boolean pending) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET pending_login = ? WHERE uuid = ?")) {
            ps.setInt(1, pending ? 1 : 0);
            ps.setString(2, uuid.toString());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public boolean isRegistered(java.util.UUID uuid) {
        try (PreparedStatement ps = conn.prepareStatement("SELECT 1 FROM users WHERE uuid = ?")) {
            ps.setString(1, uuid.toString());
            return ps.executeQuery().next();
        } catch (SQLException e) { return false; }
    }

    public boolean isLoggedIn(UUID uuid) {
        return loggedIn.getOrDefault(uuid, false);
    }

    public void setLoggedIn(java.util.UUID uuid, boolean state) {
        if (state) {
            loggedIn.put(uuid, true);
        } else {
            loggedIn.remove(uuid);
        }
    }

    private void updateNickname(Player p) {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE users SET nickname = ? WHERE uuid = ?")) {
            ps.setString(1, p.getName());
            ps.setString(2, p.getUniqueId().toString());
            ps.executeUpdate();
        } catch (SQLException ignored) {}
    }

    public void close() {
        try {
            conn.close();
        } catch (SQLException ignored) {}
    }
}
