package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.dao.AuditDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import com.ledger.model.Voucher;
import com.ledger.model.VoucherEntry;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class YearEndClosingService {

    private final VoucherService voucherService = new VoucherService();
    private final AuditDao auditDao = new AuditDao();

    public void ensureTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS year_end_closings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "fiscal_year INTEGER NOT NULL UNIQUE, " +
                "voucher_id INTEGER NOT NULL REFERENCES vouchers(id), " +
                "status TEXT NOT NULL CHECK (status IN ('DRAFT','POSTED','REVERSED')), " +
                "created_by TEXT NOT NULL, " +
                "reviewed_by TEXT, " +
                "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), " +
                "reviewed_at TEXT, " +
                "closed_at TEXT)"
            );
        }
    }

    public List<Long> closeYear(int year, String preparer, String reviewer) {
        String normPrep = OperatorRole.normalize(preparer);
        String normRev = OperatorRole.normalize(reviewer);
        if (OperatorRole.resolve(normPrep) != OperatorRole.MAKER) {
            throw new BusinessException("年度結帳制單人必須為記帳員（MAKER）");
        }
        if (OperatorRole.resolve(normRev) != OperatorRole.CHECKER) {
            throw new BusinessException("年度結帳審核人必須為會計師（CHECKER）");
        }
        if (normPrep.equalsIgnoreCase(normRev)) {
            throw new BusinessException("職責分離：制單人與審核人不得為同一人");
        }
        if (SessionContext.isLoggedIn()) {
            String su = SessionContext.getUser();
            if (!su.equalsIgnoreCase(normPrep) && !su.equalsIgnoreCase(normRev)) {
                throw new BusinessException("目前登入者必須是制單人或審核人之一");
            }
        }

        String yearStart = String.format("%04d-01-01", year);
        String yearEnd = String.format("%04d-12-31", year);

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, status FROM year_end_closings WHERE fiscal_year=?")) {
                    ps.setInt(1, year);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new BusinessException(year + " 年度已存在結帳紀錄，禁止重複結帳");
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM vouchers WHERE status='DRAFT' AND voucher_date >= ? AND voucher_date <= ?")) {
                    ps.setString(1, yearStart);
                    ps.setString(2, yearEnd);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            throw new BusinessException("該年度尚有未過帳草稿，請先處理後再結帳");
                        }
                    }
                }

                List<AcctBal> revenues = balancesByType(conn, yearStart, yearEnd, "REVENUE");
                List<AcctBal> expenses = balancesByType(conn, yearStart, yearEnd, "EXPENSE");
                long accountId3302 = findAccountId(conn, "3302");
                long accountId3301 = findAccountId(conn, "3301");
                if (accountId3302 <= 0 || accountId3301 <= 0) {
                    throw new BusinessException("缺少科目 3301 或 3302");
                }

                List<VoucherEntry> entries = new ArrayList<>();
                int line = 1;
                long totalRev = 0, totalExp = 0;
                for (AcctBal b : revenues) {
                    if (b.net <= 0) continue;
                    entries.add(new VoucherEntry(line++, b.accountId, "結清收入 " + b.code, b.net, 0));
                    totalRev += b.net;
                }
                if (totalRev > 0) {
                    entries.add(new VoucherEntry(line++, accountId3302, "收入結轉本期損益", 0, totalRev));
                }
                for (AcctBal b : expenses) {
                    if (b.net <= 0) continue;
                    totalExp += b.net;
                }
                if (totalExp > 0) {
                    entries.add(new VoucherEntry(line++, accountId3302, "費用結轉本期損益", totalExp, 0));
                    for (AcctBal b : expenses) {
                        if (b.net <= 0) continue;
                        entries.add(new VoucherEntry(line++, b.accountId, "結清費用 " + b.code, 0, b.net));
                    }
                }
                long netIncome = totalRev - totalExp;
                if (netIncome > 0) {
                    entries.add(new VoucherEntry(line++, accountId3302, "本期損益結轉保留盈餘", netIncome, 0));
                    entries.add(new VoucherEntry(line++, accountId3301, "本期損益轉入", 0, netIncome));
                } else if (netIncome < 0) {
                    long loss = -netIncome;
                    entries.add(new VoucherEntry(line++, accountId3301, "本期虧損轉入", loss, 0));
                    entries.add(new VoucherEntry(line++, accountId3302, "本期損益結轉保留盈餘", 0, loss));
                }
                if (entries.isEmpty()) {
                    throw new BusinessException(year + " 年度無損益發生額，無需結帳分錄");
                }
                long dr = 0, cr = 0;
                for (VoucherEntry e : entries) { dr += e.debitMinor(); cr += e.creditMinor(); }
                if (dr != cr) {
                    throw new BusinessException(String.format("結帳分錄借貸不平衡：借 %d / 貸 %d", dr, cr));
                }

                String summary = String.format("[YEAR-END CLOSE %d] 年度損益結轉（收入 %d / 費用 %d / 淨利 %d 分）",
                        year, totalRev, totalExp, netIncome);
                conn.commit();
                conn.setAutoCommit(ac);

                Voucher draft = voucherService.createDraft(yearEnd, summary, normPrep, entries);
                try (Connection c2 = DbManager.get()) {
                    ensureTable(c2);
                    try (PreparedStatement ps = c2.prepareStatement(
                            "INSERT INTO year_end_closings(fiscal_year, voucher_id, status, created_by) VALUES (?,?, 'DRAFT', ?)")) {
                        ps.setInt(1, year);
                        ps.setLong(2, draft.id());
                        ps.setString(3, normPrep);
                        ps.executeUpdate();
                    }
                }
                voucherService.post(draft.id(), normRev);
                try (Connection c2 = DbManager.get()) {
                    ensureTable(c2);
                    boolean ac2 = c2.getAutoCommit();
                    c2.setAutoCommit(false);
                    try {
                        try (PreparedStatement ps = c2.prepareStatement(
                                "UPDATE year_end_closings SET status='POSTED', reviewed_by=?, reviewed_at=datetime('now','localtime'), closed_at=datetime('now','localtime') WHERE fiscal_year=?")) {
                            ps.setString(1, normRev);
                            ps.setInt(2, year);
                            ps.executeUpdate();
                        }
                        try (PreparedStatement ps = c2.prepareStatement(
                                "UPDATE fiscal_periods SET status='CLOSED' WHERE year=? AND status='OPEN'")) {
                            ps.setInt(1, year);
                            ps.executeUpdate();
                        }
                        auditDao.log(c2, "YEAR_END_CLOSE", "year_end_closings", (long) year, normRev,
                                "DRAFT", "POSTED; voucher=" + draft.id());
                        c2.commit();
                    } catch (Exception e) {
                        c2.rollback();
                        throw e;
                    } finally {
                        c2.setAutoCommit(ac2);
                    }
                }
                return List.of(draft.id());
            } catch (Exception e) {
                try { conn.rollback(); } catch (Exception ignored) {}
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("年度結帳失敗: " + e.getMessage(), e);
            } finally {
                try { conn.setAutoCommit(ac); } catch (Exception ignored) {}
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        }
    }

    public List<Long> closeYear(int year, String operator) {
        throw new BusinessException("請使用 closeYear(year, preparer, reviewer)");
    }

    public long reopenYear(int year, String operator, String reason) {
        String norm = SessionContext.isLoggedIn()
                ? SessionContext.resolveOperator(operator)
                : OperatorRole.normalize(operator);
        if (OperatorRole.resolve(norm) != OperatorRole.CHECKER) {
            throw new BusinessException("年度反結帳僅會計師可執行");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("年度反結帳必須填寫原因");
        }
        long voucherId;
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id, voucher_id, status FROM year_end_closings WHERE fiscal_year=?")) {
                    ps.setInt(1, year);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new BusinessException(year + " 年度無結帳紀錄");
                        if (!"POSTED".equals(rs.getString("status"))) {
                            throw new BusinessException("結帳狀態非 POSTED，無法反結");
                        }
                        voucherId = rs.getLong("voucher_id");
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT fiscal_year FROM year_end_closings WHERE fiscal_year > ? AND status='POSTED' LIMIT 1")) {
                    ps.setInt(1, year);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new BusinessException("後續年度已結帳，請先反結後續年度");
                        }
                    }
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE year_end_closings SET status='REVERSED' WHERE fiscal_year=?")) {
                    ps.setInt(1, year);
                    ps.executeUpdate();
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE fiscal_periods SET status='OPEN' WHERE year=?")) {
                    ps.setInt(1, year);
                    ps.executeUpdate();
                }
                auditDao.log(conn, "YEAR_END_REOPEN", "year_end_closings", (long) year, norm,
                        "POSTED", "REVERSED; reason=" + reason.trim());
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("年度反結帳失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(ac);
            }
        } catch (SQLException e) {
            throw new BusinessException(e.getMessage(), e);
        }
        Voucher revDraft = voucherService.reverse(voucherId, "年度反結帳: " + reason.trim(), norm);
        return revDraft.id();
    }

    private List<AcctBal> balancesByType(Connection conn, String start, String end, String type) throws SQLException {
        String sql =
            "SELECT a.id, a.code, a.name, COALESCE(agg.dr,0) AS dr, COALESCE(agg.cr,0) AS cr " +
            "FROM accounts a " +
            "LEFT JOIN ( " +
            "  SELECT e.account_id, SUM(e.debit_minor) AS dr, SUM(e.credit_minor) AS cr " +
            "  FROM voucher_entries e " +
            "  INNER JOIN vouchers v ON v.id = e.voucher_id " +
            "  WHERE v.status = 'POSTED' " +
            "    AND v.voucher_date >= ? AND v.voucher_date <= ? " +
            "    AND (v.summary IS NULL OR v.summary NOT LIKE '%[YEAR-END CLOSE%') " +
            "  GROUP BY e.account_id " +
            ") agg ON agg.account_id = a.id " +
            "WHERE a.type = ?";
        List<AcctBal> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, start);
            ps.setString(2, end);
            ps.setString(3, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long d = rs.getLong("dr"), c = rs.getLong("cr");
                    long net = "REVENUE".equals(type) ? (c - d) : (d - c);
                    list.add(new AcctBal(rs.getLong("id"), rs.getString("code"), rs.getString("name"), net));
                }
            }
        }
        return list;
    }

    private long findAccountId(Connection conn, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM accounts WHERE code=?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private record AcctBal(long accountId, String code, String name, long net) {}
}
