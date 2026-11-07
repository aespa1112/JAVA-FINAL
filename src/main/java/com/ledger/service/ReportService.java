package com.ledger.service;

import com.ledger.dao.ReportDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.FinancialStatementItem;
import com.ledger.model.GeneralLedgerItem;
import com.ledger.model.TrialBalanceItem;
import com.ledger.util.MoneyUtil;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

public class ReportService {

    private final ReportDao reportDao = new ReportDao();

    public List<TrialBalanceItem> getTrialBalance(String startDate, String endDate) {
        try {
            return reportDao.getTrialBalance(startDate, endDate);
        } catch (SQLException e) {
            throw new BusinessException("查詢試算表失敗: " + e.getMessage(), e);
        }
    }

    public List<FinancialStatementItem> getBalanceSheet(String asOfDate) {
        if (asOfDate != null && !asOfDate.trim().isEmpty()) {
            try {
                LocalDate.parse(asOfDate.trim());
            } catch (DateTimeParseException e) {
                throw new BusinessException("資產負債表截止日期格式無效，請使用 YYYY-MM-DD 格式: " + asOfDate);
            }
        }
        try {
            return reportDao.getBalanceSheet(asOfDate);
        } catch (SQLException e) {
            throw new BusinessException("查詢資產負債表失敗: " + e.getMessage(), e);
        }
    }

    public List<FinancialStatementItem> getIncomeStatement(String startDate, String endDate) {
        try {
            return reportDao.getIncomeStatement(startDate, endDate);
        } catch (SQLException e) {
            throw new BusinessException("查詢損益表失敗: " + e.getMessage(), e);
        }
    }

    public List<GeneralLedgerItem> getGeneralLedger(long accountId, String startDate, String endDate) {
        try {
            return reportDao.getGeneralLedger(accountId, startDate, endDate);
        } catch (SQLException e) {
            throw new BusinessException("查詢總分類帳失敗: " + e.getMessage(), e);
        }
    }

    private String escapeCsv(Object val) {
        if (val == null) return "\"\"";
        String s = String.valueOf(val);
        if (s.startsWith("=") || s.startsWith("+") || s.startsWith("-") || s.startsWith("@")) {
            s = "\t" + s; // 消除 Excel 公式解析注入風險
        }
        return "\"" + s.replace("\"", "\"\"") + "\"";
    }

    private String formatMoneyCsv(long minor) {
        return MoneyUtil.toMajor(minor).toPlainString();
    }

    public void exportTrialBalanceCsv(String startDate, String endDate, Path targetFile) throws IOException {
        List<TrialBalanceItem> items = getTrialBalance(startDate, endDate);
        try (BufferedWriter writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
            writer.write('\ufeff'); // UTF-8 BOM
            writer.write("科目代號,科目名稱,科目類別,借貸性別,借方金額,貸方金額,淨餘額\n");
            for (TrialBalanceItem item : items) {
                writer.write(String.format("%s,%s,%s,%s,%s,%s,%s\n",
                        escapeCsv(item.accountCode()),
                        escapeCsv(item.accountName()),
                        escapeCsv(item.accountType()),
                        escapeCsv(item.normalSide()),
                        formatMoneyCsv(item.totalDebitMinor()),
                        formatMoneyCsv(item.totalCreditMinor()),
                        formatMoneyCsv(item.netMinor())));
            }
        }
    }

    public void exportBalanceSheetCsv(String asOfDate, Path targetFile) throws IOException {
        List<FinancialStatementItem> items = getBalanceSheet(asOfDate);
        try (BufferedWriter writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
            writer.write('\ufeff');
            writer.write("報表分類,科目代號,科目名稱,金額\n");
            for (FinancialStatementItem item : items) {
                writer.write(String.format("%s,%s,%s,%s\n",
                        escapeCsv(item.section()),
                        escapeCsv(item.code()),
                        escapeCsv(item.name()),
                        formatMoneyCsv(item.amountMinor())));
            }
        }
    }

    public void exportIncomeStatementCsv(String startDate, String endDate, Path targetFile) throws IOException {
        List<FinancialStatementItem> items = getIncomeStatement(startDate, endDate);
        try (BufferedWriter writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
            writer.write('\ufeff');
            writer.write("報表分類,科目代號,科目名稱,金額\n");
            for (FinancialStatementItem item : items) {
                writer.write(String.format("%s,%s,%s,%s\n",
                        escapeCsv(item.section()),
                        escapeCsv(item.code()),
                        escapeCsv(item.name()),
                        formatMoneyCsv(item.amountMinor())));
            }
        }
    }

    public void exportGeneralLedgerCsv(long accountId, String accountName, String startDate, String endDate, Path targetFile) throws IOException {
        List<GeneralLedgerItem> items = getGeneralLedger(accountId, startDate, endDate);
        try (BufferedWriter writer = Files.newBufferedWriter(targetFile, StandardCharsets.UTF_8)) {
            writer.write('\ufeff');
            writer.write(String.format("總分類帳 - %s\n", escapeCsv(accountName)));
            writer.write("傳票日期,傳票編號,摘要,分錄序號,備註,借方金額,貸方金額,累積餘額\n");
            for (GeneralLedgerItem item : items) {
                writer.write(String.format("%s,%s,%s,%d,%s,%s,%s,%s\n",
                        escapeCsv(item.voucherDate()),
                        escapeCsv(item.voucherNo()),
                        escapeCsv(item.summary()),
                        item.lineNo(),
                        escapeCsv(item.memo()),
                        formatMoneyCsv(item.debitMinor()),
                        formatMoneyCsv(item.creditMinor()),
                        formatMoneyCsv(item.balanceMinor())));
            }
        }
    }
}
