package com.ledger.config;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Tenant authorization gate. Fail-closed: if the auth store cannot be
 * initialized or queried, all tenant access is denied.
 * <p>
 * Authorization is stored in a dedicated system database (not a tenant DB)
 * so that tenant databases cannot influence their own access decisions.
 */
public final class TenantSecurityService {

    private static final Logger LOG = Logger.getLogger(TenantSecurityService.class.getName());

    /** Dedicated auth database path (system, not tenant). */
    public static final String AUTH_DB_PATH = "data/system/tenant_auth.db";

    private static final AtomicBoolean initialized = new AtomicBoolean(false);
    private static final AtomicBoolean healthy = new AtomicBoolean(false);
    private static final Set<String> memoryAllowList = ConcurrentHashMap.newKeySet();

    private TenantSecurityService() {
    }

    /**
     * Initialize (or re-initialize) the authorization store.
     * On failure the service remains unhealthy and all access is denied.
     */
    public static synchronized void initDb() {
        if (initialized.get() && healthy.get()) {
            return;
        }
        healthy.set(false);
        try {
            Path path = Paths.get(AUTH_DB_PATH);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                Files.createDirectories(parent);
            }
            try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + AUTH_DB_PATH);
                 Statement st = conn.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL;");
                st.execute("PRAGMA foreign_keys = ON;");
                st.execute("PRAGMA busy_timeout = 5000;");
                st.execute(
                        "CREATE TABLE IF NOT EXISTS tenant_acl (" +
                        "  tenant_id TEXT PRIMARY KEY NOT NULL," +
                        "  enabled   INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1))," +
                        "  created_at TEXT NOT NULL DEFAULT (datetime('now'))," +
                        "  note TEXT" +
                        ")");
                try (ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM tenant_acl")) {
                    if (rs.next() && rs.getInt(1) == 0) {
                        st.execute("INSERT INTO tenant_acl(tenant_id, enabled, note) VALUES ('demo', 1, 'default seed')");
                        st.execute("INSERT INTO tenant_acl(tenant_id, enabled, note) VALUES ('client_a', 1, 'legacy default')");
                    }
                }
            }
            initialized.set(true);
            healthy.set(true);
            LOG.info("TenantSecurityService initialized successfully");
        } catch (Exception e) {
            initialized.set(true);
            healthy.set(false);
            LOG.log(Level.SEVERE, "TenantSecurityService initialization FAILED — tenant access will be denied", e);
        }
    }

    public static boolean isHealthy() {
        if (!initialized.get()) {
            initDb();
        }
        return healthy.get();
    }

    public static void requireAuthorized(String tenantId) {
        if (!isHealthy()) {
            throw new SecurityException("Tenant authorization system is not healthy; access denied");
        }
        String canonical;
        try {
            canonical = TenantPathResolver.normalizeTenantId(tenantId);
        } catch (IllegalArgumentException e) {
            throw new SecurityException("Invalid tenantId: " + e.getMessage());
        }
        if (memoryAllowList.contains(canonical)) {
            return;
        }
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + AUTH_DB_PATH);
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT enabled FROM tenant_acl WHERE tenant_id = ?")) {
            ps.setString(1, canonical);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) == 1) {
                    return;
                }
            }
        } catch (SQLException e) {
            LOG.log(Level.SEVERE, "Authorization query failed (fail-closed)", e);
            throw new SecurityException("Authorization query failed; access denied");
        }
        throw new SecurityException("Tenant not authorized: " + canonical);
    }

    public static boolean isAuthorized(String tenantId) {
        try {
            requireAuthorized(tenantId);
            return true;
        } catch (SecurityException e) {
            return false;
        }
    }

    public static synchronized void grant(String tenantId, String note) {
        if (!isHealthy()) {
            throw new SecurityException("Cannot grant: auth system unhealthy");
        }
        String canonical = TenantPathResolver.normalizeTenantId(tenantId);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + AUTH_DB_PATH);
             PreparedStatement ps = conn.prepareStatement(
                     "INSERT INTO tenant_acl(tenant_id, enabled, note) VALUES (?,?,?) " +
                     "ON CONFLICT(tenant_id) DO UPDATE SET enabled = 1, note = excluded.note")) {
            ps.setString(1, canonical);
            ps.setInt(2, 1);
            ps.setString(3, note);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SecurityException("Failed to grant tenant: " + e.getMessage(), e);
        }
    }

    public static synchronized void revoke(String tenantId) {
        if (!isHealthy()) {
            throw new SecurityException("Cannot revoke: auth system unhealthy");
        }
        String canonical = TenantPathResolver.normalizeTenantId(tenantId);
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + AUTH_DB_PATH);
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE tenant_acl SET enabled = 0 WHERE tenant_id = ?")) {
            ps.setString(1, canonical);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new SecurityException("Failed to revoke tenant: " + e.getMessage(), e);
        }
        memoryAllowList.remove(canonical);
    }

    public static void allowInMemoryForTest(String tenantId) {
        String canonical = TenantPathResolver.normalizeTenantId(tenantId);
        memoryAllowList.add(canonical);
    }

    public static void clearTestState() {
        memoryAllowList.clear();
    }

    public static void markUnhealthyForTest() {
        healthy.set(false);
    }

    public static void resetForTest() {
        initialized.set(false);
        healthy.set(false);
        memoryAllowList.clear();
    }
}
