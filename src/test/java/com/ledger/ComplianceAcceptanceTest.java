package com.ledger;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.config.TenantPathResolver;
import com.ledger.dao.AccountDao;
import com.ledger.dao.FiscalPeriodDao;
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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
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
}
