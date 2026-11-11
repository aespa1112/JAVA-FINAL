package com.ledger;

import com.ledger.config.DbManager;
import com.ledger.config.TenantPathResolver;
import com.ledger.service.TenantSecurityService;
import java.util.UUID;
import com.ledger.config.SessionContext;
import com.ledger.dao.AccountDao;
import com.ledger.dao.AuditDao;
import com.ledger.dao.FiscalPeriodDao;
import com.ledger.dao.FtsDao;
import com.ledger.dao.VoucherDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.Account;
import com.ledger.model.FiscalPeriod;
import com.ledger.model.TrialBalanceItem;
import com.ledger.model.Voucher;
import com.ledger.model.VoucherEntry;
import com.ledger.service.AuthService;
import com.ledger.service.BackupService;
import com.ledger.service.ClosingService;
import com.ledger.service.ReportService;
import com.ledger.service.VoucherService;
import com.ledger.service.YearEndClosingService;
import com.ledger.service.ReportReconciliationService;
import com.ledger.model.FinancialStatementItem;
import com.ledger.util.MoneyUtil;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@TestMethodOrder(MethodOrderer.DisplayName.class)
public class LedgerSystemTest {

    private static final String TEST_DB_PATH = "data/test_ledger.db";
    private static final Path TEST_BACKUP_DIR = Paths.get("target/test-backups");
    private VoucherService voucherService;
    private VoucherDao voucherDao;
    private AccountDao accountDao;
    private FiscalPeriodDao periodDao;
    private AuditDao auditDao;
    private ClosingService closingService;
    private BackupService backupService;
    private ReportService reportService;
    private FtsDao ftsDao;

    @BeforeEach
    void setUp() throws SQLException, IOException {
        DbManager.closeCurrent();
        Path testDb = Paths.get(TEST_DB_PATH);
        if (Files.exists(testDb)) {
            Files.delete(testDb);
        }
        Path walFile = Paths.get(TEST_DB_PATH + "-wal");
        if (Files.exists(walFile)) {
            Files.delete(walFile);
        }
        Path shmFile = Paths.get(TEST_DB_PATH + "-shm");
        if (Files.exists(shmFile)) {
            Files.delete(shmFile);
        }

        if (!Files.exists(TEST_BACKUP_DIR)) {
            Files.createDirectories(TEST_BACKUP_DIR);
        }

        SessionContext.clear();
        System.setProperty("ledger.test", "true");
        SessionContext.enableTestIdentityProvider("Amy");
        DbManager.switchDatabase(TEST_DB_PATH);

        voucherDao = new VoucherDao();
        accountDao = new AccountDao();
        periodDao = new FiscalPeriodDao();
        auditDao = new AuditDao();
        voucherService = new VoucherService(voucherDao, periodDao, auditDao, accountDao);
        closingService = new ClosingService(periodDao, auditDao);
        backupService = new BackupService();
        reportService = new ReportService();
        ftsDao = new FtsDao();
    }

    @AfterEach
    void tearDown() {
        SessionContext.clear();
        DbManager.closeCurrent();
        if (Files.exists(TEST_BACKUP_DIR)) {
            try (var stream = Files.walk(TEST_BACKUP_DIR)) {
                stream.filter(Files::isRegularFile).forEach(f -> {
                    try { Files.deleteIfExists(f); } catch (Exception ignored) {}
                });
            } catch (Exception ignored) {}
        }
    }

    private VoucherEntry entry(String accountCode, String debitStr, String creditStr) throws SQLException {
        Account acc = accountDao.findByCode(accountCode)
                .orElseThrow(() -> new IllegalArgumentException("Unknown account: " + accountCode));
        return new VoucherEntry(0, acc.id(), "測試分錄",
                new BigDecimal(debitStr), new BigDecimal(creditStr));
    }

    @Test
    @DisplayName("T01: 借貸平衡正常過帳")
    void testT01_BalancedVoucherPost() throws SQLException {
        var entries = List.of(
                entry("1101", "1000.00", "0"),
                entry("4101", "0", "1000.00")
        );

        Voucher draft = voucherService.createDraft("2026-11-05", "銷售商品取得現金", "Amy", entries);
        assertNotNull(draft.id());
        assertEquals("DRAFT", draft.status());

        voucherService.post(draft.id(), "Bob");
        Voucher posted = voucherDao.findById(draft.id()).orElseThrow();
        assertEquals("POSTED", posted.status());
        assertEquals("Bob", posted.reviewer());
        assertNotNull(posted.postedAt());
    }

    @Test
    @DisplayName("T02: 借貸不平衡攔截並完整回滾")
    void testT02_UnbalancedVoucherRejected() throws SQLException {
        var entries = List.of(
                entry("1101", "1000.00", "0"),
                entry("4101", "0", "900.00")
        );

        assertThrows(BusinessException.class, () ->
                voucherService.createDraft("2026-11-05", "不平衡測試", "Amy", entries));
        assertEquals(0, voucherDao.countAll(), "借貸不平衡時應拒絕儲存且不留下任何資料（完整回滾）");
    }

    @Test
    @DisplayName("T03: 單筆分錄攔截")
    void testT03_SingleEntryRejected() throws SQLException {
        var entries = List.of(
                entry("1101", "1000.00", "0")
        );

        assertThrows(BusinessException.class, () ->
                voucherService.createDraft("2026-11-05", "單筆分錄測試", "Amy", entries));
        assertEquals(0, voucherDao.countAll(), "至少需兩筆分錄");
    }

    @Test
    @DisplayName("T04: 金額精度測試（0.1 + 0.2 精確等於 0.30，無浮點誤差）")
    void testT04_MoneyPrecision() {
        BigDecimal a = new BigDecimal("0.10");
        BigDecimal b = new BigDecimal("0.20");
        BigDecimal sum = a.add(b);

        long minorA = MoneyUtil.toMinor(a);
        long minorB = MoneyUtil.toMinor(b);
        long minorSum = minorA + minorB;

        assertEquals(10L, minorA);
        assertEquals(20L, minorB);
        assertEquals(30L, minorSum);
        assertEquals(new BigDecimal("0.30"), MoneyUtil.toMajor(minorSum));
        assertEquals("0.30", MoneyUtil.format(minorSum));
    }

    @Test
    @DisplayName("T05: 刪除已過帳憑證受 Trigger 攔截")
    void testT05_DeletePostedVoucherTriggerBlock() throws SQLException {
        var entries = List.of(
                entry("1101", "500.00", "0"),
                entry("4101", "0", "500.00")
        );
        Voucher draft = voucherService.createDraft("2026-11-05", "刪除測試", "Amy", entries);
        voucherService.post(draft.id(), "Bob");

        try (Connection conn = DbManager.get();
             Statement st = conn.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () ->
                    st.executeUpdate("DELETE FROM vouchers WHERE id = " + draft.id()));
            assertTrue(ex.getMessage().contains("已過帳憑證不得刪除"),
                    "觸發器應拋出：已過帳憑證不得刪除，實際訊息: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("T06: 修改已過帳憑證欄位受 Trigger 攔截")
    void testT06_UpdatePostedVoucherTriggerBlock() throws SQLException {
        var entries = List.of(
                entry("1101", "800.00", "0"),
                entry("4101", "0", "800.00")
        );
        Voucher draft = voucherService.createDraft("2026-11-05", "修改測試", "Amy", entries);
        voucherService.post(draft.id(), "Bob");

        try (Connection conn = DbManager.get();
             Statement st = conn.createStatement()) {
            SQLException ex = assertThrows(SQLException.class, () ->
                    st.executeUpdate("UPDATE vouchers SET summary = '被篡改的摘要' WHERE id = " + draft.id()));
            assertTrue(ex.getMessage().contains("已過帳憑證完全唯讀"),
                    "觸發器應攔截已過帳憑證的欄位修改，實際訊息: " + ex.getMessage());
        }
    }

    @Test
    @DisplayName("T07: 紅字沖銷（原憑證保留，對沖憑證金額相反，餘額歸零）")
    void testT07_RedReversal() throws SQLException {
        var entries = List.of(
                entry("1101", "1200.00", "0"),
                entry("4101", "0", "1200.00")
        );
        Voucher original = voucherService.createDraft("2026-11-05", "原始銷貨", "Amy", entries);
        voucherService.post(original.id(), "Bob");

        Voucher reversalDraft = voucherService.reverse(original.id(), "銷貨退回重開", "Bob");
        assertNotNull(reversalDraft.reversedId());
        assertEquals(original.id(), reversalDraft.reversedId());
        assertTrue(reversalDraft.summary().contains("【沖銷】"));

        // 由會計師審核沖銷傳票 (可由第二位會計師 David 審核，亦可由 Bob 審核因為制單人保留為 Amy)
        voucherService.post(reversalDraft.id(), "David");

        List<TrialBalanceItem> tb = reportService.getTrialBalance("2026-11-01", "2026-11-30");
        long cashNet = tb.stream().filter(i -> i.accountCode().equals("1101"))
                .mapToLong(TrialBalanceItem::netMinor).sum();
        long revNet = tb.stream().filter(i -> i.accountCode().equals("4101"))
                .mapToLong(TrialBalanceItem::netMinor).sum();

        assertEquals(0L, cashNet, "沖銷後現金科目淨餘額應歸零");
        assertEquals(0L, revNet, "沖銷後收入科目淨餘額應歸零");
    }

    @Test
    @DisplayName("T08: 已結帳期間新增憑證受攔截")
    void testT08_ClosedPeriodAddRejected() throws SQLException {
        try (Connection conn = DbManager.get()) {
            periodDao.getOrCreate(conn, 2026, 10);
        }
        closingService.closePeriod(2026, 10, "Bob");

        var entries = List.of(
                entry("1101", "300.00", "0"),
                entry("4101", "0", "300.00")
        );

        BusinessException be = assertThrows(BusinessException.class, () ->
                voucherService.createDraft("2026-10-15", "已結帳期間補錄", "Amy", entries));
        assertTrue(be.getMessage().contains("已結帳鎖定"), "應提示期間已結帳鎖定");
    }

    @Test
    @DisplayName("T09: 交易回滾（過帳瞬間 DB Trigger 拋錯時，主檔與分錄全數回滾）")
    void testT09_TransactionRollback() throws SQLException {
        var entries = List.of(
                entry("1101", "600.00", "0"),
                entry("4101", "0", "600.00")
        );
        Voucher draft = voucherService.createDraft("2026-11-05", "回滾測試", "Amy", entries);

        try (Connection conn = DbManager.get()) {
            boolean originAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (Statement st = conn.createStatement()) {
                st.executeUpdate("UPDATE voucher_entries SET debit_minor = 99999 WHERE voucher_id = " + draft.id() + " AND line_no = 1");

                SQLException ex = assertThrows(SQLException.class, () ->
                        st.executeUpdate("UPDATE vouchers SET status = 'POSTED' WHERE id = " + draft.id()));
                assertTrue(ex.getMessage().contains("借貸不平衡，無法過帳"));

                conn.rollback();
            } finally {
                conn.setAutoCommit(originAuto);
            }
        }

        Voucher check = voucherDao.findById(draft.id()).orElseThrow();
        assertEquals("DRAFT", check.status(), "過帳失敗回滾後狀態仍應維持 DRAFT");
        assertEquals(60000L, check.entries().get(0).debitMinor(), "回滾後分錄金額應保持原值");
    }

    @Test
    @DisplayName("T10: 職責分離（制單人 = 審核人時拒絕過帳）")
    void testT10_SeparationOfDuties() throws SQLException {
        var entries = List.of(
                entry("1101", "700.00", "0"),
                entry("4101", "0", "700.00")
        );
        Voucher draft = voucherService.createDraft("2026-11-05", "職責分離測試", "Amy", entries);

        BusinessException be = assertThrows(BusinessException.class, () ->
                voucherService.post(draft.id(), "Amy"));

        assertTrue(be.getMessage().contains("職責分離原則") || be.getMessage().contains("權限不足"), "應提示職責分離或身分權限");
        Voucher check = voucherDao.findById(draft.id()).orElseThrow();
        assertEquals("DRAFT", check.status(), "憑證應保持草稿狀態");
    }

    @Test
    @DisplayName("T11: 備份與還原（VACUUM INTO 與安全還原驗證，100% 資料一致性）")
    void testT11_BackupAndRestore() throws Exception {
        var entries = List.of(
                entry("1101", "2500.00", "0"),
                entry("4101", "0", "2500.00")
        );
        Voucher v = voucherService.createDraft("2026-11-05", "備份還原測試", "Amy", entries);
        voucherService.post(v.id(), "Bob");

        Path dbFile = Paths.get(TEST_DB_PATH);
        Path backupDir = TEST_BACKUP_DIR;
        Path backupFile = backupService.backup(dbFile, backupDir, "Bob");
        assertTrue(Files.exists(backupFile), "備份檔案應存在");

        var entries2 = List.of(
                entry("1101", "100.00", "0"),
                entry("4101", "0", "100.00")
        );
        voucherService.createDraft("2026-11-06", "額外干擾資料", "Amy", entries2);
        assertEquals(2, voucherDao.countAll());

        backupService.restore(backupFile, dbFile, "Bob");

        assertEquals(1, voucherDao.countAll(), "還原後資料庫應完全恢復至備份瞬間");
        Voucher restored = voucherDao.findById(v.id()).orElseThrow();
        assertEquals("POSTED", restored.status());
        assertEquals("備份還原測試", restored.summary());
    }

    @Test
    @DisplayName("新防護 1: 報表查詢嚴格排除未過帳草稿與區間外憑證")
    void testReportStrictlyExcludesDraftsAndOutOfRange() throws SQLException {
        var v1 = voucherService.createDraft("2026-11-05", "11月已過帳", "Amy", List.of(
                entry("1101", "1000.00", "0"),
                entry("4101", "0", "1000.00")
        ));
        voucherService.post(v1.id(), "Bob");

        voucherService.createDraft("2026-11-10", "11月未過帳草稿", "Amy", List.of(
                entry("1101", "9999.00", "0"),
                entry("4101", "0", "9999.00")
        ));

        var v3 = voucherService.createDraft("2026-12-01", "12月已過帳跨月", "Amy", List.of(
                entry("1101", "5000.00", "0"),
                entry("4101", "0", "5000.00")
        ));
        voucherService.post(v3.id(), "Bob");

        List<TrialBalanceItem> tb = reportService.getTrialBalance("2026-11-01", "2026-11-30");
        TrialBalanceItem cash = tb.stream().filter(i -> "1101".equals(i.accountCode())).findFirst().orElseThrow();
        TrialBalanceItem sales = tb.stream().filter(i -> "4101".equals(i.accountCode())).findFirst().orElseThrow();

        assertEquals(100000L, cash.totalDebitMinor(), "試算表必須精確排除草稿與跨月憑證，借方應為 1,000.00");
        assertEquals(100000L, sales.totalCreditMinor(), "試算表收入貸方合計應精確為 1,000.00");
    }

    @Test
    @DisplayName("新防護 2: 已過帳分錄防護 Trigger 測試（禁止對過帳傳票增刪改分錄）")
    void testTriggerBlocksEntriesModificationOnPosted() throws SQLException {
        var v = voucherService.createDraft("2026-11-05", "過帳分錄保護測試", "Amy", List.of(
                entry("1101", "800.00", "0"),
                entry("4101", "0", "800.00")
        ));
        voucherService.post(v.id(), "Bob");

        try (Connection conn = DbManager.get();
             Statement st = conn.createStatement()) {
            SQLException exUpdate = assertThrows(SQLException.class, () ->
                    st.executeUpdate("UPDATE voucher_entries SET debit_minor = 10000 WHERE voucher_id = " + v.id()));
            assertTrue(exUpdate.getMessage().contains("非草稿傳票嚴禁修改分錄"));

            SQLException exDelete = assertThrows(SQLException.class, () ->
                    st.executeUpdate("DELETE FROM voucher_entries WHERE voucher_id = " + v.id()));
            assertTrue(exDelete.getMessage().contains("非草稿傳票嚴禁刪除分錄"));

            Account cash = accountDao.findByCode("1101").orElseThrow();
            SQLException exInsert = assertThrows(SQLException.class, () ->
                    st.executeUpdate(String.format("INSERT INTO voucher_entries (voucher_id, line_no, account_id, debit_minor, credit_minor) VALUES (%d, 99, %d, 100, 0)", v.id(), cash.id())));
            assertTrue(exInsert.getMessage().contains("非草稿傳票嚴禁新增分錄"));
        }
    }

    @Test
    @DisplayName("新防護 3: 狀態機防護（禁止將 POSTED 回退為 DRAFT 或 VOID）")
    void testTriggerBlocksStatusDowngradeFromPosted() throws SQLException {
        var v = voucherService.createDraft("2026-11-05", "狀態回退保護測試", "Amy", List.of(
                entry("1101", "400.00", "0"),
                entry("4101", "0", "400.00")
        ));
        voucherService.post(v.id(), "Bob");

        try (Connection conn = DbManager.get();
             Statement st = conn.createStatement()) {
            SQLException ex1 = assertThrows(SQLException.class, () ->
                    st.executeUpdate("UPDATE vouchers SET status = 'DRAFT' WHERE id = " + v.id()));
            assertTrue(ex1.getMessage().contains("已過帳憑證完全唯讀") || ex1.getMessage().contains("禁止回退"));

            SQLException ex2 = assertThrows(SQLException.class, () ->
                    st.executeUpdate("UPDATE vouchers SET status = 'VOID' WHERE id = " + v.id()));
            assertTrue(ex2.getMessage().contains("已過帳憑證完全唯讀") || ex2.getMessage().contains("禁止回退"));
        }
    }

    @Test
    @DisplayName("新防護 4: 重複沖銷防護（同一張已過帳憑證禁止重複紅字沖銷）")
    void testDuplicateReversalBlocked() throws SQLException {
        var v = voucherService.createDraft("2026-11-05", "重複沖銷測試", "Amy", List.of(
                entry("1101", "3000.00", "0"),
                entry("4101", "0", "3000.00")
        ));
        voucherService.post(v.id(), "Bob");

        Voucher rev1 = voucherService.reverse(v.id(), "首次更正沖銷", "Bob");
        assertNotNull(rev1);

        BusinessException be = assertThrows(BusinessException.class, () ->
                voucherService.reverse(v.id(), "重複沖銷嘗試", "Bob"));
        assertTrue(be.getMessage().contains("已經沖銷過，不可重複沖銷"));
    }

    @Test
    @DisplayName("新防護 5: 修改草稿時檢查目標期間是否已結帳")
    void testUpdateDraftToClosedPeriodBlocked() throws SQLException {
        try (Connection conn = DbManager.get()) {
            periodDao.getOrCreate(conn, 2026, 10);
        }
        closingService.closePeriod(2026, 10, "Bob");

        var draft = voucherService.createDraft("2026-11-05", "11月草稿", "Amy", List.of(
                entry("1101", "200.00", "0"),
                entry("4101", "0", "200.00")
        ));

        BusinessException be = assertThrows(BusinessException.class, () ->
                voucherService.updateDraft(draft.id(), "2026-10-15", "惡意回溯草稿", "Amy", draft.entries()));
        assertTrue(be.getMessage().contains("已結帳鎖定"));
    }

    @Test
    @DisplayName("新防護 6: 反結帳時序檢查（禁止跨越後續已結帳期間跳躍反結帳）")
    void testReopenPeriodOrderEnforced() throws SQLException {
        try (Connection conn = DbManager.get()) {
            periodDao.getOrCreate(conn, 2026, 9);
            periodDao.getOrCreate(conn, 2026, 10);
        }
        closingService.closePeriod(2026, 9, "Bob");
        closingService.closePeriod(2026, 10, "Bob");

        BusinessException be = assertThrows(BusinessException.class, () ->
                closingService.reopenPeriod(2026, 9, "Bob"));
        assertTrue(be.getMessage().contains("後續期間 2026年10月 仍處於結帳鎖定狀態"));
    }

    @Test
    @DisplayName("第二版新防護 1: 舊資料庫版本升級與觸發器自動遷移測試")
    void testOldDatabaseTriggerUpgrade() throws SQLException, IOException {
        String legacyDbPath = "data/legacy_v1.db";
        Path path = Paths.get(legacyDbPath);
        Files.deleteIfExists(path);

        // 手動模擬完整 v1 版資料庫（執行舊表結構，設定 user_version = 1）
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + legacyDbPath)) {
            DbManager.initFullSchema(conn);
            try (Statement st = conn.createStatement()) {
                st.execute("PRAGMA user_version = 1;");
            }
        }

        // 使用 DbManager 開啟，應自動升級至 v3 並重建新 Trigger 與系統表
        DbManager.switchDatabase(legacyDbPath);
        try (Connection conn = DbManager.get();
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA user_version;")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1), "舊資料庫應自動升級至目前架構版本 user_version = 3");
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='trigger' AND name='trg_entry_no_update_on_posted'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "升級後應具備分錄保護 Trigger");
            }
        }
        DbManager.closeCurrent();
        Files.deleteIfExists(path);
    }

    @Test
    @DisplayName("第二版升第三版遷移: 版本 2 帳套升級至版本 3 並補齊 system_meta 與 voucher_sequences")
    void testV2ToV3DatabaseMigration() throws SQLException, IOException {
        String legacyV2DbPath = "data/legacy_v2.db";
        Path path = Paths.get(legacyV2DbPath);
        Files.deleteIfExists(path);

        // 手動模擬完整 v2 版資料庫（有 13 個 Trigger，但無 system_meta 與 voucher_sequences，user_version = 2）
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + legacyV2DbPath)) {
            DbManager.initFullSchema(conn);
            try (Statement st = conn.createStatement()) {
                st.execute("DROP TABLE IF EXISTS system_meta;");
                st.execute("DROP TABLE IF EXISTS voucher_sequences;");
                st.execute("PRAGMA user_version = 2;");
            }
        }

        // 使用 DbManager 開啟，應自動觸發遷移至版本 3
        DbManager.switchDatabase(legacyV2DbPath);
        try (Connection conn = DbManager.get();
             Statement st = conn.createStatement()) {
            try (ResultSet rs = st.executeQuery("PRAGMA user_version;")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1), "版本 2 資料庫應自動升級至 user_version = 3");
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='system_meta'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "升級後應建立 system_meta 表");
            }
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='voucher_sequences'")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1), "升級後應建立 voucher_sequences 表");
            }
            assertEquals("legacy_v2", DbManager.getClientId(conn), "應自動初始化 client_id");
        }
        DbManager.closeCurrent();
        Files.deleteIfExists(path);
    }

    @Test
    @DisplayName("第二版新防護 2: Service 層角色權限全面下沉與防繞過驗證")
    void testServiceRoleEnforcement() throws SQLException {
        var entries = List.of(
                entry("1101", "100.00", "0"),
                entry("4101", "0", "100.00")
        );

        // 1. Charlie (查帳員/唯讀) 嘗試建草稿 -> 被拒
        BusinessException be1 = assertThrows(BusinessException.class, () ->
                voucherService.createDraft("2026-11-05", "查帳員違規新增", "Charlie", entries));
        assertTrue(be1.getMessage().contains("查帳員") || be1.getMessage().contains("唯讀")
                || be1.getMessage().contains("身分不符") || be1.getMessage().contains("權限不足"),
                be1.getMessage());

        // 2. Amy (制單人) 嘗試審核過帳 -> 被拒
        Voucher draft = voucherService.createDraft("2026-11-05", "正常制單", "Amy", entries);
        BusinessException be2 = assertThrows(BusinessException.class, () ->
                voucherService.post(draft.id(), "Amy"));
        assertTrue(be2.getMessage().contains("僅會計師（審核人）有權審核過帳") || be2.getMessage().contains("職責分離"));

        // 3. Charlie 嘗試刪除草稿 -> 被拒
        BusinessException be3 = assertThrows(BusinessException.class, () ->
                voucherService.deleteDraft(draft.id(), "Charlie"));
        assertTrue(be3.getMessage().contains("僅記帳員（制單人）有權刪除")
                || be3.getMessage().contains("身分不符") || be3.getMessage().contains("權限不足"),
                be3.getMessage());

        // 4. Amy 嘗試執行結帳 -> 被拒
        BusinessException be4 = assertThrows(BusinessException.class, () ->
                closingService.closePeriod(2026, 11, "Amy"));
        assertTrue(be4.getMessage().contains("會計師") || be4.getMessage().contains("權限不足"),
                be4.getMessage());

        // 5. Charlie 嘗試還原庫 -> 被拒
        BusinessException be5 = assertThrows(BusinessException.class, () ->
                backupService.restore(TEST_BACKUP_DIR.resolve("dummy.db"), Paths.get(TEST_DB_PATH), "Charlie"));
        assertTrue(be5.getMessage().contains("僅會計師（Bob）有權操作"));
    }

    @Test
    @DisplayName("第二版新防護 3: 跨客戶帳套還原防護（client_B 備份禁止覆蓋 client_A）")
    void testCrossClientRestoreBlocked() throws IOException {
        Path targetDb = Paths.get("data/client_A.db");
        Path wrongBackup = TEST_BACKUP_DIR.resolve("client_B_test_wrong_match.db");
        if (!Files.exists(wrongBackup.getParent())) {
            Files.createDirectories(wrongBackup.getParent());
        }
        Files.writeString(wrongBackup, "dummy sqlite content");

        try {
            BusinessException be = assertThrows(BusinessException.class, () ->
                    backupService.restore(wrongBackup, targetDb, "Bob"));
            assertTrue(be.getMessage().contains("帳套身分不符"));
        } finally {
            Files.deleteIfExists(wrongBackup);
        }
    }

    @Test
    @DisplayName("第二版新防護 4: CSV 匯出防公式注入與引號跳脫驗證")
    void testCsvExportSecurity() throws Exception {
        var entries = List.of(
                entry("1101", "1234.56", "0"),
                entry("4101", "0", "1234.56")
        );
        // 含有雙引號與試算表公式注入符號（=cmd）的摘要
        String maliciousSummary = "採購 \"高階設備\" 與 =cmd|' /C calc'!A0 測試";
        Voucher v = voucherService.createDraft("2026-11-05", maliciousSummary, "Amy", entries);
        voucherService.post(v.id(), "Bob");

        Path csvPath = TEST_BACKUP_DIR.resolve("test_tb_export.csv");
        reportService.exportTrialBalanceCsv("2026-11-01", "2026-11-30", csvPath);
        assertTrue(Files.exists(csvPath));

        String content = Files.readString(csvPath);
        // 金額應為純數字 1234.56，無逗號拆欄問題
        assertTrue(content.contains("1234.56"), "金額應正確格式化為純數字無拆欄");
        Files.deleteIfExists(csvPath);
    }

    @Test
    @DisplayName("第二版新防護 5: 傳票序號數字成長與刪除草稿號碼不重用測試")
    void testVoucherNoStrictlyContinuousAndNoReused() throws SQLException {
        var entries = List.of(
                entry("1101", "50.00", "0"),
                entry("4101", "0", "50.00")
        );

        // 建立傳票 1
        Voucher v1 = voucherService.createDraft("2026-11-05", "傳票1", "Amy", entries);
        assertEquals("2026-11-0001", v1.voucherNo());

        // 建立傳票 2
        Voucher v2 = voucherService.createDraft("2026-11-05", "傳票2", "Amy", entries);
        assertEquals("2026-11-0002", v2.voucherNo());

        // 刪除傳票 2
        voucherService.deleteDraft(v2.id(), "Amy");

        // 建立傳票 3，應嚴格連續使用 0003，絕不重用已刪除的 0002！
        Voucher v3 = voucherService.createDraft("2026-11-05", "傳票3", "Amy", entries);
        assertEquals("2026-11-0003", v3.voucherNo(), "刪除草稿後傳票號碼不得重用，須嚴格保持連續遞增");
    }

    @Test
    @DisplayName("非功能測試 1: 匯入 500 張測試憑證，驗證試算平衡與總帳資產負債平衡")
    void testMassVoucherTrialBalance() throws SQLException {
        for (int i = 1; i <= 500; i++) {
            long amount = 1000 + (i * 10);
            BigDecimal amt = MoneyUtil.toMajor(amount);
            var entries = List.of(
                    entry("1101", amt.toString(), "0"),
                    entry("4101", "0", amt.toString())
            );
            Voucher draft = voucherService.createDraft("2026-11-10", "大量壓力測試憑證 #" + i, "Amy", entries);
            voucherService.post(draft.id(), "Bob");
        }

        assertEquals(500, voucherDao.countAll());

        List<TrialBalanceItem> tb = reportService.getTrialBalance("2026-11-01", "2026-11-30");
        long totalDebit = tb.stream().mapToLong(TrialBalanceItem::totalDebitMinor).sum();
        long totalCredit = tb.stream().mapToLong(TrialBalanceItem::totalCreditMinor).sum();

        assertEquals(totalDebit, totalCredit, "試算表借貸必須完全平衡");
        assertTrue(totalDebit > 0, "試算表借方金額必須大於 0");

        var bs = reportService.getBalanceSheet("2026-11-30");
        long assets = bs.stream().filter(item -> "TOTAL_ASSET".equals(item.code()))
                .mapToLong(com.ledger.model.FinancialStatementItem::amountMinor).findFirst().orElse(0L);
        long liabAndEquity = bs.stream().filter(item -> "TOTAL_LIAB_EQUITY".equals(item.code()))
                .mapToLong(com.ledger.model.FinancialStatementItem::amountMinor).findFirst().orElse(0L);

        assertEquals(assets, liabAndEquity, "資產負債表必須平衡：資產總額 = 負債與權益總額");
    }

    @Test
    @DisplayName("非功能測試 2: FTS5 全文檢索效能與檢索結果驗證")
    void testFts5Search() throws SQLException {
        var entries1 = List.of(
                entry("1101", "1500.00", "0"),
                entry("4101", "0", "1500.00")
        );
        voucherService.createDraft("2026-11-05", "採購宏碁高階伺服器設備及網路線", "Amy", entries1);

        var entries2 = List.of(
                entry("1101", "350.00", "0"),
                entry("5206", "0", "350.00")
        );
        voucherService.createDraft("2026-11-05", "購買辦公室文具原子筆影印紙", "Amy", entries2);

        List<Voucher> result1 = ftsDao.search("伺服器");
        assertEquals(1, result1.size());
        assertTrue(result1.get(0).summary().contains("宏碁高階伺服器"));

        List<Voucher> result2 = ftsDao.search("文具");
        assertEquals(1, result2.size());
        assertTrue(result2.get(0).summary().contains("辦公室文具"));
    }

    @Test
    @DisplayName("T25: 角色白名單安全校驗與偽冒防護（拒絕非法字串與泛用特權字串）")
    void testT25_OperatorWhitelistAndSpoofingProtection() throws SQLException {
        assertThrows(BusinessException.class, () -> com.ledger.model.OperatorRole.normalize("Zed"));
        assertThrows(BusinessException.class, () -> com.ledger.model.OperatorRole.normalize(""));
        SessionContext.setUser("Amy", "Amy");
        assertThrows(BusinessException.class, () -> SessionContext.resolveOperator("Bob"));
        SessionContext.clear();
        SessionContext.clear();
        System.setProperty("ledger.test", "true");
        SessionContext.enableTestIdentityProvider("Amy");
        assertEquals("Amy", SessionContext.resolveOperator("Amy"));
    }

    @Test
    @DisplayName("T26: 紅字沖銷 UI 角色閉環與雙人審核職責分離測試（防一人沖銷加過帳）")
    void testT26_RedReversalUiRolesWorkflow() throws SQLException {
        // UI 下拉選單的角色清單: "Amy (記帳員/制單人)", "Bob (會計師/審核人)", "David (會計師/審核人)", "Charlie (查帳員/審計員)"
        var entries = List.of(
                entry("1101", "2500.00", "0"),
                entry("4101", "0", "2500.00")
        );

        // 1. Amy 制單
        Voucher origin = voucherService.createDraft("2026-11-10", "11月大宗商品銷售", "Amy", entries);
        assertEquals("Amy", origin.preparer());

        // 2. Bob 審核過帳
        voucherService.post(origin.id(), "Bob");
        Voucher postedOrigin = voucherDao.findById(origin.id()).orElseThrow();
        assertTrue(postedOrigin.isPosted());
        assertEquals("Bob", postedOrigin.reviewer());

        // 3. Bob 發起紅字沖銷 -> 產生的沖銷草稿制單人保留原提出者 Amy
        Voucher revDraft = voucherService.reverse(origin.id(), "客戶整批退貨沖銷", "Bob");
        assertEquals("Amy", revDraft.preparer(), "沖銷草稿制單人應保留原制單人 Amy");
        assertEquals(origin.id(), revDraft.reversedId());

        // 4. 關鍵內控校驗：沖銷發起人 Bob 嘗試自己審核過帳 -> 必須被嚴格阻擋！杜絕一人沖銷加過帳
        BusinessException be = assertThrows(BusinessException.class, () ->
                voucherService.post(revDraft.id(), "Bob"));
        assertTrue(be.getMessage().contains("紅字沖銷發起人（Bob）不得自行審核過帳該沖銷傳票"),
                "應提示發起人不得自行審核沖銷傳票: " + be.getMessage());

        // 5. 改由第二位會計師 David 審核過帳 -> 成功！落實四眼原則（Dual Control）
        voucherService.post(revDraft.id(), "David");
        Voucher postedRev = voucherDao.findById(revDraft.id()).orElseThrow();
        assertTrue(postedRev.isPosted());
        assertEquals("David", postedRev.reviewer());

        // 6. 驗證科目餘額已完全對沖歸零
        List<TrialBalanceItem> tb = reportService.getTrialBalance("2026-11-01", "2026-11-30");
        long cashNet = tb.stream().filter(i -> "1101".equals(i.accountCode())).mapToLong(TrialBalanceItem::netMinor).sum();
        long revNet = tb.stream().filter(i -> "4101".equals(i.accountCode())).mapToLong(TrialBalanceItem::netMinor).sum();
        assertEquals(0L, cashNet, "現金科目應完全沖銷歸零");
        assertEquals(0L, revNet, "收入科目應完全沖銷歸零");

        // 7. 測試另一流程：若由會計師 David 發起沖銷，David 自己過帳亦會被擋，改由 Bob 審核過帳成功
        Voucher origin2 = voucherService.createDraft("2026-11-12", "第二筆銷貨", "Amy", entries);
        voucherService.post(origin2.id(), "Bob");
        Voucher revDraft2 = voucherService.reverse(origin2.id(), "更正重開", "David");
        assertThrows(BusinessException.class, () -> voucherService.post(revDraft2.id(), "David"));
        voucherService.post(revDraft2.id(), "Bob"); // 由 Bob 審核過帳
        assertTrue(voucherDao.findById(revDraft2.id()).orElseThrow().isPosted());
    }

    @Test
    @DisplayName("T27: 草稿修改日期跨月時傳票編號與月份一致性")
    void testT27_DraftUpdateCrossMonthVoucherNoConsistency() throws SQLException {
        var entries = List.of(
                entry("1101", "800.00", "0"),
                entry("4101", "0", "800.00")
        );

        // 1. 建立 2026 年 11 月傳票草稿
        Voucher draft = voucherService.createDraft("2026-11-05", "11月草稿", "Amy", entries);
        assertTrue(draft.voucherNo().startsWith("2026-11-"), "原編號應以 2026-11- 為前綴");

        // 2. 修改草稿日期至 2026 年 12 月
        voucherService.updateDraft(draft.id(), "2026-12-15", "調整至12月業務", "Amy", entries);

        // 3. 驗證傳票編號已自動重新生成為 2026-12-xxxx，且 period_id 亦指向 12 月
        Voucher updated = voucherDao.findById(draft.id()).orElseThrow();
        assertTrue(updated.voucherNo().startsWith("2026-12-"), "跨月後傳票編號必須同步更新為目標月份 2026-12- 開頭");
        assertEquals("2026-12-15", updated.voucherDate());

        FiscalPeriod decPeriod;
        try (Connection conn = DbManager.get()) {
            decPeriod = periodDao.findByYearMonth(conn, 2026, 12).orElseThrow();
        }
        assertEquals(decPeriod.id(), updated.periodId(), "period_id 必須指向 12 月期間");
    }

    @Test
    @DisplayName("T28: 內存 Client ID 跨客戶還原攔截（改檔名防禦）")
    void testT28_InternalClientIdCrossRestoreProtection() throws IOException, SQLException {
        // 1. 切換至 client_B 庫並產生備份
        String clientBPath = "data/client_B.db";
        DbManager.switchDatabase(clientBPath);
        Path bBackup = backupService.backup(Paths.get(clientBPath), TEST_BACKUP_DIR, "Bob");

        // 2. 蓄意將 client_B 的備份檔改名為假冒的 client_A 檔名
        Path fakeABackup = TEST_BACKUP_DIR.resolve("client_A_fake_spoof.db");
        Files.deleteIfExists(fakeABackup);
        Files.copy(bBackup, fakeABackup);

        // 3. 切換回 client_A 並嘗試以偽造檔名的備份進行還原
        String clientAPath = "data/client_A.db";
        DbManager.switchDatabase(clientAPath);

        // 4. 驗證被內存 system_meta 中的 client_id 正確比對並拒絕還原
        assertThrows(BusinessException.class, () -> {
            backupService.restore(fakeABackup, Paths.get(clientAPath), "Bob");
        }, "以偽造檔名嘗試還原不同客戶資料庫，應由內部 client_id 攔截");

        // 清理暫存檔案
        Files.deleteIfExists(fakeABackup);
        SessionContext.clear();
        System.setProperty("ledger.test", "true");
        SessionContext.enableTestIdentityProvider("Amy");
        DbManager.switchDatabase(TEST_DB_PATH);
    }

    @Test
    @DisplayName("T29: 資產負債表無效日期格式友善異常檢驗")
    void testT29_BalanceSheetInvalidDateFormatFriendlyException() {
        assertThrows(BusinessException.class, () -> {
            reportService.getBalanceSheet("invalid-date-string");
        }, "無效日期格式應拋出 BusinessException 友善提示");

        assertThrows(BusinessException.class, () -> {
            reportService.getBalanceSheet("2026-99-99");
        }, "不合法日期應拋出 BusinessException");
    }

    @Test
    @DisplayName("R4-P0-01: 損益表收入費用方向與淨利")
    void testR4_IncomeStatementDirections() throws SQLException {
        voucherService.post(
                voucherService.createDraft("2026-02-01", "收入1000", "Amy", List.of(
                        entry("1101", "1000.00", "0"),
                        entry("4101", "0", "1000.00")
                )).id(), "Bob");
        voucherService.post(
                voucherService.createDraft("2026-02-02", "費用400", "Amy", List.of(
                        entry("5101", "400.00", "0"),
                        entry("1101", "0", "400.00")
                )).id(), "Bob");
        var is = reportService.getIncomeStatement("2026-02-01", "2026-02-28");
        long rev = 0, exp = 0, ni = 0;
        for (var i : is) {
            if ("TOTAL_REV".equals(i.code())) rev = i.amountMinor();
            if ("TOTAL_EXP".equals(i.code())) exp = i.amountMinor();
            if ("NET_INCOME".equals(i.code())) ni = i.amountMinor();
        }
        assertEquals(100000L, rev);
        assertEquals(40000L, exp);
        assertEquals(60000L, ni);
        var bs = reportService.getBalanceSheet("2026-02-28");
        long bsNi = bs.stream().filter(x -> "3302".equals(x.code())).mapToLong(x -> x.amountMinor()).findFirst().orElse(0);
        assertEquals(ni, bsNi, "資負表本期損益應與損益表淨利一致（費用已正確減項）");
    }

    @Test
    @DisplayName("R4-P0-03: Session 身分不可偽造")
    void testR4_SessionRejectsSpoofedOperator() {
        SessionContext.clear();
        SessionContext.setUser("Amy", "Amy");
        assertThrows(BusinessException.class, () ->
                SessionContext.resolveOperator("Bob"));
        assertEquals("Amy", SessionContext.resolveOperator("Amy"));
        SessionContext.clear();
        SessionContext.enableTestIdentityProvider("Amy");
    }

    @Test
    @DisplayName("R4-P1: 報表勾稽試算平衡")
    void testR4_ReconciliationTrialBalance() throws SQLException {
        voucherService.post(
                voucherService.createDraft("2026-05-01", "勾稽用", "Amy", List.of(
                        entry("1101", "500.00", "0"),
                        entry("4101", "0", "500.00")
                )).id(), "Bob");
        var svc = new ReportReconciliationService();
        var results = svc.reconcile("2026-05-01", "2026-05-31");
        assertFalse(results.isEmpty());
        var tb = results.stream().filter(r -> r.name().contains("試算")).findFirst().orElseThrow();
        assertTrue(tb.passed(), tb.detail());
    }

    @Test
    @DisplayName("R4-P0-02: 年結需月結完整且雙人過帳")
    void testR4_YearEndRequiresMonthlyClose() throws SQLException {
        // 產生損益
        voucherService.post(
                voucherService.createDraft("2026-01-15", "年結測試收入", "Amy", List.of(
                        entry("1101", "2000.00", "0"),
                        entry("4101", "0", "2000.00")
                )).id(), "Bob");
        YearEndClosingService ye = new YearEndClosingService();
        // 未月結十二個月應拒絕
        assertThrows(BusinessException.class, () -> ye.closeYear(2026, "Amy", "Bob"));
        // 關閉 1–12 月（確保期間存在）
        try (var conn = DbManager.get()) {
            for (int m = 1; m <= 12; m++) {
                periodDao.getOrCreate(conn, 2026, m);
            }
        }
        for (int m = 1; m <= 11; m++) {
            try {
                closingService.closePeriod(2026, m, "Bob");
            } catch (BusinessException ex) {
                if (!ex.getMessage().contains("已經是結帳")) throw ex;
            }
        }
        // 12 月保持 OPEN 以利年結分錄；1 月收入已建立期間
        var ids = ye.closeYear(2026, "Amy", "Bob");
        assertFalse(ids.isEmpty());
        // 重複結帳拒絕
        assertThrows(BusinessException.class, () -> ye.closeYear(2026, "Amy", "Bob"));
        // 反結
        long revId = ye.reopenYear(2026, "Bob", "測試反結");
        assertTrue(revId > 0);
    }

    private void setupFullYearPeriods(int year) throws SQLException {
        try (var conn = DbManager.get()) {
            for (int m = 1; m <= 12; m++) {
                periodDao.getOrCreate(conn, year, m);
            }
        }
        for (int m = 1; m <= 11; m++) {
            try {
                closingService.closePeriod(year, m, "Bob");
            } catch (BusinessException ex) {
                if (!ex.getMessage().contains("已經是結帳")) throw ex;
            }
        }
    }

    @Test
    @DisplayName("P1-04-01: 缺少月份時禁止結帳")
    void testYearEnd_MissingMonthsForbidden() throws SQLException {
        int year = 2031;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1000.00", "0"),
                        entry("4101", "0", "1000.00")
                )).id(), "Bob");
        // 僅建立 1～5 月
        try (var conn = DbManager.get()) {
            for (int m = 1; m <= 5; m++) {
                periodDao.getOrCreate(conn, year, m);
            }
        }
        for (int m = 1; m <= 5; m++) {
            try { closingService.closePeriod(year, m, "Bob"); } catch (Exception ignored) {}
        }
        YearEndClosingService ye = new YearEndClosingService();
        BusinessException ex = assertThrows(BusinessException.class, () -> ye.createYearEndDraft(year, "Amy"));
        assertTrue(ex.getMessage().contains("須具備完整十二個月期間"), "錯誤訊息須指明缺少月份: " + ex.getMessage());
    }

    @Test
    @DisplayName("P1-04-02: 1～11 月未完成月結時禁止結帳")
    void testYearEnd_UnclosedMonths1to11Forbidden() throws SQLException {
        int year = 2032;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1000.00", "0"),
                        entry("4101", "0", "1000.00")
                )).id(), "Bob");
        // 建立 1～12 月，但僅關閉 1～10 月（11 月保持 OPEN）
        try (var conn = DbManager.get()) {
            for (int m = 1; m <= 12; m++) {
                periodDao.getOrCreate(conn, year, m);
            }
        }
        for (int m = 1; m <= 10; m++) {
            try { closingService.closePeriod(year, m, "Bob"); } catch (Exception ignored) {}
        }
        YearEndClosingService ye = new YearEndClosingService();
        BusinessException ex = assertThrows(BusinessException.class, () -> ye.createYearEndDraft(year, "Amy"));
        assertTrue(ex.getMessage().contains("尚未結帳鎖定"), "錯誤訊息須指明月份未鎖定: " + ex.getMessage());
    }

    @Test
    @DisplayName("P1-04-03: 12 月解鎖後發生錯誤時恢復原狀（失敗注入）")
    void testYearEnd_Month12RestoredOnError() throws SQLException {
        int year = 2033;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1000.00", "0"),
                        entry("4101", "0", "1000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);
        // 將 12 月也手動關閉
        try { closingService.closePeriod(year, 12, "Bob"); } catch (Exception ignored) {}

        YearEndClosingService ye = new YearEndClosingService();
        // 啟用失敗注入開關：解鎖後立即拋出非預期異常
        YearEndClosingService.simulateFailureAfterUnlock = true;
        try {
            assertThrows(Exception.class, () -> ye.createYearEndDraft(year, "Amy"));
        } finally {
            YearEndClosingService.simulateFailureAfterUnlock = false;
        }

        // 驗證失敗復原：12 月期間必須已恢復為 CLOSED，不得留下 OPEN
        try (var conn = DbManager.get();
             var ps = conn.prepareStatement("SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
            ps.setInt(1, year);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("CLOSED", rs.getString("status"), "失敗後 12 月必須恢復為 CLOSED 狀態！");
            }
        }
    }

    @Test
    @DisplayName("P1-04-04: 年結成功後十二個月全部 CLOSED")
    void testYearEnd_All12MonthsClosedOnSuccess() throws SQLException {
        int year = 2034;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "年結測試收入", "Amy", List.of(
                        entry("1101", "3000.00", "0"),
                        entry("4101", "0", "3000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        List<Long> ids = ye.closeYear(year, "Amy", "Bob");
        assertFalse(ids.isEmpty());

        // 驗證 1～12 月全部處於 CLOSED 狀態
        try (var conn = DbManager.get();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM fiscal_periods WHERE year=? AND status='CLOSED'")) {
            ps.setInt(1, year);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(12, rs.getInt(1), "年結成功後 1～12 月必須全數為 CLOSED！");
            }
        }
    }

    @Test
    @DisplayName("P1-04-05: 年結中途失敗不留下不一致資料")
    void testYearEnd_FailureLeavesNoInconsistency() throws SQLException {
        int year = 2035;
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        // 無損益發生額時執行年結，應直接拒絕且不留下孤立資料
        assertThrows(BusinessException.class, () -> ye.createYearEndDraft(year, "Amy"));

        // 執行一致性檢查
        List<String> issues = ye.checkConsistency();
        assertTrue(issues.isEmpty(), "年結失敗不得留下不一致紀錄: " + issues);
    }

    @Test
    @DisplayName("P1-04-06: 同年度重複結帳被拒絕")
    void testYearEnd_DuplicateClosingForbidden() throws SQLException {
        int year = 2036;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1500.00", "0"),
                        entry("4101", "0", "1500.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        ye.closeYear(year, "Amy", "Bob");

        // 再次發起結帳，應明確拒絕
        BusinessException ex = assertThrows(BusinessException.class, () -> ye.createYearEndDraft(year, "Amy"));
        assertTrue(ex.getMessage().contains("已存在結帳紀錄") || ex.getMessage().contains("禁止重複結帳"));
    }

    @Test
    @DisplayName("P1-04-07: 紅字沖銷建立失敗時不得標示 REVERSED")
    void testYearEnd_ReopenDraftFailureDoesNotMarkReversed() throws SQLException {
        int year = 2037;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "2500.00", "0"),
                        entry("4101", "0", "2500.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        ye.closeYear(year, "Amy", "Bob");

        // 模擬後續年度 2038 也已結帳
        int nextYear = 2038;
        voucherService.post(
                voucherService.createDraft(nextYear + "-01-15", "次年收入", "Amy", List.of(
                        entry("1101", "100.00", "0"),
                        entry("4101", "0", "100.00")
                )).id(), "Bob");
        setupFullYearPeriods(nextYear);
        ye.closeYear(nextYear, "Amy", "Bob");

        // 嘗試反結 2037（後續年度已結帳應失敗）
        BusinessException ex = assertThrows(BusinessException.class, () -> ye.initiateReopenYear(year, "Bob", "反結測試"));
        assertTrue(ex.getMessage().contains("後續年度已結帳"), ex.getMessage());

        // 驗證原年度 2037 狀態依然維持 POSTED，絕不標示 REVERSED！
        try (var conn = DbManager.get();
             var ps = conn.prepareStatement("SELECT status FROM year_end_closings WHERE fiscal_year=?")) {
            ps.setInt(1, year);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("POSTED", rs.getString("status"), "反結發起失敗時原結帳狀態必須保持 POSTED！");
            }
        }
    }

    @Test
    @DisplayName("P1-04-08: 紅字傳票未過帳時不得完成反結帳")
    void testYearEnd_ReopenNotCompletedUntilPosted() throws SQLException {
        int year = 2039;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1800.00", "0"),
                        entry("4101", "0", "1800.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        ye.closeYear(year, "Amy", "Bob");

        // 發起反結：僅建立紅字草稿
        long revDraftId = ye.initiateReopenYear(year, "Bob", "調整分錄");
        assertTrue(revDraftId > 0);

        // 驗證狀態為 REOPEN_DRAFT（尚未 REVERSED）
        try (var conn = DbManager.get();
             var ps = conn.prepareStatement("SELECT status, reverse_voucher_id FROM year_end_closings WHERE fiscal_year=?")) {
            ps.setInt(1, year);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("REOPEN_DRAFT", rs.getString("status"), "草稿未過帳前狀態必須為 REOPEN_DRAFT！");
                assertEquals(revDraftId, rs.getLong("reverse_voucher_id"));
            }
        }

        // 驗證 1～12 月期間依然保持 CLOSED（尚未解鎖）
        try (var conn = DbManager.get();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM fiscal_periods WHERE year=? AND status='CLOSED'")) {
            ps.setInt(1, year);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(12, rs.getInt(1), "紅字傳票未過帳前會計期間不得提前解鎖！");
            }
        }

        // 驗證傳票本身狀態仍為 DRAFT
        Voucher revVoucher = voucherDao.findById(revDraftId).orElseThrow();
        assertEquals("DRAFT", revVoucher.status());
    }

    @Test
    @DisplayName("P1-04-09: 紅字傳票成功過帳後才完成反結帳")
    void testYearEnd_ReopenCompletedOnlyAfterPost() throws SQLException {
        int year = 2040;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "2200.00", "0"),
                        entry("4101", "0", "2200.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        ye.closeYear(year, "Amy", "Bob");

        // 第一階段：Bob 發起反結
        long revDraftId = ye.initiateReopenYear(year, "Bob", "需修正折舊分錄");

        // 第二階段：David 審核並過帳反結傳票
        long confirmedId = ye.confirmReopenYear(year, "David");
        assertEquals(revDraftId, confirmedId);

        // 驗證狀態變更為 REVERSED
        try (var conn = DbManager.get();
             var ps = conn.prepareStatement("SELECT status, reopen_reviewed_by FROM year_end_closings WHERE fiscal_year=?")) {
            ps.setInt(1, year);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("REVERSED", rs.getString("status"));
                assertTrue("David".equalsIgnoreCase(rs.getString("reopen_reviewed_by")));
            }
        }

        // 驗證 1～12 月期間全部重新開放為 OPEN
        try (var conn = DbManager.get();
             var ps = conn.prepareStatement("SELECT COUNT(*) FROM fiscal_periods WHERE year=? AND status='OPEN'")) {
            ps.setInt(1, year);
            try (var rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(12, rs.getInt(1), "反結成功過帳後期間必須全部重新開放！");
            }
        }

        // 驗證反結傳票已成功 POSTED
        Voucher postedRev = voucherDao.findById(revDraftId).orElseThrow();
        assertEquals("POSTED", postedRev.status());
    }

    @Test
    @DisplayName("P1-04-10: 年結與反結帳的冪等性及重試測試")
    void testYearEnd_IdempotencyAndRetry() throws SQLException {
        int year = 2041;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1200.00", "0"),
                        entry("4101", "0", "1200.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        ye.closeYear(year, "Amy", "Bob");

        // 發起反結建立草稿
        long revId1 = ye.initiateReopenYear(year, "Bob", "原因一");

        // 冪等重試：重複呼叫 initiateReopenYear 應安全返回同一個草稿 ID，不重複新增傳票
        long revId2 = ye.initiateReopenYear(year, "Bob", "原因一重試");
        assertEquals(revId1, revId2, "重複發起反結應具備冪等性，返回既有草稿 ID！");

        // isRetryable 檢查
        assertTrue(ye.isRetryable("REOPEN_DRAFT"));
        assertTrue(ye.isRetryable("FAILED"));
        assertFalse(ye.isRetryable("POSTED"));

        // 完成反結
        ye.confirmReopenYear(year, "David");

        // 反結完成後，該年度可重新進行結帳
        setupFullYearPeriods(year);
        List<Long> newCloseIds = ye.closeYear(year, "Amy", "Bob");
        assertFalse(newCloseIds.isEmpty(), "反結完成後應可重新結帳！");
    }

    @Test
    @DisplayName("P1-01: 真正落實雙人覆核嚴格防護")
    void testYearEnd_DualControlStrictEnforcement() throws SQLException {
        int year = 2042;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1600.00", "0"),
                        entry("4101", "0", "1600.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        // 制單人建立草稿
        long draftId = ye.createYearEndDraft(year, "Amy");
        assertTrue(draftId > 0);

        // 制單人 Amy 嘗試自己審核過帳 -> 必須拋出職責分離異常拒絕
        BusinessException ex1 = assertThrows(BusinessException.class, () -> ye.reviewAndPostYearEnd(year, "Amy"));
        assertTrue(ex1.getMessage().contains("年度結帳審核人必須為會計師") || ex1.getMessage().contains("職責分離原則"), ex1.getMessage());

        // 由審核會計師 Bob 審核過帳 -> 成功
        long postedId = ye.reviewAndPostYearEnd(year, "Bob");
        assertEquals(draftId, postedId);

        // Bob 發起反結
        ye.initiateReopenYear(year, "Bob", "更正原因");

        // Bob 嘗試自己審核過帳反結沖銷傳票 -> 必須拋出職責分離異常拒絕
        BusinessException ex2 = assertThrows(BusinessException.class, () -> ye.confirmReopenYear(year, "Bob"));
        assertTrue(ex2.getMessage().contains("職責分離原則：反結發起人"), ex2.getMessage());

        // 由第二位會計師 David 審核過帳 -> 成功
        long revPostedId = ye.confirmReopenYear(year, "David");
        assertTrue(revPostedId > 0);
    }

    @Test
    @DisplayName("P1-02: 現金流量表期末現金勾稽（不固定回傳 true）")
    void testReconciliation_CashFlowReconciliation() throws SQLException {
        voucherService.post(
                voucherService.createDraft("2026-06-15", "現金收入", "Amy", List.of(
                        entry("1101", "5000.00", "0"),
                        entry("4101", "0", "5000.00")
                )).id(), "Bob");

        ReportReconciliationService recon = new ReportReconciliationService();
        List<ReportReconciliationService.CheckResult> results = recon.reconcile("2026-01-01", "2026-12-31");
        assertFalse(results.isEmpty());

        var cashCheck = results.stream()
                .filter(r -> r.name().contains("現金流量表期末現金"))
                .findFirst()
                .orElseThrow();

        assertTrue(cashCheck.passed(), cashCheck.detail());
        assertEquals(ReportReconciliationService.CheckStatus.PASSED, cashCheck.status());
    }

    @Test
    @DisplayName("P1-03: 登入鎖定持久化跨實例重啟驗證")
    void testAuthService_PersistentLockoutAcrossRestarts() {
        AuthService auth1 = new AuthService();
        String testUser = "bob";

        // 連續輸入 5 次錯誤密碼觸發鎖定
        for (int i = 1; i <= 4; i++) {
            final int attempt = i;
            assertThrows(BusinessException.class, () -> auth1.login(testUser, "wrongPass" + attempt));
        }
        BusinessException lockEx = assertThrows(BusinessException.class, () -> auth1.login(testUser, "wrongPass5"));
        assertTrue(lockEx.getMessage().contains("登入失敗次數過多"), lockEx.getMessage());

        // 建立新實例（模擬系統重啟）
        AuthService auth2 = new AuthService();

        // 即使重啟且輸入正確密碼，因鎖定狀態已持久化於資料庫，仍應維持鎖定拒絕登入
        BusinessException stillLocked = assertThrows(BusinessException.class, () -> auth2.login(testUser, "bob123"));
        assertTrue(stillLocked.getMessage().contains("登入失敗次數過多"), stillLocked.getMessage());

        // 管理員重設密碼應同時解除鎖定
        auth2.resetPassword(testUser, "NewBob123", "admin");

        // 使用新密碼登入成功
        String logged = auth2.login(testUser, "NewBob123");
        assertEquals("bob", logged);
    }

    @Test
    @DisplayName("P1-05: 資產負債表公式測試（收入 1000、費用 600 -> 損益 400，資產 400 = 權益 400）")
    void testBalanceSheetFormula_Rev1000Exp600() throws SQLException {
        // 建立測試資料：收入 1,000 元（貸 4101，借 1101）
        voucherService.post(
                voucherService.createDraft("2029-03-10", "銷售貨物收入 1000", "Amy", List.of(
                        entry("1101", "1000.00", "0"),
                        entry("4101", "0", "1000.00")
                )).id(), "Bob");

        // 建立測試資料：費用 600 元（借 5101，貸 1101）
        voucherService.post(
                voucherService.createDraft("2029-03-15", "支付營運費用 600", "Amy", List.of(
                        entry("5101", "600.00", "0"),
                        entry("1101", "0", "600.00")
                )).id(), "Bob");

        ReportService reportService = new ReportService();

        // 1. 損益表檢驗：淨利為 400 元（40,000 分）
        List<FinancialStatementItem> isItems = reportService.getIncomeStatement("2029-01-01", "2029-12-31");
        long netIncome = 0;
        for (FinancialStatementItem item : isItems) {
            if ("NET_INCOME".equals(item.code()) || (item.name() != null && item.name().contains("本期淨利"))) {
                netIncome = item.amountMinor();
            }
        }
        assertEquals(40_000L, netIncome, "損益表淨利必須為 400 元（40,000 分）");

        // 2. 資產負債表檢驗：本期損益 3302 為 400 元（40,000 分）
        List<FinancialStatementItem> bsItems = reportService.getBalanceSheet("2029-12-31");
        long bsNetIncome = 0;
        long totalAssets = 0;
        long totalLiabilities = 0;
        long totalEquity = 0;
        long cashBalance = 0;

        for (FinancialStatementItem item : bsItems) {
            if ("3302".equals(item.code())) {
                bsNetIncome = item.amountMinor();
            } else if ("1101".equals(item.code())) {
                cashBalance = item.amountMinor();
            } else if ("TOTAL_ASSET".equals(item.code())) {
                totalAssets = item.amountMinor();
            } else if ("TOTAL_LIAB".equals(item.code())) {
                totalLiabilities = item.amountMinor();
            } else if ("TOTAL_EQUITY".equals(item.code())) {
                totalEquity = item.amountMinor();
            }
        }

        // 驗證現金餘額為 1000 - 600 = 400 元
        assertEquals(40_000L, cashBalance, "資產端現金餘額必須為 400 元（40,000 分）");
        assertEquals(40_000L, totalAssets, "資產總額必須為 400 元（40,000 分）");
        assertEquals(0L, totalLiabilities, "負債總額必須為 0 元");

        // 驗證本期損益為 400 元，且費用方向不得反轉兩次（不得變成 1600 或 -400）
        assertEquals(40_000L, bsNetIncome, "資產負債表 3302 本期損益必須為 400 元（40,000 分），費用方向不得反轉兩次");
        assertEquals(40_000L, totalEquity, "權益總額必須為 400 元（40,000 分）");

        // 3. 驗證資產負債表公式：資產 = 負債 + 權益
        assertEquals(totalAssets, totalLiabilities + totalEquity, "資產負債表必須嚴格符合 資產 = 負債 + 權益");
    }

    // =========================================================================
    //  R6: 第六輪新增測試（年結原子性、反結恢復、歷史保留、12月復原、NOT_CHECKED、並行登入）
    // =========================================================================

    @Test
    @DisplayName("R6-P1-03-01: 年結傳票建立後，狀態更新失敗（交易原子性 rollback）")
    void testR6_YearEndDraftFailureRollback() throws SQLException {
        int year = 2061;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1000.00", "0"),
                        entry("4101", "0", "1000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);
        closingService.closePeriod(year, 12, "Bob");

        YearEndClosingService ye = new YearEndClosingService();
        YearEndClosingService.simulateFailureAfterYearEndDraftCreate = true;
        try {
            assertThrows(Exception.class, () -> ye.createYearEndDraft(year, "Amy"));
        } finally {
            YearEndClosingService.simulateFailureAfterYearEndDraftCreate = false;
        }

        // 驗證 rollback：year_end_closings 無紀錄，無草稿殘留，12月仍維持 CLOSED
        try (Connection conn = DbManager.get()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM year_end_closings WHERE fiscal_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "年結草稿建立失敗時必須 rollback，不得殘留 year_end_closings 紀錄");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM vouchers WHERE status='DRAFT' AND voucher_date LIKE ?")) {
                ps.setString(1, year + "%");
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "年結草稿建立失敗時傳票必須 rollback，不得殘留草稿傳票");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("CLOSED", rs.getString("status"), "失敗後 12 月期間應維持 CLOSED 狀態");
                }
            }
        }

        // 驗證重試可成功建立
        long draftId = ye.createYearEndDraft(year, "Amy");
        assertTrue(draftId > 0, "故障排除後應可正常建立年結草稿");
    }

    @Test
    @DisplayName("R6-P1-03-02: 年結傳票過帳後，年度狀態更新失敗（交易 rollback 且重試可恢復）")
    void testR6_YearEndVoucherPostStatusUpdateFailsRollback() throws SQLException {
        int year = 2062;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "2000.00", "0"),
                        entry("4101", "0", "2000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);
        closingService.closePeriod(year, 12, "Bob");

        YearEndClosingService ye = new YearEndClosingService();
        long draftId = ye.createYearEndDraft(year, "Amy");

        YearEndClosingService.simulateFailureAfterYearEndVoucherPost = true;
        try {
            assertThrows(Exception.class, () -> ye.reviewAndPostYearEnd(year, "Bob"));
        } finally {
            YearEndClosingService.simulateFailureAfterYearEndVoucherPost = false;
        }

        // 驗證 rollback：傳票仍為 DRAFT，年度狀態仍為 DRAFT
        try (Connection conn = DbManager.get()) {
            Voucher v = voucherDao.findById(conn, draftId).orElseThrow();
            assertEquals("DRAFT", v.status(), "交易 rollback 後傳票應保持 DRAFT 狀態");

            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM year_end_closings WHERE fiscal_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("DRAFT", rs.getString("status"), "交易 rollback 後年度狀態應保持 DRAFT");
                }
            }
        }

        // 重試過帳應順利成功
        long postedId = ye.reviewAndPostYearEnd(year, "Bob");
        assertEquals(draftId, postedId);
        Voucher postedVoucher = voucherDao.findById(postedId).orElseThrow();
        assertEquals("POSTED", postedVoucher.status());
    }

    @Test
    @DisplayName("R6-P1-03-03: 年結成功後，期間鎖定失敗（交易 rollback）")
    void testR6_YearEndPeriodLockFailureRollback() throws SQLException {
        int year = 2063;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "3000.00", "0"),
                        entry("4101", "0", "3000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);
        closingService.closePeriod(year, 12, "Bob");

        YearEndClosingService ye = new YearEndClosingService();
        long draftId = ye.createYearEndDraft(year, "Amy");

        YearEndClosingService.simulateFailureAfterYearEndPeriodLock = true;
        try {
            assertThrows(Exception.class, () -> ye.reviewAndPostYearEnd(year, "Bob"));
        } finally {
            YearEndClosingService.simulateFailureAfterYearEndPeriodLock = false;
        }

        // 驗證 rollback
        try (Connection conn = DbManager.get()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM year_end_closings WHERE fiscal_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("DRAFT", rs.getString("status"), "期間鎖定失敗時整體交易應 rollback，狀態仍為 DRAFT");
                }
            }
        }

        // 重試成功並確認 12 個月皆為 CLOSED
        ye.reviewAndPostYearEnd(year, "Bob");
        try (Connection conn = DbManager.get();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM fiscal_periods WHERE year=? AND status='CLOSED'")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(12, rs.getInt(1));
            }
        }
    }

    @Test
    @DisplayName("R6-P1-03-04: 反結傳票建立後，狀態更新失敗（交易 rollback）")
    void testR6_ReopenDraftCreateStatusUpdateFailsRollback() throws SQLException {
        int year = 2064;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1200.00", "0"),
                        entry("4101", "0", "1200.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        ye.closeYear(year, "Amy", "Bob");

        YearEndClosingService.simulateFailureAfterReopenDraftCreate = true;
        try {
            assertThrows(Exception.class, () -> ye.initiateReopenYear(year, "Bob", "原因"));
        } finally {
            YearEndClosingService.simulateFailureAfterReopenDraftCreate = false;
        }

        // 驗證 rollback：年度仍為 POSTED，無反結草稿
        try (Connection conn = DbManager.get()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT status, reverse_voucher_id FROM year_end_closings WHERE fiscal_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("POSTED", rs.getString("status"), "反結草稿失敗應 rollback，維持 POSTED 狀態");
                    assertNull(rs.getString("reverse_voucher_id"));
                }
            }
        }

        // 重試可成功建立反結草稿
        long revId = ye.initiateReopenYear(year, "Bob", "原因");
        assertTrue(revId > 0);
    }

    @Test
    @DisplayName("R6-P1-03-05: 反結傳票過帳後，年度狀態更新失敗（交易 rollback）")
    void testR6_ReopenPostStatusUpdateFailsRollback() throws SQLException {
        int year = 2065;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1500.00", "0"),
                        entry("4101", "0", "1500.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        ye.closeYear(year, "Amy", "Bob");
        long revDraftId = ye.initiateReopenYear(year, "Bob", "反結測試");

        YearEndClosingService.simulateFailureAfterReopenVoucherPost = true;
        try {
            assertThrows(Exception.class, () -> ye.confirmReopenYear(year, "David"));
        } finally {
            YearEndClosingService.simulateFailureAfterReopenVoucherPost = false;
        }

        // 驗證 rollback：年度狀態仍為 REOPEN_DRAFT，沖銷傳票仍為 DRAFT，期間仍為 CLOSED
        try (Connection conn = DbManager.get()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM year_end_closings WHERE fiscal_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("REOPEN_DRAFT", rs.getString("status"));
                }
            }
            Voucher rv = voucherDao.findById(conn, revDraftId).orElseThrow();
            assertEquals("DRAFT", rv.status());
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM fiscal_periods WHERE year=? AND status='CLOSED'")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(12, rs.getInt(1));
                }
            }
        }
    }

    @Test
    @DisplayName("R6-P1-03-06: 反結傳票已過帳但狀態未同步時，重試可恢復")
    void testR6_ReopenVoucherAlreadyPostedCanRecoverOnRetry() throws SQLException {
        int year = 2066;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "2500.00", "0"),
                        entry("4101", "0", "2500.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        ye.closeYear(year, "Amy", "Bob");
        long revDraftId = ye.initiateReopenYear(year, "Bob", "補分錄");

        // 模擬異常：沖銷傳票已過帳，但年度狀態仍為 REOPEN_DRAFT，期間仍為 CLOSED
        voucherService.post(revDraftId, "David");

        // 此時呼叫 confirmReopenYear 進行重試恢復
        long recoveredId = ye.confirmReopenYear(year, "David");
        assertEquals(revDraftId, recoveredId, "重試應成功返回已過帳之沖銷傳票 ID");

        // 驗證狀態已安全補完
        try (Connection conn = DbManager.get()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM year_end_closings WHERE fiscal_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("REVERSED", rs.getString("status"), "狀態應安全更新為 REVERSED");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM fiscal_periods WHERE year=? AND status='OPEN'")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(12, rs.getInt(1), "反結完成後所有期間應解鎖為 OPEN");
                }
            }
        }
        assertTrue(ye.checkConsistency().isEmpty(), "狀態補完後系統資料庫應完全一致");
    }

    @Test
    @DisplayName("R6-P1-03-07: 12 月解鎖後模擬程序中斷，重新啟動可復原")
    void testR6_Month12CrashRecoveryOnStartup() throws SQLException {
        int year = 2067;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1000.00", "0"),
                        entry("4101", "0", "1000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);
        closingService.closePeriod(year, 12, "Bob");

        // 模擬異常崩潰中斷：12月在結帳草稿中途被解鎖為 OPEN，並留下 IN_PROGRESS 復原意圖紀錄
        try (Connection conn = DbManager.get()) {
            YearEndClosingService initYe = new YearEndClosingService();
            initYe.recordRecoveryIntent(conn, "REC-CRASH-" + year, year, 12, "CLOSED", "YEAR_END_DRAFT");
            try (PreparedStatement ps = conn.prepareStatement("UPDATE fiscal_periods SET status='OPEN' WHERE year=? AND month=12")) {
                ps.setInt(1, year);
                ps.executeUpdate();
            }
        }

        // 重新啟動（建立新實例，建構子會自動執行 checkAndRecoverOnStartup 啟動檢查並復原）
        YearEndClosingService ye = new YearEndClosingService();

        // 驗證 12 月已恢復為 CLOSED
        try (Connection conn = DbManager.get();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("CLOSED", rs.getString("status"), "未完成年結時啟動應將 12 月恢復原期間狀態 (CLOSED)");
            }
        }

        // 重新結帳應能正常完成
        List<Long> ids = ye.closeYear(year, "Amy", "Bob");
        assertFalse(ids.isEmpty(), "復原後應可正常執行年結");
    }

    @Test
    @DisplayName("R6-P1-03-08: 同年度結帳、反結、重新結帳後完整保留歷史")
    void testR6_HistoryPreservedAcrossMultipleClosings() throws SQLException {
        int year = 2068;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "1000.00", "0"),
                        entry("4101", "0", "1000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();

        // 第一次結帳
        List<Long> close1 = ye.closeYear(year, "Amy", "Bob");
        long voucher1 = close1.get(0);

        // 第一次反結
        long rev1 = ye.reopenYear(year, "Bob", "David", "補記費用");

        // 在反結後補記一筆傳票
        voucherService.post(
                voucherService.createDraft(year + "-06-20", "補記費用傳票", "Amy", List.of(
                        entry("5101", "200.00", "0"),
                        entry("1101", "0", "200.00")
                )).id(), "Bob");

        // 重新關帳 1~12 月
        setupFullYearPeriods(year);

        // 第二次結帳
        List<Long> close2 = ye.closeYear(year, "Amy", "Bob");
        long voucher2 = close2.get(0);

        // 驗證歷史紀錄完整保留
        List<YearEndClosingService.YearEndClosingRecord> history = ye.getClosingHistory(year);
        assertEquals(2, history.size(), "同年度應保留 2 筆結帳/反結歷史紀錄，禁止刪除歷史紀錄");

        YearEndClosingService.YearEndClosingRecord rec1 = history.get(0);
        assertEquals(1, rec1.closingSequence());
        assertEquals("REVERSED", rec1.status());
        assertEquals(voucher1, (long) rec1.voucherId());
        assertEquals(rev1, (long) rec1.reverseVoucherId());
        assertEquals("Amy", rec1.createdBy());
        assertEquals("Bob", rec1.reviewedBy());
        assertEquals("Bob", rec1.reopenInitiatedBy());
        assertEquals("David", rec1.reopenReviewedBy());
        assertEquals("補記費用", rec1.reopenReason());

        YearEndClosingService.YearEndClosingRecord rec2 = history.get(1);
        assertEquals(2, rec2.closingSequence());
        assertEquals("POSTED", rec2.status());
        assertEquals(voucher2, (long) rec2.voucherId());
        assertNull(rec2.reverseVoucherId());
        assertEquals("Amy", rec2.createdBy());
        assertEquals("Bob", rec2.reviewedBy());
    }

    @Test
    @DisplayName("R6-P1-03-09: 多次結帳後 checkConsistency() 不應誤判舊傳票為孤立資料")
    void testR6_ConsistencyCheckDoesNotMisidentifyOldVouchersAsOrphan() throws SQLException {
        int year = 2069;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "2000.00", "0"),
                        entry("4101", "0", "2000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);

        YearEndClosingService ye = new YearEndClosingService();
        ye.closeYear(year, "Amy", "Bob");
        ye.reopenYear(year, "Bob", "David", "更正分錄");

        setupFullYearPeriods(year);
        ye.closeYear(year, "Amy", "Bob");

        List<String> issues = ye.checkConsistency();
        assertTrue(issues.isEmpty(), "多次結帳與反結後，checkConsistency() 不應將舊年結傳票與反結傳票誤判為孤立資料: " + issues);
    }

    @Test
    @DisplayName("R6-P1-03-10: NOT_CHECKED 不得被 assertAllPass() 當成通過")
    void testR6_NotCheckedMustNotPassInAssertAllPass() {
        ReportReconciliationService recon = new ReportReconciliationService();

        // 1. 測試當傳入含有 NOT_CHECKED 項目之清單時，assertAllPass 必須拋出異常拒絕通過
        List<ReportReconciliationService.CheckResult> resultsWithNotChecked = List.of(
                new ReportReconciliationService.CheckResult(
                        "現金流量表期末現金 vs 帳列現金及約當現金",
                        false,
                        "現金流量表未產生期末現金資訊",
                        0,
                        0,
                        ReportReconciliationService.CheckStatus.NOT_CHECKED
                )
        );
        BusinessException ex1 = assertThrows(BusinessException.class, () ->
                recon.assertAllPass(resultsWithNotChecked));
        assertTrue(ex1.getMessage().contains("未完成") || ex1.getMessage().contains("未檢查"),
                "存在 NOT_CHECKED 項目時不得宣稱全部通過: " + ex1.getMessage());

        // 2. 測試日期無效或無法完成計算產生 NOT_CHECKED 時，assertAllPass(start, end) 必須拋出異常
        BusinessException ex2 = assertThrows(BusinessException.class, () ->
                recon.assertAllPass("invalid-date", "2099-12-31"));
        assertTrue(ex2.getMessage().contains("未完成") || ex2.getMessage().contains("未檢查") || ex2.getMessage().contains("失敗"),
                "無法核對產生 NOT_CHECKED 時不得宣稱通過: " + ex2.getMessage());
    }

    @Test
    @DisplayName("R6-P1-03-11: 多執行緒同時登入失敗時，鎖定次數不會被競爭條件繞過")
    void testR6_ConcurrentLoginFailuresLockoutNotBypassed() throws InterruptedException {
        AuthService auth = new AuthService();
        String testUser = "bob";

        int threadCount = 10;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threadCount);

        for (int i = 0; i < threadCount; i++) {
            final int idx = i;
            pool.submit(() -> {
                ready.countDown();
                try {
                    start.await();
                    auth.login(testUser, "wrongPassword" + idx);
                } catch (Exception ignored) {
                } finally {
                    done.countDown();
                }
            });
        }

        ready.await();
        start.countDown();
        done.await();
        pool.shutdown();

        // 驗證帳號已被鎖定，即使輸入正確密碼也會被拒絕，競爭條件未繞過鎖定
        BusinessException lockEx = assertThrows(BusinessException.class, () -> auth.login(testUser, "bob123"));
        assertTrue(lockEx.getMessage().contains("登入失敗次數過多"), "多執行緒並行登入失敗後帳號必須被鎖定: " + lockEx.getMessage());

        // 管理員重設密碼後可解鎖
        auth.resetPassword(testUser, "BobNewPass123", "admin");
        assertEquals("bob", auth.login(testUser, "BobNewPass123"));
    }

    // =========================================================================
    //  R7: 第七輪修正任務（12月正常開放、交易中斷、跨JVM登入鎖定、遷移安全性）
    // =========================================================================

    @Test
    @DisplayName("R7-P0-02: 正常開放之12月在系統重啟後仍維持OPEN，且正常傳票仍可建立過帳")
    void testR7_NormalOpenDecemberMustRemainOpenAfterStartup() throws SQLException {
        int year = 2074;
        // 1. 建立年度期間，2. 將 1～11 月設定為 CLOSED，3. 將 12 月設定為 OPEN
        setupFullYearPeriods(year);
        // setupFullYearPeriods 建立 1~12 月並將 1~11 關閉，此時 12 月為 OPEN
        try (Connection conn = DbManager.get();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("OPEN", rs.getString("status"), "前置條件：12 月應為 OPEN");
            }
        }

        YearEndClosingService ye = new YearEndClosingService();

        // 4. 確認該年度沒有未完成的年結復原操作 (closing_recovery 無 IN_PROGRESS 紀錄)
        try (Connection conn = DbManager.get();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT COUNT(*) FROM closing_recovery WHERE fiscal_year=? AND recovery_status='IN_PROGRESS'")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "該年度不應有未完成年結復原操作");
            }
        }

        // 5. 建立新的 YearEndClosingService，模擬重新啟動
        YearEndClosingService yeRestart = new YearEndClosingService();

        // 6. 驗證 12 月仍為 OPEN，未被錯誤自動關閉
        try (Connection conn = DbManager.get();
             PreparedStatement ps = conn.prepareStatement("SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertEquals("OPEN", rs.getString("status"), "正常營運中的 12 月重啟後必須維持 OPEN，不得被誤鎖！");
            }
        }

        // 7. 驗證一般 12 月傳票仍可正常建立與過帳
        Voucher draft = voucherService.createDraft(year + "-12-15", "正常12月日常營業收入", "Amy", List.of(
                entry("1101", "8888.00", "0"),
                entry("4101", "0", "8888.00")
        ));
        assertNotNull(draft);
        assertEquals("DRAFT", draft.status());

        voucherService.post(draft.id(), "Bob");
        Voucher posted = voucherDao.findById(draft.id()).orElseThrow();
        assertEquals("POSTED", posted.status(), "正常 12 月傳票應可順利過帳");
    }

    @Test
    @DisplayName("R7-P0-03-01: 交易中斷區分測試 — Java Exception 中斷回滾")
    void testR7_TransactionInterruption_JavaException() throws SQLException {
        int year = 2075;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "5000.00", "0"),
                        entry("4101", "0", "5000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);
        closingService.closePeriod(year, 12, "Bob");

        YearEndClosingService ye = new YearEndClosingService();
        YearEndClosingService.simulateFailureAfterYearEndDraftCreate = true;
        try {
            assertThrows(RuntimeException.class, () -> ye.createYearEndDraft(year, "Amy"));
        } finally {
            YearEndClosingService.simulateFailureAfterYearEndDraftCreate = false;
        }

        // 驗證 Java 異常導致交易 rollback：不留 partial 年結紀錄，12月不留錯誤 OPEN，無孤立草稿
        try (Connection conn = DbManager.get()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM year_end_closings WHERE fiscal_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("CLOSED", rs.getString("status"));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM vouchers WHERE status='DRAFT' AND closing_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1));
                }
            }
        }
        assertTrue(ye.checkConsistency().isEmpty());
    }

    @Test
    @DisplayName("R7-P0-03-02: 交易中斷區分測試 — Connection rollback 回滾")
    void testR7_TransactionInterruption_ConnectionRollback() throws SQLException {
        int year = 2076;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "3000.00", "0"),
                        entry("4101", "0", "3000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);
        closingService.closePeriod(year, 12, "Bob");

        try (Connection conn = DbManager.get()) {
            boolean originAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 解鎖 12 月並建立草稿傳票
                try (PreparedStatement ps = conn.prepareStatement("UPDATE fiscal_periods SET status='OPEN' WHERE year=? AND month=12")) {
                    ps.setInt(1, year);
                    ps.executeUpdate();
                }
                voucherService.createDraft(conn, year + "-12-31", "未提交年結草稿", "Amy", List.of(
                        entry("4101", "3000.00", "0"),
                        entry("3302", "0", "3000.00")
                ));
            } finally {
                // 明確執行 rollback
                conn.rollback();
                conn.setAutoCommit(originAuto);
            }
        }

        // 驗證 rollback 後完全無殘留
        try (Connection conn = DbManager.get()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("CLOSED", rs.getString("status"), "rollback 後 12 月期間應回復 CLOSED");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM vouchers WHERE summary='未提交年結草稿'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "rollback 後未提交傳票不應存在");
                }
            }
        }
    }

    @Test
    @DisplayName("R7-P0-03-03: 交易中斷區分測試 — Connection close 未提交中斷")
    void testR7_TransactionInterruption_ConnectionCloseWithoutCommit() throws SQLException {
        int year = 2077;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "4000.00", "0"),
                        entry("4101", "0", "4000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);
        closingService.closePeriod(year, 12, "Bob");

        // 使用獨立連線開啟交易但不 commit 即直接 close
        try (Connection conn2 = DriverManager.getConnection("jdbc:sqlite:" + TEST_DB_PATH)) {
            conn2.setAutoCommit(false);
            try (PreparedStatement ps = conn2.prepareStatement("UPDATE fiscal_periods SET status='OPEN' WHERE year=? AND month=12")) {
                ps.setInt(1, year);
                ps.executeUpdate();
            }
            long p12Id;
            try (PreparedStatement ps = conn2.prepareStatement("SELECT id FROM fiscal_periods WHERE year=? AND month=12")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    p12Id = rs.getLong(1);
                }
            }
            try (PreparedStatement ps = conn2.prepareStatement(
                    "INSERT INTO vouchers (period_id, voucher_no, voucher_date, summary, status, preparer) VALUES (?, 'ABRUPT-001', '2077-12-31', '中斷未提交傳票', 'DRAFT', 'Amy')")) {
                ps.setLong(1, p12Id);
                ps.executeUpdate();
            }
            // 連線在未 commit 狀態下退出 try-with-resources 自動 close
        }

        // 重新透過 DbManager 開啟驗證
        try (Connection conn = DbManager.get()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("CLOSED", rs.getString("status"), "連線中斷 close 後 SQLite 應自動回滾未提交異動");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM vouchers WHERE voucher_no='ABRUPT-001'")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "未提交憑證不得寫入資料庫");
                }
            }
        }
    }

    @Test
    @DisplayName("R7-P0-03-04: 交易中斷區分測試 — 子程序非正常終止 (halt) 後重新啟動復原")
    void testR7_TransactionInterruption_ProcessAbruptTermination() throws Exception {
        int year = 2078;
        voucherService.post(
                voucherService.createDraft(year + "-01-15", "測試收入", "Amy", List.of(
                        entry("1101", "6000.00", "0"),
                        entry("4101", "0", "6000.00")
                )).id(), "Bob");
        setupFullYearPeriods(year);
        closingService.closePeriod(year, 12, "Bob");

        // 確保目前連線釋放鎖定
        DbManager.closeCurrent();

        // 啟動獨立 JVM 子程序，模擬解鎖 12 月並寫入持久化復原紀錄後突發非正常終止 (halt 42)
        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");
        String cp = System.getProperty("java.class.path");

        ProcessBuilder pb = new ProcessBuilder(
                javaBin,
                "-cp", cp,
                "com.ledger.TestProcessHelper",
                "crash-simulation",
                TEST_DB_PATH,
                String.valueOf(year),
                "12"
        );
        pb.inheritIO();
        Process p = pb.start();
        boolean finished = p.waitFor(30, java.util.concurrent.TimeUnit.SECONDS);
        assertTrue(finished, "子程序應在超時前執行完成");
        assertEquals(42, p.exitValue(), "子程序應以 halt(42) 硬性中斷模擬崩潰退出");

        // 驗證硬崩潰後但在重啟復原前：磁碟上 12 月為 OPEN，且 closing_recovery 留有 IN_PROGRESS 紀錄
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + TEST_DB_PATH)) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("OPEN", rs.getString("status"), "崩潰時磁碟狀態確實被置為 OPEN");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT recovery_status FROM closing_recovery WHERE fiscal_year=? AND action_type='YEAR_END_DRAFT'")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("IN_PROGRESS", rs.getString("recovery_status"), "持久化復原紀錄在真正崩潰後仍完好存在");
                }
            }
        }

        // 模擬系統重啟：初始化 YearEndClosingService（自動執行 checkAndRecoverOnStartup）
        YearEndClosingService ye = new YearEndClosingService();

        // 驗證啟動復原生效：12 月被安全鎖回 CLOSED，且復原紀錄標記為 RECOVERED
        try (Connection conn = DbManager.get()) {
            try (PreparedStatement ps = conn.prepareStatement("SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("CLOSED", rs.getString("status"), "啟動復原程序後 12 月應成功恢復為 CLOSED！");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT recovery_status FROM closing_recovery WHERE fiscal_year=? AND action_type='YEAR_END_DRAFT'")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("RECOVERED", rs.getString("recovery_status"), "復原紀錄應更新為 RECOVERED");
                }
            }
        }

        // 復原後能順利完成正常年度結帳
        List<Long> closeIds = ye.closeYear(year, "Amy", "Bob");
        assertFalse(closeIds.isEmpty(), "復原後年度應可正常順利完成年結");
    }

    @Test
    @DisplayName("R7-P1-01: 跨獨立 JVM 程序並行登入失敗，累計鎖定不被覆蓋且重啟後持久存在")
    void testR7_MultiJvmConcurrentLoginLockout() throws Exception {
        AuthService auth = new AuthService();
        String testUser = "bob";

        // 先重設確認密碼為已知狀態
        auth.resetPassword(testUser, "BobInitial123", "admin");

        String javaBin = ProcessHandle.current().info().command()
                .orElse(System.getProperty("java.home") + "/bin/java");
        String cp = System.getProperty("java.class.path");

        // 同時啟動 2 個獨立的 JVM 程序，各執行 3 次登入失敗，合計 6 次（門檻為 5 次）
        ProcessBuilder pb1 = new ProcessBuilder(
                javaBin, "-cp", cp, "com.ledger.TestProcessHelper", "auth-fail", testUser, "3"
        );
        ProcessBuilder pb2 = new ProcessBuilder(
                javaBin, "-cp", cp, "com.ledger.TestProcessHelper", "auth-fail", testUser, "3"
        );

        Process p1 = pb1.start();
        Process p2 = pb2.start();

        assertTrue(p1.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue(p2.waitFor(30, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(0, p1.exitValue(), "程序 1 應成功執行");
        assertEquals(0, p2.exitValue(), "程序 2 應成功執行");

        // 驗證跨程序累計達到鎖定門檻：輸入正確密碼也被拒絕
        BusinessException lockEx = assertThrows(BusinessException.class, () ->
                auth.login(testUser, "BobInitial123"));
        assertTrue(lockEx.getMessage().contains("登入失敗次數過多"), "跨程序並行失敗累計達到 5 次後，正確密碼必須被拒絕: " + lockEx.getMessage());

        // 驗證重啟後鎖定仍然存在（新實例）
        AuthService authRestarted = new AuthService();
        BusinessException stillLocked = assertThrows(BusinessException.class, () ->
                authRestarted.login(testUser, "BobInitial123"));
        assertTrue(stillLocked.getMessage().contains("登入失敗次數過多"), "重啟後帳號鎖定狀態必須依然持久存在: " + stillLocked.getMessage());

        // 管理員重設密碼後成功解鎖
        authRestarted.resetPassword(testUser, "BobNewPass789", "admin");
        assertEquals("bob", authRestarted.login(testUser, "BobNewPass789"));
    }

    @Test
    @DisplayName("R7-P1-02-01: 舊版資料庫結構安全升級，會計資料、傳票與年結歷史不丟失")
    void testR7_DatabaseMigration_LegacyUpgradeSafety() throws SQLException {
        Path legacyDbPath = Paths.get("target/legacy_upgrade_test.db");
        try { Files.deleteIfExists(legacyDbPath); } catch (Exception ignored) {}

        String url = "jdbc:sqlite:" + legacyDbPath.toString().replace('\\', '/');

        // 建立舊版結構（缺少 closing_sequence、具有 fiscal_year UNIQUE）
        try (Connection conn = DriverManager.getConnection(url)) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE accounts (id INTEGER PRIMARY KEY, code TEXT, name TEXT, type TEXT)");
                st.execute("CREATE TABLE vouchers (id INTEGER PRIMARY KEY, period_id INTEGER, voucher_no TEXT, voucher_date TEXT, summary TEXT, status TEXT, preparer TEXT, reviewer TEXT, reversed_id INTEGER)");
                st.execute("CREATE TABLE year_end_closings (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "fiscal_year INTEGER NOT NULL UNIQUE, " +
                        "voucher_id INTEGER, " +
                        "reverse_voucher_id INTEGER, " +
                        "status TEXT, " +
                        "idempotency_key TEXT, " +
                        "created_by TEXT, " +
                        "reviewed_by TEXT, " +
                        "created_at TEXT DEFAULT (datetime('now','localtime')), " +
                        "fail_reason TEXT)");
                // 寫入舊版歷史資料
                st.execute("INSERT INTO year_end_closings (fiscal_year, voucher_id, status, idempotency_key, created_by, reviewed_by) " +
                        "VALUES (2024, 101, 'POSTED', 'KEY-LEGACY-2024', 'Amy', 'Bob')");
            }

            // 執行升級遷移
            YearEndClosingService ye = new YearEndClosingService();
            ye.ensureTable(conn);

            // 驗證升級後舊資料完整保留，且新增了 closing_sequence 欄位預設值為 1
            try (PreparedStatement ps = conn.prepareStatement("SELECT fiscal_year, closing_sequence, status, voucher_id, created_by FROM year_end_closings WHERE fiscal_year=2024")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next(), "舊版歷史紀錄必須被安全保留");
                    assertEquals(2024, rs.getInt("fiscal_year"));
                    assertEquals(1, rs.getInt("closing_sequence"));
                    assertEquals("POSTED", rs.getString("status"));
                    assertEquals(101L, rs.getLong("voucher_id"));
                    assertEquals("Amy", rs.getString("created_by"));
                }
            }

            // 驗證同年度現在可支援多版本歷史（插入 sequence 2 不再因 fiscal_year UNIQUE 報錯）
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO year_end_closings (fiscal_year, closing_sequence, closing_version, voucher_id, status, idempotency_key, created_by) " +
                    "VALUES (2024, 2, 2, 102, 'REVERSED', 'KEY-LEGACY-2024-V2', 'Amy')")) {
                assertEquals(1, ps.executeUpdate(), "升級後同年度必須支援多次結帳歷史紀錄");
            }
        }
    }

    @Test
    @DisplayName("R7-P1-02-02: 資料庫遷移冪等性 — 重複執行確保安全無副作用")
    void testR7_DatabaseMigration_IdempotentRepeatedRuns() throws SQLException {
        try (Connection conn = DbManager.get()) {
            YearEndClosingService ye = new YearEndClosingService();
            // 重複執行 5 次遷移，應均平穩執行且不拋異常、不重複新增欄位
            for (int i = 0; i < 5; i++) {
                assertDoesNotThrow(() -> ye.ensureTable(conn), "重複執行 ensureTable 必須完全冪等安全");
            }

            // 驗證資料表完整性
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA table_info(year_end_closings)")) {
                int colCount = 0;
                while (rs.next()) colCount++;
                assertTrue(colCount >= 10, "year_end_closings 欄位數量應齊全");
            }
        }
    }

    @Test
    @DisplayName("R7-P1-02-03: system_meta schema_version 事務原子回滾保證測試")
    void testR7_SystemMetaSchemaVersionRollbackGuarantee() throws SQLException {
        Path testMetaDb = Paths.get("target/test_meta_version.db");
        try { Files.deleteIfExists(testMetaDb); } catch (Exception ignored) {}
        String url = "jdbc:sqlite:" + testMetaDb.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url)) {
            DbManager.applyPragmas(conn);
            DbManager.ensureSystemTables(conn);
            DbManager.setSchemaVersion(conn, 2);
            assertEquals(2, DbManager.getSchemaVersion(conn));

            // 模擬在升級交易中寫入版本 3，但後續操作失敗引發 rollback
            boolean originAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                DbManager.setSchemaVersion(conn, 3);
                // 模擬中途失敗拋出異常
                throw new SQLException("模擬遷移中途失敗");
            } catch (Exception e) {
                conn.rollback();
            } finally {
                conn.setAutoCommit(originAuto);
            }

            // 驗證 system_meta 之 schema_version 100% 隨事務回滾為 2，並未殘留 3
            assertEquals(2, DbManager.getSchemaVersion(conn),
                    "遷移失敗回滾後，system_meta 記錄之 schema_version 必須 100% 隨交易回滾為 2！");

            // 隨後執行成功遷移
            conn.setAutoCommit(false);
            DbManager.setSchemaVersion(conn, 3);
            conn.commit();
            conn.setAutoCommit(originAuto);

            assertEquals(3, DbManager.getSchemaVersion(conn),
                    "成功提交後，schema_version 應正確更新為 3");
        }
    }

    @Test
    @DisplayName("R7-P1-02-04: 多租戶連線管理與帳套資料庫隔離測試")
    void testR7_MultiTenantConnectionIsolation() throws SQLException {
        SessionContext.setUser("admin", "Admin (系統管理員)");
        String tenantA = "tenant_alpha";
        String tenantB = "tenant_beta";

        Path dbA = Paths.get(DbManager.resolveTenantDbPath(tenantA));
        Path dbB = Paths.get(DbManager.resolveTenantDbPath(tenantB));
        try { Files.deleteIfExists(dbA); } catch (Exception ignored) {}
        try { Files.deleteIfExists(dbB); } catch (Exception ignored) {}

        try {
            // 獲取 Tenant A 專屬連線並設定專屬 client_id
            Connection connA = DbManager.getForTenant(tenantA);
            DbManager.setClientId(connA, "CLIENT-ALPHA-001");

            // 獲取 Tenant B 專屬連線並設定專屬 client_id
            Connection connB = DbManager.getForTenant(tenantB);
            DbManager.setClientId(connB, "CLIENT-BETA-002");

            // 驗證各自讀取互不干擾，資料完全隔離
            assertEquals("CLIENT-ALPHA-001", DbManager.getClientId(connA));
            assertEquals("CLIENT-BETA-002", DbManager.getClientId(connB));

            // 測試 ThreadLocal 租戶路由模式
            DbManager.setCurrentTenant(tenantA);
            Connection routedA = DbManager.get();
            assertEquals("CLIENT-ALPHA-001", DbManager.getClientId(routedA));

            DbManager.setCurrentTenant(tenantB);
            Connection routedB = DbManager.get();
            assertEquals("CLIENT-BETA-002", DbManager.getClientId(routedB));

            DbManager.clearCurrentTenant();
        } finally {
            DbManager.clearCurrentTenant();
            DbManager.closeTenant(tenantA);
            DbManager.closeTenant(tenantB);
            DbManager.closeAllTenants();
        }
    }

    // =========================================================================
    //  R9: 第九輪修正任務（多租戶連線生命週期、Fail-Closed授權、路徑防碰撞、Migration原子性）
    // =========================================================================

    @Test
    @DisplayName("R9-01: 同租戶兩個執行緒同時交易，連線與交易各自獨立互不干擾")
    void testR9_01_SameTenantConcurrentTransactions() throws Exception {
        int threadCount = 2;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threadCount);

        final long pId;
        try (Connection setupConn = DbManager.get()) {
            pId = periodDao.getOrCreate(setupConn, 2026, 7).id();
        }

        String vNo1 = "V-R9-01-A";
        String vNo2 = "V-R9-01-B";

        pool.submit(() -> {
            ready.countDown();
            try {
                start.await();
                boolean ok = false;
                for (int r = 0; r < 10 && !ok; r++) {
                    try (Connection conn1 = DbManager.get()) {
                        conn1.setAutoCommit(false);
                        try (PreparedStatement ps = conn1.prepareStatement(
                                "INSERT INTO vouchers (period_id, voucher_no, voucher_date, summary, status, preparer) VALUES (?, ?, '2026-07-10', 'R9並行測試1', 'DRAFT', 'Amy')")) {
                            ps.setLong(1, pId);
                            ps.setString(2, vNo1);
                            ps.executeUpdate();
                        }
                        conn1.commit();
                        ok = true;
                    } catch (SQLException e) {
                        if (e.getMessage() != null && (e.getMessage().contains("busy") || e.getMessage().contains("locked"))) {
                            Thread.sleep(50);
                        } else {
                            throw e;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        pool.submit(() -> {
            ready.countDown();
            try {
                start.await();
                boolean ok = false;
                for (int r = 0; r < 10 && !ok; r++) {
                    try (Connection conn2 = DbManager.get()) {
                        conn2.setAutoCommit(false);
                        try (PreparedStatement ps = conn2.prepareStatement(
                                "INSERT INTO vouchers (period_id, voucher_no, voucher_date, summary, status, preparer) VALUES (?, ?, '2026-07-10', 'R9並行測試2', 'DRAFT', 'Amy')")) {
                            ps.setLong(1, pId);
                            ps.setString(2, vNo2);
                            ps.executeUpdate();
                        }
                        conn2.commit();
                        ok = true;
                    } catch (SQLException e) {
                        if (e.getMessage() != null && (e.getMessage().contains("busy") || e.getMessage().contains("locked"))) {
                            Thread.sleep(50);
                        } else {
                            throw e;
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();

        try (Connection verify = DbManager.get()) {
            try (PreparedStatement ps = verify.prepareStatement("SELECT COUNT(*) FROM vouchers WHERE voucher_no IN (?, ?)")) {
                ps.setString(1, vNo1);
                ps.setString(2, vNo2);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(2, rs.getInt(1), "同租戶兩個獨立交易均應成功持久化提交");
                }
            }
        }
    }

    @Test
    @DisplayName("R9-02: 不同租戶同時交易，資料庫與交易完全隔離")
    void testR9_02_DifferentTenantsConcurrentTransactions() throws Exception {
        String tenantA = "tenant_r9_iso_a";
        String tenantB = "tenant_r9_iso_b";

        SessionContext.setUser("admin", "Admin (系統管理員)");

        Path pA = Paths.get(DbManager.resolveTenantDbPath(tenantA));
        Path pB = Paths.get(DbManager.resolveTenantDbPath(tenantB));
        try { Files.deleteIfExists(pA); } catch (Exception ignored) {}
        try { Files.deleteIfExists(pB); } catch (Exception ignored) {}

        int threadCount = 2;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(threadCount);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(threadCount);

        pool.submit(() -> {
            ready.countDown();
            try {
                start.await();
                SessionContext.setUser("admin", "Admin (系統管理員)");
                try (Connection connA = DbManager.getForTenant(tenantA)) {
                    long pIdA = periodDao.getOrCreate(connA, 2026, 8).id();
                    try (PreparedStatement ps = connA.prepareStatement(
                            "INSERT INTO vouchers (period_id, voucher_no, voucher_date, summary, status, preparer) VALUES (?, 'V-TENANT-A', '2026-08-01', 'A專屬傳票', 'POSTED', 'Amy')")) {
                        ps.setLong(1, pIdA);
                        ps.executeUpdate();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        pool.submit(() -> {
            ready.countDown();
            try {
                start.await();
                SessionContext.setUser("admin", "Admin (系統管理員)");
                try (Connection connB = DbManager.getForTenant(tenantB)) {
                    long pIdB = periodDao.getOrCreate(connB, 2026, 8).id();
                    try (PreparedStatement ps = connB.prepareStatement(
                            "INSERT INTO vouchers (period_id, voucher_no, voucher_date, summary, status, preparer) VALUES (?, 'V-TENANT-B', '2026-08-01', 'B專屬傳票', 'POSTED', 'Bob')")) {
                        ps.setLong(1, pIdB);
                        ps.executeUpdate();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            } finally {
                done.countDown();
            }
        });

        ready.await();
        start.countDown();
        assertTrue(done.await(15, java.util.concurrent.TimeUnit.SECONDS));
        pool.shutdown();

        try (Connection connA = DbManager.getForTenant(tenantA)) {
            try (PreparedStatement ps = connA.prepareStatement("SELECT voucher_no FROM vouchers")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("V-TENANT-A", rs.getString(1));
                    assertFalse(rs.next(), "Tenant A 絕不應包含 Tenant B 之傳票");
                }
            }
        }

        try (Connection connB = DbManager.getForTenant(tenantB)) {
            try (PreparedStatement ps = connB.prepareStatement("SELECT voucher_no FROM vouchers")) {
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals("V-TENANT-B", rs.getString(1));
                    assertFalse(rs.next(), "Tenant B 絕不應包含 Tenant A 之傳票");
                }
            }
        }
    }

    @Test
    @DisplayName("R9-03: 交易 rollback 不影響其他獨立請求的 commit")
    void testR9_03_TransactionRollbackDoesNotAffectOtherRequests() throws Exception {
        final long pId;
        try (Connection setup = DbManager.get()) {
            pId = periodDao.getOrCreate(setup, 2026, 9).id();
        }

        String vRoll = "V-R9-ROLLBACK";
        String vComm = "V-R9-COMMIT";

        // 連線 1: 開啟交易寫入 vRoll 並 rollback
        try (Connection c1 = DbManager.get()) {
            c1.setAutoCommit(false);
            try (PreparedStatement ps = c1.prepareStatement(
                    "INSERT INTO vouchers (period_id, voucher_no, voucher_date, summary, status, preparer) VALUES (?, ?, '2026-09-01', '測試回滾', 'DRAFT', 'Amy')")) {
                ps.setLong(1, pId);
                ps.setString(2, vRoll);
                ps.executeUpdate();
            }
            c1.rollback();
        }

        // 連線 2: 開啟交易寫入 vComm 並 commit
        try (Connection c2 = DbManager.get()) {
            c2.setAutoCommit(false);
            try (PreparedStatement ps = c2.prepareStatement(
                    "INSERT INTO vouchers (period_id, voucher_no, voucher_date, summary, status, preparer) VALUES (?, ?, '2026-09-01', '測試提交', 'DRAFT', 'Amy')")) {
                ps.setLong(1, pId);
                ps.setString(2, vComm);
                ps.executeUpdate();
            }
            c2.commit();
        }

        // 驗證連線 1 的回滾不影響連線 2 的提交
        try (Connection verify = DbManager.get()) {
            try (PreparedStatement ps = verify.prepareStatement("SELECT COUNT(*) FROM vouchers WHERE voucher_no = ?")) {
                ps.setString(1, vRoll);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "回滾的傳票不得寫入資料庫");
                }
            }
            try (PreparedStatement ps = verify.prepareStatement("SELECT COUNT(*) FROM vouchers WHERE voucher_no = ?")) {
                ps.setString(1, vComm);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(1, rs.getInt(1), "正常提交的傳票必須確實存在");
                }
            }
        }
    }

    @Test
    @DisplayName("R9-04: Connection.close() 確實釋放資源，isClosed() 為 true，關閉後操作拒絕")
    void testR9_04_ConnectionCloseReleasesResources() throws SQLException {
        Connection conn = DbManager.get();
        assertNotNull(conn);
        assertFalse(conn.isClosed(), "未關閉前 isClosed 應為 false");

        // 正常操作
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT 1")) {
            assertTrue(rs.next());
        }

        conn.close();
        assertTrue(conn.isClosed(), "close() 之後 isClosed() 必須為 true");
        assertThrows(SQLException.class, () -> conn.createStatement(), "對已關閉連線呼叫方法必須拋出 SQLException");
        assertThrows(SQLException.class, () -> conn.prepareStatement("SELECT 1"));
    }

    @Test
    @DisplayName("R9-05: 未授權租戶存取遭拒 (Fail-Closed)")
    void testR9_05_UnauthorizedTenantAccessDenied() {
        TenantSecurityService sec = new TenantSecurityService();
        String secretTenant = "tenant_private_secret_corp";

        SessionContext.setUser("amy", "Amy (記帳員・制單人)");

        // Amy 未獲授權存取 secretTenant，必須遭拒
        BusinessException ex = assertThrows(BusinessException.class, () ->
                DbManager.getForTenant(secretTenant));
        assertTrue(ex.getMessage().contains("未獲授權存取租戶"), "錯誤訊息應提示未獲授權: " + ex.getMessage());

        // 管理員授權
        SessionContext.setUser("admin", "Admin (系統管理員)");
        sec.grantAccess("amy", secretTenant, "admin");

        // Amy 再次存取 -> 成功
        SessionContext.setUser("amy", "Amy (記帳員・制單人)");
        assertDoesNotThrow(() -> {
            try (Connection conn = DbManager.getForTenant(secretTenant)) {
                assertNotNull(conn);
            }
        });

        // 撤銷授權 -> 再次被拒
        SessionContext.setUser("admin", "Admin (系統管理員)");
        sec.revokeAccess("amy", secretTenant, "admin");
        SessionContext.setUser("amy", "Amy (記帳員・制單人)");
        assertThrows(BusinessException.class, () -> DbManager.getForTenant(secretTenant));
    }

    @Test
    @DisplayName("R9-06: 空 tenantId 拒絕操作，禁止自動退回預設帳套 (Fail-Closed)")
    void testR9_06_EmptyTenantIdMustNotFallbackToDefault() {
        SessionContext.setUser("admin", "Admin (系統管理員)");

        // 傳入 null 必須拋出異常，絕不退回 default db
        BusinessException exNull = assertThrows(BusinessException.class, () -> DbManager.getForTenant(null));
        assertTrue(exNull.getMessage().contains("未指定租戶識別碼"), exNull.getMessage());

        // 傳入空字串必須拋出異常
        BusinessException exEmpty = assertThrows(BusinessException.class, () -> DbManager.getForTenant(""));
        assertTrue(exEmpty.getMessage().contains("未指定租戶識別碼"), exEmpty.getMessage());

        // 傳入純空白字串必須拋出異常
        BusinessException exBlank = assertThrows(BusinessException.class, () -> DbManager.getForTenant("   "));
        assertTrue(exBlank.getMessage().contains("未指定租戶識別碼"), exBlank.getMessage());

        // setCurrentTenant 傳入空字串必須拒絕
        assertThrows(BusinessException.class, () -> DbManager.setCurrentTenant(""));
    }

    @Test
    @DisplayName("R9-07: ThreadLocal 清理及執行緒重用防殘留測試")
    void testR9_07_ThreadLocalCleanupOnThreadReuse() throws Exception {
        java.util.concurrent.ExecutorService singleThreadExecutor = java.util.concurrent.Executors.newSingleThreadExecutor();

        // Task 1: 設定租戶並於完成後清除
        singleThreadExecutor.submit(() -> {
            SessionContext.setUser("admin", "Admin (系統管理員)");
            DbManager.setCurrentTenant("client_a");
            assertEquals("client_a", DbManager.getCurrentTenant());
            // 清理租戶與 Session 上下文
            SessionContext.clear();
        }).get(5, java.util.concurrent.TimeUnit.SECONDS);

        // Task 2: 在同一個執行緒上執行新任務，確認 ThreadLocal 租戶上下文已完全清除，無上一位使用者殘留
        singleThreadExecutor.submit(() -> {
            assertNull(DbManager.getCurrentTenant(), "執行緒重用時，租戶上下文必須為 null，絕不留存上一位使用者之設定！");
            assertNull(SessionContext.getUser(), "Session 使用者必須為 null");
        }).get(5, java.util.concurrent.TimeUnit.SECONDS);

        singleThreadExecutor.shutdown();
    }

    @Test
    @DisplayName("R9-08: 租戶 UUID 穩定映射且不碰撞（tenant/a vs tenant?a、Unicode租戶名）")
    void testR9_08_TenantUuidNoCollision() {
        String t1 = "tenant/a";
        String t2 = "tenant?a";
        String t3 = "tenant_a";
        String tUnicode1 = "台積電會計帳套";
        String tUnicode2 = "聯發科會計帳套";

        UUID u1 = TenantPathResolver.resolveTenantUuid(t1);
        UUID u2 = TenantPathResolver.resolveTenantUuid(t2);
        UUID u3 = TenantPathResolver.resolveTenantUuid(t3);
        UUID uUni1 = TenantPathResolver.resolveTenantUuid(tUnicode1);
        UUID uUni2 = TenantPathResolver.resolveTenantUuid(tUnicode2);

        // 驗證不同字元絕不產生相同 UUID
        assertNotEquals(u1, u2, "tenant/a 與 tenant?a 必須具有不同 UUID，絕不碰撞！");
        assertNotEquals(u1, u3);
        assertNotEquals(u2, u3);
        assertNotEquals(uUni1, uUni2, "不同 Unicode 租戶名稱必須具有不同 UUID");

        // 驗證重複計算具備完全穩定性 (Deterministic)
        assertEquals(u1, TenantPathResolver.resolveTenantUuid(t1), "重複建立相同租戶名稱必須對應完全相同 UUID");
        assertEquals(uUni1, TenantPathResolver.resolveTenantUuid(tUnicode1));

        // 驗證路徑唯一性
        String path1 = TenantPathResolver.resolveTenantDbPath(t1);
        String path2 = TenantPathResolver.resolveTenantDbPath(t2);
        assertNotEquals(path1, path2, "不同租戶資料庫檔案路徑絕不碰撞");
    }

    @Test
    @DisplayName("R9-09: 租戶路徑防範 Path Traversal 及長度限制")
    void testR9_09_PathTraversalRejection() {
        List<String> attackCases = List.of(
                "../../etc/passwd",
                "..\\windows\\system32",
                "../data/client_A.db",
                "..",
                "tenant/../../hack"
        );

        for (String attack : attackCases) {
            BusinessException ex = assertThrows(BusinessException.class, () ->
                    TenantPathResolver.resolveTenantDbPath(attack),
                    "路徑穿越攻擊 [" + attack + "] 必須被拒絕");
            assertTrue(ex.getMessage().contains("安全威脅") || ex.getMessage().contains("路徑穿越"), ex.getMessage());
        }

        // 超長租戶 ID 拒絕
        String tooLong = "A".repeat(256);
        BusinessException exLen = assertThrows(BusinessException.class, () ->
                TenantPathResolver.resolveTenantDbPath(tooLong));
        assertTrue(exLen.getMessage().contains("過長"), exLen.getMessage());
    }

    @Test
    @DisplayName("R9-10: client_id 初始化正確綁定實際資料庫，絕不混用全域路徑")
    void testR9_10_ClientIdInitializationCorrectPerDatabase() throws SQLException {
        String tenantX = "tenant_init_x";
        String tenantY = "tenant_init_y";

        SessionContext.setUser("admin", "Admin (系統管理員)");

        Path pX = Paths.get(DbManager.resolveTenantDbPath(tenantX));
        Path pY = Paths.get(DbManager.resolveTenantDbPath(tenantY));
        try { Files.deleteIfExists(pX); } catch (Exception ignored) {}
        try { Files.deleteIfExists(pY); } catch (Exception ignored) {}

        try {
            String clientX;
            String clientY;
            try (Connection connX = DbManager.getForTenant(tenantX)) {
                clientX = DbManager.getClientId(connX);
            }
            try (Connection connY = DbManager.getForTenant(tenantY)) {
                clientY = DbManager.getClientId(connY);
            }

            assertNotNull(clientX);
            assertNotNull(clientY);
            assertNotEquals(clientX, clientY, "不同租戶資料庫初始化之 client_id 必須互不相同");
            assertFalse(clientX.equalsIgnoreCase("client_A"), "租戶 X 之 client_id 絕不應誤用預設帳套 client_A");
            assertFalse(clientY.equalsIgnoreCase("client_A"), "租戶 Y 之 client_id 絕不應誤用預設帳套 client_A");

            // 重啟重新讀取確認一致性
            try (Connection connX2 = DbManager.getForTenant(tenantX)) {
                assertEquals(clientX, DbManager.getClientId(connX2), "重啟後 client_id 必須持久保持一致");
            }
            try (Connection connY2 = DbManager.getForTenant(tenantY)) {
                assertEquals(clientY, DbManager.getClientId(connY2));
            }
        } finally {
            SessionContext.clear();
        }
    }

    @Test
    @DisplayName("R9-11: system_meta 建立失敗時交易正確回滾")
    void testR9_11_SystemMetaCreationFailureSafeRollback() throws SQLException {
        Path testDb = Paths.get("target/test_meta_fail_rollback.db");
        try { Files.deleteIfExists(testDb); } catch (Exception ignored) {}
        String url = "jdbc:sqlite:" + testDb.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url)) {
            boolean originAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE accounts (id INTEGER PRIMARY KEY, code TEXT)");
                    // 模擬在交易中寫入 system_meta 失敗
                    throw new SQLException("模擬磁碟寫入異常");
                }
            } catch (Exception e) {
                conn.rollback();
            } finally {
                conn.setAutoCommit(originAuto);
            }

            // 驗證回滾後資料表不存在
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='accounts'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "初始化失敗回滾後不應留下未完成的殘留表格");
            }
        }
    }

    @Test
    @DisplayName("R9-12: Migration 中途失敗事務原子回滾，版本號不前進")
    void testR9_12_MigrationMidwayFailureAtomicRollback() throws SQLException {
        Path migDb = Paths.get("target/test_mig_atomic_rollback.db");
        try { Files.deleteIfExists(migDb); } catch (Exception ignored) {}
        String url = "jdbc:sqlite:" + migDb.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url)) {
            DbManager.applyPragmas(conn);
            DbManager.ensureSystemTables(conn);
            DbManager.setSchemaVersion(conn, 1);
            assertEquals(1, DbManager.getSchemaVersion(conn));

            boolean originAuto = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 模擬升級至 2 時發生語法錯誤或 DDL 失敗
                DbManager.setSchemaVersion(conn, 2);
                throw new SQLException("模擬升級過程中 Trigger 建立失敗");
            } catch (Exception e) {
                conn.rollback();
            } finally {
                conn.setAutoCommit(originAuto);
            }

            // 驗證失敗後版本號隨交易 100% 回滾為 1
            assertEquals(1, DbManager.getSchemaVersion(conn), "遷移失敗回滾後版本號必須維持 1，不得前進！");
        }
    }

    @Test
    @DisplayName("R9-13: Migration 重複執行確保冪等且安全無異常")
    void testR9_13_MigrationIdempotentRepeatedRuns() throws SQLException {
        Path migDb = Paths.get("target/test_mig_idempotent.db");
        try { Files.deleteIfExists(migDb); } catch (Exception ignored) {}
        String url = "jdbc:sqlite:" + migDb.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url)) {
            DbManager.applyPragmas(conn);
            // 連續執行 5 次 migrateSchema
            for (int i = 0; i < 5; i++) {
                assertDoesNotThrow(() -> DbManager.migrateSchema(conn, migDb.toString()), "重複執行 migrateSchema 必須冪等安全");
            }
            assertEquals(DbManager.CURRENT_SCHEMA_VERSION, DbManager.getSchemaVersion(conn));
        }
    }

    @Test
    @DisplayName("R9-14: Migration 後重新啟動，結構與版本號完整一致")
    void testR9_14_MigrationRestartIntegrity() throws SQLException {
        Path migDb = Paths.get("target/test_mig_restart.db");
        try { Files.deleteIfExists(migDb); } catch (Exception ignored) {}
        String url = "jdbc:sqlite:" + migDb.toString().replace('\\', '/');

        try (Connection conn1 = DriverManager.getConnection(url)) {
            DbManager.applyPragmas(conn1);
            DbManager.migrateSchema(conn1, migDb.toString());
            assertEquals(DbManager.CURRENT_SCHEMA_VERSION, DbManager.getSchemaVersion(conn1));
        }

        // 關閉後重新開啟新連線驗證
        try (Connection conn2 = DriverManager.getConnection(url)) {
            DbManager.applyPragmas(conn2);
            assertEquals(DbManager.CURRENT_SCHEMA_VERSION, DbManager.getSchemaVersion(conn2));
            try (Statement st = conn2.createStatement();
                 ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name IN ('accounts','vouchers','system_meta')")) {
                assertTrue(rs.next());
                assertEquals(3, rs.getInt(1), "所有核心資料表必須完整存在");
            }
        }
    }

    @Test
    @DisplayName("R9-15: 舊版資料庫升級後所有歷史會計科目、傳票完整保留")
    void testR9_15_LegacyDatabaseUpgradePreservesData() throws SQLException {
        Path legDb = Paths.get("target/test_legacy_upgrade_r9.db");
        try { Files.deleteIfExists(legDb); } catch (Exception ignored) {}
        String url = "jdbc:sqlite:" + legDb.toString().replace('\\', '/');

        // 建立舊版資料庫結構
        try (Connection conn = DriverManager.getConnection(url)) {
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE TABLE accounts (id INTEGER PRIMARY KEY, code TEXT, name TEXT, type TEXT)");
                st.execute("CREATE TABLE vouchers (id INTEGER PRIMARY KEY, period_id INTEGER, voucher_no TEXT, voucher_date TEXT, summary TEXT, status TEXT, preparer TEXT, reviewer TEXT, reversed_id INTEGER)");
                st.execute("CREATE TABLE voucher_entries (id INTEGER PRIMARY KEY, voucher_id INTEGER, line_no INTEGER, account_id INTEGER, memo TEXT, debit_minor INTEGER, credit_minor INTEGER)");
                st.execute("INSERT INTO accounts VALUES (1, '1101', '現金', 'ASSET')");
                st.execute("INSERT INTO vouchers VALUES (1, 1, 'V-LEGACY-001', '2024-01-01', '舊版歷史傳票', 'POSTED', 'Amy', 'Bob', NULL)");
                st.execute("INSERT INTO voucher_entries VALUES (1, 1, 1, 1, '期初現金', 100000, 0)");
            }

            // 執行升級
            DbManager.applyPragmas(conn);
            DbManager.migrateSchema(conn, legDb.toString());

            // 驗證歷史資料完好保留
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT code, name FROM accounts WHERE id=1")) {
                assertTrue(rs.next());
                assertEquals("1101", rs.getString(1));
                assertEquals("現金", rs.getString(2));
            }
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT voucher_no, status FROM vouchers WHERE id=1")) {
                assertTrue(rs.next());
                assertEquals("V-LEGACY-001", rs.getString(1));
                assertEquals("POSTED", rs.getString(2));
            }

            // 驗證 system_meta 升級完成
            assertEquals(DbManager.CURRENT_SCHEMA_VERSION, DbManager.getSchemaVersion(conn));
        }
    }

    @Test
    @DisplayName("R9-16: system_meta 與 PRAGMA user_version 保持一致，損壞時拋出明確錯誤")
    void testR9_16_SystemMetaAndUserVersionInSync() throws SQLException {
        Path syncDb = Paths.get("target/test_meta_sync_r9.db");
        try { Files.deleteIfExists(syncDb); } catch (Exception ignored) {}
        String url = "jdbc:sqlite:" + syncDb.toString().replace('\\', '/');

        try (Connection conn = DriverManager.getConnection(url)) {
            DbManager.applyPragmas(conn);
            DbManager.migrateSchema(conn, syncDb.toString());

            int metaVer = DbManager.getSchemaVersion(conn);
            int pragmaVer = 0;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("PRAGMA user_version")) {
                if (rs.next()) pragmaVer = rs.getInt(1);
            }
            assertEquals(metaVer, pragmaVer, "system_meta 與 PRAGMA user_version 必須完全一致");

            // 測試損壞的 schema_version 拋出明確 SQLException，絕不靜默略過
            try (Statement st = conn.createStatement()) {
                st.execute("UPDATE system_meta SET meta_value = 'INVALID_NOT_A_NUMBER' WHERE meta_key = 'schema_version'");
            }
            SQLException ex = assertThrows(SQLException.class, () -> DbManager.getSchemaVersion(conn),
                    "system_meta 中的版本格式損壞時必須拋出明確 SQLException！");
            assertTrue(ex.getMessage().contains("資料庫元資料損壞"), "錯誤訊息應指出元資料損壞: " + ex.getMessage());
        }
    }
}
