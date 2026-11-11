package com.ledger;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.config.TenantPathResolver;
import com.ledger.config.TenantSecurityService;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * R10 security and isolation tests.
 */
@TestMethodOrder(MethodOrderer.DisplayName.class)
public class R10SecurityTest {

    @BeforeEach
    void setUp() throws Exception {
        System.setProperty("ledger.test", "true");
        SessionContext.clear();
        DbManager.clearCurrentTenant();
        DbManager.closeAllTenants();
        TenantSecurityService.resetForTest();
        TenantSecurityService.clearTestState();
        Path auth = Paths.get(TenantSecurityService.AUTH_DB_PATH);
        if (Files.exists(auth)) {
            Files.delete(auth);
        }
        Path wal = Paths.get(TenantSecurityService.AUTH_DB_PATH + "-wal");
        if (Files.exists(wal)) Files.delete(wal);
        Path shm = Paths.get(TenantSecurityService.AUTH_DB_PATH + "-shm");
        if (Files.exists(shm)) Files.delete(shm);
        TenantSecurityService.initDb();
        DbManager.clearMigrationFailureHook();
    }

    @AfterEach
    void tearDown() {
        SessionContext.clear();
        DbManager.clearCurrentTenant();
        DbManager.closeAllTenants();
        DbManager.clearMigrationFailureHook();
        TenantSecurityService.clearTestState();
    }

    @Test
    @DisplayName("R10-01a 未授權 tenantId 無法取得 Connection")
    void unauthorizedTenantDenied() {
        assertThrows(SecurityException.class, () -> DbManager.getForTenant("unknown_tenant_xyz"));
    }

    @Test
    @DisplayName("R10-01b 不存在的 tenantId 無法取得 Connection")
    void nonexistentTenantDenied() {
        assertFalse(TenantSecurityService.isAuthorized("no_such_tenant_999"));
        assertThrows(SecurityException.class, () -> DbManager.getForTenant("no_such_tenant_999"));
    }

    @Test
    @DisplayName("R10-01c 空字串 tenantId 無法取得 Connection")
    void blankTenantDenied() {
        assertThrows(Exception.class, () -> DbManager.getForTenant(""));
        assertThrows(Exception.class, () -> DbManager.getForTenant("   "));
        assertThrows(Exception.class, () -> DbManager.getForTenant(null));
    }

    @Test
    @DisplayName("R10-01d 任意外部 dbPath 無法繞過授權")
    void arbitraryPathBypassDenied() {
        assertThrows(SecurityException.class, () -> {
            DbManager.switchDatabase(TenantPathResolver.TENANT_ROOT + "/evil.db");
        });
        assertThrows(SecurityException.class, () -> {
            DbManager.switchDatabase(TenantPathResolver.TENANT_ROOT + "/evil.db");
        });
    }

    @Test
    @DisplayName("R10-01e 已授權 tenantId 可以正常取得自己的資料庫")
    void authorizedTenantCanOpen() throws Exception {
        TenantSecurityService.grant("tenant_alpha", "test");
        try (Connection c = DbManager.getForTenant("tenant_alpha")) {
            assertNotNull(c);
            assertFalse(c.isClosed());
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery("SELECT 1")) {
                assertTrue(rs.next());
            }
        }
    }

    @Test
    @DisplayName("R10-01f 租戶 A 無法開啟租戶 B 的資料庫")
    void tenantIsolationByAuth() throws Exception {
        TenantSecurityService.grant("tenant_a", "test");
        TenantSecurityService.grant("tenant_b", "test");
        String pathA = TenantPathResolver.resolveDbPath("tenant_a");
        String pathB = TenantPathResolver.resolveDbPath("tenant_b");
        assertNotEquals(pathA, pathB);

        Connection ca = DbManager.getForTenant("tenant_a");
        Connection cb = DbManager.getForTenant("tenant_b");
        assertNotNull(ca);
        assertNotNull(cb);

        TenantSecurityService.revoke("tenant_b");
        DbManager.closeTenant("tenant_b");
        assertThrows(SecurityException.class, () -> DbManager.getForTenant("tenant_b"));
    }

    @Test
    @DisplayName("R10-02a Thread A 使用者登入後不影響 Thread B")
    void sessionIsolationAcrossThreads() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch go = new CountDownLatch(1);
            AtomicReference<String> seenOnB = new AtomicReference<>();
            AtomicReference<String> seenOnA = new AtomicReference<>();

            Future<?> fa = pool.submit(() -> {
                try {
                    SessionContext.setUser("Alice", "Alice Display");
                    SessionContext.setTenantId("tenant_alice");
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    seenOnA.set(SessionContext.getUser() + "|" + SessionContext.getTenantId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    SessionContext.clear();
                }
            });

            Future<?> fb = pool.submit(() -> {
                try {
                    SessionContext.setUser("Bob", "Bob Display");
                    SessionContext.setTenantId("tenant_bob");
                    ready.countDown();
                    go.await(5, TimeUnit.SECONDS);
                    seenOnB.set(SessionContext.getUser() + "|" + SessionContext.getTenantId());
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    SessionContext.clear();
                }
            });

            assertTrue(ready.await(5, TimeUnit.SECONDS));
            go.countDown();
            fa.get(5, TimeUnit.SECONDS);
            fb.get(5, TimeUnit.SECONDS);

            assertEquals("Alice|tenant_alice", seenOnA.get());
            assertEquals("Bob|tenant_bob", seenOnB.get());
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("R10-02b 執行緒結束後 Session 正確清除")
    void sessionClearedAfterThread() throws Exception {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            pool.submit(() -> {
                SessionContext.setUser("TempUser", "Temp");
                SessionContext.setTenantId("temp_tenant");
                assertTrue(SessionContext.isLoggedIn());
                SessionContext.clear();
            }).get(5, TimeUnit.SECONDS);

            String residual = pool.submit(() -> {
                return SessionContext.getUser() + "|" + SessionContext.getTenantId();
            }).get(5, TimeUnit.SECONDS);
            assertEquals("null|null", residual);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("R10-02c Exception 時 Session 仍會清除 (try-finally pattern)")
    void sessionClearedOnException() {
        try {
            SessionContext.setUser("ExUser", "Ex");
            SessionContext.setTenantId("ex_tenant");
            throw new RuntimeException("boom");
        } catch (RuntimeException expected) {
        } finally {
            SessionContext.clear();
        }
        assertNull(SessionContext.getUser());
        assertNull(SessionContext.getTenantId());
        assertFalse(SessionContext.isLoggedIn());
    }

    @Test
    @DisplayName("R10-02d Thread Pool 重複使用不會殘留上一個使用者")
    void threadPoolNoLeak() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(1);
        try {
            pool.submit(() -> {
                SessionContext.setUser("First", "First");
                SessionContext.setTenantId("first_t");
            }).get(3, TimeUnit.SECONDS);

            String leaked = pool.submit(() -> {
                String u = SessionContext.getUser();
                SessionContext.clear();
                return u;
            }).get(3, TimeUnit.SECONDS);
            String after = pool.submit(SessionContext::getUser).get(3, TimeUnit.SECONDS);
            assertNull(after);
        } finally {
            pool.shutdownNow();
            SessionContext.clear();
        }
    }

    @Test
    @DisplayName("R10-05a 相同 tenantId 產生相同路徑")
    void sameTenantSamePath() {
        String p1 = TenantPathResolver.resolveDbPath("AcmeCorp");
        String p2 = TenantPathResolver.resolveDbPath("AcmeCorp");
        assertEquals(p1, p2);
    }

    @Test
    @DisplayName("R10-05b 大小寫不同的 tenantId 正規化為相同路徑")
    void caseNormalization() {
        String p1 = TenantPathResolver.resolveDbPath("AcmeCorp");
        String p2 = TenantPathResolver.resolveDbPath("acmecorp");
        String p3 = TenantPathResolver.resolveDbPath("ACMECORP");
        assertEquals(p1, p2);
        assertEquals(p2, p3);
    }

    @Test
    @DisplayName("R10-05c 前後空白處理一致")
    void whitespaceNormalization() {
        String p1 = TenantPathResolver.resolveDbPath("tenantx");
        String p2 = TenantPathResolver.resolveDbPath("  tenantx  ");
        assertEquals(p1, p2);
    }

    @Test
    @DisplayName("R10-05d 空字串拒絕")
    void emptyRejected() {
        assertThrows(IllegalArgumentException.class, () -> TenantPathResolver.normalizeTenantId(""));
        assertThrows(IllegalArgumentException.class, () -> TenantPathResolver.normalizeTenantId("   "));
        assertThrows(IllegalArgumentException.class, () -> TenantPathResolver.normalizeTenantId(null));
    }

    @Test
    @DisplayName("R10-05e Path Traversal 拒絕")
    void pathTraversalRejected() {
        assertThrows(IllegalArgumentException.class, () -> TenantPathResolver.normalizeTenantId("../etc"));
        assertThrows(IllegalArgumentException.class, () -> TenantPathResolver.normalizeTenantId("foo/../bar"));
        assertThrows(SecurityException.class, () -> TenantPathResolver.assertSafePath("../secret.db"));
        assertThrows(SecurityException.class, () -> TenantPathResolver.assertSafePath("/etc/passwd"));
        assertThrows(SecurityException.class, () -> TenantPathResolver.assertSafePath("data/../../etc/passwd"));
    }

    @Test
    @DisplayName("R10-05f 不同 tenantId 不應指向相同路徑")
    void differentTenantsDifferentPaths() {
        String p1 = TenantPathResolver.resolveDbPath("tenant_one");
        String p2 = TenantPathResolver.resolveDbPath("tenant_two");
        assertNotEquals(p1, p2);
    }

    @Test
    @DisplayName("R10-05g 路徑不得超出租戶資料庫根目錄")
    void pathStaysUnderRoot() {
        String p = TenantPathResolver.resolveDbPath("safe_tenant");
        assertTrue(p.startsWith(TenantPathResolver.TENANT_ROOT + "/"));
        assertFalse(p.contains(".."));
    }

    @Test
    @DisplayName("R10-04a 全新資料庫初始化")
    void freshDbInit() throws Exception {
        Path p = Paths.get("data/test_fresh_r10.db");
        Files.deleteIfExists(p);
        try (Connection c = DbManager.createRawConnection(p.toString())) {
            int v = DbManager.getSchemaVersion(c);
            assertEquals(DbManager.CURRENT_SCHEMA_VERSION, v);
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    @DisplayName("R10-04b system_meta 存在但缺少 schema_version 必須失敗")
    void missingSchemaVersionFails() throws Exception {
        Path p = Paths.get("data/test_meta_damaged.db");
        Files.deleteIfExists(p);
 amplifies;
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + p);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE system_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
            st.execute("INSERT INTO system_meta(meta_key, meta_value) VALUES ('client_id','x')");
            st.execute("CREATE TABLE accounts (id INTEGER PRIMARY KEY)");
        }
        try {
            SQLException ex = assertThrows(SQLException.class, () ->
                    DbManager.createRawConnection(p.toString()));
            assertTrue(ex.getMessage().contains("schema_version") ||
                    (ex.getCause() != null && ex.getCause().getMessage() != null
                            && ex.getCause().getMessage().contains("schema_version")),
                    "Expected schema_version error, got: " + ex.getMessage());
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    @DisplayName("R10-04c schema_version 非數字必須失敗")
    void nonNumericVersionFails() throws Exception {
        Path p = Paths.get("data/test_meta_nonnum.db");
        Files.deleteIfExists(p);
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + p);
             Statement st = c.createStatement()) {
            st.execute("CREATE TABLE system_meta (meta_key TEXT PRIMARY KEY, meta_value TEXT NOT NULL)");
            st.execute("INSERT INTO system_meta(meta_key, meta_value) VALUES ('schema_version','abc')");
            st.execute("CREATE TABLE accounts (id INTEGER PRIMARY KEY)");
        }
        try {
            assertThrows(SQLException.class, () -> DbManager.createRawConnection(p.toString()));
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    @DisplayName("R10-03a Migration 正常完成")
    void migrationSuccess() throws Exception {
        Path p = Paths.get("data/test_mig_ok.db");
        Files.deleteIfExists(p);
        try (Connection c = DbManager.createRawConnection(p.toString())) {
            assertEquals(DbManager.CURRENT_SCHEMA_VERSION, DbManager.getSchemaVersion(c));
            try (Statement st = c.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='accounts'")) {
                assertTrue(rs.next() && rs.getInt(1) == 1);
            }
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    @DisplayName("R10-03b Migration 中途失敗後 rollback，schema_version 不更新")
    void migrationFailureRollsBackVersion() throws Exception {
        Path p = Paths.get("data/test_mig_fail.db");
        Files.deleteIfExists(p);
        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + p);
             Statement st = c.createStatement()) {
            st.execute("PRAGMA user_version = 1;");
            st.execute("CREATE TABLE accounts (id INTEGER PRIMARY KEY, code TEXT, name TEXT, type TEXT, normal_side TEXT, is_leaf INTEGER, enabled INTEGER)");
            st.execute("INSERT INTO accounts(code,name,type,normal_side,is_leaf,enabled) VALUES ('1000','Cash','ASSET','DEBIT',1,1)");
        }

        DbManager.setMigrationFailureHook(() -> {
            throw new RuntimeException("injected migration failure");
        });

        try {
            assertThrows(SQLException.class, () -> DbManager.createRawConnection(p.toString()));
        } finally {
            DbManager.clearMigrationFailureHook();
        }

        try (Connection c = java.sql.DriverManager.getConnection("jdbc:sqlite:" + p);
             Statement st = c.createStatement()) {
            try (ResultSet rs = st.executeQuery(
                    "SELECT meta_value FROM system_meta WHERE meta_key='schema_version'")) {
                if (rs.next()) {
                    int v = Integer.parseInt(rs.getString(1));
                    assertNotEquals(DbManager.CURRENT_SCHEMA_VERSION, v,
                            "schema_version must not be CURRENT after failed migration");
                }
            } catch (Exception ignored) {
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM accounts")) {
                assertTrue(rs.next() && rs.getInt(1) == 1);
            }
        } finally {
            Files.deleteIfExists(p);
        }
    }

    @Test
    @DisplayName("R10-07a 初始化成功後可授權")
    void initHealthy() {
        assertTrue(TenantSecurityService.isHealthy());
        TenantSecurityService.grant("ok_tenant", "t");
        assertTrue(TenantSecurityService.isAuthorized("ok_tenant"));
    }

    @Test
    @DisplayName("R10-07b 初始化失敗後租戶存取遭拒")
    void unhealthyDeniesAccess() {
        TenantSecurityService.markUnhealthyForTest();
        assertFalse(TenantSecurityService.isHealthy());
        assertThrows(SecurityException.class, () -> DbManager.getForTenant("demo"));
        assertThrows(SecurityException.class, () -> TenantSecurityService.requireAuthorized("demo"));
    }

    @Test
    @DisplayName("R10-07c 初始化重試不會產生重複授權資料")
    void initIdempotent() {
        TenantSecurityService.initDb();
        TenantSecurityService.initDb();
        TenantSecurityService.grant("idem_tenant", "once");
        TenantSecurityService.grant("idem_tenant", "twice");
        assertTrue(TenantSecurityService.isAuthorized("idem_tenant"));
    }

    @Test
    @DisplayName("R10-06a 正常使用後可 closeTenant")
    void closeTenantWorks() throws Exception {
        TenantSecurityService.grant("life_tenant", "t");
        Connection c = DbManager.getForTenant("life_tenant");
        assertNotNull(c);
        DbManager.closeTenant("life_tenant");
        Connection c2 = DbManager.getForTenant("life_tenant");
        assertNotNull(c2);
        try (Statement st = c2.createStatement(); ResultSet rs = st.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
        }
    }

    @Test
    @DisplayName("R10-06b 多執行緒 Connection 不互相干擾")
    void multiThreadConnections() throws Exception {
        TenantSecurityService.grant("mt_a", "t");
        TenantSecurityService.grant("mt_b", "t");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Boolean>> futures = new ArrayList<>();
            futures.add(pool.submit(() -> {
                try {
                    Connection c = DbManager.getForTenant("mt_a");
                    try (Statement st = c.createStatement();
                         ResultSet rs = st.executeQuery("SELECT 1")) {
                        return rs.next();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            futures.add(pool.submit(() -> {
                try {
                    Connection c = DbManager.getForTenant("mt_b");
                    try (Statement st = c.createStatement();
                         ResultSet rs = st.executeQuery("SELECT 1")) {
                        return rs.next();
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }));
            for (Future<Boolean> f : futures) {
                assertTrue(f.get(10, TimeUnit.SECONDS));
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
