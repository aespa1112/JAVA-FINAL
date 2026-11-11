package com.ledger.dao;

import com.ledger.config.DbManager;
import com.ledger.exception.BusinessException;
import com.ledger.model.FinancialStatementItem;
import com.ledger.model.GeneralLedgerItem;
import com.ledger.model.TrialBalanceItem;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class ReportDao {

    public List<TrialBalanceItem> getTrialBalance(String startDate, String endDate) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return getTrialBalance(conn, startDate, endDate);
        }
    }

    public List<TrialBalanceItem> getTrialBalance(Connection conn, String startDate, String endDate) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT a.code, a.name, a.type, a.normal_side, " +
                "       COALESCE(SUM(ve.debit_minor), 0) AS total_debit, " +
                "       COALESCE(SUM(ve.credit_minor), 0) AS total_credit " +
                "FROM accounts a " +
                "LEFT JOIN ( " +
                "    SELECT e.account_id, e.debit_minor, e.credit_minor " +
                "    FROM voucher_entries e " +
                "    JOIN vouchers v ON v.id = e.voucher_id " +
                "    WHERE v.status = 'POSTED' "
        );
        List<String> params = new ArrayList<>();
        if (startDate != null && !startDate.trim().isEmpty()) {
            sql.append(" AND v.voucher_date >= ? ");
            params.add(startDate.trim());
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            sql.append(" AND v.voucher_date <= ? ");
            params.add(endDate.trim());
        }
        sql.append(") ve ON a.id = ve.account_id GROUP BY a.id ORDER BY a.code");

        List<TrialBalanceItem> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String code = rs.getString("code");
                    String name = rs.getString("name");
                    String type = rs.getString("type");
                    String normalSide = rs.getString("normal_side");
                    long debit = rs.getLong("total_debit");
                    long credit = rs.getLong("total_credit");
                    long net;
                    if ("DEBIT".equalsIgnoreCase(normalSide)) {
                        net = debit - credit;
                    } else {
                        net = credit - debit;
                    }
                    list.add(new TrialBalanceItem(code, name, type, normalSide, debit, credit, net));
                }
            }
        }
        return list;
    }

    public List<GeneralLedgerItem> getGeneralLedger(long accountId, String startDate, String endDate) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return getGeneralLedger(conn, accountId, startDate, endDate);
        }
    }

    public List<GeneralLedgerItem> getGeneralLedger(Connection conn, long accountId, String startDate, String endDate) throws SQLException {
        String normalSide = "DEBIT";
        String accSql = "SELECT normal_side FROM accounts WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(accSql)) {
            ps.setLong(1, accountId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    normalSide = rs.getString("normal_side");
                }
            }
        }

        // ---------- 計算期初餘額（startDate 之前已過帳分錄的淨額）----------
        long openingBalance = 0;
        boolean hasStartDate = startDate != null && !startDate.trim().isEmpty();
        if (hasStartDate) {
            String openingSql =
                    "SELECT COALESCE(SUM(e.debit_minor), 0) AS total_debit, " +
                    "       COALESCE(SUM(e.credit_minor), 0) AS total_credit " +
                    "FROM voucher_entries e " +
                    "JOIN vouchers v ON v.id = e.voucher_id " +
                    "WHERE e.account_id = ? AND v.status = 'POSTED' AND v.voucher_date < ?";
            try (PreparedStatement ps = conn.prepareStatement(openingSql)) {
                ps.setLong(1, accountId);
                ps.setString(2, startDate.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        long debit = rs.getLong("total_debit");
                        long credit = rs.getLong("total_credit");
                        if ("DEBIT".equalsIgnoreCase(normalSide)) {
                            openingBalance = debit - credit;
                        } else {
                            openingBalance = credit - debit;
                        }
                    }
                }
            }
        }

        // ---------- 查詢期間內明細 ----------
        StringBuilder sql = new StringBuilder(
                "SELECT v.voucher_date, v.voucher_no, v.summary, e.line_no, e.memo, e.debit_minor, e.credit_minor " +
                "FROM voucher_entries e " +
                "JOIN vouchers v ON v.id = e.voucher_id " +
                "WHERE e.account_id = ? AND v.status = 'POSTED' "
        );
        List<Object> params = new ArrayList<>();
        params.add(accountId);

        if (hasStartDate) {
            sql.append(" AND v.voucher_date >= ? ");
            params.add(startDate.trim());
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            sql.append(" AND v.voucher_date <= ? ");
            params.add(endDate.trim());
        }
        sql.append(" ORDER BY v.voucher_date ASC, v.voucher_no ASC, e.line_no ASC");

        List<GeneralLedgerItem> list = new ArrayList<>();
        long runningBalance = openingBalance;

        // 有期初餘額時，在最前方插入「期初餘額」列，方便閱讀與 CSV 匯出
        if (hasStartDate) {
            list.add(new GeneralLedgerItem(
                    startDate.trim(),
                    "—",
                    "期初餘額",
                    0,
                    "",
                    0L,
                    0L,
                    openingBalance
            ));
        }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long debit = rs.getLong("debit_minor");
                    long credit = rs.getLong("credit_minor");
                    if ("DEBIT".equalsIgnoreCase(normalSide)) {
                        runningBalance += (debit - credit);
                    } else {
                        runningBalance += (credit - debit);
                    }
                    list.add(new GeneralLedgerItem(
                            rs.getString("voucher_date"),
                            rs.getString("voucher_no"),
                            rs.getString("summary"),
                            rs.getInt("line_no"),
                            rs.getString("memo"),
                            debit,
                            credit,
                            runningBalance
                    ));
                }
            }
        }
        return list;
    }

    public List<FinancialStatementItem> getIncomeStatement(String startDate, String endDate) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return getIncomeStatement(conn, startDate, endDate);
        }
    }

    public List<FinancialStatementItem> getIncomeStatement(Connection conn, String startDate, String endDate) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT a.type, a.code, a.name, " +
                "       COALESCE(SUM(ve.debit_minor), 0) AS total_debit, " +
                "       COALESCE(SUM(ve.credit_minor), 0) AS total_credit " +
                "FROM accounts a " +
                "LEFT JOIN ( " +
                "    SELECT e.account_id, e.debit_minor, e.credit_minor " +
                "    FROM voucher_entries e " +
                "    JOIN vouchers v ON v.id = e.voucher_id " +
                "    WHERE v.status = 'POSTED' "
        );
        List<String> params = new ArrayList<>();
        if (startDate != null && !startDate.trim().isEmpty()) {
            sql.append(" AND v.voucher_date >= ? ");
            params.add(startDate.trim());
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            sql.append(" AND v.voucher_date <= ? ");
            params.add(endDate.trim());
        }
        sql.append(") ve ON a.id = ve.account_id " +
                   "WHERE a.type IN ('REVENUE', 'EXPENSE') " +
                   "GROUP BY a.id ORDER BY a.code");

        List<FinancialStatementItem> list = new ArrayList<>();
        long totalRevenue = 0;
        long totalExpense = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("type");
                    String code = rs.getString("code");
                    String name = rs.getString("name");
                    long debit = rs.getLong("total_debit");
                    long credit = rs.getLong("total_credit");
                    long amount;
                    if ("REVENUE".equalsIgnoreCase(type)) {
                        amount = credit - debit;
                        totalRevenue += amount;
                        list.add(new FinancialStatementItem("營業收入", code, name, amount));
                    } else {
                        amount = debit - credit;
                        totalExpense += amount;
                        list.add(new FinancialStatementItem("營業成本與費用", code, name, amount));
                    }
                }
            }
        }
        list.add(new FinancialStatementItem("合計", "TOTAL_REV", "營業收入合計", totalRevenue));
        list.add(new FinancialStatementItem("合計", "TOTAL_EXP", "營業費用合計", totalExpense));
        list.add(new FinancialStatementItem("結算", "NET_INCOME", "本期淨利（損）", totalRevenue - totalExpense));
        return list;
    }

    public List<FinancialStatementItem> getBalanceSheet(String asOfDate) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return getBalanceSheet(conn, asOfDate);
        }
    }

    public List<FinancialStatementItem> getBalanceSheet(Connection conn, String asOfDate) throws SQLException {
        // 截止日期必填：空值一律視為系統當日，避免納入未來已過帳傳票
        String effectiveAsOf;
        if (asOfDate == null || asOfDate.trim().isEmpty()) {
            effectiveAsOf = LocalDate.now().toString();
        } else {
            try {
                effectiveAsOf = LocalDate.parse(asOfDate.trim()).toString();
            } catch (Exception e) {
                throw new BusinessException("資產負債表截止日期格式無效，請使用 YYYY-MM-DD 格式: " + asOfDate);
            }
        }
        int currentYear = LocalDate.parse(effectiveAsOf).getYear();
        String yearStart = String.format("%04d-01-01", currentYear);

        StringBuilder sql = new StringBuilder(
                "SELECT a.type, a.code, a.name, " +
                "       COALESCE(SUM(ve.debit_minor), 0) AS total_debit, " +
                "       COALESCE(SUM(ve.credit_minor), 0) AS total_credit, " +
                "       COALESCE(SUM(CASE WHEN ve.voucher_date < ? THEN (ve.credit_minor - ve.debit_minor) ELSE 0 END), 0) AS prior_net, " +
                "       COALESCE(SUM(CASE WHEN ve.voucher_date >= ? THEN (ve.credit_minor - ve.debit_minor) ELSE 0 END), 0) AS curr_net " +
                "FROM accounts a " +
                "LEFT JOIN ( " +
                "    SELECT e.account_id, e.debit_minor, e.credit_minor, v.voucher_date " +
                "    FROM voucher_entries e " +
                "    JOIN vouchers v ON v.id = e.voucher_id " +
                "    WHERE v.status = 'POSTED' "
        );
        List<String> params = new ArrayList<>();
        params.add(yearStart);
        params.add(yearStart);

        // 永遠加上截止日條件
        sql.append(" AND v.voucher_date <= ? ");
        params.add(effectiveAsOf);
        sql.append(") ve ON a.id = ve.account_id " +
                   "WHERE a.type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE') " +
                   "GROUP BY a.id ORDER BY a.code");

        List<FinancialStatementItem> list = new ArrayList<>();
        long totalAssets = 0;
        long totalLiabilities = 0;
        long totalEquity = 0;
        long priorNetIncome = 0;
        long currentNetIncome = 0;
        long baseRetainedEarnings = 0;

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String type = rs.getString("type");
                    String code = rs.getString("code");
                    String name = rs.getString("name");
                    long debit = rs.getLong("total_debit");
                    long credit = rs.getLong("total_credit");
                    long priorNet = rs.getLong("prior_net");
                    long currNet = rs.getLong("curr_net");

                    if ("3302".equals(code)) {
                        // 3302 本期損益後續統整計算，避免重複
                        continue;
                    }

                    if ("3301".equals(code)) {
                        // 記錄保留盈餘科目原有記帳餘額
                        baseRetainedEarnings = credit - debit;
                        continue;
                    }

                    if ("ASSET".equalsIgnoreCase(type)) {
                        long net = debit - credit;
                        totalAssets += net;
                        list.add(new FinancialStatementItem("資產", code, name, net));
                    } else if ("LIABILITY".equalsIgnoreCase(type)) {
                        long net = credit - debit;
                        totalLiabilities += net;
                        list.add(new FinancialStatementItem("負債", code, name, net));
                    } else if ("EQUITY".equalsIgnoreCase(type)) {
                        long net = credit - debit;
                        totalEquity += net;
                        list.add(new FinancialStatementItem("權益", code, name, net));
                    } else if ("REVENUE".equalsIgnoreCase(type)) {
                        // 收入 = 貸 - 借；SQL prior_net/curr_net = (credit - debit)
                        priorNetIncome += priorNet;
                        currentNetIncome += currNet;
                    } else if ("EXPENSE".equalsIgnoreCase(type)) {
                        // 費用科目的 (credit - debit) 為費用淨額之相反數；
                        // 直接累加 (credit - debit) 等價於「淨利 -= (借 - 貸)」，避免二次反轉。
                        priorNetIncome += priorNet;
                        currentNetIncome += currNet;
                    }
                }
            }
        }

        // 年度損益結轉處理：前期未分配利潤自動滾入 3301 保留盈餘；當期淨利列入 3302 本期損益
        long totalRetained = baseRetainedEarnings + priorNetIncome;
        list.add(new FinancialStatementItem("權益", "3301", "保留盈餘（含前期累計結轉）", totalRetained));
        totalEquity += totalRetained;

        list.add(new FinancialStatementItem("權益", "3302", "本期損益", currentNetIncome));
        totalEquity += currentNetIncome;

        list.add(new FinancialStatementItem("合計", "TOTAL_ASSET", "資產總額", totalAssets));
        list.add(new FinancialStatementItem("合計", "TOTAL_LIAB", "負債總額", totalLiabilities));
        list.add(new FinancialStatementItem("合計", "TOTAL_EQUITY", "權益總額", totalEquity));
        list.add(new FinancialStatementItem("合計", "TOTAL_LIAB_EQUITY", "負債及權益總額", totalLiabilities + totalEquity));
        return list;
    }
}
