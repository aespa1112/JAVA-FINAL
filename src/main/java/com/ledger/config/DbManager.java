package com.ledger.config;

import com.ledger.exception.BusinessException;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 資料庫管理中心 (R9)：
 * 1. 禁止多使用者共用同一 JDBC Connection；每次取得均為獨立連線，交易具備獨立生命週期。
 * 2. 修正 close() 被攔截問題：呼叫 close() 時確實關閉連線，isClosed() 為 true，關閉後操作拋出 SQLException。
 * 3. 租戶授權嚴格執行 Fail-Closed：未登入、租戶識別為空或未授權時拒絕存取，禁止自動退回預設資料庫。
 * 4. 租戶路徑安全與防碰撞：使用穩定 Tenant UUID 映射唯一路徑，防範 Path Traversal。
 * 5. client_id 正確綁定各租戶資料庫，禁止使用全域路徑推導。
 * 6. Migration 完整原子性與版本錯誤處理：區分損壞與舊版資料庫，system_meta 與 PRAGMA user_version 同步。
 */
public final class DbManager {

    public static final int CURRENT_SCHEMA_VERSION = 3;

    private static final String[] PRAGMAS = {
            "PRAGMA journal_mode = WAL;",     // 讀寫並行，查報表時不阻塞寫入
            "PRAGMA foreign_keys = ON;",      // SQLite 預設關閉，必須手動開啟
            "PRAGMA synchronous = NORMAL;",   // WAL 模式下兼具安全性與並行效能
            "PRAGMA busy_timeout = 15000;",   // 延長逾時至 15 秒，避免高並行競爭拋 BUSY
            "PRAGMA cache_size = -8000;"      // 約 8MB 分頁快取
    };

    private static String currentDbPath = "data/client_A.db";
    private static final ThreadLocal<String> currentTenant = new ThreadLocal<>();
    private static final Set<String> initializedDbs = ConcurrentHashMap.newKeySet();

    private DbManager() {
    }

    /**
     * 取得當前工作單位的獨立連線：
     * 若已設定租戶上下文，路由至該租戶；
     * 連線具有獨立生命週期，close() 確實釋放底層連線。
     */
    public static Connection get() throws SQLException {
        String tenant = currentTenant.get();
        if (tenant != null && !tenant.isBlank()) {
            return getForTenant(tenant);
        }
        if (SessionContext.isProduction()) {
            throw new BusinessException("未指定租戶帳套，拒絕操作（生產環境禁止使用未指定租戶的預設資料庫）");
        }
        Connection raw = createRawConnection(currentDbPath);
        return wrapManagedConnection(raw, currentDbPath);
    }

    /**
     * 設定當前執行緒之租戶上下文（Fail-Closed 驗證）。
     */
    public static void setCurrentTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("未指定租戶識別碼，拒絕設定租戶上下文");
        }
        new com.ledger.service.TenantSecurityService().assertAccess(tenantId);
        currentTenant.set(tenantId);
    }

    public static String getCurrentTenant() {
        return currentTenant.get();
    }

    /**
     * 清除當前執行緒租戶上下文，防止執行緒重用污染。
     */
    public static void clearCurrentTenant() {
        currentTenant.remove();
    }

    /**
     * 獲取指定租戶之獨立資料庫連線（Fail-Closed 驗證）。
     */
    public static Connection getForTenant(String tenantId) throws SQLException {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BusinessException("未指定租戶識別碼，拒絕操作（禁止自動退回預設資料庫）");
        }
        new com.ledger.service.TenantSecurityService().assertAccess(tenantId);
        String dbPath = resolveTenantDbPath(tenantId);
        return getForDb(dbPath);
    }

    /**
     * 獲取指定資料庫檔案路徑之獨立連線。
     */
    public static Connection getForDb(String dbPath) throws SQLException {
        Connection raw = createRawConnection(dbPath);
        return wrapManagedConnection(raw, dbPath);
    }

    /**
     * 安全解析租戶資料庫路徑（使用 UUID 與防穿越驗證）。
     */
    public static String resolveTenantDbPath(String tenantId) {
        return TenantPathResolver.resolveTenantDbPath(tenantId);
    }

    public static synchronized void closeTenant(String tenantId) {
        clearCurrentTenant();
    }

    public static synchronized void closeAllTenants() {
        clearCurrentTenant();
    }

    public static synchronized void switchDatabase(String dbPath) throws SQLException {
        currentDbPath = dbPath;
    }

    public static synchronized String getCurrentDbPath() {
        return currentDbPath;
    }

    public static synchronized void closeCurrent() {
        clearCurrentTenant();
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

        // 確保結構遷移與 client_id 綁定使用實際建立的 dbPath（R9-05）
        migrateSchema(conn, dbPath);
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
        migrateSchema(conn, currentDbPath);
    }

    public static void migrateSchema(Connection conn, String dbPath) throws SQLException {
        boolean tablesExist = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='accounts'")) {
            if (rs.next() && rs.getInt(1) > 0) {
                tablesExist = true;
            }
        }

        if (!tablesExist) {
            // 全新資料庫：系統表、Trigger、Index、版本號及 client_id 納入單一交易原子初始化 (R9-06)
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                initFullSchema(conn);
                ensureSystemTables(conn);
                upgradeTriggers(conn);
                setSchemaVersion(conn, CURRENT_SCHEMA_VERSION);
                getOrInitClientId(conn, dbPath);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new SQLException("全新資料庫初始化失敗，已安全回滾: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originAutoCommit);
            }
            return;
        }

        int version = getSchemaVersion(conn);

        if (version < CURRENT_SCHEMA_VERSION) {
            // 升級舊資料庫：包覆於交易中以保證結構遷移原子性
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 升級前確保所有核心表格結構均存在，避免舊資料庫缺少部分表格導致 Trigger 建立失敗
                ensureAllSchemaTables(conn);
                ensureSystemTables(conn);

                if (version < 2) {
                    upgradeTriggers(conn);
                }
                if (version < 3) {
                    upgradeTriggers(conn);
                    getOrInitClientId(conn, dbPath);
                }
                setSchemaVersion(conn, CURRENT_SCHEMA_VERSION);
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw new SQLException("資料庫升級遷移失敗，已安全回滾: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originAutoCommit);
            }
        }

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

    /**
     * 讀取 Schema 版本 (R9-07)：
     * 1. 區分資料表不存在、版本資料不存在、格式錯誤與 SQL 錯誤。
     * 2. 若 system_meta 資料損壞拋出明確 SQLException，絕不靜默略過。
     * 3. 僅確定為舊版資料庫時才 fallback 至 PRAGMA user_version。
     */
    public static int getSchemaVersion(Connection conn) throws SQLException {
        boolean metaTableExists = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='system_meta'")) {
            metaTableExists = rs.next() && rs.getInt(1) > 0;
        }

        if (metaTableExists) {
            String val = null;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT meta_value FROM system_meta WHERE meta_key = 'schema_version'")) {
                if (rs.next()) {
                    val = rs.getString(1);
                }
            }
            if (val != null) {
                try {
                    return Integer.parseInt(val.trim());
                } catch (NumberFormatException nfe) {
                    throw new SQLException("資料庫元資料損壞：system_meta 中的 schema_version 格式錯誤 [" + val + "]", nfe);
                }
            }
        }

        // 檢查是否為舊版資料庫（具有 accounts 但無 system_meta 或無 schema_version 鍵值）
        boolean accountsExist = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='accounts'")) {
            accountsExist = rs.next() && rs.getInt(1) > 0;
        }

        if (accountsExist) {
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA user_version;")) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }

        return 0;
    }

    /**
     * 同步更新 system_meta 與 PRAGMA user_version，保持嚴格一致。
     */
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

    public static void ensureAllSchemaTables(Connection conn) throws SQLException {
        List<String> statements = loadSchemaStatements();
        try (Statement st = conn.createStatement()) {
            for (String sql : statements) {
                String upper = sql.trim().toUpperCase();
                if (upper.startsWith("CREATE TABLE")) {
                    st.execute(sql);
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

    public static String getOrInitClientId(Connection conn, String actualDbPath) throws SQLException {
        String existing = getClientId(conn);
        if (existing != null && !existing.trim().isEmpty()) {
            return existing;
        }
        String derived = extractClientIdFromPath(actualDbPath);
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
            throw new SQLException("缺少 /sql/schema.sql 資源檔案");
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

        /**
     * ManagedConnection 代理包裝：
     * 1. 呼叫 close() 時確實關閉底層連線並釋放資源。
     * 2. isClosed() 於關閉後返回 true。
     * 3. 已關閉連線禁止繼續被使用，拋出 SQLException。
     */
    private static Connection wrapManagedConnection(Connection realConnection, String dbPath) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                new InvocationHandler() {
                    private volatile boolean closed = false;

                    @Override
                    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
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
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                    }
                }
        );
    }
}
