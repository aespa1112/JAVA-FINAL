package com.ledger.service;

import com.ledger.config.SessionContext;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.sql.*;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public class AuthService {

    public record UserAccount(String username, String displayName, OperatorRole role, boolean enabled) {}

    private static final String DB_PATH = "data/system_users.db";
    private static final int PBKDF2_ITERATIONS = 120_000;
    private static final int SALT_LEN = 16;
    private static final int KEY_LEN = 256;

    public AuthService() { initDb(); }

    private Connection open() throws SQLException {
        Path p = Paths.get(DB_PATH);
        try {
            Path parent = p.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new SQLException("無法建立 system_users 目錄: " + e.getMessage(), e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
    }

    private void initDb() {
        try (Connection conn = open()) {
            try (Statement st = conn.createStatement()) {
                st.execute(
                    "CREATE TABLE IF NOT EXISTS users (" +
                    "username TEXT PRIMARY KEY, " +
                    "password TEXT NOT NULL, " +
                    "display_name TEXT NOT NULL, " +
                    "role TEXT NOT NULL CHECK (role IN ('ADMIN','MAKER','CHECKER','AUDITOR')), " +
                    "enabled INTEGER NOT NULL DEFAULT 1, " +
                    "must_change_password INTEGER NOT NULL DEFAULT 0)"
                );
            }
            try (Statement st2 = conn.createStatement()) {
                st2.execute("ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0");
            } catch (SQLException ignored) {}
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    String env = System.getenv().getOrDefault("LEDGER_ENV", "DEMO");
                    if ("PRODUCTION".equalsIgnoreCase(env)) {
                        String tmp = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                        seed(conn, "admin", tmp, "Admin (系統管理員)", "ADMIN");
                        try (PreparedStatement ups = conn.prepareStatement(
                                "UPDATE users SET must_change_password=1 WHERE lower(username)='admin'")) {
                            ups.executeUpdate();
                        }
                        System.err.println("[AuthService] PRODUCTION admin 初始密碼（僅顯示一次）: " + tmp);
                    } else {
                        seed(conn, "admin", "admin123", "Admin (系統管理員)", "ADMIN");
                        seed(conn, "amy", "amy123", "Amy (記帳員/制單人)", "MAKER");
                        seed(conn, "bob", "bob123", "Bob (會計師/審核人)", "CHECKER");
                        seed(conn, "david", "david123", "David (會計師/審核人)", "CHECKER");
                        seed(conn, "charlie", "charlie123", "Charlie (查帳員/審計員)", "AUDITOR");
                    }
                }
            }
            reloadRoleWhitelist(conn);
        } catch (SQLException e) {
            throw new BusinessException("初始化系統帳號資料庫失敗: " + e.getMessage(), e);
        }
    }

    private void seed(Connection conn, String user, String pass, String display, String role) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO users (username, password, display_name, role, enabled) VALUES (?,?,?,?,1)")) {
            ps.setString(1, user.toLowerCase());
            ps.setString(2, hashPassword(pass));
            ps.setString(3, display);
            ps.setString(4, role);
            ps.executeUpdate();
        }
    }

    private void reloadRoleWhitelist(Connection conn) throws SQLException {
        OperatorRole.clearDynamic();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT username, role FROM users WHERE enabled = 1")) {
            while (rs.next()) {
                OperatorRole.register(rs.getString(1), OperatorRole.valueOf(rs.getString(2)));
            }
        }
    }

    public String login(String username, String password) {
        if (username == null || username.isBlank()) throw new BusinessException("請輸入帳號");
        if (password == null || password.isEmpty()) throw new BusinessException("請輸入密碼");
        String key = username.trim().toLowerCase();
        try (Connection conn = open()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT username, password, display_name, role, enabled FROM users WHERE lower(username) = ?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new BusinessException("帳號或密碼錯誤");
                    if (rs.getInt("enabled") != 1) throw new BusinessException("此帳號已停用，請聯絡管理員");
                    String stored = rs.getString("password");
                    if (!verifyPassword(password, stored)) throw new BusinessException("帳號或密碼錯誤");
                    if (!isHashed(stored)) {
                        try (PreparedStatement ups = conn.prepareStatement(
                                "UPDATE users SET password = ? WHERE lower(username) = ?")) {
                            ups.setString(1, hashPassword(password));
                            ups.setString(2, key);
                            ups.executeUpdate();
                        }
                    }
                    String canonical = rs.getString("username");
                    OperatorRole.register(canonical, OperatorRole.valueOf(rs.getString("role")));
                    return canonical;
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException("登入失敗: " + e.getMessage(), e);
        }
    }

    public boolean mustChangePassword(String username) {
        if (username == null) return false;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT must_change_password FROM users WHERE lower(username)=?")) {
            ps.setString(1, username.trim().toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getInt(1) == 1;
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public void changePassword(String username, String oldPass, String newPass) {
        if (newPass == null || newPass.length() < 6) throw new BusinessException("新密碼至少 6 個字元");
        String key = username.trim().toLowerCase();
        try (Connection conn = open()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT password FROM users WHERE lower(username)=?")) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new BusinessException("找不到帳號");
                    if (!verifyPassword(oldPass, rs.getString(1))) throw new BusinessException("目前密碼不正確");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE users SET password=?, must_change_password=0 WHERE lower(username)=?")) {
                ps.setString(1, hashPassword(newPass));
                ps.setString(2, key);
                ps.executeUpdate();
            }
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException("變更密碼失敗: " + e.getMessage(), e);
        }
    }

    public UserAccount getAccount(String username) {
        if (username == null) return null;
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT username, display_name, role, enabled FROM users WHERE lower(username) = ?")) {
            ps.setString(1, username.trim().toLowerCase());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return map(rs);
            }
        } catch (SQLException e) {
            throw new BusinessException("查詢帳號失敗: " + e.getMessage(), e);
        }
    }

    public boolean isEnabled(String canonicalName) {
        UserAccount acc = getAccount(canonicalName);
        return acc != null && acc.enabled();
    }

    public boolean isAdmin(String canonicalName) {
        UserAccount acc = getAccount(canonicalName);
        return acc != null && acc.role() == OperatorRole.ADMIN;
    }

    public String getDisplayName(String username) {
        UserAccount acc = getAccount(username);
        return acc == null ? username : acc.displayName();
    }

    public List<UserAccount> listUsers() {
        List<UserAccount> list = new ArrayList<>();
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT username, display_name, role, enabled FROM users ORDER BY username")) {
            while (rs.next()) list.add(map(rs));
        } catch (SQLException e) {
            throw new BusinessException("列出帳號失敗: " + e.getMessage(), e);
        }
        return list;
    }

    public void createUser(String username, String password, String displayName, OperatorRole role, String operator) {
        requireAdmin(operator);
        if (username == null || username.isBlank()) throw new BusinessException("帳號不可空白");
        if (password == null || password.length() < 6) throw new BusinessException("密碼至少 6 個字元");
        if (role == null || role == OperatorRole.ADMIN) {
            throw new BusinessException("不可透過此介面建立管理員");
        }
        String user = username.trim().toLowerCase();
        if (!user.matches("[a-z0-9_]{2,32}")) throw new BusinessException("帳號限小寫英數與底線，長度 2～32");
        String display = (displayName == null || displayName.isBlank())
                ? user + " (" + role.toChinese() + ")" : displayName.trim();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO users (username, password, display_name, role, enabled, must_change_password) VALUES (?,?,?,?,1,1)")) {
            ps.setString(1, user);
            ps.setString(2, hashPassword(password));
            ps.setString(3, display);
            ps.setString(4, role.name());
            ps.executeUpdate();
            OperatorRole.register(user, role);
        } catch (SQLException e) {
            if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) {
                throw new BusinessException("帳號已存在: " + user);
            }
            throw new BusinessException("新增人員失敗: " + e.getMessage(), e);
        }
    }

    public void setEnabled(String username, boolean enabled, String operator) {
        requireAdmin(operator);
        if (username == null) throw new BusinessException("請指定帳號");
        String user = username.trim().toLowerCase();
        if ("admin".equals(user) && !enabled) throw new BusinessException("不可停用內建管理員 admin");
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET enabled = ? WHERE lower(username) = ?")) {
            ps.setInt(1, enabled ? 1 : 0);
            ps.setString(2, user);
            if (ps.executeUpdate() == 0) throw new BusinessException("找不到帳號: " + user);
            if (!enabled) {
                OperatorRole.unregister(user);
                if (user.equalsIgnoreCase(SessionContext.getUser())) {
                    SessionContext.invalidate();
                }
            } else {
                UserAccount acc = getAccount(user);
                if (acc != null) OperatorRole.register(user, acc.role());
            }
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException("更新啟用狀態失敗: " + e.getMessage(), e);
        }
    }

    public void resetPassword(String username, String newPassword, String operator) {
        requireAdmin(operator);
        if (newPassword == null || newPassword.length() < 6) throw new BusinessException("新密碼至少 6 個字元");
        String user = username.trim().toLowerCase();
        try (Connection conn = open();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE users SET password = ?, must_change_password=1 WHERE lower(username) = ?")) {
            ps.setString(1, hashPassword(newPassword));
            ps.setString(2, user);
            if (ps.executeUpdate() == 0) throw new BusinessException("找不到帳號: " + user);
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException("重設密碼失敗: " + e.getMessage(), e);
        }
    }

    private void requireAdmin(String operator) {
        UserAccount acc = getAccount(operator);
        if (acc == null || acc.role() != OperatorRole.ADMIN) {
            throw new BusinessException("僅系統管理員可管理人員帳號");
        }
    }

    private UserAccount map(ResultSet rs) throws SQLException {
        return new UserAccount(
                rs.getString("username"),
                rs.getString("display_name"),
                OperatorRole.valueOf(rs.getString("role")),
                rs.getInt("enabled") == 1);
    }

    public static String hashPassword(String plain) {
        try {
            byte[] salt = new byte[SALT_LEN];
            new SecureRandom().nextBytes(salt);
            byte[] hash = pbkdf2(plain.toCharArray(), salt, PBKDF2_ITERATIONS);
            return "pbkdf2$" + PBKDF2_ITERATIONS + "$"
                    + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new BusinessException("密碼雜湊失敗: " + e.getMessage(), e);
        }
    }

    public static boolean verifyPassword(String plain, String stored) {
        if (stored == null) return false;
        if (!isHashed(stored)) return stored.equals(plain);
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            int iter = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            byte[] actual = pbkdf2(plain.toCharArray(), salt, iter);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean isHashed(String stored) {
        return stored != null && stored.startsWith("pbkdf2$");
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations) throws Exception {
        KeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_LEN);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
    }
}
