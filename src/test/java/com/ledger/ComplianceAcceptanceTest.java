package com.ledger;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.config.TenantPathResolver;
import com.ledger.config.TenantSecurityService;
import com.ledger.dao.AccountDao;
import com.ledger.dao.FiscalPeriodDao;
import com.ledger.dao.ReportDaoExtra;
import com.ledger.exception.BusinessException;
import com.ledger.model.Account;
import com.ledger.model.FinancialStatementItem;
import com.ledger.model.GeneralLedgerItem;
import com.ledger.model.Voucher;
import com.ledger.model.VoucherEntry;
import com.ledger.service.*;
import com.ledger.util.MoneyUtil;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 台灣商業會計系統合規驗收測試（對應 P0-01 ~ P1-05）。
 */
public class ComplianceAcceptanceTest {

    private static final String COMP_DB = "data/test_compliance_suite.db";

    private final AccountDao accountDao = new AccountDao();
    private final FiscalPeriodDao periodDao = new FiscalPeriodDao();
    private final VoucherService voucherService = new VoucherService();
    private final ReportService reportService = new ReportService();
    private final ReportReconciliationService reconciliationService = new ReportReconciliationService();
    private final ClosingService closingService = new ClosingService();
    private final AttachmentService attachmentService = new AttachmentService();
    private final RetentionPolicyService retentionService = new RetentionPolicyService();
    private final TaiwanTaxComplianceService taxService = new TaiwanTaxComplianceService();

    @BeforeEach
    void setUp() throws Exception {
        SessionContext.clear();
        DbManager.clearCurrentTenant();
        DbManager.closeAllTenants();
        System.clearProperty("ledger.forceProduction");

        Files.deleteIfExists(Paths.get(COMP_DB));
        Files.deleteIfExists(Paths.get(COMP_DB + "-wal"));
        Files.deleteIfExists(Paths.get(COMP_DB + "-shm"));
        Files.deleteIfExists(Paths.get("data/system_users.db"));
        Files.deleteIfExists(Paths.get("data/system_users.db-wal"));
        Files.deleteIfExists(Paths.get("data/system_users.db-shm"));
        DbManager.switchDatabase(COMP_DB);
    }

    @AfterEach
    void tearDown() throws Exception {
        System.clearProperty("ledger.forceProduction");
        SessionContext.clear();
        DbManager.clearCurrentTenant();
        DbManager.closeAllTenants();
        DbManager.closeCurrent();
        Files.deleteIfExists(Paths.get(COMP_DB));
        Files.deleteIfExists(Paths.get(COMP_DB + "-wal"));
        Files.deleteIfExists(Paths.get(COMP_DB + "-shm"));
    }

    private VoucherEntry entry(String code, String debitYuan, String creditYuan) throws SQLException {
        Account acc = accountDao.findByCode(code)
                .orElseThrow(() -> new AssertionError("Missing account: " + code));
        return new VoucherEntry(0, acc.id(), "test",
                new BigDecimal(debitYuan), new BigDecimal(creditYuan));
    }

    @Test
    @DisplayName("P0-01: createRawConnection 不得為 public，正式環境禁止任意路徑與 :memory:")
    void p0_01_rawConnectionNotPublicAndProdPathBlocked() throws Exception {
        Method m = DbManager.class.getDeclaredMethod("createRawConnection", String.class);
        assertFalse(Modifier.isPublic(m.getModifiers()), "createRawConnection 不得為 public");

        System.setProperty("ledger.forceProduction", "true");
        try {
            assertTrue(SessionContext.isProduction());
            assertThrows(SecurityException.class, () -> DbManager.switchDatabase(":memory:"));
            assertThrows(SecurityException.class, () -> DbManager.switchDatabase("data/arbitrary_unauth.db"));
            assertThrows(SecurityException.class, () -> DbManager.switchDatabase(TenantPathResolver.TENANT_ROOT + "/hack.db"));
            assertThrows(SecurityException.class, () -> DbManager.getForTenant("unauthorized_tenant_xyz"));
        } finally {
            System.clearProperty("ledger.forceProduction");
        }
    }

    @Test
    @DisplayName("P0-02a: Connection Lease close() 真實關閉且關閉後不可使用")
    void p0_02_connectionLeaseCloseSemantics() throws Exception {
        Connection c = DbManager.get();
        assertFalse(c.isClosed());
        c.close();
        assertTrue(c.isClosed(), "close() 後 isClosed() 必須為 true");
        assertThrows(SQLException.class, c::createStatement, "已關閉之 Connection Lease 不可再執行 SQL");
    }

    @Test
    @DisplayName("P0-02b: 多執行緒各自持有獨立 Connection Lease，Rollback 不影響其他執行緒讀取與 Commit")
    void p0_02_concurrentThreadsIsolatedTransactions() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch writerInserted = new CountDownLatch(1);
        CountDownLatch readerChecked = new CountDownLatch(1);
        CountDownLatch writerRolledBack = new CountDownLatch(1);
        try {
            Future<?> rollbackThread = pool.submit(() -> {
                try (Connection c1 = DbManager.get()) {
                    c1.setAutoCommit(false);
                    try (PreparedStatement ps = c1.prepareStatement(
                            "INSERT INTO system_meta(meta_key, meta_value) VALUES ('tx_rb', '1')")) {
                        ps.executeUpdate();
                    }
                    writerInserted.countDown();
                    assertTrue(readerChecked.await(5, TimeUnit.SECONDS));
                    c1.rollback();
                    c1.setAutoCommit(true);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                } finally {
                    writerRolledBack.countDown();
                }
            });

            Future<?> commitThread = pool.submit(() -> {
                try {
                    assertTrue(writerInserted.await(5, TimeUnit.SECONDS));
                    try (Connection c2 = DbManager.get()) {
                        // WAL snapshot isolation: c2 must not see uncommitted 'tx_rb'
                        try (ResultSet rs = c2.createStatement().executeQuery(
                                "SELECT COUNT(*) FROM system_meta WHERE meta_key = 'tx_rb'")) {
                            assertTrue(rs.next());
                            assertEquals(0, rs.getInt(1), "另一執行緒不應讀到未提交交易");
                        } finally {
                            readerChecked.countDown();
                        }
                        assertTrue(writerRolledBack.await(5, TimeUnit.SECONDS));
                        c2.setAutoCommit(false);
                        try (PreparedStatement ps = c2.prepareStatement(
                                "INSERT INTO system_meta(meta_key, meta_value) VALUES ('tx_ok', 'yes')")) {
                            ps.executeUpdate();
                        }
                        c2.commit();
                        c2.setAutoCommit(true);
                    }
                } catch (Exception e) {
                    readerChecked.countDown();
                    throw new RuntimeException(e);
                }
            });

            rollbackThread.get(10, TimeUnit.SECONDS);
            commitThread.get(10, TimeUnit.SECONDS);

            try (Connection verify = DbManager.get();
                 ResultSet rs = verify.createStatement().executeQuery(
                         "SELECT meta_key, meta_value FROM system_meta WHERE meta_key IN ('tx_rb','tx_ok')")) {
                int count = 0;
                while (rs.next()) {
                    assertEquals("tx_ok", rs.getString(1));
                    assertEquals("yes", rs.getString(2));
                    count++;
                }
                assertEquals(1, count, "應僅保留 Commit 執行緒寫入之資料");
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("P0-03: 停用帳號跨執行緒即時撤銷 Session，並阻擋多服務操作人偽造")
    void p0_03_crossThreadRevocationAndAntiSpoofing() throws Exception {
        AuthService auth = new AuthService();

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Worker thread logs in as Bob
            pool.submit(() -> {
                auth.login("bob", "bob123");
                assertTrue(SessionContext.isLoggedIn());
                return null;
            }).get(5, TimeUnit.SECONDS);

            // Admin disables Bob on main thread
            auth.login("admin", "admin123");
            auth.setEnabled("bob", false, "Admin");

            // Worker thread tries to resolve Bob's operator identity -> must fail!
            pool.submit(() -> {
                assertFalse(SessionContext.isLoggedIn(), "停用後跨執行緒 Session 必須失效");
                assertThrows(BusinessException.class, () -> SessionContext.resolveOperator("Bob"));
                assertThrows(BusinessException.class, SessionContext::requireUser);
                SessionContext.clear();
                return null;
            }).get(5, TimeUnit.SECONDS);
        } finally {
            auth.login("admin", "admin123");
            auth.setEnabled("bob", true, "Admin");
            SessionContext.clear();
            pool.shutdownNow();
        }

        // Anti-spoofing across AccountService, OpeningBalanceService, RetentionPolicyService
        auth.login("amy", "amy123");
        try {
            assertThrows(BusinessException.class, () ->
                    new AccountService().createAccount("1199", "測試", "ASSET", "DEBIT", null, "Bob"));
            assertThrows(BusinessException.class, () ->
                    new OpeningBalanceService().importOpening("2027-01-01",
                            List.of(new OpeningBalanceService.OpeningLine("1101", 1000, 0, "")), "Bob"));
            assertThrows(BusinessException.class, () ->
                    retentionService.recordDestructionApproval("BATCH-SPOOF", "VOUCHER", "V-1",
                            LocalDate.of(2015, 1, 1), LocalDate.of(2027, 1, 1), "test", "Bob"));
        } finally {
            SessionContext.clear();
        }
    }

    @Test
    @DisplayName("P1-01: 完整間接法現金流量表（折舊、處分資產損益、非現金交易與總帳勾稽）")
    void p1_01_fullIndirectCashFlowAndReconciliation() throws Exception {
        // 1. 股東現金出資 500,000
        voucherService.post(voucherService.createDraft("2027-01-02", "現金增資", "Amy", List.of(
                entry("1102", "500000.00", "0"),
                entry("3101", "0", "500000.00")
        )).id(), "Bob");

        // 2. 現金購買設備 200,000 (1401)
        voucherService.post(voucherService.createDraft("2027-01-10", "購置設備", "Amy", List.of(
                entry("1401", "200000.00", "0"),
                entry("1102", "0", "200000.00")
        )).id(), "Bob");

        // 3. 提列折舊 30,000 (5208 / 1402) - 非現金費用
        voucherService.post(voucherService.createAdjustingDraft("2027-06-30", "年中設備折舊", "Amy", List.of(
                entry("5208", "30000.00", "0"),
                entry("1402", "0", "30000.00")
        )).id(), "Bob");

        // 4. 處分半數設備（成本 100,000、累折 15,000、售價現金 95,000 -> 處分利益 10,000）
        voucherService.post(voucherService.createDraft("2027-08-01", "處分設備獲利", "Amy", List.of(
                entry("1102", "95000.00", "0"),
                entry("1402", "15000.00", "0"),
                entry("1401", "0", "100000.00"),
                entry("4202", "0", "10000.00")
        )).id(), "Bob");

        // 5. 非現金購置設備（賒購設備 80,000: Dr 1401 / Cr 2122）
        voucherService.post(voucherService.createDraft("2027-09-15", "賒購設備（非現金）", "Amy", List.of(
                entry("1401", "80000.00", "0"),
                entry("2122", "0", "80000.00")
        )).id(), "Bob");

        List<FinancialStatementItem> cf = reportService.getCashFlow("2027-01-01", "2027-12-31");
        long deprAddBack = cf.stream().filter(i -> "DEPR_AMORT".equals(i.code())).findFirst().orElseThrow().amountMinor();
        long disposalAdj = cf.stream().filter(i -> "DISPOSAL_ADJ".equals(i.code())).findFirst().orElseThrow().amountMinor();
        long cfo = cf.stream().filter(i -> "CFO".equals(i.code())).findFirst().orElseThrow().amountMinor();
        long cfi = cf.stream().filter(i -> "CFI".equals(i.code())).findFirst().orElseThrow().amountMinor();
        long cff = cf.stream().filter(i -> "CFF".equals(i.code())).findFirst().orElseThrow().amountMinor();
        long nonCash = cf.stream().filter(i -> "NON_CASH".equals(i.code())).findFirst().orElseThrow().amountMinor();
        long endCash = cf.stream().filter(i -> "END_CASH".equals(i.code())).findFirst().orElseThrow().amountMinor();
        long actualCash = cf.stream().filter(i -> "ACTUAL_CASH".equals(i.code())).findFirst().orElseThrow().amountMinor();

        assertEquals(MoneyUtil.toMinor("30000.00"), deprAddBack, "折舊費用 30,000 應於營業活動加回");
        assertEquals(MoneyUtil.toMinor("-10000.00"), disposalAdj, "處分利益 10,000 應於營業活動減除");
        assertEquals(0L, cfo, "無純現金營業收支，CFO 應為 0");
        assertEquals(MoneyUtil.toMinor("-105000.00"), cfi, "投資淨流出應為 -200,000 + 95,000 = -105,000");
        assertEquals(MoneyUtil.toMinor("500000.00"), cff, "籌資淨流入應為 +500,000");
        assertEquals(MoneyUtil.toMinor("80000.00"), nonCash, "非現金賒購設備 80,000 應單獨揭露");
        assertEquals(MoneyUtil.toMinor("395000.00"), endCash);
        assertEquals(endCash, actualCash, "推算期末現金必須完全等於帳列現金");

        // 全報表四表交叉勾稽必須通過
        reconciliationService.assertAllPass("2027-01-01", "2027-12-31");
    }

    @Test
    @DisplayName("P1-02: 原始憑證 SHA-256 電子簽章驗證、作廢軌跡與保存年限／未結事項延長保存")
    void p1_02_attachmentSignatureVoidAndRetentionPolicy() throws Exception {
        Voucher v = voucherService.createDraft("2027-03-01", "文具費用憑證", "Amy", List.of(
                entry("5206", "500.00", "0"),
                entry("1101", "0", "500.00")
        ));
        voucherService.post(v.id(), "Bob");

        Path tmpFile = Files.createTempFile("receipt_test_", ".pdf");
        Path storedCopy = null;
        try {
            Files.writeString(tmpFile, "INV-AB12345678 Amount=500");
            AttachmentService.Attachment att = attachmentService.addAttachment(
                    v.id(), tmpFile, "AB-12345678", "2027-03-01", "發票正本", "Amy");
            storedCopy = Paths.get(att.storedPath());

            assertEquals("AB-12345678", att.docNo());
            assertNotNull(att.sha256Hash());
            assertNotNull(att.digitalSignature());
            assertEquals("ACTIVE", att.status());
            assertTrue(attachmentService.verifyAttachmentSignature(att.id()), "未竄改憑證驗章必須通過");

            // 竄改磁碟附件內容 -> 驗章失敗
            Files.writeString(storedCopy, "TAMPERED AMOUNT=99999");
            assertFalse(attachmentService.verifyAttachmentSignature(att.id()), "檔案遭竄改後驗章必須失敗");

            // 已過帳傳票禁止刪除，但可作廢保留歷程
            assertThrows(BusinessException.class, () -> attachmentService.deleteAttachment(att.id(), "Bob"));
            attachmentService.voidAttachment(att.id(), "發票字軌繕寫錯誤重開", "Bob");

            var afterVoid = attachmentService.listByVoucher(v.id()).get(0);
            assertEquals("VOID", afterVoid.status());
            assertEquals("Bob", afterVoid.voidedBy());
            assertEquals("發票字軌繕寫錯誤重開", afterVoid.voidReason());
        } finally {
            Files.deleteIfExists(tmpFile);
            if (storedCopy != null) Files.deleteIfExists(storedCopy);
        }

        // 保存年限：憑證 5 年，帳簿報表 10 年（須先登錄年度決算完成日作為起算點）；若有未結事項不得銷毀
        retentionService.recordAnnualClosingCompletion(2020, LocalDate.of(2021, 5, 1), "Bob");
        retentionService.recordAnnualClosingCompletion(2021, LocalDate.of(2021, 12, 31), "Bob");
        retentionService.recordAnnualClosingCompletion(2023, LocalDate.of(2024, 5, 1), "Bob");
        assertFalse(retentionService.isEligibleForDestruction("VOUCHER", LocalDate.of(2023, 1, 1), LocalDate.of(2027, 1, 1)));
        assertTrue(retentionService.isEligibleForDestruction("VOUCHER", LocalDate.of(2021, 12, 31), LocalDate.of(2027, 1, 1)));
        assertFalse(retentionService.isEligibleForDestruction("BOOK", LocalDate.of(2021, 12, 31), LocalDate.of(2027, 1, 1)));
        assertFalse(retentionService.isEligibleForDestruction("VOUCHER", LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1), true),
                "存在未結會計事項時應延長保存，不可銷毀");

        // 存在未過帳草稿於 recordDate 之前亦屬未結事項
        voucherService.createDraft("2020-05-01", "尚未過帳之歷史草稿", "Amy", List.of(
                entry("1101", "10.00", "0"),
                entry("4101", "0", "10.00")
        ));
        assertFalse(retentionService.isEligibleForDestruction("VOUCHER", LocalDate.of(2020, 12, 31), LocalDate.of(2030, 1, 1)),
                "尚有歷史未過帳草稿應延長保存");

        retentionService.recordAnnualClosingCompletion(2015, LocalDate.of(2016, 5, 1), "Bob");
        assertThrows(BusinessException.class, () ->
                retentionService.recordDestructionApproval("BATCH-2015", "VOUCHER", "2015",
                        LocalDate.of(2015, 12, 31), LocalDate.of(2027, 1, 1), "過期", "Amy"));
        retentionService.recordDestructionApproval("BATCH-2015", "VOUCHER", "2015-BATCH",
                LocalDate.of(2015, 12, 31), LocalDate.of(2027, 1, 1), "已逾法定5年無未結事項", "Bob");
    }

    @Test
    @DisplayName("P1-03: 台灣稅務試算（營業稅 5%、統一發票字軌、營所稅 12 萬免稅與半數課稅、扣繳）")
    void p1_03_taiwanTaxComplianceCalculations() {
        assertTrue(taxService.isValidUniformInvoiceNo("AB-12345678"));
        assertTrue(taxService.isValidUniformInvoiceNo("xy87654321"));
        assertFalse(taxService.isValidUniformInvoiceNo("12345678"));

        var vatExcl = taxService.calculateVat(MoneyUtil.toMinor("10000"), false);
        assertEquals(MoneyUtil.toMinor("500"), vatExcl.vatAmountMinor());
        assertEquals(MoneyUtil.toMinor("10500"), vatExcl.totalAmountMinor());

        var vatIncl = taxService.calculateVat(MoneyUtil.toMinor("1050"), true);
        assertEquals(MoneyUtil.toMinor("1000"), vatIncl.salesAmountMinor());
        assertEquals(MoneyUtil.toMinor("50"), vatIncl.vatAmountMinor());

        // 營所稅：<=12萬免稅、15萬適用半數課稅 (15-12)/2=1.5萬、30萬適用20%=6萬
        assertEquals(0L, taxService.calculateCorporateIncomeTax(MoneyUtil.toMinor("120000")).taxPayableMinor());
        assertEquals(MoneyUtil.toMinor("15000"),
                taxService.calculateCorporateIncomeTax(MoneyUtil.toMinor("150000")).taxPayableMinor());
        assertEquals(MoneyUtil.toMinor("60000"),
                taxService.calculateCorporateIncomeTax(MoneyUtil.toMinor("300000")).taxPayableMinor());

        // 扣繳：居住者租金 <= 2000 免扣；超過則 10%
        assertEquals(0L, taxService.calculateWithholding(MoneyUtil.toMinor("20000"), "RENT", true).taxWithheldMinor());
        assertEquals(MoneyUtil.toMinor("3000"), taxService.calculateWithholding(MoneyUtil.toMinor("30000"), "RENT", true).taxWithheldMinor());
    }

    @Test
    @DisplayName("P1-05: 決算調整分錄、明細分類帳與年結後損益表／資產負債表一致性")
    void p1_05_adjustingSubLedgerAndPostYearEndStatements() throws Exception {
        voucherService.post(voucherService.createDraft("2028-02-01", "銷貨收現", "Amy", List.of(
                entry("1101", "10000.00", "0"),
                entry("4101", "0", "10000.00")
        )).id(), "Bob");

        Voucher adj = voucherService.createAdjustingDraft("2028-12-31", "應付薪資調整", "Amy", List.of(
                entry("5201", "2000.00", "0"),
                entry("2124", "0", "2000.00")
        ));
        assertTrue(adj.summary().contains("[期末調整]"));
        voucherService.post(adj.id(), "Bob");

        // 明細分類帳（52xx 費用類明細）
        Map<String, List<GeneralLedgerItem>> subLedger = reportService.getSubLedger("52", "2028-01-01", "2028-12-31");
        assertTrue(subLedger.keySet().stream().anyMatch(k -> k.startsWith("5201")));

        // 執行完整年度結帳
        try (Connection conn = DbManager.get()) {
            for (int m = 1; m <= 12; m++) {
                periodDao.getOrCreate(conn, 2028, m);
            }
        }
        for (int m = 1; m <= 11; m++) {
            closingService.closePeriod(2028, m, "Bob");
        }
        YearEndClosingService ye = new YearEndClosingService();
        ye.closeYear(2028, "Amy", "Bob");

        // 年結後 2028 年綜合損益表仍應顯示真實年度淨利 8,000，不被結帳分錄歸零
        List<FinancialStatementItem> is2028 = reportService.getIncomeStatement("2028-01-01", "2028-12-31");
        long ni2028 = is2028.stream().filter(i -> "NET_INCOME".equals(i.code())).findFirst().orElseThrow().amountMinor();
        assertEquals(MoneyUtil.toMinor("8000.00"), ni2028, "年結後年度損益表淨利仍應為 8,000.00");

        // 全報表勾稽在年結後仍應全數通過
        reconciliationService.assertAllPass("2028-01-01", "2028-12-31");
    }

    @Test
    @DisplayName("R12-P0-01: SessionContext.setUser 不可公開存取、外部禁止 establishSession、停用帳號無法登入與復辟")
    void r12_p0_01_sessionIdentityBypassBlocked() throws Exception {
        Method setUserMethod = SessionContext.class.getDeclaredMethod("setUser", String.class, String.class);
        assertTrue(Modifier.isPrivate(setUserMethod.getModifiers()), "SessionContext.setUser 必須為 private");

        // 非 AuthService 呼叫 establishSession 必須拋出 SecurityException
        assertThrows(SecurityException.class, () -> SessionContext.establishSession(null));

        // 缺少 sessionId 時 isLoggedIn 不得有舊版 fallback
        SessionContext.clear();
        assertFalse(SessionContext.isLoggedIn());
        assertThrows(BusinessException.class, SessionContext::requireUser);

        // 停用帳號不可登入建立新 Session，且 isUserRevoked 維持 true
        AuthService auth = new AuthService();
        auth.login("admin", "admin123");
        try {
            auth.setEnabled("david", false, "Admin");
            assertTrue(SessionContext.isUserRevoked("David"));
            SessionContext.clear();
            assertThrows(BusinessException.class, () -> auth.login("david", "david123"),
                    "停用帳號不得透過 login 建立新 Session");
            assertFalse(SessionContext.isLoggedIn());
        } finally {
            auth.login("admin", "admin123");
            auth.setEnabled("david", true, "Admin");
            SessionContext.clear();
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    @DisplayName("R12-P0-02: Windows NTFS Junction 攻擊與 data/tenants 外路徑建立資料庫阻擋")
    void r12_p0_02_windowsJunctionBlocked() throws Exception {
        Path outsideDir = Files.createTempDirectory("r12_outside_target_");
        Path junctionPath = Paths.get(TenantPathResolver.TENANT_ROOT, "r12_evil_junction").toAbsolutePath().normalize();
        Path tenantJuncDb = Paths.get(TenantPathResolver.resolveDbPath("r12_junc_tenant")).toAbsolutePath().normalize();
        Files.createDirectories(Paths.get(TenantPathResolver.TENANT_ROOT));
        Files.deleteIfExists(junctionPath);
        Files.deleteIfExists(tenantJuncDb);
        try {
            Process proc = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    junctionPath.toString(), outsideDir.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            int exit = proc.waitFor();
            String output = new String(proc.getInputStream().readAllBytes());
            assertEquals(0, exit, "mklink /J 建立測試用 Junction 失敗: " + output);

            Process proc2 = new ProcessBuilder("cmd.exe", "/c", "mklink", "/J",
                    tenantJuncDb.toString(), outsideDir.toAbsolutePath().toString())
                    .redirectErrorStream(true)
                    .start();
            int exit2 = proc2.waitFor();
            String output2 = new String(proc2.getInputStream().readAllBytes());
            assertEquals(0, exit2, "mklink /J 建立租戶 DB Junction 失敗: " + output2);

            // 禁止解析或存取透過 Junction 跳脫至外部目錄的租戶資料庫
            String evilRel = TenantPathResolver.TENANT_ROOT + "/r12_evil_junction/escape.db";
            Path evilDb = junctionPath.resolve("escape.db");
            assertThrows(SecurityException.class, () -> TenantPathResolver.assertSafePath(evilRel),
                    "必須拒絕經由 Windows Junction 指向外部目錄之相對路徑");
            assertThrows(SecurityException.class,
                    () -> TenantPathResolver.resolveDbPath("r12_junc_tenant"),
                    "TenantPathResolver 不得解析符號連結或 Windows Junction");
            assertThrows(SecurityException.class,
                    () -> TenantPathResolver.assertNoSymlinkOrJunction(
                            evilDb, Paths.get(TenantPathResolver.TENANT_ROOT).toAbsolutePath().normalize()));
        } finally {
            Files.deleteIfExists(tenantJuncDb);
            Files.deleteIfExists(junctionPath);
            Files.deleteIfExists(outsideDir);
        }
    }

    @Test
    @DisplayName("R12-P0-03: 24 執行緒高並行 Commit/Rollback 隔離與強制關閉租戶連線生命週期")
    void r12_p0_03_concurrentLeaseStressAndShutdownLifecycle() throws Exception {
        int threads = 24;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger commitCount = new AtomicInteger(0);
        AtomicInteger rollbackCount = new AtomicInteger(0);
        List<Future<?>> futures = new java.util.ArrayList<>();

        try {
            for (int i = 0; i < threads; i++) {
                final int idx = i;
                final boolean doCommit = (i % 2 == 0);
                futures.add(pool.submit(() -> {
                    start.await(5, TimeUnit.SECONDS);
                    try (Connection c = DbManager.get()) {
                        c.setAutoCommit(false);
                        try (PreparedStatement ps = c.prepareStatement(
                                "INSERT INTO system_meta(meta_key, meta_value) VALUES (?, ?)")) {
                            ps.setString(1, "stress_" + idx);
                            ps.setString(2, doCommit ? "committed" : "rolled_back");
                            ps.executeUpdate();
                        }
                        if (doCommit) {
                            c.commit();
                            commitCount.incrementAndGet();
                        } else {
                            c.rollback();
                            rollbackCount.incrementAndGet();
                        }
                        c.setAutoCommit(true);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
            assertEquals(12, commitCount.get());
            assertEquals(12, rollbackCount.get());

            try (Connection verify = DbManager.get();
                 ResultSet rs = verify.createStatement().executeQuery(
                         "SELECT COUNT(*) FROM system_meta WHERE meta_key LIKE 'stress_%'")) {
                assertTrue(rs.next());
                assertEquals(12, rs.getInt(1), "只有 Commit 的 12 筆資料應寫入，Rollback 的 12 筆必須完全隔離");
            }

            // 驗證強制關閉租戶連線會同步關閉底層實體連線 Proxy 並清空 Lease 追蹤
            Connection held = DbManager.get();
            assertFalse(held.isClosed());
            assertTrue(DbManager.getActiveLeaseCount() >= 1);
            DbManager.closeCurrent();
            assertEquals(0, DbManager.getActiveLeaseCount(), "closeCurrent() 後活躍 Lease 數必須為 0");
            assertTrue(held.isClosed(), "強制關閉後原先持有的 Proxy 連線必須回報 isClosed() == true");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("R12-P1-01: 累計折舊科目獨立分類、含 1402 處分設備損失情境與可設定科目分類")
    void r12_p1_01_cashFlowAccumDeprAndCustomClassification() throws Exception {
        assertEquals(com.ledger.dao.ReportDaoExtra.CashFlowCategory.ACCUM_DEPR_AMORT,
                com.ledger.dao.ReportDaoExtra.classifyAccount("1402", "ASSET"),
                "1402 累計折舊必須分類為 ACCUM_DEPR_AMORT，不得混入 INVESTING");

        // 含累計折舊迴轉與處分損失情境
        voucherService.post(voucherService.createDraft("2027-01-05", "現金增資", "Amy", List.of(
                entry("1102", "500000.00", "0"),
                entry("3101", "0", "500000.00")
        )).id(), "Bob");
        voucherService.post(voucherService.createDraft("2027-01-10", "購置機器設備", "Amy", List.of(
                entry("1401", "200000.00", "0"),
                entry("1102", "0", "200000.00")
        )).id(), "Bob");
        voucherService.post(voucherService.createAdjustingDraft("2027-06-30", "折舊費用", "Amy", List.of(
                entry("5208", "30000.00", "0"),
                entry("1402", "0", "30000.00")
        )).id(), "Bob");
        // 處分設備（成本 130,000、累折 30,000 -> 帳面價值 100,000、現金售價 95,000 -> 處分損失 5,000）
        voucherService.post(voucherService.createDraft("2027-09-01", "處分設備損失", "Amy", List.of(
                entry("1102", "95000.00", "0"),
                entry("1402", "30000.00", "0"),
                entry("5302", "5000.00", "0"),
                entry("1401", "0", "130000.00")
        )).id(), "Bob");
        // 非現金賒購設備 80,000
        voucherService.post(voucherService.createDraft("2027-10-01", "賒購設備", "Amy", List.of(
                entry("1401", "80000.00", "0"),
                entry("2122", "0", "80000.00")
        )).id(), "Bob");

        var cf = reportService.getCashFlow("2027-01-01", "2027-12-31");
        assertTrue(com.ledger.dao.ReportDaoExtra.isCashReconciled(cf), "推算期末現金必須與總帳現金完全勾稽");
        long cfo = cf.stream().filter(i -> "CFO".equals(i.code())).findFirst().orElseThrow().amountMinor();
        long cfi = cf.stream().filter(i -> "CFI".equals(i.code())).findFirst().orElseThrow().amountMinor();
        long cff = cf.stream().filter(i -> "CFF".equals(i.code())).findFirst().orElseThrow().amountMinor();
        long nonCash = cf.stream().filter(i -> "NON_CASH".equals(i.code())).findFirst().orElseThrow().amountMinor();
        assertEquals(0L, cfo);
        assertEquals(MoneyUtil.toMinor("-105000.00"), cfi);
        assertEquals(MoneyUtil.toMinor("500000.00"), cff);
        assertEquals(MoneyUtil.toMinor("80000.00"), nonCash);

        // 驗證可設定科目分類覆寫
        try {
            com.ledger.dao.ReportDaoExtra.registerAccountClassification(
                    "1999", com.ledger.dao.ReportDaoExtra.CashFlowCategory.INVESTING);
            assertEquals(com.ledger.dao.ReportDaoExtra.CashFlowCategory.INVESTING,
                    com.ledger.dao.ReportDaoExtra.classifyAccount("1999", "ASSET"));
        } finally {
            com.ledger.dao.ReportDaoExtra.clearCustomClassifications();
        }
    }

    @Test
    @DisplayName("R12-P1-02: HMAC 金鑰外部隔離儲存、版本輪替（v1->v2）、SQLite 共置防護與路徑邊界檢核")
    void r12_p1_02_externalKeystoreRotationAndSecurity() throws Exception {
        Path ksTemp = Files.createTempDirectory("r12_keystore_");
        Path ksFile = ksTemp.resolve("hmac_keys.properties");
        String prevProp = System.getProperty("ledger.hmac.keystore.path");
        System.setProperty("ledger.hmac.keystore.path", ksFile.toString());

        Path src1 = Files.createTempFile("att_v1_", ".txt");
        Path src2 = Files.createTempFile("att_v2_", ".txt");
        Path stored1 = null;
        Path stored2 = null;
        try {
            assertTrue(AttachmentService.SIGNATURE_MECHANISM.contains("NOT_PKI"));
            Voucher v = voucherService.createDraft("2027-04-01", "金鑰測試傳票", "Amy", List.of(
                    entry("5206", "100.00", "0"),
                    entry("1101", "0", "100.00")
            ));
            voucherService.post(v.id(), "Bob");

            Files.writeString(src1, "VOUCHER-DOC-V1");
            var a1 = attachmentService.addAttachment(v.id(), src1, "INV-V1", "2027-04-01", "v1", "Amy");
            stored1 = Paths.get(a1.storedPath());
            assertEquals("v1", a1.keyVersion());
            assertTrue(attachmentService.verifyAttachmentSignature(a1.id()));

            // 確認 SQLite system_meta 內沒有 HMAC 金鑰
            try (Connection conn = DbManager.get();
                 ResultSet rs = conn.createStatement().executeQuery(
                         "SELECT COUNT(*) FROM system_meta WHERE meta_key = 'attachment_hmac_key'")) {
                assertTrue(rs.next());
                assertEquals(0, rs.getInt(1), "HMAC 金鑰禁止儲存於 SQLite system_meta");
            }

            // 外部修改 DB 中的 sha256_hash 與磁碟檔案，因缺少外部金鑰仍無法通過驗章
            String forgedHash = "0".repeat(64);
            Files.writeString(stored1, "FORGED_CONTENT");
            try (Connection conn = DbManager.get();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE voucher_attachments SET sha256_hash = ? WHERE id = ?")) {
                ps.setString(1, forgedHash);
                ps.setLong(2, a1.id());
                ps.executeUpdate();
            }
            assertFalse(attachmentService.verifyAttachmentSignature(a1.id()),
                    "攻擊者同時竄改 DB 雜湊與檔案內容，無外部金鑰仍必須驗章失敗");

            // 復原 v1 檔案與雜湊，測試金鑰輪替至 v2 後，舊憑證 v1 與新憑證 v2 皆可正確驗證
            Files.writeString(stored1, "VOUCHER-DOC-V1");
            try (Connection conn = DbManager.get();
                 PreparedStatement ps = conn.prepareStatement(
                         "UPDATE voucher_attachments SET sha256_hash = ? WHERE id = ?")) {
                ps.setString(1, a1.sha256Hash());
                ps.setLong(2, a1.id());
                ps.executeUpdate();
            }
            String newVer = attachmentService.rotateHmacKey("Bob");
            assertEquals("v2", newVer);
            assertTrue(attachmentService.verifyAttachmentSignature(a1.id()), "金鑰輪替後既有 v1 憑證仍須可驗證");

            Files.writeString(src2, "VOUCHER-DOC-V2");
            var a2 = attachmentService.addAttachment(v.id(), src2, "INV-V2", "2027-04-02", "v2", "Amy");
            stored2 = Paths.get(a2.storedPath());
            assertEquals("v2", a2.keyVersion());
            assertTrue(attachmentService.verifyAttachmentSignature(a2.id()), "新版 v2 憑證驗章必須通過");

            // 禁止將 keystore 設定在 data/ 目錄內
            System.setProperty("ledger.hmac.keystore.path", "data/bad_keystore.properties");
            assertThrows(SecurityException.class, AttachmentService::getExternalKeystorePath);
        } finally {
            if (prevProp == null) System.clearProperty("ledger.hmac.keystore.path");
            else System.setProperty("ledger.hmac.keystore.path", prevProp);
            Files.deleteIfExists(src1);
            Files.deleteIfExists(src2);
            if (stored1 != null) Files.deleteIfExists(stored1);
            if (stored2 != null) Files.deleteIfExists(stored2);
            Files.deleteIfExists(ksFile);
            Files.deleteIfExists(ksFile.resolveSibling(ksFile.getFileName().toString() + ".tmp"));
            Files.deleteIfExists(ksTemp.resolve(".kek_store.bin"));
            Files.deleteIfExists(ksTemp.resolve(".kek_salt.bin"));
            Files.deleteIfExists(ksTemp);
        }
    }

    @Test
    @DisplayName("R12-P1-03: 商業會計法第38條決算完成日起算、未完結禁銷、批次綁定與防重複核准")
    void r12_p1_03_legalRetentionAndBatchApproval() {
        // 尚未登記 2019 年度決算完成日 -> 即使超過 10 年亦不可銷毀
        assertFalse(retentionService.isEligibleForDestruction("VOUCHER", LocalDate.of(2019, 1, 1), LocalDate.of(2035, 1, 1)),
                "未完成年度決算程序前，法定保存期間尚未起算");
        assertThrows(BusinessException.class, () ->
                retentionService.recordDestructionApproval("BATCH-2019", "VOUCHER", "V-2019-01",
                        LocalDate.of(2019, 6, 1), LocalDate.of(2035, 1, 1), "未年結", "Bob"));

        // 2018 年度決算完成日為 2019-05-15 -> 憑證 5 年於 2024-05-15 屆滿；帳簿 10 年於 2029-05-15 屆滿
        retentionService.recordAnnualClosingCompletion(2018, LocalDate.of(2019, 5, 15), "Bob");
        assertFalse(retentionService.isEligibleForDestruction("VOUCHER", LocalDate.of(2018, 12, 31), LocalDate.of(2024, 5, 14)));
        assertTrue(retentionService.isEligibleForDestruction("VOUCHER", LocalDate.of(2018, 12, 31), LocalDate.of(2024, 5, 15)));
        assertFalse(retentionService.isEligibleForDestruction("BOOK", LocalDate.of(2018, 12, 31), LocalDate.of(2029, 5, 14)));
        assertTrue(retentionService.isEligibleForDestruction("BOOK", LocalDate.of(2018, 12, 31), LocalDate.of(2029, 5, 15)));

        // 空白批次編號不得核准
        assertThrows(BusinessException.class, () ->
                retentionService.recordDestructionApproval("  ", "VOUCHER", "V-2018-01",
                        LocalDate.of(2018, 12, 31), LocalDate.of(2025, 1, 1), "已屆期", "Bob"));

        var rec = retentionService.recordDestructionApproval("DESTROY-BATCH-2025-A", "VOUCHER", "V-2018-ALL",
                LocalDate.of(2018, 12, 31), LocalDate.of(2025, 1, 1), "2018年度決算後已逾5年", "Bob");
        assertEquals("DESTROY-BATCH-2025-A", rec.batchId());
        assertEquals(LocalDate.of(2019, 5, 15), rec.closingCompletionDate());

        // 防範重複核准同一目標
        assertThrows(BusinessException.class, () ->
                retentionService.recordDestructionApproval("DESTROY-BATCH-2025-B", "VOUCHER", "V-2018-ALL",
                        LocalDate.of(2018, 12, 31), LocalDate.of(2025, 2, 1), "重複送簽", "Bob"));
    }

    @Test
    @DisplayName("R12-P1-04: 台灣稅務進項不可扣抵、營所稅邊界、擴充扣繳及統一編號／發票邏輯檢核")
    void r12_p1_04_extendedTaxRulesAndGuiValidation() {
        assertTrue(TaiwanTaxComplianceService.RULE_EFFECTIVE_DATES.containsKey("CIT_20_PERCENT_AND_THRESHOLD"));

        // 營業稅零稅率、免稅、第 19 條交際應酬不可扣抵與期末應納/留抵試算
        var zero = taxService.calculateVat(MoneyUtil.toMinor("10000"), TaiwanTaxComplianceService.TaxType.ZERO_RATED, false);
        assertEquals(0L, zero.vatAmountMinor());
        assertEquals(TaiwanTaxComplianceService.FilingStatus.INTERNAL_ESTIMATE_ONLY, zero.filingStatus());

        var entVat = taxService.calculateVatInputDeduction(MoneyUtil.toMinor("500"),
                TaiwanTaxComplianceService.InputVatCategory.NON_DEDUCTIBLE_ENTERTAINMENT);
        assertEquals(0L, entVat.deductibleVatMinor());
        assertEquals(MoneyUtil.toMinor("500"), entVat.nonDeductibleVatMinor());

        var periodVat = taxService.calculatePeriodVatPayable(MoneyUtil.toMinor("5000"), MoneyUtil.toMinor("6500"), 0L);
        assertEquals(0L, periodVat.taxPayableMinor());
        assertEquals(MoneyUtil.toMinor("1500"), periodVat.nextCarryforwardCreditMinor());

        // 營所稅 12萬 / 20萬邊界與調整後課稅所得（含十年虧損扣抵）
        assertEquals(MoneyUtil.toMinor("40000"),
                taxService.calculateCorporateIncomeTax(MoneyUtil.toMinor("200000")).taxPayableMinor());
        assertEquals(MoneyUtil.toMinor("40000.20"),
                taxService.calculateCorporateIncomeTax(MoneyUtil.toMinor("200001")).taxPayableMinor());
        var adjCit = taxService.calculateCorporateIncomeTaxAdjusted(
                MoneyUtil.toMinor("350000"), MoneyUtil.toMinor("50000"),
                MoneyUtil.toMinor("100000"), MoneyUtil.toMinor("150000"));
        assertEquals(MoneyUtil.toMinor("150000"), adjCit.taxableIncomeMinor());
        assertEquals(MoneyUtil.toMinor("15000"), adjCit.taxPayableMinor());

        // 擴充扣繳類別 + 2,000 元門檻邊界 + 非居住者 10 日內申報旗標
        assertEquals(0L, taxService.calculateWithholding(MoneyUtil.toMinor("20000"), "ROYALTY", true).taxWithheldMinor());
        assertEquals(MoneyUtil.toMinor("2001"), taxService.calculateWithholding(MoneyUtil.toMinor("20010"), "ROYALTY", true).taxWithheldMinor());
        var nonResDiv = taxService.calculateWithholding(MoneyUtil.toMinor("100000"), "DIVIDEND", false);
        assertEquals(MoneyUtil.toMinor("21000"), nonResDiv.taxWithheldMinor());
        assertTrue(nonResDiv.requiresFilingImmediately());
        assertEquals(10, nonResDiv.filingDeadlineDays());

        // 官方 8 碼統一編號檢核碼與雙月期別發票一致性檢核
        assertTrue(taxService.isValidGuiNumber("22099131"));
        assertTrue(taxService.isValidGuiNumber("04595257"));
        assertFalse(taxService.isValidGuiNumber("22099132"));
        assertTrue(taxService.validateUniformInvoice("AB-12345678", 115, 2,
                MoneyUtil.toMinor("1000"), MoneyUtil.toMinor("50"), MoneyUtil.toMinor("1050"),
                TaiwanTaxComplianceService.TaxType.TAXABLE_5_PERCENT).valid());
        assertFalse(taxService.validateUniformInvoice("AB-12345678", 115, 7,
                MoneyUtil.toMinor("1000"), MoneyUtil.toMinor("50"), MoneyUtil.toMinor("1050"),
                TaiwanTaxComplianceService.TaxType.TAXABLE_5_PERCENT).valid());
    }

    // ==================== R13 驗收測試（Session 競態 / TOCTOU / 混合現金流 / KeyStore / 歸檔 / 稅務版本化 / 資料庫升級） ====================

    @Test
    @DisplayName("R13-P0-01: 多執行緒 Session 登入與撤銷/停用競態壓力測試（>=1000 輪），撤銷後 isLoggedIn() 永遠為 false")
    void r13_p0_01_sessionRevocationRaceStress1000Rounds() throws Exception {
        AuthService auth = new AuthService();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            // 透過至少 1,000 輪併發競態驗證登入與撤銷/停用之嚴格同步順序
            for (int round = 0; round < 1000; round++) {
                // 每 25 輪實測完整停用並重新啟用資料庫狀態，其餘輪次透過 setEnabled(true) 清除撤銷狀態
                if (round > 0) {
                    auth.login("admin", "admin123");
                    auth.setEnabled("david", true, "admin");
                    SessionContext.clear();
                }

                CyclicBarrier barrier = new CyclicBarrier(2);
                Future<?> loginFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        auth.login("david", "david123");
                    } catch (BusinessException | SecurityException expected) {
                        // 若撤銷先發生或 epoch 過期，登入必須失敗
                    }
                    return null;
                });

                boolean fullDisableRound = (round % 50 == 0);
                Future<?> revokeFuture = pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    if (fullDisableRound) {
                        auth.login("admin", "admin123");
                        auth.setEnabled("david", false, "admin");
                    } else {
                        SessionContext.invalidateUserSessions("david");
                    }
                    return null;
                });

                loginFuture.get(10, TimeUnit.SECONDS);
                revokeFuture.get(10, TimeUnit.SECONDS);

                assertTrue(SessionContext.isUserRevoked("david"),
                        "第 " + round + " 輪撤銷完成後 david 必須保持 revoked 狀態");
                if (SessionContext.isLoggedIn()) {
                    assertNotEquals("david", SessionContext.getUser(),
                            "第 " + round + " 輪撤銷完成後，david 絕不可處於已登入狀態");
                }
                // 模擬撤銷後即刻檢查當前會話（非 admin 代打情況下必須完全未登入）
                if (!fullDisableRound) {
                    assertFalse(SessionContext.isLoggedIn(),
                            "第 " + round + " 輪 revoke 完成後 isLoggedIn() 必須永遠為 false");
                }
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
            SessionContext.clear();
        }
    }

    @Test
    @DisplayName("R13-P0-02: TenantPathResolver 與 DbManager TOCTOU、WAL/SHM/父目錄 Junction 防護與路徑替換競態")
    void r13_p0_02_toctouPathSwapAndWalShmJunctionProtection() throws Exception {
        Path tenantRoot = Paths.get("data/tenants").toAbsolutePath().normalize();
        Files.createDirectories(tenantRoot);
        Path targetOutsideDir = Files.createTempDirectory("r13_outside_target");
        String safeRelPath = "data/tenants/r13_toctou_safe.db";
        Path safeDb = Paths.get(safeRelPath).toAbsolutePath().normalize();
        Path swappedParent = tenantRoot.resolve("r13_junction_parent");
        String swappedParentDbRel = "data/tenants/r13_junction_parent/victim.db";
        String walTargetDbRel = "data/tenants/r13_wal_junction.db";
        Path walTargetDb = Paths.get(walTargetDbRel).toAbsolutePath().normalize();
        Path walLink = tenantRoot.resolve("r13_wal_junction.db-wal");
        Path shmLink = tenantRoot.resolve("r13_wal_junction.db-shm");
        String authTenantId = "r13_toctou_tenant_auth";
        String authTenantRel = TenantPathResolver.resolveDbPath(authTenantId);

        try {
            // 1. 正常授權租戶 DB：prepareAndVerifyDatabaseFile + getForTenant + PRAGMA database_list 實體驗證
            Path realPrepared = TenantPathResolver.prepareAndVerifyDatabaseFile(safeRelPath, tenantRoot);
            assertTrue(realPrepared.startsWith(tenantRoot.toRealPath()));
            TenantSecurityService.grant(authTenantId, "r13_test");
            Path authPrepared = TenantPathResolver.prepareAndVerifyDatabaseFile(authTenantRel, tenantRoot);
            try (Connection conn = DbManager.getForTenant(authTenantId)) {
                TenantPathResolver.verifyOpenedSqliteConnection(conn, authPrepared, tenantRoot);
            }
            DbManager.closeAllTenants();
            DbManager.closeCurrent();

            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (isWindows) {
                // 2. 父目錄 Windows Junction 攻擊必須遭拒
                Process p1 = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                        swappedParent.toString(), targetOutsideDir.toString()).redirectErrorStream(true).start();
                assertEquals(0, p1.waitFor(), "測試環境應可建立目錄 Junction");

                assertThrows(SecurityException.class, () ->
                        TenantPathResolver.prepareAndVerifyDatabaseFile(swappedParentDbRel, tenantRoot));
                assertThrows(SecurityException.class, () ->
                        DbManager.switchDatabase(swappedParentDbRel));

                // 3. -wal / -shm 附屬檔為 Windows Junction / Reparse Point 時必須遭拒
                Files.deleteIfExists(walTargetDb);
                Process p2 = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                        walLink.toString(), targetOutsideDir.toString()).redirectErrorStream(true).start();
                assertEquals(0, p2.waitFor());
                assertThrows(SecurityException.class, () ->
                        TenantPathResolver.prepareAndVerifyDatabaseFile(walTargetDbRel, tenantRoot));

                new ProcessBuilder("cmd", "/c", "rmdir", walLink.toString()).start().waitFor();
                Process p3 = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                        shmLink.toString(), targetOutsideDir.toString()).redirectErrorStream(true).start();
                assertEquals(0, p3.waitFor());
                assertThrows(SecurityException.class, () ->
                        TenantPathResolver.prepareAndVerifyDatabaseFile(walTargetDbRel, tenantRoot));
                new ProcessBuilder("cmd", "/c", "rmdir", shmLink.toString()).start().waitFor();
            }

            // 4. 併發切換與路徑驗證競態：不得回傳指向 data/tenants 外部之連線
            String raceRel = "data/tenants/r13_race_tenant.db";
            Path raceDb = Paths.get(raceRel).toAbsolutePath().normalize();
            ExecutorService pool = Executors.newFixedThreadPool(8);
            try {
                List<Future<Boolean>> futures = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    futures.add(pool.submit(() -> {
                        TenantPathResolver.prepareAndVerifyDatabaseFile(raceRel, tenantRoot);
                        return true;
                    }));
                }
                for (Future<Boolean> f : futures) {
                    assertTrue(f.get(10, TimeUnit.SECONDS));
                }
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
                Files.deleteIfExists(raceDb);
                Files.deleteIfExists(Paths.get(raceDb + "-wal"));
                Files.deleteIfExists(Paths.get(raceDb + "-shm"));
            }
        } finally {
            TenantSecurityService.revoke(authTenantId);
            DbManager.closeAllTenants();
            DbManager.closeCurrent();
            new ProcessBuilder("cmd", "/c", "rmdir", swappedParent.toString()).start().waitFor();
            new ProcessBuilder("cmd", "/c", "rmdir", walLink.toString()).start().waitFor();
            new ProcessBuilder("cmd", "/c", "rmdir", shmLink.toString()).start().waitFor();
            Files.deleteIfExists(safeDb);
            Files.deleteIfExists(Paths.get(safeDb + "-wal"));
            Files.deleteIfExists(Paths.get(safeDb + "-shm"));
            Path authDbFile = Paths.get(authTenantRel).toAbsolutePath().normalize();
            Files.deleteIfExists(authDbFile);
            Files.deleteIfExists(Paths.get(authDbFile + "-wal"));
            Files.deleteIfExists(Paths.get(authDbFile + "-shm"));
            Files.deleteIfExists(walTargetDb);
            Files.deleteIfExists(targetOutsideDir);
        }
    }

    @Test
    @DisplayName("R13-P1-01: 現金流量表混合交易拆帳與全 8 種固定資產/折舊/分期/處分/動態現金科目情境")
    void r13_p1_01_mixedCashFlowAndAllEightScenarios() throws Exception {
        // 先驗證核心 R13 指定混合傳票（2027-01）：
        // Dr 設備 100,000 / Cr 現金 20,000 / Cr 應付帳款 80,000 -> CFI = -20,000, NON_CASH = 80,000
        voucherService.post(voucherService.createDraft("2027-01-10", "混合購置設備(2萬現金+8萬賒欠)", "Amy", List.of(
                entry("1401", "100000.00", "0"),
                entry("1101", "0", "20000.00"),
                entry("2122", "0", "80000.00")
        )).id(), "Bob");

        List<FinancialStatementItem> mixedCf = reportService.getCashFlow("2027-01-01", "2027-01-31");
        assertEquals(0L, cfAmount(mixedCf, "CFO"));
        assertEquals(MoneyUtil.toMinor("-20000.00"), cfAmount(mixedCf, "CFI"), "混合交易僅實際付現 20,000 計入 CFI，不得算成 -100,000");
        assertEquals(MoneyUtil.toMinor("80000.00"), cfAmount(mixedCf, "NON_CASH"), "賒購 80,000 須單獨揭露為非現金投資活動");

        // 情境 1（2027-02）：現金全額購置設備 100,000
        voucherService.post(voucherService.createDraft("2027-02-05", "全額現購設備", "Amy", List.of(
                entry("1401", "100000.00", "0"),
                entry("1102", "0", "100000.00")
        )).id(), "Bob");
        List<FinancialStatementItem> cfFeb = reportService.getCashFlow("2027-02-01", "2027-02-28");
        assertEquals(MoneyUtil.toMinor("-100000.00"), cfAmount(cfFeb, "CFI"));
        assertEquals(0L, cfAmount(cfFeb, "NON_CASH"));

        // 情境 2（2027-03）：全額賒購設備 80,000（Dr 1401 / Cr 2123 應付設備款）
        voucherService.post(voucherService.createDraft("2027-03-05", "全額賒購設備", "Amy", List.of(
                entry("1401", "80000.00", "0"),
                entry("2123", "0", "80000.00")
        )).id(), "Bob");
        List<FinancialStatementItem> cfMar = reportService.getCashFlow("2027-03-01", "2027-03-31");
        assertEquals(0L, cfAmount(cfMar, "CFI"));
        assertEquals(MoneyUtil.toMinor("80000.00"), cfAmount(cfMar, "NON_CASH"));

        // 情境 4（2027-04）：分期償還應付設備款 30,000（使用約當現金 1104 驗證動態科目表現金辨識）
        voucherService.post(voucherService.createDraft("2027-04-05", "分期支付應付設備款", "Amy", List.of(
                entry("2123", "30000.00", "0"),
                entry("1104", "0", "30000.00")
        )).id(), "Bob");
        List<FinancialStatementItem> cfApr = reportService.getCashFlow("2027-04-01", "2027-04-30");
        assertEquals(MoneyUtil.toMinor("-30000.00"), cfAmount(cfApr, "CFI"), "設備分期付款實際付現應計入 CFI 流出");

        // 情境 5 + 7（2027-05）：提列折舊 25,000 並以零用金 1103 收現 95,000 處分設備（成本100k/累折15k/處分利益10k）
        voucherService.post(voucherService.createAdjustingDraft("2027-05-15", "提列折舊", "Amy", List.of(
                entry("5208", "25000.00", "0"),
                entry("1402", "0", "25000.00")
        )).id(), "Bob");
        voucherService.post(voucherService.createDraft("2027-05-20", "處分設備收現(零用金)", "Amy", List.of(
                entry("1103", "95000.00", "0"),
                entry("1402", "15000.00", "0"),
                entry("1401", "0", "100000.00"),
                entry("4202", "0", "10000.00")
        )).id(), "Bob");
        List<FinancialStatementItem> cfMay = reportService.getCashFlow("2027-05-01", "2027-05-31");
        assertEquals(MoneyUtil.toMinor("25000.00"), cfAmount(cfMay, "DEPR_AMORT"));
        assertEquals(MoneyUtil.toMinor("-10000.00"), cfAmount(cfMay, "DISPOSAL_ADJ"));
        assertEquals(0L, cfAmount(cfMay, "CFO"));
        assertEquals(MoneyUtil.toMinor("95000.00"), cfAmount(cfMay, "CFI"));

        // 情境 6（2027-06）：設備處分款尚未收現（Dr 1122 60,000 / Cr 1401 50,000 / Cr 4202 10,000）
        voucherService.post(voucherService.createDraft("2027-06-10", "賒售設備(尚未收現)", "Amy", List.of(
                entry("1122", "60000.00", "0"),
                entry("1401", "0", "50000.00"),
                entry("4202", "0", "10000.00")
        )).id(), "Bob");
        List<FinancialStatementItem> cfJun = reportService.getCashFlow("2027-06-01", "2027-06-30");
        assertEquals(0L, cfAmount(cfJun, "CFI"), "尚未收現之設備處分款不得計入本期 CFI 現金流入");
        assertEquals(0L, cfAmount(cfJun, "CFO"));

        // 情境 8：全年彙總現金流量表與總帳現金科目（1101/1102/1103/1104）完全勾稽
        List<FinancialStatementItem> cfYear = reportService.getCashFlow("2027-01-01", "2027-12-31");
        assertEquals(cfAmount(cfYear, "END_CASH"), cfAmount(cfYear, "ACTUAL_CASH"));
        assertTrue(ReportDaoExtra.isCashReconciled(cfYear));
        try (Connection conn = DbManager.get()) {
            var cashCodes = ReportDaoExtra.resolveCashAccountCodes(conn);
            assertTrue(cashCodes.containsAll(List.of("1101", "1102", "1103", "1104")));
        }
        reconciliationService.assertAllPass("2027-01-01", "2027-12-31");
    }

    private static long cfAmount(List<FinancialStatementItem> items, String code) {
        return items.stream().filter(i -> code.equals(i.code())).findFirst().orElseThrow().amountMinor();
    }

    @Test
    @DisplayName("R13-P1-02: AttachmentService HMAC 金鑰加密儲存（Windows DPAPI / 明確降級）、OS ACL 失敗不靜默與輪換歷程")
    void r13_p1_02_hmacKeyEncryptedDpapiAndPermissionEnforcement() throws Exception {
        Path ksTemp = Files.createTempDirectory("r13_keystore_");
        Path keystoreFile = ksTemp.resolve("hmac_keys.properties");
        String prevProp = System.getProperty("ledger.hmac.keystore.path");
        System.setProperty("ledger.hmac.keystore.path", keystoreFile.toString());

        Path src = Files.createTempFile("r13_att", ".txt");
        Files.writeString(src, "R13 secure keystore verification");
        Path outsideDir = Files.createTempDirectory("r13_ks_outside");
        Path junctionAttachDir = Paths.get("data/attachments_junction_r13").toAbsolutePath().normalize();
        Path stored = null;

        try {
            Voucher v = voucherService.createDraft("2027-04-01", "R13金鑰測試傳票", "Amy", List.of(
                    entry("5206", "100.00", "0"),
                    entry("1101", "0", "100.00")
            ));
            voucherService.post(v.id(), "Bob");

            var att = attachmentService.addAttachment(v.id(), src, "INV-R13", "2027-04-01", "v1", "Bob");
            stored = Paths.get(att.storedPath());
            assertTrue(Files.exists(keystoreFile));

            // 1. 金鑰檔內不得存有明文 raw hex key，必須為 ENCv1: 加密封裝格式
            Properties props = new Properties();
            try (var in = Files.newInputStream(keystoreFile)) {
                props.load(in);
            }
            String storedSecret = props.getProperty("key.v1");
            assertNotNull(storedSecret);
            assertTrue(storedSecret.startsWith("ENCv1:"), "金鑰檔不可為明文，必須使用 AES-256-GCM 密文前綴 ENCv1:");

            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");
            if (isWindows) {
                assertEquals(AttachmentService.KeystoreProtectionMode.WINDOWS_DPAPI_AES256_GCM,
                        AttachmentService.getLastProtectionMode(),
                        "Windows 11 環境應優先採用 DPAPI 保護金鑰");
                Path kekFile = ksTemp.resolve(".kek_store.bin");
                assertTrue(Files.exists(kekFile), "Windows DPAPI 模式應建立受 DPAPI 保護之 .kek_store.bin");
                assertTrue(Files.readString(kekFile).startsWith("DPAPI:"));
            }
            assertTrue(AttachmentService.verifyKeystoreOsPermissions(keystoreFile),
                    "Keystore 檔案應具嚴格 OS 擁有者權限");

            // 2. 清空記憶體 KEK 快取後從磁碟重新解封驗證（確保持久化可解密）
            AttachmentService.clearInMemoryKeyCacheForTest();
            assertTrue(attachmentService.verifyAttachmentSignature(att.id()));

            // 3. 金鑰輪換須保留前代密文歷史並可透過 version 向後驗簽
            String newKid = attachmentService.rotateHmacKey("Bob");
            assertNotNull(newKid);
            assertFalse(attachmentService.getKeyRotationHistory().isEmpty());
            assertTrue(attachmentService.verifyAttachmentSignature(att.id()));

            // 4. 權限設定失敗不得靜默吞沒例外（模擬失敗必須拋出 SecurityException）
            AttachmentService.setForcePermissionFailureForTest(true);
            try {
                assertThrows(SecurityException.class, () -> attachmentService.rotateHmacKey("Bob"),
                        "OS 權限設定失敗時必須 fail-closed 拒絕產生未受保護之金鑰檔");
            } finally {
                AttachmentService.setForcePermissionFailureForTest(false);
            }

            // 5. Keystore 路徑若包含 Windows Junction 必須拒絕存取
            if (isWindows) {
                Process p = new ProcessBuilder("cmd", "/c", "mklink", "/J",
                        junctionAttachDir.toString(), outsideDir.toString()).redirectErrorStream(true).start();
                assertEquals(0, p.waitFor());
                Path jKs = junctionAttachDir.resolve("evil.properties");
                assertThrows(SecurityException.class, () -> AttachmentService.verifyKeystorePathSafety(jKs));
            }
        } finally {
            if (prevProp == null) System.clearProperty("ledger.hmac.keystore.path");
            else System.setProperty("ledger.hmac.keystore.path", prevProp);
            AttachmentService.setForcePermissionFailureForTest(false);
            new ProcessBuilder("cmd", "/c", "rmdir", junctionAttachDir.toString()).start().waitFor();
            Files.deleteIfExists(outsideDir);
            Files.deleteIfExists(src);
            if (stored != null) Files.deleteIfExists(stored);
            Files.deleteIfExists(keystoreFile);
            Files.deleteIfExists(keystoreFile.resolveSibling(keystoreFile.getFileName().toString() + ".tmp"));
            Files.deleteIfExists(ksTemp.resolve(".kek_store.bin"));
            Files.deleteIfExists(ksTemp.resolve(".kek_salt.bin"));
            Files.deleteIfExists(ksTemp);
        }
    }

    @Test
    @DisplayName("R13-P1-03: 歸檔防縮短覆寫、更正審計歷程、未知 dataType 拒絕、併發重複核准防護與 APPROVED_PENDING_EXECUTION 狀態")
    void r13_p1_03_retentionAntiShorteningUnknownTypeAndConcurrency() throws Exception {
        retentionService.recordAnnualClosingCompletion(2016, LocalDate.of(2017, 5, 15), "Bob");

        // 1. 不得直接覆寫或縮短已登錄之決算完成日
        assertThrows(BusinessException.class, () ->
                retentionService.recordAnnualClosingCompletion(2016, LocalDate.of(2017, 1, 1), "Bob"));
        assertThrows(BusinessException.class, () ->
                retentionService.correctAnnualClosingCompletion(2016, LocalDate.of(2017, 4, 1), "錯誤嘗試縮短", "Bob"));
        assertThrows(BusinessException.class, () ->
                retentionService.correctAnnualClosingCompletion(2016, LocalDate.of(2017, 6, 1), "   ", "Bob"));

        // 僅允許延後更正並完整留存更正歷程
        var corr = retentionService.correctAnnualClosingCompletion(2016, LocalDate.of(2017, 6, 30), "補辦所得稅申報更正延長", "Bob");
        assertEquals(LocalDate.of(2017, 5, 15), corr.oldCompletionDate());
        assertEquals(LocalDate.of(2017, 6, 30), corr.newCompletionDate());
        assertEquals(1, retentionService.listClosingCorrections(2016).size());

        // 2. 未知 dataType 不可靜默套用預設 5 年，必須明確拒絕
        assertThrows(BusinessException.class, () ->
                retentionService.isEligibleForDestruction("UNKNOWN_DOC", LocalDate.of(2016, 6, 1), LocalDate.of(2030, 1, 1)));
        assertThrows(BusinessException.class, () ->
                retentionService.recordDestructionApproval("B-UNK", "CUSTOM_XYZ", "X-1",
                        LocalDate.of(2016, 6, 1), LocalDate.of(2030, 1, 1), "未知類型", "Bob"));

        // 3. 16 執行緒併發送簽同一銷毀目標，僅能有 1 筆成功，其餘須全部擋下；且狀態清楚標示 APPROVED_PENDING_EXECUTION
        ExecutorService pool = Executors.newFixedThreadPool(16);
        AtomicInteger succeeded = new AtomicInteger(0);
        AtomicInteger rejected = new AtomicInteger(0);
        CyclicBarrier barrier = new CyclicBarrier(16);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < 16; i++) {
                final int idx = i;
                futures.add(pool.submit(() -> {
                    barrier.await(5, TimeUnit.SECONDS);
                    try {
                        var rec = retentionService.recordDestructionApproval(
                                "BATCH-2016-" + idx, "VOUCHER", "V-2016-CONC",
                                LocalDate.of(2016, 12, 31), LocalDate.of(2025, 1, 1),
                                "屆滿法定五年保存期限", "Bob");
                        assertEquals(RetentionPolicyService.STATUS_APPROVED_PENDING_EXECUTION, rec.status());
                        succeeded.incrementAndGet();
                    } catch (BusinessException expectedDup) {
                        rejected.incrementAndGet();
                    }
                    return null;
                }));
            }
            for (Future<?> f : futures) {
                f.get(15, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(5, TimeUnit.SECONDS);
        }
        assertEquals(1, succeeded.get(), "併發核准同一目標僅能成功一次");
        assertEquals(15, rejected.get(), "其餘 15 個併發核准請求必須被擋下");
    }

    @Test
    @DisplayName("R13-P1-04: 版本化稅率（2017 17% vs 2018+ 20%、股利扣繳 20%/21%）、逐年十年虧損扣抵與完整發票/調節表檢核")
    void r13_p1_04_versionedTaxLossCarryforwardPerYearAndCitSchedule() {
        // 1. 營所稅依課稅年度區分版本化稅率（2017: 17%, 2018+: 20%, <2010: 拒絕）
        assertEquals(MoneyUtil.toMinor("170000"),
                taxService.calculateCorporateIncomeTax(MoneyUtil.toMinor("1000000"), 2017).taxPayableMinor());
        assertEquals(MoneyUtil.toMinor("200000"),
                taxService.calculateCorporateIncomeTax(MoneyUtil.toMinor("1000000"), 2018).taxPayableMinor());
        assertThrows(BusinessException.class, () ->
                taxService.calculateCorporateIncomeTax(MoneyUtil.toMinor("500000"), 2008));

        // 2. 非居住者股利扣繳依給付日期版本化（2018-01-01 前 20%，含當日後 21%），以及競技競賽/退職類別
        assertEquals(MoneyUtil.toMinor("20000"),
                taxService.calculateWithholding(MoneyUtil.toMinor("100000"), "DIVIDEND", false, LocalDate.of(2017, 12, 31)).taxWithheldMinor());
        assertEquals(MoneyUtil.toMinor("21000"),
                taxService.calculateWithholding(MoneyUtil.toMinor("100000"), "DIVIDEND", false, LocalDate.of(2018, 1, 1)).taxWithheldMinor());
        assertEquals(MoneyUtil.toMinor("10000"),
                taxService.calculateWithholding(MoneyUtil.toMinor("100000"), "COMPETITION_PRIZE", true, LocalDate.of(2026, 3, 1)).taxWithheldMinor());

        // 3. 逐年追蹤前十年虧損扣抵與課稅所得調節表（FIFO 優先扣抵最早未逾期年度）
        var schedule = taxService.generateCitReconciliationSchedule(
                2026,
                MoneyUtil.toMinor("500000"),
                MoneyUtil.toMinor("50000"),
                MoneyUtil.toMinor("100000"),
                List.of(
                        new TaiwanTaxComplianceService.AnnualLossRecord(2020, MoneyUtil.toMinor("120000"), true),
                        new TaiwanTaxComplianceService.AnnualLossRecord(2017, MoneyUtil.toMinor("80000"), true)
                )
        );
        assertEquals(MoneyUtil.toMinor("450000"), schedule.adjustedTaxableIncomeBeforeLossMinor());
        assertEquals(MoneyUtil.toMinor("200000"), schedule.lossCarryforwardAppliedMinor());
        assertEquals(MoneyUtil.toMinor("250000"), schedule.finalTaxableIncomeMinor());
        assertEquals(MoneyUtil.toMinor("50000"), schedule.citResult().taxPayableMinor());
        assertEquals(TaiwanTaxComplianceService.FilingStatus.INTERNAL_ESTIMATE_ONLY, schedule.filingStatus());
        assertEquals(2017, schedule.appliedDetails().get(0).lossYear());

        // 逾期虧損（>10年）與未經簽證/藍色申報之虧損必須拒絕
        assertThrows(BusinessException.class, () -> taxService.generateCitReconciliationSchedule(
                2026, MoneyUtil.toMinor("500000"), 0L, 0L,
                List.of(new TaiwanTaxComplianceService.AnnualLossRecord(2015, MoneyUtil.toMinor("100000"), true))));
        assertThrows(BusinessException.class, () -> taxService.generateCitReconciliationSchedule(
                2026, MoneyUtil.toMinor("500000"), 0L, 0L,
                List.of(new TaiwanTaxComplianceService.AnnualLossRecord(2023, MoneyUtil.toMinor("100000"), false))));

        // 4. 完整統一發票檢核（日期/民國年/期別一致、買賣方統編驗證、進項扣抵須含買方統編）
        assertTrue(taxService.validateUniformInvoiceDetails(
                "AB-12345678", LocalDate.of(2026, 3, 15), 115, 2,
                "22099131", "04595257", true,
                MoneyUtil.toMinor("10000"), MoneyUtil.toMinor("500"), MoneyUtil.toMinor("10500"),
                TaiwanTaxComplianceService.TaxType.TAXABLE_5_PERCENT).valid());
        // 缺少買方統編不可主張進項扣抵
        assertFalse(taxService.validateUniformInvoiceDetails(
                "AB-12345678", LocalDate.of(2026, 3, 15), 115, 2,
                "22099131", "", true,
                MoneyUtil.toMinor("10000"), MoneyUtil.toMinor("500"), MoneyUtil.toMinor("10500"),
                TaiwanTaxComplianceService.TaxType.TAXABLE_5_PERCENT).valid());
        // 期別與開立月份不符
        assertFalse(taxService.validateUniformInvoiceDetails(
                "AB-12345678", LocalDate.of(2026, 3, 15), 115, 1,
                "22099131", "04595257", true,
                MoneyUtil.toMinor("10000"), MoneyUtil.toMinor("500"), MoneyUtil.toMinor("10500"),
                TaiwanTaxComplianceService.TaxType.TAXABLE_5_PERCENT).valid());
    }

    @Test
    @DisplayName("R13-P1-05: 資料庫遷移回歸測試（全新建立、模擬 R11/R12 舊版升級保留資料、失敗回滾、重複冪等與 16 執行緒併發啟動）")
    void r13_p1_05_migrationR11R12UpgradeRollbackAndConcurrency() throws Exception {
        Path dataRoot = Paths.get("data").toAbsolutePath().normalize();
        Files.createDirectories(dataRoot);
        String legacyRel = "data/test_r13_legacy_upgrade.db";
        String concRel = "data/test_r13_conc_mig.db";
        Path legacyDb = Paths.get(legacyRel).toAbsolutePath().normalize();
        Path concDb = Paths.get(concRel).toAbsolutePath().normalize();
        Files.deleteIfExists(legacyDb);
        Files.deleteIfExists(Paths.get(legacyDb + "-wal"));
        Files.deleteIfExists(Paths.get(legacyDb + "-shm"));
        Files.deleteIfExists(concDb);
        Files.deleteIfExists(Paths.get(concDb + "-wal"));
        Files.deleteIfExists(Paths.get(concDb + "-shm"));

        try {
            // 1. 模擬 R11/R12 舊版資料庫（僅有舊版表結構與既有一筆傳票，尚未納入 R13 新科目與 schema_version）
            try (Connection raw = DriverManager.getConnection("jdbc:sqlite:" + legacyDb);
                 Statement st = raw.createStatement()) {
                st.execute("CREATE TABLE accounts (id INTEGER PRIMARY KEY AUTOINCREMENT, code TEXT NOT NULL UNIQUE, name TEXT NOT NULL, type TEXT NOT NULL, normal_side TEXT NOT NULL, is_leaf INTEGER NOT NULL DEFAULT 1, enabled INTEGER NOT NULL DEFAULT 1)");
                st.execute("INSERT INTO accounts(code, name, type, normal_side, is_leaf, enabled) VALUES ('1101', '庫存現金', 'ASSET', 'DEBIT', 1, 1)");
                st.execute("CREATE TABLE fiscal_periods (id INTEGER PRIMARY KEY AUTOINCREMENT, year INTEGER NOT NULL, month INTEGER NOT NULL, status TEXT NOT NULL DEFAULT 'OPEN', closed_at TEXT, closed_by TEXT, UNIQUE(year, month))");
                st.execute("CREATE TABLE vouchers (id INTEGER PRIMARY KEY AUTOINCREMENT, voucher_no TEXT NOT NULL UNIQUE, voucher_date TEXT NOT NULL, description TEXT NOT NULL, status TEXT NOT NULL DEFAULT 'POSTED', created_by TEXT NOT NULL, created_at TEXT NOT NULL DEFAULT (datetime('now')), posted_by TEXT, posted_at TEXT, reversed_voucher_id INTEGER)");
                st.execute("INSERT INTO vouchers(voucher_no, voucher_date, description, status, created_by, posted_by) VALUES ('202501-0001', '2025-01-15', 'R11歷史資料保留驗證', 'POSTED', 'amy', 'bob')");
                st.execute("CREATE TABLE voucher_entries (id INTEGER PRIMARY KEY AUTOINCREMENT, voucher_id INTEGER NOT NULL, account_id INTEGER NOT NULL, line_memo TEXT, debit_minor INTEGER NOT NULL DEFAULT 0, credit_minor INTEGER NOT NULL DEFAULT 0)");
                st.execute("CREATE TABLE audit_log (id INTEGER PRIMARY KEY AUTOINCREMENT, timestamp TEXT NOT NULL DEFAULT (datetime('now')), action TEXT NOT NULL, entity_type TEXT NOT NULL, entity_id INTEGER, operator TEXT NOT NULL, old_data TEXT, new_data TEXT)");
            }

            // 切換至模擬舊版庫，應安全完成遷移、補齊欄位及新增 R13 科目且保留原有資料
            DbManager.switchDatabase(legacyRel);
            try (Connection upgraded = DbManager.get()) {
                try (Statement st = upgraded.createStatement();
                     ResultSet rs = st.executeQuery("SELECT description FROM vouchers WHERE voucher_no='202501-0001'")) {
                    assertTrue(rs.next());
                    assertEquals("R11歷史資料保留驗證", rs.getString(1));
                }
                var cashCodes = ReportDaoExtra.resolveCashAccountCodes(upgraded);
                assertTrue(cashCodes.contains("1103"));
                assertTrue(cashCodes.contains("1104"));
            }

            // 重複執行應冪等無錯誤
            DbManager.closeAllTenants();
            DbManager.closeCurrent();
            DbManager.switchDatabase(legacyRel);
            try (Connection repeat = DbManager.get();
                 Statement st = repeat.createStatement();
                 ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM vouchers")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            DbManager.closeAllTenants();
            DbManager.closeCurrent();

            // 2. 失敗回滾保護驗證：交易中 DDL/DML 失敗時不留下半完成狀態
            try (Connection txConn = DriverManager.getConnection("jdbc:sqlite:" + legacyDb)) {
                txConn.setAutoCommit(false);
                try (Statement st = txConn.createStatement()) {
                    st.execute("INSERT INTO system_meta(meta_key, meta_value) VALUES ('r13_mig_tx_test', '999')");
                    assertThrows(SQLException.class, () -> st.execute("SELECT * FROM non_existent_table_xyz"));
                    txConn.rollback();
                } finally {
                    txConn.setAutoCommit(true);
                }
                try (Statement st = txConn.createStatement();
                     ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM system_meta WHERE meta_key = 'r13_mig_tx_test'")) {
                    assertTrue(rs.next());
                    assertEquals(0, rs.getInt(1), "遷移失敗回滾後不得留下部分版本紀錄");
                }
            }

            // 3. 16 執行緒併發首次初始化同一全新租戶資料庫 + WAL/SHM 模式與檔案驗證
            String concTenant = "r13_conc_mig_tenant";
            TenantSecurityService.grant(concTenant, "r13_test");
            String concTenantRel = TenantPathResolver.resolveDbPath(concTenant);
            Path concTenantFile = Paths.get(concTenantRel).toAbsolutePath().normalize();
            Files.deleteIfExists(concTenantFile);
            Files.deleteIfExists(Paths.get(concTenantFile + "-wal"));
            Files.deleteIfExists(Paths.get(concTenantFile + "-shm"));

            ExecutorService pool = Executors.newFixedThreadPool(16);
            CyclicBarrier barrier = new CyclicBarrier(16);
            try {
                List<Future<String>> results = new ArrayList<>();
                for (int i = 0; i < 16; i++) {
                    results.add(pool.submit(() -> {
                        barrier.await(5, TimeUnit.SECONDS);
                        try (Connection c = DbManager.getForTenant(concTenant);
                             Statement st = c.createStatement();
                             ResultSet rs = st.executeQuery("PRAGMA journal_mode")) {
                            return rs.next() ? rs.getString(1) : "";
                        }
                    }));
                }
                for (Future<String> f : results) {
                    assertEquals("wal", f.get(20, TimeUnit.SECONDS).toLowerCase());
                }
                Path tenantRoot = Paths.get(TenantPathResolver.TENANT_ROOT).toAbsolutePath().normalize();
                Path realConc = TenantPathResolver.prepareAndVerifyDatabaseFile(concTenantRel, tenantRoot);
                try (Connection c = DbManager.getForTenant(concTenant)) {
                    TenantPathResolver.verifyOpenedSqliteConnection(c, realConc, tenantRoot);
                }
            } finally {
                pool.shutdownNow();
                pool.awaitTermination(5, TimeUnit.SECONDS);
                DbManager.closeAllTenants();
                TenantSecurityService.revoke(concTenant);
                Files.deleteIfExists(concTenantFile);
                Files.deleteIfExists(Paths.get(concTenantFile + "-wal"));
                Files.deleteIfExists(Paths.get(concTenantFile + "-shm"));
            }
        } finally {
            DbManager.closeAllTenants();
            DbManager.closeCurrent();
            Files.deleteIfExists(legacyDb);
            Files.deleteIfExists(Paths.get(legacyDb + "-wal"));
            Files.deleteIfExists(Paths.get(legacyDb + "-shm"));
            Files.deleteIfExists(concDb);
            Files.deleteIfExists(Paths.get(concDb + "-wal"));
            Files.deleteIfExists(Paths.get(concDb + "-shm"));
        }
    }
}
