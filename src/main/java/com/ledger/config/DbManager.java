package com.ledger.config;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * Database access manager with tenant isolation.
 * <p>
 * Public entry points that return a Connection for a tenant MUST go through
 * TenantSecurityService authorization and TenantPathResolver path derivation.
 * Arbitrary external dbPath cannot be used to open a tenant database.
 * <p>
 * Connection lifecycle:
 * - The default / legacy single-connection path keeps one Connection open and
 *   returns a proxy that ignores close() (historical UI behaviour).
 * - Tenant connections are also pooled per path; closeTenant / closeAllTenants
 *   close connections known to this process. They do not (and cannot safely)
 *   force-close Connections still in use on other threads; callers should use
 *   try-with-resources only on Connections they themselves created via
 *   createRawConnection for short-lived work.
 */
public final class DbManager {

    private static final Logger LOG = Logger.getLogger(DbManager.class.getName());

    public static final int CURRENT_SCHEMA_VERSION = 3;

    private static final String[] PRAGMAS = {
            "PRAGMA journal_mode = WAL;",
            "PRAGMA foreign_keys = ON;",
            "PRAGMA synchronous = FULL;",
            "PRAGMA busy_timeout = 5000;",
            "PRAGMA cache_size = -8000;"
    };

    private static String currentDbPath = "data/client_A.db";
    private static Connection currentConnection;
    private static final Map<String, Connection> tenantConnections = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();

    private static volatile Runnable migrationFailureHook;

    private DbManager() {
    }

    public static synchronized Connection get() throws SQLException {
        String tenant = currentTenant.get();
        if (tenant != null && !tenant.isBlank()) {
            return getForTenant(tenant);
        }
        String sessionTenant = SessionContext.getTenantId();
        if (sessionTenant != null && !sessionTenant.isBlank()) {
            return getForTenant(sessionTenant);
        }
        if (currentConnection == null || currentConnection.isClosed()) {
            openConnection(currentDbPath);
        }
        return wrapNonClosing(currentConnection);
    }

    public static void setCurrentTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            currentTenant.remove();
            return;
        }
        TenantSecurityService.requireAuthorized(tenantId);
        currentTenant.set(TenantPathResolver.normalizeTenantId(tenantId));
        SessionContext.setTenantId(tenantId);
    }

    public static String getCurrentTenant() {
        String t = currentTenant.get();
        if (t != null) return t;
        return SessionContext.getTenantId();
    }

    public static void clearCurrentTenant() {
        currentTenant.remove();
    }

    public static synchronized Connection getForTenant(String tenantId) throws SQLException {
        if (tenantId == null || tenantId.isBlank()) {
            throw new SecurityException("tenantId must not be blank");
        }
        TenantSecurityService.requireAuthorized(tenantId);
        String dbPath = TenantPathResolver.resolveDbPath(tenantId);
        return getOrOpenTenantConnection(dbPath);
    }

    static synchronized Connection getForDb(String dbPath) throws SQLException {
        TenantPathResolver.assertSafePath(dbPath);
        if (dbPath != null && dbPath.replace('\\', '/').startsWith(TenantPathResolver.TENANT_ROOT + "/")) {
            throw new SecurityException(
                    "Tenant databases must be opened via getForTenant(tenantId), not by path");
        }
        return getOrOpenTenantConnection(dbPath);
    }

    private static Connection getOrOpenTenantConnection(String dbPath) throws SQLException {
        Connection conn = tenantConnections.get(dbPath);
        if (conn == null || conn.isClosed()) {
            conn = createRawConnection(dbPath);
            tenantConnections.put(dbPath, conn);
        }
        return wrapNonClosing(conn);
    }

    @Deprecated
    public static String resolveTenantDbPath(String tenantId) {
        String clean = tenantId.trim().replaceAll("[^a-zA-Z0-9_.-]", "_");
        return "data/" + clean + ".db";
    }

    public static synchronized void closeTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            clearCurrentTenant();
            return;
        }
        String canonical;
        try {
            canonical = TenantPathResolver.normalizeTenantId(tenantId);
        } catch (IllegalArgumentException e) {
            clearCurrentTenant();
            return;
        }
        if (canonical.equals(currentTenant.get())) {
            currentTenant.remove();
        }
        String dbPath;
        try {
            dbPath = TenantPathResolver.resolveDbPath(canonical);
        } catch (Exception e) {
            dbPath = resolveTenantDbPath(canonical);
        }
        Connection conn = tenantConnections.remove(dbPath);
        if (conn != null) {
            try {
                if (!conn.isClosed()) conn.close();
            } catch (SQLException ignored) {
            }
        }
    }

    public static synchronized void closeAllTenants() {
        for (Connection conn : tenantConnections.values()) {
            if (conn != null) {
                try {
                    if (!conn.isClosed()) conn.close();
                } catch (SQLException ignored) {
                }
            }
        }
        tenantConnections.clear();
        closeCurrent();
        currentTenant.remove();
    }

    public static synchronized void switchDatabase(String dbPath) throws SQLException {
        TenantPathResolver.assertSafePath(dbPath);
        if (dbPath != null && dbPath.replace('\\', '/').startsWith(TenantPathResolver.TENANT_ROOT + "/")) {
            throw new SecurityException("Use setCurrentTenant / getForTenant for tenant databases");
        }
        closeCurrent();
        currentDbPath = dbPath;
        openConnection(dbPath);
    }

    public static synchronized String getCurrentDbPath() {
        return currentDbPath;
    }

    public static synchronized void closeCurrent() {
        if (currentConnection != null) {
            try {
                if (!currentConnection.isClosed()) {
                    currentConnection.close();
                }
            } catch (SQLException ignored) {
            }
            currentConnection = null;
        }
    }

    private static void openConnection(String dbPath) throws SQLException {
        currentConnection = createRawConnection(dbPath);
    }

    public static Connection createRawConnection(String dbPath) throws SQLException {
        boolean isMemory = ":memory:".equalsIgnoreCase(dbPath);
        if (!isMemory) {
            Path path = Paths.get(dbPath);
            Path parent = path.getParent();
            if (parent != null && !Files.exists(parent)) {
                try {
                    Files.createDirectories(parent);
                } catch (Exception e) {
                    throw new SQLException("無法建立資料庫目錄: " + parent, e);
                }
            }
        }

        String url = "jdbc:sqlite:" + dbPath;
        Connection conn = DriverManager.getConnection(url);
        applyPragmas(conn);
        migrateSchema(conn);
        return conn;
    }

    private static Connection wrapNonClosing(Connection target) {
        final Connection targetConn = target;
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        return null;
                    }
                    if ("isClosed".equals(method.getName())) {
                        return targetConn == null || targetConn.isClosed();
                    }
                    try {
                        return method.invoke(targetConn, args);
                    } catch (java.lang.reflect.InvocationTargetException e) {
                        throw e.getCause();
                    }
                }
        );
    }

    public static void applyPragmas(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String pragma : PRAGMAS) {
                st.execute(pragma);
            }
        }
    }

    public static void setMigrationFailureHook(Runnable hook) {
        migrationFailureHook = hook;
    }

    public static void clearMigrationFailureHook() {
        migrationFailureHook = null;
    }

    public static void migrateSchema(Connection conn) throws SQLException {
        boolean tablesExist = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='accounts'")) {
            if (rs.next() && rs.getInt(1) > 0) {
                tablesExist = true;
            }
        }

        if (!tablesExist) {
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                initFullSchema(conn);
                setSchemaVersion(conn, CURRENT_SCHEMA_VERSION);
                getOrInitClientId(conn, currentDbPath);
                if (migrationFailureHook != null) {
                    migrationFailureHook.run();
                }
                conn.commit();
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw new SQLException("全新資料庫初始化失敗，已回滾: " + e.getMessage(), e);
            } finally {
                try { conn.setAutoCommit(originAutoCommit); } catch (SQLException ignored) {}
            }
            return;
        }

        int version = getSchemaVersion(conn);

        if (version < 0) {
            throw new SQLException("不合法的 schema_version: " + version);
        }
        if (version > CURRENT_SCHEMA_VERSION) {
            throw new SQLException("資料庫 schema_version (" + version +
                    ") 高於本程式支援版本 (" + CURRENT_SCHEMA_VERSION + ")，拒絕開啟");
        }

        if (version < CURRENT_SCHEMA_VERSION) {
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (version < 2) {
                    upgradeTriggers(conn);
                    maybeInjectFailure("after-triggers-v2");
                }
                if (version < 3) {
                    ensureSystemTables(conn);
                    upgradeTriggers(conn);
                    getOrInitClientId(conn, currentDbPath);
                    maybeInjectFailure("after-v3-upgrade");
                }
                setSchemaVersion(conn, CURRENT_SCHEMA_VERSION);
                maybeInjectFailure("after-set-version");
                verifyAndRepairSchemaIntegrity(conn);
                maybeInjectFailure("after-integrity");
                conn.commit();
            } catch (Exception e) {
                try { conn.rollback(); } catch (SQLException ignored) {}
                throw new SQLException("資料庫升級遷移失敗，已安全回滾: " + e.getMessage(), e);
            } finally {
                try { conn.setAutoCommit(originAutoCommit); } catch (SQLException ignored) {}
            }
        }
    }

    private static void maybeInjectFailure(String point) {
        Runnable hook = migrationFailureHook;
        if (hook != null) {
            hook.run();
        }
    }

    public static void verifyAndRepairSchemaIntegrity(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('accounts','vouchers','voucher_entries','system_meta')")) {
            int found = 0;
            while (rs.next()) found++;
            if (found < 4) {
                throw new SQLException("Schema integrity check failed: missing core tables");
            }
        }
        int v = getSchemaVersion(conn);
        if (v != CURRENT_SCHEMA_VERSION) {
            throw new SQLException("Schema version mismatch after migration: expected "
                    + CURRENT_SCHEMA_VERSION + " got " + v);
        }
    }

    public static int getSchemaVersion(Connection conn) throws SQLException {
        boolean metaExists = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='system_meta'")) {
            if (rs.next() && rs.getInt(1) > 0) {
                metaExists = true;
            }
        }

        if (metaExists) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT meta_value FROM system_meta WHERE meta_key = 'schema_version'")) {
                if (!rs.next()) {
                    throw new SQLException(
                            "system_meta exists but schema_version is missing — metadata damaged");
                }
                String raw = rs.getString(1);
                if (raw == null || raw.trim().isEmpty()) {
                    throw new SQLException("schema_version is NULL or empty — metadata damaged");
                }
                int v;
                try {
                    v = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    throw new SQLException("schema_version is not numeric: " + raw);
                }
                if (v < 0) {
                    throw new SQLException("schema_version is negative: " + v);
                }
                int userVer = readUserVersion(conn);
                if (userVer >= 0 && userVer != v) {
                    throw new SQLException(
                            "system_meta.schema_version (" + v + ") inconsistent with PRAGMA user_version ("
                                    + userVer + ")");
                }
                return v;
            }
        }

        return readUserVersion(conn);
    }

    private static int readUserVersion(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version;")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    public static void setSchemaVersion(Connection conn, int v) throws SQLException {
        if (v < 0) {
            throw new SQLException("Cannot set negative schema_version");
        }
        ensureSystemTables(conn);
        String sql = "INSERT INTO system_meta (meta_key, meta_value) VALUES ('schema_version', ?) " +
                     "ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, String.valueOf(v));
            ps.executeUpdate();
        }
        setUserVersion(conn, v);
    }

    public static void initFullSchema(Connection conn) throws SQLException {
        List<String> statements = loadSchemaStatements();
        try (Statement st = conn.createStatement()) {
            for (String sql : statements) {
                if (!sql.trim().isEmpty()) {
                    st.execute(sql);
                }
            }
        }
    }

    private static void setUserVersion(Connection conn, int v) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA user_version = " + v + ";");
        }
    }

    public static void ensureSystemTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS system_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL);");
            st.execute("CREATE TABLE IF NOT EXISTS voucher_sequences (prefix TEXT PRIMARY KEY, last_seq INTEGER NOT NULL);");
        }
    }

    public static String getClientId(Connection conn) throws SQLException {
        ensureSystemTables(conn);
        String sql = "SELECT meta_value FROM system_meta WHERE meta_key = 'client_id'";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        return null;
    }

    public static void setClientId(Connection conn, String clientId) throws SQLException {
        ensureSystemTables(conn);
        String sql = "INSERT INTO system_meta (meta_key, meta_value) VALUES ('client_id', ?) " +
                     "ON CONFLICT(meta_key) DO UPDATE SET meta_value = excluded.meta_value";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, clientId);
            ps.executeUpdate();
        }
    }

    public static String getOrInitClientId(Connection conn, String fallbackDbPath) throws SQLException {
        String existing = getClientId(conn);
        if (existing != null && !existing.trim().isEmpty()) {
            return existing;
        }
        String derived = extractClientIdFromPath(fallbackDbPath);
        if (derived != null && !derived.trim().isEmpty()) {
            setClientId(conn, derived);
            return derived;
        }
        return null;
    }

    public static String extractClientIdFromPath(String pathStr) {
        if (pathStr == null || pathStr.trim().isEmpty() || ":memory:".equalsIgnoreCase(pathStr.trim())) {
            return "memory";
        }
        String fileName;
        try {
            fileName = Paths.get(pathStr).getFileName().toString();
        } catch (Exception e) {
            int slashIdx = Math.max(pathStr.lastIndexOf('/'), pathStr.lastIndexOf('\\'));
            fileName = slashIdx >= 0 ? pathStr.substring(slashIdx + 1) : pathStr;
        }
        int dotIdx = fileName.lastIndexOf('.');
        String baseName = dotIdx > 0 ? fileName.substring(0, dotIdx) : fileName;
        return baseName.replaceAll("_\\d{8}_\\d{6}$", "");
    }

    public static void upgradeTriggers(Connection conn) throws SQLException {
        String[] dropTriggers = {
                "trg_voucher_no_delete",
                "trg_voucher_no_update",
                "trg_voucher_status_lock",
                "trg_voucher_balance_on_post",
                "trg_entry_no_insert_on_posted",
                "trg_entry_no_update_on_posted",
                "trg_entry_no_delete_on_posted",
                "trg_period_locked",
                "trg_audit_immutable",
                "trg_audit_immutable_update",
                "trg_audit_immutable_delete",
                "trg_vouchers_fts_ai",
                "trg_vouchers_fts_ad",
                "trg_vouchers_fts_au"
        };

        try (Statement st = conn.createStatement()) {
            for (String trg : dropTriggers) {
                st.execute("DROP TRIGGER IF EXISTS " + trg + ";");
            }

            List<String> statements = loadSchemaStatements();
            for (String sql : statements) {
                String upper = sql.trim().toUpperCase();
                if (upper.startsWith("CREATE TRIGGER")) {
                    st.execute(sql);
                }
            }
        }
    }

    private static List<String> loadSchemaStatements() throws SQLException {
        List<String> list = new ArrayList<>();
        InputStream is = DbManager.class.getResourceAsStream("/sql/schema.sql");
        if (is == null) {
            Path fallback = Paths.get("src/main/resources/sql/schema.sql");
            if (Files.exists(fallback)) {
                try {
                    is = Files.newInputStream(fallback);
                } catch (Exception e) {
                    throw new SQLException("無法讀取 schema.sql 檔案", e);
                }
            }
        }
        if (is == null) {
            throw new SQLException("找不到 schema.sql 資源檔案");
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            boolean inTrigger = false;

            while ((line = reader.readLine()) != null) {
                String trimmed = line.trim();
                if (trimmed.startsWith("--") || trimmed.isEmpty()) {
                    continue;
                }

                if (trimmed.toUpperCase().contains("CREATE TRIGGER")) {
                    inTrigger = true;
                }

                sb.append(line).append("\n");

                if (!inTrigger) {
                    if (trimmed.endsWith(";")) {
                        list.add(sb.toString().trim());
                        sb.setLength(0);
                    }
                } else {
                    if (trimmed.equalsIgnoreCase("END;")) {
                        list.add(sb.toString().trim());
                        sb.setLength(0);
                        inTrigger = false;
                    }
                }
            }
            if (!sb.toString().trim().isEmpty()) {
                list.add(sb.toString().trim());
            }
        } catch (Exception e) {
            throw new SQLException("解析 schema.sql 失敗: " + e.getMessage(), e);
        }
        return list;
    }
}
