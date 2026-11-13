package com.ledger.dao;

import com.ledger.config.DbManager;
import com.ledger.model.FinancialStatementItem;
import java.sql.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 補充報表：權益變動表與完整間接法現金流量表（依據 EAS / IAS 7 間接法）。
 * <p>
 * 涵蓋：
 * - 營業活動：本期淨利、折舊及攤銷費用加回（5208/5209）、處分資產損益調整（4202/5302）、營運資金變動
 * - 投資活動：不動產廠房設備（14xx）、無形資產（15xx）及處分資產價款調整
 * - 籌資活動：短期/長期借款（2101/22xx）、股本（31xx）、資本公積（32xx）及保留盈餘直接變動（3301）
 * - 非現金投資及籌資活動揭露（NON_CASH）
 * - 自動排除結帳/反結帳分錄（YEAR_END_CLOSE / YEAR_END_REVERSE）並與總帳期初、期末現金核對
 */
public class ReportDaoExtra {

    public enum CashFlowCategory {
        CASH,
        OPERATING_WC,
        INVESTING,
        FINANCING,
        REVENUE,
        EXPENSE
    }

    public static CashFlowCategory classifyAccount(String code, String type) {
        if (code == null) return CashFlowCategory.OPERATING_WC;
        String c = code.trim();
        if ("1101".equals(c) || "1102".equals(c)) {
            return CashFlowCategory.CASH;
        }
        if ("4202".equals(c) || "REVENUE".equalsIgnoreCase(type)) {
            return CashFlowCategory.REVENUE;
        }
        if ("5208".equals(c) || "5209".equals(c) || "5302".equals(c) || "EXPENSE".equalsIgnoreCase(type)) {
            return CashFlowCategory.EXPENSE;
        }
        if (c.startsWith("14") || c.startsWith("15")) {
            return CashFlowCategory.INVESTING;
        }
        if ("2101".equals(c) || c.startsWith("22") || c.startsWith("31") || c.startsWith("32") || "3301".equals(c) || "3302".equals(c)
                || "EQUITY".equalsIgnoreCase(type)) {
            return CashFlowCategory.FINANCING;
        }
        return CashFlowCategory.OPERATING_WC;
    }

    private record EntryRow(String code, String type, long debitMinor, long creditMinor) {}

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
        Map<Long, List<EntryRow>> vouchers = new LinkedHashMap<>();
        StringBuilder sql = new StringBuilder(
                "SELECT v.id, a.code, a.type, e.debit_minor, e.credit_minor " +
                "FROM voucher_entries e " +
                "JOIN vouchers v ON v.id = e.voucher_id " +
                "JOIN accounts a ON a.id = e.account_id " +
                "WHERE v.status = 'POSTED' " +
                "  AND COALESCE(v.voucher_type, 'NORMAL') NOT IN ('YEAR_END_CLOSE', 'YEAR_END_REVERSE')"
        );
        List<String> params = new ArrayList<>();
        if (startDate != null && !startDate.isBlank()) {
            sql.append(" AND v.voucher_date >= ?");
            params.add(startDate.trim());
        }
        if (endDate != null && !endDate.isBlank()) {
            sql.append(" AND v.voucher_date <= ?");
            params.add(endDate.trim());
        }
        sql.append(" ORDER BY v.id, e.line_no");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long vid = rs.getLong(1);
                    vouchers.computeIfAbsent(vid, k -> new ArrayList<>())
                            .add(new EntryRow(rs.getString(2), rs.getString(3), rs.getLong(4), rs.getLong(5)));
                }
            }
        }

        long revNet = 0;
        long expNet = 0;
        long deprAmort = 0;
        long disposalGain = 0;
        long disposalLoss = 0;
        long wcNet = 0;
        long invRawNet = 0;
        long finNet = 0;
        long nonCashInvFinTotal = 0;

        for (List<EntryRow> rows : vouchers.values()) {
            boolean hasCash = false;
            boolean hasDeprOrAmort = false;
            boolean hasDisposal = false;
            boolean touchesInvOrFin = false;
            long invFinDr = 0;
            long invFinCr = 0;

            for (EntryRow r : rows) {
                CashFlowCategory cat = classifyAccount(r.code(), r.type());
                if (cat == CashFlowCategory.CASH) hasCash = true;
                if ("5208".equals(r.code()) || "5209".equals(r.code())) hasDeprOrAmort = true;
                if ("4202".equals(r.code()) || "5302".equals(r.code())) hasDisposal = true;
                if (cat == CashFlowCategory.INVESTING || cat == CashFlowCategory.FINANCING) {
                    touchesInvOrFin = true;
                    invFinDr += r.debitMinor();
                    invFinCr += r.creditMinor();
                }
            }

            if (!hasCash && !hasDeprOrAmort && !hasDisposal && touchesInvOrFin) {
                nonCashInvFinTotal += Math.max(invFinDr, invFinCr);
                continue;
            }

            for (EntryRow r : rows) {
                CashFlowCategory cat = classifyAccount(r.code(), r.type());
                long crMinusDr = r.creditMinor() - r.debitMinor();
                long drMinusCr = r.debitMinor() - r.creditMinor();

                switch (cat) {
                    case CASH -> {
                        // Cash side is what we reconcile against
                    }
                    case REVENUE -> {
                        revNet += crMinusDr;
                        if ("4202".equals(r.code())) {
                            disposalGain += crMinusDr;
                        }
                    }
                    case EXPENSE -> {
                        expNet += drMinusCr;
                        if ("5208".equals(r.code()) || "5209".equals(r.code())) {
                            deprAmort += drMinusCr;
                        }
                        if ("5302".equals(r.code())) {
                            disposalLoss += drMinusCr;
                        }
                    }
                    case OPERATING_WC -> wcNet += crMinusDr;
                    case INVESTING -> invRawNet += crMinusDr;
                    case FINANCING -> finNet += crMinusDr;
                }
            }
        }

        long ni = revNet - expNet;
        long disposalAdj = disposalLoss - disposalGain;
        long operating = ni + deprAmort + disposalAdj + wcNet;
        long investing = invRawNet - deprAmort - disposalAdj;
        long financing = finNet;

        long netChange = operating + investing + financing;
        long beginCash = cashBalanceBefore(conn, startDate);
        long endCash = beginCash + netChange;
        long actualEndCash = cashBalanceAsOf(conn, endDate);

        List<FinancialStatementItem> list = new ArrayList<>();
        list.add(new FinancialStatementItem("營業活動", "NI", "本期淨利（損）", ni));
        list.add(new FinancialStatementItem("營業活動", "DEPR_AMORT", "折舊及攤銷費用加回", deprAmort));
        list.add(new FinancialStatementItem("營業活動", "DISPOSAL_ADJ", "處分不動產廠房及設備（利益）損失調整", disposalAdj));
        list.add(new FinancialStatementItem("營業活動", "WC", "營運資金變動合計", wcNet));
        list.add(new FinancialStatementItem("營業活動", "CFO", "營業活動之淨現金流", operating));
        list.add(new FinancialStatementItem("投資活動", "CFI", "投資活動之淨現金流", investing));
        list.add(new FinancialStatementItem("籌資活動", "CFF", "籌資活動之淨現金流", financing));
        list.add(new FinancialStatementItem("非現金交易", "NON_CASH", "不影響現金流量之投資及籌資活動", nonCashInvFinTotal));
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
                "WHERE v.status='POSTED' " +
                "AND COALESCE(v.voucher_type,'NORMAL') NOT IN ('YEAR_END_CLOSE','YEAR_END_REVERSE') " +
                "AND a.type IN ('REVENUE','EXPENSE')"
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

    /** 負債／權益：credit - debit（排除年終結帳分錄避免重複計算） */
    private long accountNetChange(Connection conn, String start, String end, String code) throws SQLException {
        long[] dc = sumDebitCredit(conn, start, end, code);
        return dc[1] - dc[0];
    }

    private long[] sumDebitCredit(Connection conn, String start, String end, String code) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(e.debit_minor),0), COALESCE(SUM(e.credit_minor),0) " +
                "FROM voucher_entries e JOIN vouchers v ON v.id=e.voucher_id JOIN accounts a ON a.id=e.account_id " +
                "WHERE v.status='POSTED' " +
                "AND COALESCE(v.voucher_type,'NORMAL') NOT IN ('YEAR_END_CLOSE','YEAR_END_REVERSE') " +
                "AND a.code = ?"
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
