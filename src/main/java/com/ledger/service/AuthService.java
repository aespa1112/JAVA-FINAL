package com.ledger.service;

import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 帳號密碼驗證與人員管理。
 * 帳號資料存放於 data/system_users.db（與客戶帳套分離）。
 */
public class AuthService {

    public record UserAccount(
            String username,
            String password,
            String displayName,
            OperatorRole role,
            boolean enabled
    ) {}

    private static final String DB_PATH = "data/system_users.db";

    public AuthService() {
        ensureSchemaAndSeed();
    }

    private Connection open() throws SQLException {
        try {
            Files.createDirectories(Paths.get("data"));
        } catch (Exception e) {
            throw new SQLException("無法建立 data 目錄", e);
        }
        return DriverManager.getConnection("jdbc:sqlite:" + Paths.get(DB_PATH).toAbsolutePath());
    }

    private void ensureSchemaAndSeed() {
        try (Connection conn = open(); Statement st = conn.createStatement()) {
            st.execute("""
                CREATE TABLE IF NOT EXISTS users (
                    username     TEXT PRIMARY KEY,
                    password     TEXT NOT NULL,
                    display_name TEXT NOT NULL,
                    role         TEXT NOT NULL CHECK (role IN ('ADMIN','MAKER','CHECKER','AUDITOR')),
                    enabled      INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1))
                )
                """);

            try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
                if (rs.next() && rs.getInt(1) == 0) {
                    seed(conn, "admin", "admin123", "Admin (系統管理員)", "ADMIN");
                    seed(conn, "amy", "amy123", "Amy (記帳員/制單人)", "MAKER");
                    seed(conn, "bob", "bob123", "Bob (會計師/審核人)", "CHECKER");
                    seed(conn, "david", "david123", "David (會計師/審核人)", "CHECKER");
                    seed(conn, "charlie", "charlie123", "Charlie (查帳員/審計員)", "AUDITOR");
                }
            }

            reloadRoleWhitelist(conn);
        } catch (SQLException e) {
            throw new BusinessException("初始化人員帳號資料庫失敗: " + e.getMessage(), e);
        }
    }

    private void seed(Connection conn, String user, String pass, String display, String role) throws SQLException {
        String sql = "INSERT OR IGNORE INTO users (username, password, display_name, role, enabled) VALUES (?,?,?,?,1)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user);
            ps.setString(2, pass);
            ps.setString(3, display);
            ps.setString(4, role);
            ps.executeUpdate();
        }
    }

    private void reloadRoleWhitelist(Connection conn) throws SQLException {
        String sql = "SELECT username, role FROM users WHERE enabled = 1";
        try (Statement st = conn.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                OperatorRole role = OperatorRole.valueOf(rs.getString("role"));
                OperatorRole.register(rs.getString("username"), role);
            }
        }
    }

    public String login(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            throw new BusinessException("請輸入帳號");
        }
        if (password == null || password.isEmpty()) {
            throw new BusinessException("請輸入密碼");
        }

        String key = username.trim().toLowerCase();
        try (Connection conn = open()) {
            String sql = "SELECT username, password, display_name, role, enabled FROM users WHERE lower(username) = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, key);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new BusinessException("帳號或密碼錯誤");
                    }
                    if (rs.getInt("enabled") != 1) {
                        throw new BusinessException("此帳號已停用，請聯絡系統管理員");
                    }
                    if (!rs.getString("password").equals(password)) {
                        throw new BusinessException("帳號或密碼錯誤");
                    }
                    OperatorRole role = OperatorRole.valueOf(rs.getString("role"));
                    OperatorRole.register(rs.getString("username"), role);
                    return OperatorRole.normalize(rs.getString("username"));
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException("登入驗證失敗: " + e.getMessage(), e);
        }
    }

    public UserAccount getAccount(String username) {
        if (username == null) return null;
        try (Connection conn = open()) {
            String sql = "SELECT username, password, display_name, role, enabled FROM users WHERE lower(username) = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username.trim().toLowerCase());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return null;
                    return map(rs);
                }
            }
        } catch (SQLException e) {
            return null;
        }
    }

    public String getDisplayName(String canonicalName) {
        UserAccount acc = getAccount(canonicalName);
        return acc != null ? acc.displayName() : canonicalName;
    }

    public boolean isAdmin(String canonicalName) {
        UserAccount acc = getAccount(canonicalName);
        return acc != null && acc.role() == OperatorRole.ADMIN;
    }

    public List<UserAccount> listUsers() {
        List<UserAccount> list = new ArrayList<>();
        try (Connection conn = open();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT username, password, display_name, role, enabled FROM users ORDER BY username")) {
            while (rs.next()) {
                list.add(map(rs));
            }
        } catch (SQLException e) {
            throw new BusinessException("查詢人員清單失敗: " + e.getMessage(), e);
        }
        return list;
    }

    public void createUser(String username, String password, String displayName, OperatorRole role, String operator) {
        requireAdmin(operator);
        if (username == null || username.trim().isEmpty()) {
            throw new BusinessException("帳號不可為空");
        }
        if (password == null || password.length() < 4) {
            throw new BusinessException("密碼至少 4 個字元");
        }
        if (role == null) {
            throw new BusinessException("請選擇角色");
        }
        if (role == OperatorRole.ADMIN) {
            throw new BusinessException("不可透過此功能新增其他系統管理員");
        }

        String user = username.trim().toLowerCase();
        if (!user.matches("[a-z][a-z0-9_]{1,30}")) {
            throw new BusinessException("帳號須為小寫英數，且以字母開頭（2～31 字元）");
        }

        String display = (displayName == null || displayName.trim().isEmpty())
                ? user.substring(0, 1).toUpperCase() + user.substring(1) + " (" + role.toChinese() + ")"
                : displayName.trim();

        try (Connection conn = open()) {
            String sql = "INSERT INTO users (username, password, display_name, role, enabled) VALUES (?,?,?,?,1)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, user);
                ps.setString(2, password);
                ps.setString(3, display);
                ps.setString(4, role.name());
                ps.executeUpdate();
            }
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
        if (username == null || username.trim().isEmpty()) {
            throw new BusinessException("帳號不可為空");
        }
        String user = username.trim().toLowerCase();
        if ("admin".equals(user) && !enabled) {
            throw new BusinessException("不可停用系統管理員帳號");
        }

        try (Connection conn = open()) {
            String sql = "UPDATE users SET enabled = ? WHERE lower(username) = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setInt(1, enabled ? 1 : 0);
                ps.setString(2, user);
                int n = ps.executeUpdate();
                if (n == 0) {
                    throw new BusinessException("找不到帳號: " + user);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException("更新帳號狀態失敗: " + e.getMessage(), e);
        }
    }

    public void resetPassword(String username, String newPassword, String operator) {
        requireAdmin(operator);
        if (newPassword == null || newPassword.length() < 4) {
            throw new BusinessException("密碼至少 4 個字元");
        }
        String user = username.trim().toLowerCase();
        try (Connection conn = open()) {
            String sql = "UPDATE users SET password = ? WHERE lower(username) = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, newPassword);
                ps.setString(2, user);
                if (ps.executeUpdate() == 0) {
                    throw new BusinessException("找不到帳號: " + user);
                }
            }
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException("重設密碼失敗: " + e.getMessage(), e);
        }
    }

    private void requireAdmin(String operator) {
        UserAccount acc = getAccount(operator);
        if (acc == null || acc.role() != OperatorRole.ADMIN) {
            throw new BusinessException("權限不足：僅系統管理員可管理人員帳號");
        }
    }

    private UserAccount map(ResultSet rs) throws SQLException {
        return new UserAccount(
                rs.getString("username"),
                rs.getString("password"),
                rs.getString("display_name"),
                OperatorRole.valueOf(rs.getString("role"),
                rs.getInt("enabled") == 1
        );
    }
}
