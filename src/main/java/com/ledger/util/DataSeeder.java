package com.ledger.util;

import com.ledger.config.DbManager;
import com.ledger.dao.AccountDao;
import com.ledger.dao.FiscalPeriodDao;
import com.ledger.model.Account;
import com.ledger.service.ClosingService;
import com.ledger.service.VoucherService;
import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;

public class DataSeeder {

    public static void main(String[] args) throws Exception {
        seedDatabase("data/client_A.db", "宏達科技股份有限公司");
        seedDatabase("data/client_B.db", "大安聯合顧問有限公司");
        System.out.println("Demo 資料庫種子資料生成完成！");
    }

    public static void seedDatabase(String dbPath, String clientName) throws Exception {
        DbManager.switchDatabase(dbPath);
        AccountDao accDao = new AccountDao();
        FiscalPeriodDao periodDao = new FiscalPeriodDao();
        VoucherService voucherService = new VoucherService();
        ClosingService closingService = new ClosingService();

        // 建立 10 月與 11 月傳票
        Account cash = accDao.findByCode("1101").orElseThrow();
        Account bank = accDao.findByCode("1102").orElseThrow();
        Account ar = accDao.findByCode("1122").orElseThrow();
        Account inv = accDao.findByCode("1201").orElseThrow();
        Account ap = accDao.findByCode("2122").orElseThrow();
        Account capital = accDao.findByCode("3101").orElseThrow();
        Account sales = accDao.findByCode("4101").orElseThrow();
        Account cogs = accDao.findByCode("5101").orElseThrow();
        Account salary = accDao.findByCode("5201").orElseThrow();
        Account rent = accDao.findByCode("5202").orElseThrow();

        // 1. 創立資本注資 (10月)
        var v1 = voucherService.createDraft("2026-10-01", "股東注資成立公司 " + clientName, "Amy", List.of(
                new com.ledger.model.VoucherEntry(1, bank.id(), "銀行存款", new BigDecimal("5000000.00"), BigDecimal.ZERO),
                new com.ledger.model.VoucherEntry(2, capital.id(), "普通股股本", BigDecimal.ZERO, new BigDecimal("5000000.00"))
        ));
        voucherService.post(v1.id(), "Bob");

        // 2. 支付辦公室租金與保證金 (10月)
        var v2 = voucherService.createDraft("2026-10-05", "支付辦公室 10 月份租金", "Amy", List.of(
                new com.ledger.model.VoucherEntry(1, rent.id(), "10月份辦公室租金", new BigDecimal("60000.00"), BigDecimal.ZERO),
                new com.ledger.model.VoucherEntry(2, bank.id(), "銀行轉帳支付租金", BigDecimal.ZERO, new BigDecimal("60000.00"))
        ));
        voucherService.post(v2.id(), "Bob");

        // 3. 採購電腦設備與首批商品存貨 (10月)
        var v3 = voucherService.createDraft("2026-10-10", "採購伺服器與高階商用存貨", "Amy", List.of(
                new com.ledger.model.VoucherEntry(1, inv.id(), "首批商品進貨", new BigDecimal("250000.00"), BigDecimal.ZERO),
                new com.ledger.model.VoucherEntry(2, ap.id(), "應付供應商貨款", BigDecimal.ZERO, new BigDecimal("250000.00"))
        ));
        voucherService.post(v3.id(), "Bob");

        // 4. 銷售出貨給客戶 (10月)
        var v4 = voucherService.createDraft("2026-10-20", "銷貨第一期軟硬體專案給客戶", "Amy", List.of(
                new com.ledger.model.VoucherEntry(1, ar.id(), "應收帳款", new BigDecimal("480000.00"), BigDecimal.ZERO),
                new com.ledger.model.VoucherEntry(2, sales.id(), "銷貨收入", BigDecimal.ZERO, new BigDecimal("480000.00"))
        ));
        voucherService.post(v4.id(), "Bob");

        // 5. 10 月發放員工薪資
        var v5 = voucherService.createDraft("2026-10-31", "發放 10 月份同仁薪資", "Amy", List.of(
                new com.ledger.model.VoucherEntry(1, salary.id(), "十月份薪資支出", new BigDecimal("120000.00"), BigDecimal.ZERO),
                new com.ledger.model.VoucherEntry(2, bank.id(), "銀行薪資轉帳", BigDecimal.ZERO, new BigDecimal("120000.00"))
        ));
        voucherService.post(v5.id(), "Bob");

        // 結帳 10 月份
        closingService.closePeriod(2026, 10, "Bob");

        // 6. 11 月客戶匯入部分貨款 (未結帳月份)
        var v6 = voucherService.createDraft("2026-11-02", "收回客戶專案款項", "Amy", List.of(
                new com.ledger.model.VoucherEntry(1, bank.id(), "銀行電匯入帳", new BigDecimal("300000.00"), BigDecimal.ZERO),
                new com.ledger.model.VoucherEntry(2, ar.id(), "沖銷應收帳款", BigDecimal.ZERO, new BigDecimal("300000.00"))
        ));
        voucherService.post(v6.id(), "Bob");

        // 7. 11 月購買文具辦公耗材
        var v7 = voucherService.createDraft("2026-11-05", "零星文具與影印紙耗材支出", "Amy", List.of(
                new com.ledger.model.VoucherEntry(1, cash.id(), "零用金支出", BigDecimal.ZERO, new BigDecimal("3500.00")),
                new com.ledger.model.VoucherEntry(2, rent.id(), "文具什項費用", new BigDecimal("3500.00"), BigDecimal.ZERO)
        ));
        // 留作草稿供展示過帳與審核功能
    }
}
