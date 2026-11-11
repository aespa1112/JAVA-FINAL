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

public final class DbManager {

    public static final int CURRENT_SCHEMA_VERSION = 3;

    private static final String[] PRAGMAS = {
            "PRAGMA journal_mode = WAL;",     // 讀寫並行，查報表時不阻塞寫入
            "PRAGMA foreign_keys = ON;",      // SQLite 預設關閉，必須手動開啟
            "PRAGMA synchronous = FULL;",     // 財務資料優先確保耐久性，不犧牲安全換效能
            "PRAGMA busy_timeout = 15000;",
            "PRAGMA cache_size = -8000;"      // 約 8MB 分頁快取
    };

    private static String currentDbPath = "data/client_A.db";
    private static Connection currentConnection;
    private static final Map<String, Connection> tenantConnections = new ConcurrentHashMap<>();
    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();

    private DbManager() {
    }

    public static Connection get() throws SQLException {
        String tenant = currentTenant.get();
        if (tenant != null && !tenant.isBlank()) {
            return getForTenant(tenant);
        }
        Connection raw = createRawConnection(currentDbPath);
        return wrapManagedConnection(raw, currentDbPath);
    }

    public static void setCurrentTenant(String tenantId) {
        if (tenantId != null && !tenantId.isBlank()) {
            new com.ledger.service.TenantSecurityService().assertAccess(tenantId);
        }
        currentTenant.set(tenantId);
    }

    public static String getCurrentTenant() {
        return currentTenant.get();
    }

    public static void clearCurrentTenant() {
        currentTenant.remove();
    }

    public static Connection getForTenant(String tenantId) throws SQLException {
        if (tenantId == null || tenantId.isBlank()) {
            return get();
        }
        // 租戶授權嚴格檢查：禁止存取未授權租戶
        new com.ledger.service.TenantSecurityService().assertAccess(tenantId);

        // 租戶路徑安全解析：防範路徑穿越與碰撞
        String dbPath = resolveTenantDbPath(tenantId);
        return getForDb(dbPath);
    }

    public static Connection getForDb(String dbPath) throws SQLException {
        Connection raw = createRawConnection(dbPath);
        return wrapManagedConnection(raw, dbPath);
    }

    public static String resolveTenantDbPath(String tenantId) {
        return TenantPathResolver.resolveTenantDbPath(tenantId);
    }

    public static synchronized void closeTenant(String tenantId) {
        clearCurrentTenant();
    }

    public static synchronized void closeAllTenants() {
        clearCurrentTenant();
        closeCurrent();
    }

    private static Connection wrapManagedConnection(Connection realConnection, String dbPath) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new java.lang.reflect.InvocationHandler() {
                    private volatile boolean closed = false;

                    @Override
                    public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable {
                        String name = method.getName();
                        if ("close".equals(name)) {
                            if (!closed) {
                                closed = true;
                                realConnection.close();
                            }
                            return null;
                        }
                        if ("isClosed".equals(name)) {
                            return closed || realConnection.isClosed();
                        }
                        if (closed || realConnection.isClosed()) {
                            throw new SQLException("Connection is closed");
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

    public static synchronized void switchDatabase(String dbPath) throws SQLException {
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

    public static void applyPragmas(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            for (String pragma : PRAGMAS) {
                st.execute(pragma);
            }
        }
    }

    public static void migrateSchema(Connection conn) throws SQLException {
        boolean tablesExist = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='accounts'")) {
            if (rs.next() && rs.getInt(1) > 0) {
                tablesExist = true;
            }
        }

        if (!tablesExist) {
            // 全新資料庫，執行全套建表腳本
            initFullSchema(conn);
            setSchemaVersion(conn, CURRENT_SCHEMA_VERSION);
            getOrInitClientId(conn, currentDbPath);
            return;
        }

        int version = getSchemaVersion(conn);

        if (version < CURRENT_SCHEMA_VERSION) {
            // 升級舊資料庫：包覆於交易中以保證結構遷移原子性（版本號寫入 system_meta 隨交易回滾）
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (version < 2) {
                    upgradeTriggers(conn);
                }
                if (version < 3) {
                    ensureSystemTables(conn);
                    upgradeTriggers(conn);
                    getOrInitClientId(conn, currentDbPath);
                }
                // 寫入 system_meta（在交易內，若遷移拋出例外，版本號隨事務 100% 回滾）
                setSchemaVersion(conn, CURRENT_SCHEMA_VERSION);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new SQLException("資料庫升級遷移失敗，已安全回滾: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originAutoCommit);
            }
        }

        // 防範版本號與實際 Schema 不一致（例如版本號雖為 3 但觸發器或系統表意外缺失）：主動驗證並安全修復
        verifyAndRepairSchemaIntegrity(conn);
    }

    public static void verifyAndRepairSchemaIntegrity(Connection conn) throws SQLException {
        ensureSystemTables(conn);
        boolean triggersOk = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='trg_voucher_no_delete'")) {
            triggersOk = rs.next() && rs.getInt(1) > 0;
        }
        if (!triggersOk) {
            upgradeTriggers(conn);
        }
    }

    public static int getSchemaVersion(Connection conn) throws SQLException {
        // 優先從 system_meta 查詢（支援 100% 事務回滾）
        try {
            ensureSystemTables(conn);
            String sql = "SELECT meta_value FROM system_meta WHERE meta_key = 'schema_version'";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                if (rs.next()) {
                    return Integer.parseInt(rs.getString(1).trim());
                }
            }
        } catch (Exception ignored) {
            // 尚未建立 system_meta 或舊版資料庫時 fallback 至 PRAGMA user_version
        }

        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version;")) {
            if (rs.next()) {
                return rs.getInt(1);
            }
        }
        return 0;
    }

    public static void setSchemaVersion(Connection conn, int v) throws SQLException {
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

        // 若帶有備份檔時間戳格式（如 client_A_20261107_174034 或 client_A_1_20261107_174034），以正則精確去除時間戳
        // 如此可精確區分 client_A_1 與 client_A_2，避免只截斷前兩段造成的 ID 衝突
        return baseName.replaceAll("_\\d{8}_\\d{6}$", "");
    }

    public static void upgradeTriggers(Connection conn) throws SQLException {
        // 先刪除舊有或過時的 Trigger
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

            // 重新建立最新安全 Trigger
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
