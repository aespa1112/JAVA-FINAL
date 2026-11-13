package com.ledger.config;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
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
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Database access manager with tenant isolation and Connection Lease lifecycle.
 * <p>
 * Public entry points that return a Connection for a tenant MUST go through
 * TenantSecurityService authorization and TenantPathResolver path derivation.
 * Arbitrary external dbPath cannot be used to open a tenant database.
 * <p>
 * Connection lifecycle (P0-02):
 * - Every call to get() / getForTenant() / getForDb() leases a dedicated JDBC
 *   Connection wrapped with a lifecycle proxy.
 * - Calling close() on a leased Connection genuinely commits/rolls back any
 *   unfinished transaction, closes the underlying SQLite Connection, and marks
 *   the handle closed so subsequent operations fail with SQLException.
 * - Concurrent threads receive independent leases and never share a single
 *   physical Connection or overwrite each other's transactions.
 */
public final class DbManager {

    private static final Logger LOG = Logger.getLogger(DbManager.class.getName());

    public static final int CURRENT_SCHEMA_VERSION = 3;

    private static final String[] PRAGMAS = {
            "PRAGMA journal_mode = WAL;",
            "PRAGMA foreign_keys = ON;",
            "PRAGMA synchronous = FULL;",
            "PRAGMA busy_timeout = 15000;",
            "PRAGMA cache_size = -8000;"
    };

    private static final class LeaseHolder {
        final Connection raw;
        final String normKey;
        final AtomicBoolean closed = new AtomicBoolean(false);
        final Object opLock = new Object();

        LeaseHolder(Connection raw, String normKey) {
            this.raw = raw;
            this.normKey = normKey;
        }

        void closeLease() {
            synchronized (opLock) {
                if (closed.compareAndSet(false, true)) {
                    closeUnderlyingQuietly(raw);
                }
            }
        }

        boolean isClosed() {
            synchronized (opLock) {
                try {
                    return closed.get() || raw == null || raw.isClosed();
                } catch (SQLException e) {
                    return true;
                }
            }
        }
    }

    private static volatile String currentDbPath = "data/client_A.db";
    private static final Map<String, Set<LeaseHolder>> activeLeases = new ConcurrentHashMap<>();
    private static final Map<String, Object> pathLocks = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();

    private static volatile Runnable migrationFailureHook;

    private DbManager() {
    }

    private static String normalizePathKey(String dbPath) {
        if (dbPath == null) return "";
        return dbPath.trim().replace('\\', '/');
    }

    public static Connection get() throws SQLException {
        String tenant = currentTenant.get();
        if (tenant != null && !tenant.isBlank()) {
            return getForTenant(tenant);
        }
        String sessionTenant = SessionContext.getTenantId();
        if (sessionTenant != null && !sessionTenant.isBlank()) {
            return getForTenant(sessionTenant);
        }
        return leaseConnection(currentDbPath);
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

    public static Connection getForTenant(String tenantId) throws SQLException {
        if (tenantId == null || tenantId.isBlank()) {
            throw new SecurityException("tenantId must not be blank");
        }
        TenantSecurityService.requireAuthorized(tenantId);
        String dbPath = TenantPathResolver.resolveDbPath(tenantId);
        return leaseConnection(dbPath);
    }

    static Connection getForDb(String dbPath) throws SQLException {
        TenantPathResolver.assertSafePath(dbPath);
        String norm = normalizePathKey(dbPath);
        if (norm.startsWith(TenantPathResolver.TENANT_ROOT + "/")) {
            throw new SecurityException(
                    "Tenant databases must be opened via getForTenant(tenantId), not by path");
        }
        if (SessionContext.isProduction() && (":memory:".equalsIgnoreCase(norm) || !"data/client_A.db".equals(norm))) {
            TenantSecurityService.requireAuthorized(extractClientIdFromPath(norm));
        }
        return leaseConnection(dbPath);
    }

    private static Connection leaseConnection(String dbPath) throws SQLException {
        String normKey = normalizePathKey(dbPath);
        Object lock = pathLocks.computeIfAbsent(normKey, k -> new Object());
        synchronized (lock) {
            Connection raw = createRawConnection(dbPath);
            return wrapLeasedConnection(raw, normKey);
        }
    }

    public static int getActiveLeaseCount() {
        int total = 0;
        for (Set<LeaseHolder> set : activeLeases.values()) {
            if (set != null) {
                for (LeaseHolder h : set) {
                    if (!h.isClosed()) total++;
                }
            }
        }
        return total;
    }

    public static int getActiveLeaseCountForTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) return 0;
        String dbPath = TenantPathResolver.resolveDbPath(tenantId);
        Set<LeaseHolder> set = activeLeases.get(normalizePathKey(dbPath));
        if (set == null) return 0;
        int count = 0;
        for (LeaseHolder h : set) {
            if (!h.isClosed()) count++;
        }
        return count;
    }

    @Deprecated
    public static String resolveTenantDbPath(String tenantId) {
        return TenantPathResolver.resolveDbPath(tenantId);
    }

    public static void closeTenant(String tenantId) {
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
        closeAllLeasesForPath(dbPath);
    }

    public static void closeAllTenants() {
        List<String> keys = new ArrayList<>(activeLeases.keySet());
        for (String key : keys) {
            closeAllLeasesForPath(key);
        }
        activeLeases.clear();
        currentTenant.remove();
    }

    private static void closeAllLeasesForPath(String dbPath) {
        String normKey = normalizePathKey(dbPath);
        Object lock = pathLocks.computeIfAbsent(normKey, k -> new Object());
        List<LeaseHolder> holders;
        synchronized (lock) {
            Set<LeaseHolder> set = activeLeases.remove(normKey);
            if (set == null || set.isEmpty()) return;
            holders = new ArrayList<>(set);
            set.clear();
        }
        for (LeaseHolder h : holders) {
            h.closeLease();
        }
    }

    private static void closeUnderlyingQuietly(Connection conn) {
        if (conn == null) return;
        try {
            if (!conn.isClosed()) {
                try {
                    if (!conn.getAutoCommit()) {
                        conn.rollback();
                        conn.setAutoCommit(true);
                    }
                } catch (SQLException ignored) {
                }
                conn.close();
            }
        } catch (SQLException ignored) {
        }
    }

    public static synchronized void switchDatabase(String dbPath) throws SQLException {
        TenantPathResolver.assertSafePath(dbPath);
        String norm = normalizePathKey(dbPath);
        if (norm.startsWith(TenantPathResolver.TENANT_ROOT + "/")) {
            throw new SecurityException("Use setCurrentTenant / getForTenant for tenant databases");
        }
        if (SessionContext.isProduction()) {
            if (":memory:".equalsIgnoreCase(norm)) {
                throw new SecurityException("正式環境禁止使用 :memory: 測試資料庫");
            }
            if (!"data/client_A.db".equals(norm)) {
                TenantSecurityService.requireAuthorized(extractClientIdFromPath(norm));
            }
        }
        closeCurrent();
        String previous = currentDbPath;
        currentDbPath = dbPath;
        try (Connection ignored = leaseConnection(dbPath)) {
            // Ensure schema migration / validation executes immediately on switch
        } catch (SQLException | RuntimeException e) {
            currentDbPath = previous;
            throw e;
        }
    }

    public static String getCurrentDbPath() {
        return currentDbPath;
    }

    public static void closeCurrent() {
        closeAllLeasesForPath(currentDbPath);
    }

    static Connection createRawConnection(String dbPath) throws SQLException {
        boolean isMemory = ":memory:".equalsIgnoreCase(dbPath);
        Path allowedRoot = null;
        Path preReal = null;
        String url = "jdbc:sqlite:" + dbPath;
        Connection conn;

        if (!isMemory) {
            String norm = normalizePathKey(dbPath);
            allowedRoot = norm.startsWith(TenantPathResolver.TENANT_ROOT + "/")
                    ? Paths.get(TenantPathResolver.TENANT_ROOT)
                    : Paths.get("data");
            synchronized (TenantPathResolver.PATH_GUARD_LOCK) {
                preReal = TenantPathResolver.prepareAndVerifyDatabaseFile(dbPath, allowedRoot);
                conn = DriverManager.getConnection(url);
                try {
                    TenantPathResolver.verifyOpenedSqliteConnection(conn, preReal, allowedRoot);
                    applyPragmas(conn);
                    TenantPathResolver.verifyOpenedSqliteConnection(conn, preReal, allowedRoot);
                } catch (Throwable t) {
                    closeUnderlyingQuietly(conn);
                    if (t instanceof SQLException se) throw se;
                    if (t instanceof RuntimeException re) throw re;
                    if (t instanceof Error err) throw err;
                    throw new SQLException(t);
                }
            }
        } else {
            conn = DriverManager.getConnection(url);
            try {
                applyPragmas(conn);
            } catch (Throwable t) {
                closeUnderlyingQuietly(conn);
                if (t instanceof SQLException se) throw se;
                if (t instanceof RuntimeException re) throw re;
                if (t instanceof Error err) throw err;
                throw new SQLException(t);
            }
        }

        try {
            migrateSchema(conn, dbPath);
            if (!isMemory) {
                TenantPathResolver.verifyOpenedSqliteConnection(conn, preReal, allowedRoot);
            }
            return conn;
        } catch (SQLException e) {
            closeUnderlyingQuietly(conn);
            throw e;
        } catch (Exception | Error e) {
            closeUnderlyingQuietly(conn);
            if (e instanceof SecurityException se) throw se;
            if (e instanceof RuntimeException re) throw re;
            if (e instanceof Error err) throw err;
            throw new SQLException(e);
        }
    }

    private static Connection wrapLeasedConnection(Connection realConnection, String normKey) {
        LeaseHolder holder = new LeaseHolder(realConnection, normKey);
        activeLeases.computeIfAbsent(normKey, k -> ConcurrentHashMap.newKeySet()).add(holder);
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, args) -> {
                    String name = method.getName();
                    if ("equals".equals(name)) {
                        return proxy == (args != null && args.length > 0 ? args[0] : null);
                    }
                    if ("hashCode".equals(name)) {
                        return System.identityHashCode(proxy);
                    }
                    if ("toString".equals(name)) {
                        return "LeasedConnection[" + normKey + ", closed=" + holder.isClosed() + "]";
                    }
                    if ("close".equals(name)) {
                        Set<LeaseHolder> set = activeLeases.get(normKey);
                        if (set != null) {
                            set.remove(holder);
                        }
                        holder.closeLease();
                        return null;
                    }
                    if ("isClosed".equals(name)) {
                        return holder.isClosed();
                    }
                    if ("isValid".equals(name) && holder.isClosed()) {
                        return false;
                    }
                    synchronized (holder.opLock) {
                        if (holder.isClosed()) {
                            throw new SQLException("Connection lease is already closed");
                        }
                        try {
                            return method.invoke(realConnection, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                }
        );
    }

    public static void applyPragmas(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 15000;");
            boolean alreadyWal = false;
            try (ResultSet rs = st.executeQuery("PRAGMA journal_mode;")) {
                if (rs.next() && "wal".equalsIgnoreCase(rs.getString(1))) {
                    alreadyWal = true;
                }
            }
            if (!alreadyWal) {
                st.execute("PRAGMA journal_mode = WAL;");
            }
            st.execute("PRAGMA foreign_keys = ON;");
            st.execute("PRAGMA synchronous = FULL;");
            st.execute("PRAGMA cache_size = -8000;");
        }
    }

    public static void setMigrationFailureHook(Runnable hook) {
        migrationFailureHook = hook;
    }

    public static void clearMigrationFailureHook() {
        migrationFailureHook = null;
    }

    public static void migrateSchema(Connection conn) throws SQLException {
        migrateSchema(conn, currentDbPath);
    }

    public static void migrateSchema(Connection conn, String dbPath) throws SQLException {
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
                getOrInitClientId(conn, dbPath);
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
                    getOrInitClientId(conn, dbPath);
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
        } else {
            ensureSystemMetaSchemaVersionSynced(conn, CURRENT_SCHEMA_VERSION);
            ensureExtendedColumnsAndAccounts(conn);
        }
    }

    private static void ensureSystemMetaSchemaVersionSynced(Connection conn, int version) throws SQLException {
        boolean hasSchemaVersion = false;
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
                         "SELECT 1 FROM system_meta WHERE meta_key = 'schema_version'")) {
                hasSchemaVersion = rs.next();
            }
        }
        if (!hasSchemaVersion) {
            setSchemaVersion(conn, version);
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
                    int userVer = readUserVersion(conn);
                    if (userVer > 0 && userVer <= CURRENT_SCHEMA_VERSION) {
                        return userVer;
                    }
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
        ensureExtendedColumnsAndAccounts(conn);
    }

    private static void ensureExtendedColumnsAndAccounts(Connection conn) throws SQLException {
        boolean hasVouchers = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='vouchers'")) {
            if (rs.next() && rs.getInt(1) > 0) {
                hasVouchers = true;
            }
        }
        if (hasVouchers) {
            boolean hasVoucherType = false;
            boolean hasClosingYear = false;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(vouchers)")) {
                while (rs.next()) {
                    String col = rs.getString("name");
                    if ("voucher_type".equalsIgnoreCase(col)) hasVoucherType = true;
                    if ("closing_year".equalsIgnoreCase(col)) hasClosingYear = true;
                }
            }
            if (!hasVoucherType || !hasClosingYear) {
                try (Statement st = conn.createStatement()) {
                    if (!hasVoucherType) {
                        st.execute("ALTER TABLE vouchers ADD COLUMN voucher_type TEXT NOT NULL DEFAULT 'NORMAL'");
                    }
                    if (!hasClosingYear) {
                        st.execute("ALTER TABLE vouchers ADD COLUMN closing_year INTEGER");
                    }
                }
            }
        }
        boolean hasAccounts = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='accounts'")) {
            if (rs.next() && rs.getInt(1) > 0) {
                hasAccounts = true;
            }
        }
        if (hasAccounts) {
            int present = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT count(*) FROM accounts WHERE code IN ('1103','1104','1402','1501','1502','2123','2201','4202','5209','5302')")) {
                if (rs.next()) present = rs.getInt(1);
            }
            if (present < 10) {
                try (Statement st = conn.createStatement()) {
                    st.execute("INSERT OR IGNORE INTO accounts (code, name, type, normal_side, is_leaf, enabled) VALUES " +
                            "('1103', '零用金', 'ASSET', 'DEBIT', 1, 1)," +
                            "('1104', '約當現金', 'ASSET', 'DEBIT', 1, 1)," +
                            "('1402', '累計折舊', 'ASSET', 'CREDIT', 1, 1)," +
                            "('1501', '無形資產', 'ASSET', 'DEBIT', 1, 1)," +
                            "('1502', '累計攤銷', 'ASSET', 'CREDIT', 1, 1)," +
                            "('2123', '應付設備款', 'LIABILITY', 'CREDIT', 1, 1)," +
                            "('2201', '長期借款', 'LIABILITY', 'CREDIT', 1, 1)," +
                            "('4202', '處分資產利益', 'REVENUE', 'CREDIT', 1, 1)," +
                            "('5209', '攤銷費用', 'EXPENSE', 'DEBIT', 1, 1)," +
                            "('5302', '處分資產損失', 'EXPENSE', 'DEBIT', 1, 1);");
                }
            }
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
