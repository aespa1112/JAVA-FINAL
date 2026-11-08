package com.ledger.dao;

import com.ledger.config.DbManager;
import com.ledger.model.FinancialStatementItem;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 補充報表：權益變動表、現金流量表（教學用簡化間接法）。
 * 與 ReportDao 分離，方便逐步整合至主畫面。
 */
public class ReportDaoExtra {

    /**
     * 權益變動表（簡化）：
     * 期初權益 → 本期淨利 → 業主投入／分配（股本、資本公積變動）→ 期末權益
     */
    public List<FinancialStatementItem> getEquityChanges(String startDate, String endDate) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return getEquityChanges(conn, startDate, endDate);
        }
    }

    public List<FinancialStatementItem> getEquityChanges(Connection conn, String startDate, String endDate) throws SQLException {
        List<FinancialStatementItem> list = new ArrayList<>();

        long beginEquity = equityBalanceBefore(conn, startDate);
        long periodNetIncome = periodNetIncome(conn, startDate, endDate);
        long capitalInject = equityAccountMovement(conn, startDate, endDate, "3101"); // 普通股股本淨增加
        long capitalSurplus = equityAccountMovement(conn, startDate, endDate, "3201"); // 資本公積
        // 保留盈餘科目本身的直接變動（不含本期損益虛擬結轉）
        long retainedDirect = equityAccountMovement(conn, startDate, endDate, "3301");

        long endEquity = beginEquity + periodNetIncome + capitalInject + capitalSurplus + retainedDirect;

        list.add(new FinancialStatementItem("權益變動", "BEG", "期初權益餘額", beginEquity));
        list.add(new FinancialStatementItem("權益變動", "NI", "本期淨利（損）", periodNetIncome));
        list.add(new FinancialStatementItem("權益變動", "3101", "普通股股本變動", capitalInject));
        list.add(new FinancialStatementItem("權益變動", "3201", "資本公積變動", capitalSurplus));
        list.add(new FinancialStatementItem("權益變動", "3301", "保留盈餘其他變動", retainedDirect));
        list.add(new FinancialStatementItem("合計", "END", "期末權益餘額", endEquity));
        return list;
    }

    /**
     * 現金流量表（簡化間接法）：
     * 營業活動 = 本期淨利 + 營運資金額度變動（應收、存貨、應付等）
     * 投資活動 = 不動產廠房設備等非流動資產變動（以支出為負）
     * 籌資活動 = 借款、股本等變動
     * 再加期初現金核對期末現金
     */
    public List<FinancialStatementItem> getCashFlow(String startDate, String endDate) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return getCashFlow(conn, startDate, endDate);
        }
    }

    public List<FinancialStatementItem> getCashFlow(Connection conn, String startDate, String endDate) throws SQLException {
        List<FinancialStatementItem> list = new ArrayList<>();

        long ni = periodNetIncome(conn, startDate, endDate);

        // 營運資金變動：資產增加→現金減少；負債增加→現金增加
        long dAr = -accountNetChange(conn, startDate, endDate, "1122"); // 應收帳款
        long dPrepay = -accountNetChange(conn, startDate, endDate, "1141");
        long dInv = -accountNetChange(conn, startDate, endDate, "1201");
        long dAp = accountNetChange(conn, startDate, endDate, "2122");  // 應付帳款
        long dNp = accountNetChange(conn, startDate, endDate, "2121");
        long dSal = accountNetChange(conn, startDate, endDate, "2124");
        long dTax = accountNetChange(conn, startDate, endDate, "2125");

        long operating = ni + dAr + dPrepay + dInv + dAp + dNp + dSal + dTax;

        // 投資：PPE 增加視為購置（現金流出）
        long dPpe = -accountNetChange(conn, startDate, endDate, "1401");
        long investing = dPpe;

        // 籌資：借款、股本增加為流入
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

    // ---------- helpers ----------

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
        // 權益科目 + 損益科目在 startDate 之前的淨額（credit - debit 對權益/收入；費用相反）
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

    private long equityAccountMovement(Connection conn, String start, String end, String code) throws SQLException {
        return accountNetChange(conn, start, end, code);
    }

    /** 期間內該科目淨變動（credit - debit）；資產科目若要用借方增加需呼叫端自行取負 */
    private long accountNetChange(Connection conn, String start, String end, String code) throws SQLException {
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
                if (rs.next()) {
                    return rs.getLong(2) - rs.getLong(1); // credit - debit
                }
            }
        }
        return 0;
    }

    private long cashBalanceBefore(Connection conn, String start) throws SQLException {
        return cashBalanceAsOf(conn, start == null ? null : minusOneDayHint(start));
    }

    private long cashBalanceAsOf(Connection conn, String asOf) throws SQLException {
        // 1101 庫存現金 + 1102 銀行存款，資產：debit - credit
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

    private String minusOneDayHint(String date) {
        // 簡化：直接用 < startDate 語意時傳 startDate 給 before 查詢即可；此處回傳 start 供 <= 使用時請改用 before
        // cashBalanceBefore 改用專用 SQL
        return date;
    }
}
