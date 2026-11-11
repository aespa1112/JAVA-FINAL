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
    private static final int PBKDF2_ITERATIONS = 210_000;
    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final long LOCKOUT_MILLIS = 15 * 60 * 1000L;
    private static final int SALT_LEN = 16;
    private static final int KEY_LEN = 256;
    private static final Object AUTH_LOCK = new Object();

    public AuthService() { initDb(); }

    private Connection open() throws SQLException {
        Path p = Paths.get(DB_PATH);
        try {
            Path parent = p.getParent();
            if (parent != null && !Files.exists(parent)) Files.createDirectories(parent);
        } catch (Exception e) {
            throw new SQLException("無法建立 system_users 目錄: " + e.getMessage(), e);
        }
        Connection conn = DriverManager.getConnection("jdbc:sqlite:" + DB_PATH);
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA journal_mode = WAL;");
            st.execute("PRAGMA busy_timeout = 15000;");
            st.execute("PRAGMA synchronous = NORMAL;");
        }
        return conn;
    }

    private void initDb() {
        try (Connection conn = open()) {
            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute(
                        "CREATE TABLE IF NOT EXISTS users (" +
                        "username TEXT PRIMARY KEY, " +
                        "password TEXT NOT NULL, " +
                        "display_name TEXT NOT NULL, " +
                        "role TEXT NOT NULL CHECK (role IN ('ADMIN','MAKER','CHECKER','AUDITOR')), " +
                        "enabled INTEGER NOT NULL DEFAULT 1, " +
                        "must_change_password INTEGER NOT NULL DEFAULT 0, " +
                        "failed_attempts INTEGER NOT NULL DEFAULT 0, " +
                        "last_failed_at INTEGER NOT NULL DEFAULT 0, " +
                        "locked_until INTEGER NOT NULL DEFAULT 0)"
                    );
                }

                // 檢查並安全升級欄位，明確區分欄位已存在與真正遷移失敗，不得靜默忽略
                java.util.Set<String> existingCols = new java.util.HashSet<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("PRAGMA table_info(users)")) {
                    while (rs.next()) {
                        existingCols.add(rs.getString("name").toLowerCase());
                    }
                }

                java.util.Map<String, String> requiredCols = new java.util.LinkedHashMap<>();
                requiredCols.put("must_change_password", "ALTER TABLE users ADD COLUMN must_change_password INTEGER NOT NULL DEFAULT 0");
                requiredCols.put("failed_attempts", "ALTER TABLE users ADD COLUMN failed_attempts INTEGER NOT NULL DEFAULT 0");
                requiredCols.put("last_failed_at", "ALTER TABLE users ADD COLUMN last_failed_at INTEGER NOT NULL DEFAULT 0");
                requiredCols.put("locked_until", "ALTER TABLE users ADD COLUMN locked_until INTEGER NOT NULL DEFAULT 0");

                for (var entry : requiredCols.entrySet()) {
                    if (!existingCols.contains(entry.getKey().toLowerCase())) {
                        try (Statement st = conn.createStatement()) {
                            st.execute(entry.getValue());
                        } catch (SQLException ex) {
                            throw new SQLException("使用者資料表結構升級失敗，無法新增欄位 [" + entry.getKey() + "]: " + ex.getMessage(), ex);
                        }
                    }
                }

                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM users")) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        String env = System.getenv().getOrDefault("LEDGER_ENV", "PRODUCTION");
                        if ("PRODUCTION".equalsIgnoreCase(env)) {
                            String tmp = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 12);
                            seed(conn, "admin", tmp, "Admin (系統管理員)", "ADMIN");
                            try (PreparedStatement ups = conn.prepareStatement(
                                    "UPDATE users SET must_change_password=1 WHERE lower(username)='admin'")) {
                                ups.executeUpdate();
                            }
                            try {
                                Path boot = Paths.get("data/.admin_bootstrap");
                                Files.createDirectories(boot.getParent());
                                Files.writeString(boot, tmp);
                                boot.toFile().setReadable(true, true);
                                boot.toFile().setWritable(true, true);
                                System.err.println("[AuthService] PRODUCTION admin 已建立，臨時密碼已寫入 data/.admin_bootstrap（請立即登入並修改密碼）");
                            } catch (Exception ioEx) {
                                System.err.println("[AuthService] 無法寫入 bootstrap 檔案，請以安全管道重設 admin 密碼");
                            }
                        } else {
                            seed(conn, "admin", "admin123", "Admin (系統管理員)", "ADMIN");
                            seed(conn, "amy", "amy123", "Amy (記帳員・制單人)", "MAKER");
                            seed(conn, "bob", "bob123", "Bob (會計師・審核人)", "CHECKER");
                            seed(conn, "david", "david123", "David (會計師・審核人)", "CHECKER");
                            seed(conn, "charlie", "charlie123", "Charlie (審計員・查帳人)", "AUDITOR");
                            System.err.println("[AuthService] DEMO 模式預設帳號已載入");
                        }
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(ac);
            }
            try (Connection conn2 = open()) {
                reloadRoleWhitelist(conn2);
            }
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
        synchronized (AUTH_LOCK) {
            if (username == null || username.isBlank()) throw new BusinessException("請輸入帳號");
            if (password == null || password.isEmpty()) throw new BusinessException("請輸入密碼");
            String key = username.trim().toLowerCase();

            int maxRetries = 10;
            for (int retry = 0; retry < maxRetries; retry++) {
                try (Connection conn = open()) {
                    boolean ac = conn.getAutoCommit();
                    conn.setAutoCommit(false);
                    try {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "SELECT username, password, display_name, role, enabled, failed_attempts, last_failed_at, locked_until FROM users WHERE lower(username) = ?")) {
                            ps.setString(1, key);
                            try (ResultSet rs = ps.executeQuery()) {
                                if (!rs.next()) {
                                    auditAuth("LOGIN_FAIL", key, "user_not_found");
                                    conn.commit();
                                    throw new BusinessException("帳號或密碼錯誤");
                                }

                                long nowSec = System.currentTimeMillis() / 1000L;
                                long lockedUntil = rs.getLong("locked_until");
                                if (lockedUntil > nowSec) {
                                    long remMin = (lockedUntil - nowSec) / 60 + 1;
                                    auditAuth("LOGIN_LOCKOUT", key, "locked_until_" + lockedUntil);
                                    conn.commit();
                                    throw new BusinessException("登入失敗次數過多，請 " + remMin + " 分鐘後再試");
                                }

                                if (rs.getInt("enabled") != 1) {
                                    auditAuth("LOGIN_FAIL", key, "disabled");
                                    conn.commit();
                                    throw new BusinessException("此帳號已停用，請聯絡管理員");
                                }

                                String stored = rs.getString("password");
                                if (!verifyPassword(password, stored)) {
                                    // 跨程序原子更新失敗次數與鎖定狀態，防止並行競爭條件覆蓋
                                    long newLockedUntil = nowSec + (LOCKOUT_MILLIS / 1000L);
                                    try (PreparedStatement ups = conn.prepareStatement(
                                            "UPDATE users SET " +
                                            "failed_attempts = failed_attempts + 1, " +
                                            "last_failed_at = ?, " +
                                            "locked_until = CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE 0 END " +
                                            "WHERE lower(username) = ?")) {
                                        ups.setLong(1, nowSec);
                                        ups.setInt(2, MAX_FAILED_ATTEMPTS);
                                        ups.setLong(3, newLockedUntil);
                                        ups.setString(4, key);
                                        ups.executeUpdate();
                                    }

                                    long freshLockedUntil = 0;
                                    try (PreparedStatement checkPs = conn.prepareStatement(
                                            "SELECT failed_attempts, locked_until FROM users WHERE lower(username) = ?")) {
                                        checkPs.setString(1, key);
                                        try (ResultSet freshRs = checkPs.executeQuery()) {
                                            if (freshRs.next()) {
                                                freshLockedUntil = freshRs.getLong("locked_until");
                                            }
                                        }
                                    }

                                    conn.commit();
                                    auditAuth("LOGIN_FAIL", key, "bad_password");
                                    if (freshLockedUntil > nowSec) {
                                        throw new BusinessException("登入失敗次數過多，請 15 分鐘後再試");
                                    }
                                    throw new BusinessException("帳號或密碼錯誤");
                                }

                                // 登入成功：重設失敗次數與鎖定期限
                                try (PreparedStatement ups = conn.prepareStatement(
                                        "UPDATE users SET failed_attempts = 0, last_failed_at = 0, locked_until = 0 WHERE lower(username) = ?")) {
                                    ups.setString(1, key);
                                    ups.executeUpdate();
                                }

                                if (!isHashed(stored)) {
                                    try (PreparedStatement ups = conn.prepareStatement(
                                            "UPDATE users SET password = ? WHERE lower(username) = ?")) {
                                        ups.setString(1, hashPassword(password));
                                        ups.setString(2, key);
                                        ups.executeUpdate();
                                    }
                                }
                                conn.commit();
                                String canonical = rs.getString("username");
                                OperatorRole.register(canonical, OperatorRole.valueOf(rs.getString("role")));
                                auditAuth("LOGIN_OK", canonical, "success");
                                return canonical;
                            }
                        }
                    } catch (SQLException e) {
                        conn.rollback();
                        if (isBusy(e) && retry < maxRetries - 1) {
                            try { Thread.sleep(50 + (int)(Math.random() * 50)); } catch (InterruptedException ignored) {}
                            continue;
                        }
                        throw e;
                    } finally {
                        conn.setAutoCommit(ac);
                    }
                } catch (BusinessException e) {
                    throw e;
                } catch (SQLException e) {
                    if (isBusy(e) && retry < maxRetries - 1) {
                        try { Thread.sleep(50 + (int)(Math.random() * 50)); } catch (InterruptedException ignored) {}
                        continue;
                    }
                    throw new BusinessException("登入失敗: " + e.getMessage(), e);
                }
            }
            throw new BusinessException("登入失敗：資料庫忙碌，請稍後再試");
        }
    }

    private static boolean isBusy(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("busy") || msg.contains("locked") || e.getErrorCode() == 5 || e.getErrorCode() == 6;
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
        validatePasswordStrength(newPass);
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
        validatePasswordStrength(password);
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
        synchronized (AUTH_LOCK) {
            requireAdmin(operator);
            validatePasswordStrength(newPassword);
            String user = username.trim().toLowerCase();
            try (Connection conn = open();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE users SET password = ?, must_change_password=1, failed_attempts=0, last_failed_at=0, locked_until=0 WHERE lower(username) = ?")) {
                ps.setString(1, hashPassword(newPassword));
                ps.setString(2, user);
                if (ps.executeUpdate() == 0) throw new BusinessException("找不到帳號: " + user);
            } catch (BusinessException e) {
                throw e;
            } catch (SQLException e) {
                throw new BusinessException("重設密碼失敗: " + e.getMessage(), e);
            }
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


    private void validatePasswordStrength(String password) {
        if (password == null || password.length() < 8) {
            throw new BusinessException("密碼至少 8 個字元");
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        if (!hasLetter || !hasDigit) {
            throw new BusinessException("密碼須同時包含字母與數字");
        }
    }


    private void auditAuth(String action, String username, String detail) {
        try {
            Path logDir = Paths.get("data");
            if (!Files.exists(logDir)) Files.createDirectories(logDir);
            String line = java.time.LocalDateTime.now() + "\t" + action + "\t" + username + "\t" + detail + System.lineSeparator();
            Files.writeString(Paths.get("data/auth_audit.log"), line,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (Exception ignored) {
        }
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
