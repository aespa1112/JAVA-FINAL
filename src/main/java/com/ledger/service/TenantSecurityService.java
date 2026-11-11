package com.ledger.service;

import com.ledger.config.SessionContext;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 租戶安全授權服務 (R9)：
 * 嚴格執行 Fail-Closed 租戶授權原則：
 * 1. 租戶識別碼必須與已驗證使用者身分綁定。
 * 2. 登入後自伺服器端授權表驗證，禁止僅依前端傳入識別碼判斷。
 * 3. 未登入、租戶識別為 null、空字串或未授權時一律拒絕，絕不自動退回預設資料庫。
 */
public class TenantSecurityService {

    private static final String DB_PATH = "data/system_users.db";

    public TenantSecurityService() {
        initDb();
    }

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
        int maxRetries = 10;
        for (int retry = 0; retry < maxRetries; retry++) {
            try (Connection conn = open()) {
                boolean ac = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (Statement st = conn.createStatement()) {
                        st.execute(
                            "CREATE TABLE IF NOT EXISTS user_tenants (" +
                            "username TEXT NOT NULL, " +
                            "tenant_id TEXT NOT NULL, " +
                            "granted_by TEXT NOT NULL, " +
                            "granted_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), " +
                            "PRIMARY KEY (username, tenant_id))"
                        );
                    }

                    seedDefaultAccess(conn, "admin", "client_a", "SYSTEM");
                    seedDefaultAccess(conn, "amy", "client_a", "SYSTEM");
                    seedDefaultAccess(conn, "bob", "client_a", "SYSTEM");
                    seedDefaultAccess(conn, "david", "client_a", "SYSTEM");
                    seedDefaultAccess(conn, "charlie", "client_a", "SYSTEM");

                    conn.commit();
                    return;
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
            } catch (SQLException e) {
                if (isBusy(e) && retry < maxRetries - 1) {
                    try { Thread.sleep(50 + (int)(Math.random() * 50)); } catch (InterruptedException ignored) {}
                    continue;
                }
                System.err.println("WARN: 初始化 user_tenants 異常: " + e.getMessage());
                break;
            }
        }
    }

    private static boolean isBusy(SQLException e) {
        String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
        return msg.contains("busy") || msg.contains("locked") || e.getErrorCode() == 5 || e.getErrorCode() == 6;
    }

    private void seedDefaultAccess(Connection conn, String user, String tenant, String grantedBy) throws SQLException {
        String sql = "INSERT OR IGNORE INTO user_tenants (username, tenant_id, granted_by) VALUES (?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.toLowerCase().trim());
            ps.setString(2, tenant.toLowerCase().trim());
            ps.setString(3, grantedBy);
            ps.executeUpdate();
        }
    }

    public void grantAccess(String username, String tenantId, String operator) {
        String normOp = SessionContext.resolveOperator(operator);
        OperatorRole role = OperatorRole.resolve(normOp);
        if (role != OperatorRole.ADMIN) {
            throw new BusinessException("權限不足：僅系統管理員有權分配租戶存取權限");
        }
        if (username == null || username.isBlank() || tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("使用者帳號與租戶識別碼不可為空");
        }

        try (Connection conn = open()) {
            String sql = "INSERT INTO user_tenants (username, tenant_id, granted_by, granted_at) " +
                         "VALUES (?, ?, ?, datetime('now','localtime')) " +
                         "ON CONFLICT(username, tenant_id) DO UPDATE SET granted_by = excluded.granted_by";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username.toLowerCase().trim());
                ps.setString(2, tenantId.toLowerCase().trim());
                ps.setString(3, normOp);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new BusinessException("授權租戶存取失敗: " + e.getMessage(), e);
        }
    }

    public void revokeAccess(String username, String tenantId, String operator) {
        String normOp = SessionContext.resolveOperator(operator);
        OperatorRole role = OperatorRole.resolve(normOp);
        if (role != OperatorRole.ADMIN) {
            throw new BusinessException("權限不足：僅系統管理員有權撤銷租戶存取權限");
        }
        try (Connection conn = open()) {
            String sql = "DELETE FROM user_tenants WHERE lower(username) = ? AND lower(tenant_id) = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username.toLowerCase().trim());
                ps.setString(2, tenantId.toLowerCase().trim());
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new BusinessException("撤銷租戶授權失敗: " + e.getMessage(), e);
        }
    }

    public boolean hasAccess(String username, String tenantId) {
        if (username == null || tenantId == null || username.isBlank() || tenantId.isBlank()) {
            return false;
        }
        String u = username.toLowerCase().trim();
        String t = tenantId.toLowerCase().trim();

        // 系統管理員具備所有租戶的完整維護授權
        if (OperatorRole.resolve(u) == OperatorRole.ADMIN) {
            return true;
        }

        try (Connection conn = open()) {
            String sql = "SELECT 1 FROM user_tenants WHERE lower(username) = ? AND lower(tenant_id) = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, u);
                ps.setString(2, t);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next();
                }
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 驗證當前 Session 使用者是否有權存取目標租戶（Fail-Closed 原則）。
     */
    public void assertAccess(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("未指定租戶識別碼 (Tenant ID)，拒絕操作（禁止自動退回預設資料庫）");
        }
        String u = SessionContext.requireUser();
        assertAccess(u, tenantId);
    }

    public void assertAccess(String username, String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("未指定租戶識別碼 (Tenant ID)，拒絕操作（禁止自動退回預設資料庫）");
        }
        if (!hasAccess(username, tenantId)) {
            throw new BusinessException(String.format(
                    "安全權限拒絕：使用者 [%s] 未獲授權存取租戶帳套 [%s]，禁止跨租戶存取！",
                    username, tenantId));
        }
    }

    public List<String> getAuthorizedTenants(String username) {
        List<String> list = new ArrayList<>();
        if (username == null || username.isBlank()) return list;
        String u = username.toLowerCase().trim();
        try (Connection conn = open()) {
            String sql = "SELECT tenant_id FROM user_tenants WHERE lower(username) = ? ORDER BY tenant_id ASC";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, u);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(rs.getString(1));
                    }
                }
            }
        } catch (SQLException e) {
            throw new BusinessException("讀取授權租戶清單失敗: " + e.getMessage(), e);
        }
        return list;
    }
}
