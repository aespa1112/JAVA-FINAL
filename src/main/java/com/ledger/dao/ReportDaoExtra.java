package com.ledger.dao;

import com.ledger.config.DbManager;
import com.ledger.model.FinancialStatementItem;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 補充報表：權益變動表與完整間接法現金流量表（依據 EAS / IAS 7 間接法）。
 * <p>
 * 涵蓋：
 * - 營業活動：本期淨利、折舊及攤銷費用加回（5208/5209）、處分資產損益調整（4202/5302）、營運資金變動
 * - 投資活動：依交易現金流辨識不動產廠房設備（14xx）及無形資產（15xx）價款，排除累計折舊（1402/1502）與非現金交易
 * - 籌資活動：短期/長期借款（2101/22xx）、股本（31xx）、資本公積（32xx）及保留盈餘直接變動（3301）
 * - 非現金投資及籌資活動揭露（NON_CASH）
 * - 可擴充科目分類規則並與總帳期初、期末現金餘額勾稽
 */
public class ReportDaoExtra {

    public enum CashFlowCategory {
        CASH,
        OPERATING_WC,
        ACCUM_DEPR_AMORT,
        INVESTING,
        FINANCING,
        DEPR_AMORT_EXPENSE,
        DISPOSAL_GAIN_LOSS,
        REVENUE,
        EXPENSE
    }

    private static final java.util.concurrent.ConcurrentHashMap<String, CashFlowCategory> CUSTOM_CLASSIFICATIONS =
            new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * 註冊可設定的會計科目現金流量分類覆寫規則。
     */
    public static void registerAccountClassification(String accountCode, CashFlowCategory category) {
        if (accountCode == null || accountCode.isBlank() || category == null) {
            throw new IllegalArgumentException("科目代碼與現金流量分類不可為空");
        }
        CUSTOM_CLASSIFICATIONS.put(accountCode.trim(), category);
    }

    /**
     * 清除所有自訂現金流量分類覆寫規則。
     */
    public static void clearCustomClassifications() {
        CUSTOM_CLASSIFICATIONS.clear();
    }

    public static CashFlowCategory classifyAccount(String code, String type) {
        return classifyAccount(code, null, type);
    }

    public static CashFlowCategory classifyAccount(String code, String name, String type) {
        if (code == null) return CashFlowCategory.OPERATING_WC;
        String c = code.trim();
        CashFlowCategory custom = CUSTOM_CLASSIFICATIONS.get(c);
        if (custom != null) {
            return custom;
        }
        String n = name != null ? name.trim() : "";
        if (c.startsWith("110") || c.startsWith("111")
                || ("ASSET".equalsIgnoreCase(type) && (n.contains("現金") || n.contains("銀行存款") || n.contains("零用金") || n.contains("約當現金")))) {
            return CashFlowCategory.CASH;
        }
        // 累計折舊（1402）與累計攤銷（1502）為資產減項評價科目，不得併入投資活動現金流
        if ("1402".equals(c) || "1502".equals(c)
                || ("ASSET".equalsIgnoreCase(type) && (n.contains("累計折舊") || n.contains("累計攤銷")))) {
            return CashFlowCategory.ACCUM_DEPR_AMORT;
        }
        if ("5208".equals(c) || "5209".equals(c)) {
            return CashFlowCategory.DEPR_AMORT_EXPENSE;
        }
        if ("4202".equals(c) || "5302".equals(c)) {
            return CashFlowCategory.DISPOSAL_GAIN_LOSS;
        }
        if ("REVENUE".equalsIgnoreCase(type)) {
            return CashFlowCategory.REVENUE;
        }
        if ("EXPENSE".equalsIgnoreCase(type)) {
            return CashFlowCategory.EXPENSE;
        }
        if (c.startsWith("14") || c.startsWith("15")) {
            return CashFlowCategory.INVESTING;
        }
        if ("2101".equals(c) || c.startsWith("22") || c.startsWith("31") || c.startsWith("32")
                || "3301".equals(c) || "3302".equals(c) || "EQUITY".equalsIgnoreCase(type)) {
            return CashFlowCategory.FINANCING;
        }
        return CashFlowCategory.OPERATING_WC;
    }

    /**
     * 依完整科目表動態解析所有啟用中之「現金及約當現金」科目代碼（不再僅硬編碼 1101/1102）。
     */
    public static Set<String> resolveCashAccountCodes(Connection conn) throws SQLException {
        Set<String> codes = new LinkedHashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT code, name, type FROM accounts WHERE enabled = 1")) {
            while (rs.next()) {
                String code = rs.getString(1);
                String name = rs.getString(2);
                String type = rs.getString(3);
                if (classifyAccount(code, name, type) == CashFlowCategory.CASH) {
                    codes.add(code);
                }
            }
        }
        if (codes.isEmpty()) {
            codes.add("1101");
            codes.add("1102");
        }
        return codes;
    }

    private record EntryRow(String code, String name, String type, long debitMinor, long creditMinor) {}

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
                "SELECT v.id, a.code, a.name, a.type, e.debit_minor, e.credit_minor " +
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
        sql.append(" ORDER BY v.voucher_date, v.id, e.line_no");

        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setString(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long vid = rs.getLong(1);
                    vouchers.computeIfAbsent(vid, k -> new ArrayList<>())
                            .add(new EntryRow(rs.getString(2), rs.getString(3), rs.getString(4), rs.getLong(5), rs.getLong(6)));
                }
            }
        }

        long revNet = 0;
        long expNet = 0;
        long deprAmort = 0;
        long disposalGain = 0;
        long disposalLoss = 0;
        long wcNet = 0;
        long invCashNet = 0;
        long finCashNet = 0;
        long nonCashInvFinTotal = 0;

        Map<String, Long> unpaidInvestingPayables = new HashMap<>();
        Map<String, Long> uncollectedInvestingReceivables = new HashMap<>();

        for (List<EntryRow> rows : vouchers.values()) {
            boolean hasCash = false;
            boolean hasDeprOrAmort = false;
            boolean hasDisposal = false;
            boolean touchesInvesting = false;
            boolean touchesFinancing = false;
            long voucherCashDrMinusCr = 0;
            long invDrMinusCr = 0;
            long invCrMinusDrTotal = 0;
            long disposalGainLossCrMinusDr = 0;
            long financingDr = 0;
            long financingCr = 0;

            for (EntryRow r : rows) {
                CashFlowCategory cat = classifyAccount(r.code(), r.name(), r.type());
                long crMinusDr = r.creditMinor() - r.debitMinor();
                long drMinusCr = r.debitMinor() - r.creditMinor();
                switch (cat) {
                    case CASH -> {
                        hasCash = true;
                        voucherCashDrMinusCr += drMinusCr;
                    }
                    case DEPR_AMORT_EXPENSE -> hasDeprOrAmort = true;
                    case DISPOSAL_GAIN_LOSS -> {
                        hasDisposal = true;
                        disposalGainLossCrMinusDr += crMinusDr;
                    }
                    case INVESTING -> {
                        touchesInvesting = true;
                        invDrMinusCr += drMinusCr;
                        invCrMinusDrTotal += crMinusDr;
                    }
                    case ACCUM_DEPR_AMORT -> invCrMinusDrTotal += crMinusDr;
                    case FINANCING -> {
                        touchesFinancing = true;
                        financingDr += r.debitMinor();
                        financingCr += r.creditMinor();
                    }
                    default -> {
                        // Checked in second pass
                    }
                }
            }

            // 情況 A：購置長期資產（含全額付現、全額賒購、及「部分現金＋部分賒購/負債」混合交易）
            if (touchesInvesting && invDrMinusCr > 0 && !hasDisposal) {
                long cashPaidForInv = hasCash ? Math.min(invDrMinusCr, Math.max(0L, -voucherCashDrMinusCr)) : 0L;
                long nonCashInv = invDrMinusCr - cashPaidForInv;
                invCashNet -= cashPaidForInv;
                nonCashInvFinTotal += nonCashInv;

                long remainingNonCashToMatch = nonCashInv;
                for (EntryRow r : rows) {
                    CashFlowCategory cat = classifyAccount(r.code(), r.name(), r.type());
                    long crMinusDr = r.creditMinor() - r.debitMinor();
                    long drMinusCr = r.debitMinor() - r.creditMinor();
                    switch (cat) {
                        case REVENUE -> revNet += crMinusDr;
                        case EXPENSE -> expNet += drMinusCr;
                        case DEPR_AMORT_EXPENSE -> {
                            expNet += drMinusCr;
                            deprAmort += drMinusCr;
                        }
                        case OPERATING_WC -> {
                            if (crMinusDr > 0 && remainingNonCashToMatch > 0) {
                                long absorbed = Math.min(crMinusDr, remainingNonCashToMatch);
                                unpaidInvestingPayables.merge(r.code(), absorbed, Long::sum);
                                remainingNonCashToMatch -= absorbed;
                                wcNet += (crMinusDr - absorbed);
                            } else {
                                wcNet += crMinusDr;
                            }
                        }
                        case FINANCING -> {
                            if (crMinusDr > 0 && remainingNonCashToMatch > 0) {
                                long absorbed = Math.min(crMinusDr, remainingNonCashToMatch);
                                remainingNonCashToMatch -= absorbed;
                                finCashNet += (crMinusDr - absorbed);
                            } else {
                                finCashNet += crMinusDr;
                            }
                        }
                        case CASH, INVESTING, ACCUM_DEPR_AMORT, DISPOSAL_GAIN_LOSS -> {
                            // Handled above
                        }
                    }
                }
                continue;
            }

            // 情況 B：處分長期資產（含全額收現、應收款尚未收現、部分收現及累計折舊／處分損益沖轉）
            if (hasDisposal || (touchesInvesting && invDrMinusCr < 0)) {
                long totalProceeds = invCrMinusDrTotal + disposalGainLossCrMinusDr;
                long cashReceivedFromDisp = hasCash
                        ? Math.min(Math.max(0L, totalProceeds), Math.max(0L, voucherCashDrMinusCr))
                        : 0L;
                long nonCashDisp = Math.max(0L, totalProceeds - cashReceivedFromDisp);
                invCashNet += cashReceivedFromDisp;
                nonCashInvFinTotal += nonCashDisp;

                long remainingReceivableToMatch = nonCashDisp;
                for (EntryRow r : rows) {
                    CashFlowCategory cat = classifyAccount(r.code(), r.name(), r.type());
                    long crMinusDr = r.creditMinor() - r.debitMinor();
                    long drMinusCr = r.debitMinor() - r.creditMinor();
                    switch (cat) {
                        case REVENUE -> revNet += crMinusDr;
                        case EXPENSE -> expNet += drMinusCr;
                        case DEPR_AMORT_EXPENSE -> {
                            expNet += drMinusCr;
                            deprAmort += drMinusCr;
                        }
                        case DISPOSAL_GAIN_LOSS -> {
                            if (crMinusDr >= 0) {
                                revNet += crMinusDr;
                                disposalGain += crMinusDr;
                            } else {
                                expNet += -crMinusDr;
                                disposalLoss += -crMinusDr;
                            }
                        }
                        case OPERATING_WC -> {
                            if (drMinusCr > 0 && remainingReceivableToMatch > 0) {
                                long absorbed = Math.min(drMinusCr, remainingReceivableToMatch);
                                uncollectedInvestingReceivables.merge(r.code(), absorbed, Long::sum);
                                remainingReceivableToMatch -= absorbed;
                                wcNet += -(drMinusCr - absorbed);
                            } else {
                                wcNet += crMinusDr;
                            }
                        }
                        case FINANCING -> finCashNet += crMinusDr;
                        case CASH, INVESTING, ACCUM_DEPR_AMORT -> {
                            // Handled above
                        }
                    }
                }
                continue;
            }

            // 情況 C：不含直接固定資產異動（純折舊提列、非現金籌資、分期償付設備款、收回處分應收款或一般營運／籌資）
            if (!hasCash && touchesFinancing && !hasDeprOrAmort) {
                nonCashInvFinTotal += Math.max(financingDr, financingCr);
                continue;
            }

            for (EntryRow r : rows) {
                CashFlowCategory cat = classifyAccount(r.code(), r.name(), r.type());
                long crMinusDr = r.creditMinor() - r.debitMinor();
                long drMinusCr = r.debitMinor() - r.creditMinor();
                switch (cat) {
                    case CASH -> {
                        // Target reconciled item
                    }
                    case REVENUE -> revNet += crMinusDr;
                    case EXPENSE -> expNet += drMinusCr;
                    case DEPR_AMORT_EXPENSE -> {
                        expNet += drMinusCr;
                        deprAmort += drMinusCr;
                    }
                    case DISPOSAL_GAIN_LOSS -> {
                        if (crMinusDr >= 0) {
                            revNet += crMinusDr;
                            disposalGain += crMinusDr;
                        } else {
                            expNet += -crMinusDr;
                            disposalLoss += -crMinusDr;
                        }
                    }
                    case OPERATING_WC -> {
                        if (hasCash && drMinusCr > 0) {
                            long pending = unpaidInvestingPayables.getOrDefault(r.code(), 0L);
                            if ("2123".equals(r.code()) || pending > 0) {
                                long matched = "2123".equals(r.code()) ? drMinusCr : Math.min(drMinusCr, pending);
                                if (pending > 0) {
                                    unpaidInvestingPayables.put(r.code(), Math.max(0L, pending - matched));
                                }
                                invCashNet -= matched;
                                long rem = drMinusCr - matched;
                                if (rem > 0) wcNet -= rem;
                                continue;
                            }
                        }
                        if (hasCash && crMinusDr > 0) {
                            long pendingRec = uncollectedInvestingReceivables.getOrDefault(r.code(), 0L);
                            if (pendingRec > 0) {
                                long matched = Math.min(crMinusDr, pendingRec);
                                uncollectedInvestingReceivables.put(r.code(), pendingRec - matched);
                                invCashNet += matched;
                                long rem = crMinusDr - matched;
                                if (rem > 0) wcNet += rem;
                                continue;
                            }
                        }
                        wcNet += crMinusDr;
                    }
                    case FINANCING -> {
                        if (hasCash) {
                            finCashNet += crMinusDr;
                        }
                    }
                    case ACCUM_DEPR_AMORT, INVESTING -> {
                        // Pure depreciation contra-asset lines never affect cash flow
                    }
                }
            }
        }

        long ni = revNet - expNet;
        long disposalAdj = disposalLoss - disposalGain;
        long operating = ni + deprAmort + disposalAdj + wcNet;
        long investing = invCashNet;
        long financing = finCashNet;

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

    /**
     * 檢查推算之期末現金是否與總帳帳列期末現金完全勾稽。
     */
    public static boolean isCashReconciled(List<FinancialStatementItem> cashFlowItems) {
        if (cashFlowItems == null || cashFlowItems.isEmpty()) return false;
        Long endCash = null;
        Long actualCash = null;
        for (FinancialStatementItem item : cashFlowItems) {
            if ("END_CASH".equals(item.code())) endCash = item.amountMinor();
            if ("ACTUAL_CASH".equals(item.code())) actualCash = item.amountMinor();
        }
        return endCash != null && endCash.equals(actualCash);
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
        return sumCashAccounts(conn, start, true);
    }

    private long cashBalanceAsOf(Connection conn, String asOf) throws SQLException {
        return sumCashAccounts(conn, asOf, false);
    }

    private long sumCashAccounts(Connection conn, String dateBound, boolean strictlyBefore) throws SQLException {
        Set<String> cashCodes = resolveCashAccountCodes(conn);
        String placeholders = String.join(",", java.util.Collections.nCopies(cashCodes.size(), "?"));
        StringBuilder sql = new StringBuilder(
                "SELECT COALESCE(SUM(e.debit_minor - e.credit_minor),0) " +
                "FROM voucher_entries e JOIN vouchers v ON v.id=e.voucher_id JOIN accounts a ON a.id=e.account_id " +
                "WHERE v.status='POSTED' AND a.code IN (" + placeholders + ")"
        );
        boolean hasDate = dateBound != null && !dateBound.isBlank();
        if (hasDate) {
            sql.append(strictlyBefore ? " AND v.voucher_date < ?" : " AND v.voucher_date <= ?");
        }
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            for (String code : cashCodes) {
                ps.setString(idx++, code);
            }
            if (hasDate) {
                ps.setString(idx, dateBound.trim());
            }
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
