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
                SessionContext.setUser("Bob", "會計師 Bob");
                assertTrue(SessionContext.isLoggedIn());
                return null;
            }).get(5, TimeUnit.SECONDS);

            // Admin disables Bob on main thread
            SessionContext.setUser("Admin", "系統管理員");
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
            auth.setEnabled("bob", true, "Admin");
            SessionContext.clear();
            pool.shutdownNow();
        }

        // Anti-spoofing across AccountService, OpeningBalanceService, RetentionPolicyService, AttachmentService
        SessionContext.setUser("Amy", "記帳員 Amy");
        try {
            assertThrows(BusinessException.class, () ->
                    new AccountService().createAccount("1199", "測試", "ASSET", "DEBIT", null, "Bob"));
            assertThrows(BusinessException.class, () ->
                    new OpeningBalanceService().importOpening("2027-01-01",
                            List.of(new OpeningBalanceService.OpeningLine("1101", 1000, 0, "")), "Bob"));
            assertThrows(BusinessException.class, () ->
                    retentionService.recordDestructionApproval("VOUCHER", "V-1", "test", "Bob"));
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

        // 保存年限：憑證 5 年，帳簿報表 10 年；若有未結事項不得銷毀
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

        assertThrows(BusinessException.class, () ->
                retentionService.recordDestructionApproval("VOUCHER", "2015", "過期", "Amy"));
        retentionService.recordDestructionApproval("VOUCHER", "2015-BATCH", "已逾法定5年無未結事項", "Bob");
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
}
