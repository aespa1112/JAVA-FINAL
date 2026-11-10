package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.exception.BusinessException;
import com.ledger.model.FinancialStatementItem;
import com.ledger.model.GeneralLedgerItem;
import com.ledger.model.TrialBalanceItem;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 報表自動勾稽，含試算表與總分類帳逐科目核對。
 */
public class ReportReconciliationService {

    public record CheckResult(String name, boolean passed, String detail, long leftMinor, long rightMinor) {}

    public record AccountMatch(
            String accountCode,
            String accountName,
            long tbDebitMinor,
            long tbCreditMinor,
            long glDebitMinor,
            long glCreditMinor,
            boolean matched
    ) {
        public String detail() {
            return String.format("TB借=%d/貸=%d  GL借=%d/貸=%d",
                    tbDebitMinor, tbCreditMinor, glDebitMinor, glCreditMinor);
        }
    }

    private final ReportService reportService = new ReportService();

    public List<CheckResult> reconcile(String startDate, String endDate) {
        List<CheckResult> results = new ArrayList<>();

        List<TrialBalanceItem> tb = reportService.getTrialBalance(startDate, endDate);
        long tbDr = 0, tbCr = 0;
        for (TrialBalanceItem i : tb) {
            tbDr += i.totalDebitMinor();
            tbCr += i.totalCreditMinor();
        }
        results.add(new CheckResult("試算表借貸平衡", tbDr == tbCr,
                String.format("借方=%d 分, 貸方=%d 分", tbDr, tbCr), tbDr, tbCr));

        List<FinancialStatementItem> bs = reportService.getBalanceSheet(endDate);
        long assets = 0, liabEq = 0;
        for (FinancialStatementItem i : bs) {
            if ("TOTAL_ASSET".equals(i.code())) assets = i.amountMinor();
            if ("TOTAL_LIAB_EQUITY".equals(i.code())) liabEq = i.amountMinor();
        }
        results.add(new CheckResult("資產=負債+權益", assets == liabEq,
                String.format("資產=%d, 負債及權益=%d", assets, liabEq), assets, liabEq));

        List<FinancialStatementItem> is = reportService.getIncomeStatement(startDate, endDate);
        long ni = 0;
        for (FinancialStatementItem i : is) {
            if (i.code() != null && (i.code().contains("NET") || "NET_INCOME".equals(i.code()))) {
                ni = i.amountMinor();
            }
            if (i.name() != null && i.name().contains("本期淨利")) ni = i.amountMinor();
        }
        long bsNi = 0;
        for (FinancialStatementItem i : bs) {
            if ("3302".equals(i.code())) bsNi = i.amountMinor();
        }
        results.add(new CheckResult("損益淨利 vs 資負本期損益", ni == bsNi,
                String.format("損益淨利=%d, 3302=%d", ni, bsNi), ni, bsNi));

        List<AccountMatch> matches = reconcileTrialBalanceVsGeneralLedger(startDate, endDate);
        int fail = 0;
        StringBuilder failDetail = new StringBuilder();
        for (AccountMatch m : matches) {
            if (!m.matched()) {
                fail++;
                if (failDetail.length() < 500) {
                    failDetail.append(m.accountCode()).append(' ').append(m.detail()).append("; ");
                }
            }
        }
        results.add(new CheckResult(
                "試算表 vs 總分類帳（逐科目）",
                fail == 0,
                fail == 0
                        ? String.format("全部 %d 個有發生額科目一致", matches.size())
                        : String.format("%d 科不一致: %s", fail, failDetail),
                matches.size() - fail,
                fail
        ));

        return results;
    }

    public List<AccountMatch> reconcileTrialBalanceVsGeneralLedger(String startDate, String endDate) {
        List<TrialBalanceItem> tb = reportService.getTrialBalance(startDate, endDate);
        Map<String, Long> codeToId = loadAccountIds();
        List<AccountMatch> list = new ArrayList<>();

        for (TrialBalanceItem item : tb) {
            if (item.totalDebitMinor() == 0 && item.totalCreditMinor() == 0) {
                continue;
            }
            Long accountId = codeToId.get(item.accountCode());
            if (accountId == null) {
                list.add(new AccountMatch(item.accountCode(), item.accountName(),
                        item.totalDebitMinor(), item.totalCreditMinor(), 0, 0, false));
                continue;
            }
            List<GeneralLedgerItem> gl = reportService.getGeneralLedger(accountId, startDate, endDate);
            long glDr = 0, glCr = 0;
            for (GeneralLedgerItem g : gl) {
                if (g.lineNo() == 0 || "—".equals(g.voucherNo()) || "期初餘額".equals(g.summary())) {
                    continue;
                }
                glDr += g.debitMinor();
                glCr += g.creditMinor();
            }
            boolean ok = glDr == item.totalDebitMinor() && glCr == item.totalCreditMinor();
            list.add(new AccountMatch(
                    item.accountCode(),
                    item.accountName(),
                    item.totalDebitMinor(),
                    item.totalCreditMinor(),
                    glDr,
                    glCr,
                    ok
            ));
        }
        return list;
    }

    public void assertAllPass(String startDate, String endDate) {
        for (CheckResult r : reconcile(startDate, endDate)) {
            if (!r.passed()) {
                throw new BusinessException("報表勾稽失敗: " + r.name() + " — " + r.detail());
            }
        }
    }

    public void assertTrialBalanceMatchesGeneralLedger(String startDate, String endDate) {
        List<AccountMatch> matches = reconcileTrialBalanceVsGeneralLedger(startDate, endDate);
        List<String> fails = new ArrayList<>();
        for (AccountMatch m : matches) {
            if (!m.matched()) {
                fails.add(m.accountCode() + " " + m.detail());
            }
        }
        if (!fails.isEmpty()) {
            throw new BusinessException("試算表與總分類帳不一致: " + String.join("; ", fails));
        }
    }

    private Map<String, Long> loadAccountIds() {
        Map<String, Long> map = new LinkedHashMap<>();
        try (Connection conn = DbManager.get();
             PreparedStatement ps = conn.prepareStatement("SELECT code, id FROM accounts");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.put(rs.getString(1), rs.getLong(2));
            }
        } catch (SQLException e) {
            throw new BusinessException("載入科目對照失敗: " + e.getMessage(), e);
        }
        return map;
    }
}
