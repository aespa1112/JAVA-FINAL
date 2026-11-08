package com.ledger.dao;

import com.ledger.config.DbManager;
import com.ledger.model.FinancialStatementItem;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 補充報表：權益變動表、現金流量表（教學用簡化間接法）。
 */
public class ReportDaoExtra {

    public List<FinancialStatementItem> getEquityChanges(String startDate, String endDate) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return getEquityChanges(conn, startDate, endDate);
        }
    }

    public List<FinancialStatementItem> getEquityChanges(Connection conn, String startDate, String endDate) throws SQLException {
        List<FinancialStatementItem> list = new ArrayList<>();

        long beginEquity = equityBalanceBefore(conn, startDate);
        long periodNetIncome = periodNetIncome(conn, startDate, endDate);
        long capitalInject = accountNetChange(conn, startDate, endDate, "3101");
        long capitalSurplus = accountNetChange(conn, startDate, endDate, "3201");
        long retainedDirect = accountNetChange(conn, startDate, endDate, "3301");

        long endEquity = beginEquity + periodNetIncome + capitalInject + capitalSurplus + retainedDirect;

        list.add(new FinancialStatementItem("權益變動", "BEG", "期初權益餘額", beginEquity));
        list.add(new FinancialStatementItem("權益變動", "NI", "本期淨利（損）", periodNetIncome));
        list.add(new FinancialStatementItem("權益變動", "3101", "普通股股本變動", capitalInject));
        list.add(new FinancialStatementItem("權益變動", "3201", "資本公積變動", capitalSurplus));
        list.add(new FinancialStatementItem("權益變動", "3301", "保留盈餘其他變動", retainedDirect));
        list.add(new FinancialStatementItem("合計", "END", "期末權益餘額", endEquity));
        return list;
    }

    public List<FinancialStatementItem> getCashFlow(String startDate, String endDate) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return getCashFlow(conn, startDate, endDate);
        }
    }

    public List<FinancialStatementItem> getCashFlow(Connection conn, String startDate, String endDate) throws SQLException {
        List<FinancialStatementItem> list = new ArrayList<>();

        long ni = periodNetIncome(conn, startDate, endDate);

        long dAr = -accountDebitNetChange(conn, startDate, endDate, "1122");
        long dPrepay = -accountDebitNetChange(conn, startDate, endDate, "1141");
        long dInv = -accountDebitNetChange(conn, startDate, endDate, "1201");
        long dAp = accountNetChange(conn, startDate, endDate, "2122");
        long dNp = accountNetChange(conn, startDate, endDate, "2121");
        long dSal = accountNetChange(conn, startDate, endDate, "2124");
        long dTax = accountNetChange(conn, startDate, endDate, "2125");

        long operating = ni + dAr + dPrepay + dInv + dAp + dNp + dSal + dTax;

        long dPpe = -accountDebitNetChange(conn, startDate, endDate, "1401");
        long investing = dPpe;

        long dLoan = accountNetChange(conn, startDate, endDate, "2101");
        long dCap = accountNetChange(conn, startDate, endDate, "3101");
        long dSur = accountNetChange(conn, startDate, endDate, "3201");
        long financing = dLoan + dCap + dSur;

        long netChange = operating + investing + financing;
        long beginCash = cashBalanceBefore(conn, startDate);
        long endCash = beginCash + netChange;
        long actualEndCash = cashBalanceAsOf(conn, endDate);

        list.add(new FinancialStatementItem("營業活動", "NI", "本期淨利（損）", ni));
        list.add(new FinancialStatementItem("營業活動", "WC", "營運資金變動合計", operating - ni));
        list.add(new FinancialStatementItem("營業活動", "CFO", "營業活動之淨現金流", operating));
        list.add(new FinancialStatementItem("投資活動", "CFI", "投資活動之淨現金流", investing));
        list.add(new FinancialStatementItem("籌資活動", "CFF", "籌資活動之淨現金流", financing));
        list.add(new FinancialStatementItem("合計", "NET", "本期現金增（減）淨額", netChange));
        list.add(new FinancialStatementItem("合計", "BEG_CASH", "期初現金及約當現金", beginCash));
        list.add(new FinancialStatementItem("合計", "END_CASH", "期末現金及約當現金（推算）", endCash));
        list.add(new FinancialStatementItem("合計", "ACTUAL_CASH", "期末現金及約當現金（帳列）", actualEndCash));
        return list;
    }

    private long periodNetIncome(Connection conn, String start, String end) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT a.type, COALESCE(SUM(e.debit_minor),0) d, COALESCE(SUM(e.credit_minor),0) c " +
                "FROM voucher_entries e JOIN vouchers v ON v.id=e.voucher_id JOIN accounts a ON a.id=e.account_id " +
                "WHERE v.status='POSTED' AND a.type IN ('REVENUE','EXPENSE')"
        );
        List<String> params = new ArrayList<>();
        if (start != null && !start.isBlank()) { sql.append(" AND v.voucher_date >= ?"); params.add(start.trim()); }
        if (end != null && !end.isBlank()) { sql.append(" AND v.voucher_date <= ?"); params.add(end.trim()); }
        sql.append(" GROUP BY a.type");

        long rev = 0, exp = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    if ("REVENUE".equalsIgnoreCase(rs.getString(1))) rev += rs.getLong(3) - rs.getLong(2);
                    else exp += rs.getLong(2) - rs.getLong(3);
                }
            }
        }
        return rev - exp;
    }

    private long equityBalanceBefore(Connection conn, String startDate) throws SQLException {
        String sql =
                "SELECT a.type, COALESCE(SUM(e.debit_minor),0) d, COALESCE(SUM(e.credit_minor),0) c " +
                "FROM accounts a LEFT JOIN (" +
                "  SELECT e.account_id, e.debit_minor, e.credit_minor FROM voucher_entries e " +
                "  JOIN vouchers v ON v.id=e.voucher_id WHERE v.status='POSTED'" +
                (startDate != null && !startDate.isBlank() ? " AND v.voucher_date < ?" : "") +
                ") e ON a.id=e.account_id WHERE a.type IN ('EQUITY','REVENUE','EXPENSE') GROUP BY a.type";
        long total = 0;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            if (startDate != null && !startDate.isBlank()) ps.setString(1, startDate.trim());
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String t = rs.getString(1);
                    long d = rs.getLong(2), c = rs.getLong(3);
                    if ("EXPENSE".equalsIgnoreCase(t)) total += -(d - c);
                    else total += (c - d);
                }
            }
        }
        return total;
    }

    /** 負債／權益：credit - debit */
    private long accountNetChange(Connection conn, String start, String end, String code) throws SQLException {
        long[] dc = sumDebitCredit(conn, start, end, code);
        return dc[1] - dc[0];
    }

    /** 資產：debit - credit（期間淨增加） */
    private long accountDebitNetChange(Connection conn, String start, String end, String code) throws SQLException {
        long[] dc = sumDebitCredit(conn, start, end, code);
        return dc[0] - dc[1];
    }

    private long[] sumDebitCredit(Connection conn, String start, String end, String code) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(e.debit_minor),0), COALESCE(SUM(e.credit_minor),0) " +
                "FROM voucher_entries e JOIN vouchers v ON v.id=e.voucher_id JOIN accounts a ON a.id=e.account_id " +
                "WHERE v.status='POSTED' AND a.code = ?"
        );
        List<String> params = new ArrayList<>();
        params.add(code);
        if (start != null && !start.isBlank()) { sql.append(" AND v.voucher_date >= ?"); params.add(start.trim()); }
        if (end != null && !end.isBlank()) { sql.append(" AND v.voucher_date <= ?"); params.add(end.trim()); }

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) ps.setString(i + 1, params.get(i));
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return new long[]{rs.getLong(1), rs.getLong(2)};
            }
        }
        return new long[]{0, 0};
    }

    private long cashBalanceBefore(Connection conn, String start) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(e.debit_minor - e.credit_minor),0) " +
                "FROM voucher_entries e JOIN vouchers v ON v.id=e.voucher_id JOIN accounts a ON a.id=e.account_id " +
                "WHERE v.status='POSTED' AND a.code IN ('1101','1102')"
        );
        if (start != null && !start.isBlank()) {
            sql.append(" AND v.voucher_date < ?");
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (start != null && !start.isBlank()) ps.setString(1, start.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private long cashBalanceAsOf(Connection conn, String asOf) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(e.debit_minor - e.credit_minor),0) " +
                "FROM voucher_entries e JOIN vouchers v ON v.id=e.voucher_id JOIN accounts a ON a.id=e.account_id " +
                "WHERE v.status='POSTED' AND a.code IN ('1101','1102')"
        );
        if (asOf != null && !asOf.isBlank()) {
            sql.append(" AND v.voucher_date <= ?");
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (asOf != null && !asOf.isBlank()) ps.setString(1, asOf.trim());
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
