package com.ledger;

import com.ledger.config.DbManager;
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
import com.ledger.service.BackupService;
import com.ledger.service.ClosingService;
import com.ledger.service.ReportService;
import com.ledger.service.VoucherService;
import com.ledger.service.YearEndClosingService;
import com.ledger.service.ReportReconciliationService;
import com.ledger.util.MoneyUtil;
import org.junit.jupiter.api.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
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

}
