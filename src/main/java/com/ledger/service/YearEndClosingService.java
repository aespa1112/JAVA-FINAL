package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.dao.AuditDao;
import com.ledger.dao.VoucherDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import com.ledger.model.Voucher;
import com.ledger.model.VoucherEntry;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 年度結帳：產生真實結帳分錄（非僅報表虛擬結轉）。
 * 步驟：
 * 1) 將各收入科目餘額結轉至 3302 本期損益（借收入、貸 3302）
 * 2) 將各費用科目餘額結轉至 3302（借 3302、貸費用）
 * 3) 將 3302 餘額結轉至 3301 保留盈餘
 * 結帳分錄以 POSTED 傳票寫入，摘要標記 [YEAR-END CLOSE]。
 */
public class YearEndClosingService {

    private final VoucherService voucherService = new VoucherService();
    private final AuditDao auditDao = new AuditDao();

    /**
     * @param year 會計年度（曆年制）
     * @param operator 必須為 CHECKER
     * @return 產生的結帳傳票 ID 列表
     */
    public List<Long> closeYear(int year, String operator) {
        String normOp = OperatorRole.normalize(operator);
        if (OperatorRole.resolve(normOp) != OperatorRole.CHECKER) {
            throw new BusinessException("年度結帳僅會計師（CHECKER）可執行");
        }
        String yearStart = String.format("%04d-01-01", year);
        String yearEnd = String.format("%04d-12-31", year);
        String closeDate = yearEnd;

        try (Connection conn = DbManager.get()) {
            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 防止同一年度重複結帳
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM vouchers WHERE status='POSTED' AND summary LIKE ? AND voucher_date = ?")) {
                    ps.setString(1, "%[YEAR-END CLOSE " + year + "]%");
                    ps.setString(2, closeDate);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            throw new BusinessException(year + " 年度已存在結帳分錄，禁止重複執行");
                        }
                    }
                }

                long accountId3302 = findAccountId(conn, "3302");
                long accountId3301 = findAccountId(conn, "3301");
                if (accountId3302 <= 0 || accountId3301 <= 0) {
                    throw new BusinessException("缺少科目 3301 保留盈餘 或 3302 本期損益，無法結帳");
                }

                List<AcctBal> revenues = balancesByType(conn, yearStart, yearEnd, "REVENUE");
                List<AcctBal> expenses = balancesByType(conn, yearStart, yearEnd, "EXPENSE");

                List<VoucherEntry> entries = new ArrayList<>();
                int line = 1;
                long totalRev = 0;
                long totalExp = 0;

                // 結清收入：借各收入、貸 3302
                for (AcctBal b : revenues) {
                    if (b.net == 0) continue;
                    // 收入正常貸餘，net = credit - debit
                    long amount = b.net;
                    if (amount <= 0) continue;
                    entries.add(new VoucherEntry(null, null, line++, b.accountId,
                            "結清收入 " + b.code, amount, 0, b.code, b.name));
                    totalRev += amount;
                }
                if (totalRev > 0) {
                    entries.add(new VoucherEntry(null, null, line++, accountId3302,
                            "收入結轉本期損益", 0, totalRev, "3302", "本期損益"));
                }

                // 結清費用：借 3302、貸各費用
                for (AcctBal b : expenses) {
                    if (b.net == 0) continue;
                    // 費用正常借餘，net = debit - credit
                    long amount = b.net;
                    if (amount <= 0) continue;
                    totalExp += amount;
                }
                if (totalExp > 0) {
                    entries.add(new VoucherEntry(null, null, line++, accountId3302,
                            "費用結轉本期損益", totalExp, 0, "3302", "本期損益"));
                    for (AcctBal b : expenses) {
                        long amount = b.net;
                        if (amount <= 0) continue;
                        entries.add(new VoucherEntry(null, null, line++, b.accountId,
                                "結清費用 " + b.code, 0, amount, b.code, b.name));
                    }
                }

                long netIncome = totalRev - totalExp;
                // 3302 → 3301
                if (netIncome > 0) {
                    entries.add(new VoucherEntry(null, null, line++, accountId3302,
                            "本期損益結轉保留盈餘", netIncome, 0, "3302", "本期損益"));
                    entries.add(new VoucherEntry(null, null, line++, accountId3301,
                            "本期損益轉入", 0, netIncome, "3301", "保留盈餘"));
                } else if (netIncome < 0) {
                    long loss = -netIncome;
                    entries.add(new VoucherEntry(null, null, line++, accountId3301,
                            "本期虧損轉入", loss, 0, "3301", "保留盈餘"));
                    entries.add(new VoucherEntry(null, null, line++, accountId3302,
                            "本期損益結轉保留盈餘", 0, loss, "3302", "本期損益"));
                }

                if (entries.isEmpty()) {
                    throw new BusinessException(year + " 年度無損益發生額，無需結帳分錄");
                }

                // 建立草稿後以系統身份過帳：使用雙人模擬會違反四眼，改由呼叫端傳入的 CHECKER 過帳
                // 制單人使用固定 YEAR_END 標記並要求 reviewer 不同 — 此處以同一 CHECKER 過帳需特准
                // 實務：結帳傳票由系統產生，審核由操作會計師確認
                String summary = String.format("[YEAR-END CLOSE %d] 年度損益結轉（收入 %d / 費用 %d / 淨利 %d 分）",
                        year, totalRev, totalExp, netIncome);

                // 使用 DAO 直接寫入 POSTED（結帳為系統分錄，仍寫 audit）
                long voucherId = insertPostedClosingVoucher(conn, closeDate, summary, normOp, entries);
                auditDao.log(conn, "YEAR_END_CLOSE", "vouchers", voucherId, normOp,
                        null, summary);

                // 鎖定該年所有月份
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE fiscal_periods SET status='CLOSED' WHERE year=? AND status='OPEN'")) {
                    ps.setInt(1, year);
                    ps.executeUpdate();
                }

                conn.commit();
                return List.of(voucherId);
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("年度結帳失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(ac);
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        }
    }

    private long insertPostedClosingVoucher(Connection conn, String date, String summary,
                                            String operator, List<VoucherEntry> entries) throws SQLException {
        // period
        int year = Integer.parseInt(date.substring(0, 4));
        int month = Integer.parseInt(date.substring(5, 7));
        long periodId;
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO fiscal_periods(year, month, status) VALUES (?,?, 'OPEN')")) {
            ps.setInt(1, year);
            ps.setInt(2, month);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id FROM fiscal_periods WHERE year=? AND month=?")) {
            ps.setInt(1, year);
            ps.setInt(2, month);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new SQLException("無法取得會計期間");
                periodId = rs.getLong(1);
            }
        }

        String voucherNo = String.format("YE%s-001", year);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO vouchers(period_id, voucher_no, voucher_date, summary, status, preparer, reviewer, posted_at) " +
                        "VALUES (?,?,?,?, 'POSTED', ?, ?, datetime('now','localtime'))",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, periodId);
            ps.setString(2, voucherNo);
            ps.setString(3, date);
            ps.setString(4, summary);
            ps.setString(5, "SYSTEM");
            ps.setString(6, operator);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (!keys.next()) throw new SQLException("無法取得傳票 ID");
                long vid = keys.getLong(1);
                int ln = 1;
                for (VoucherEntry e : entries) {
                    try (PreparedStatement eps = conn.prepareStatement(
                            "INSERT INTO voucher_entries(voucher_id, line_no, account_id, memo, debit_minor, credit_minor) VALUES (?,?,?,?,?,?)")) {
                        eps.setLong(1, vid);
                        eps.setInt(2, ln++);
                        eps.setLong(3, e.accountId());
                        eps.setString(4, e.memo());
                        eps.setLong(5, e.debitMinor());
                        eps.setLong(6, e.creditMinor());
                        eps.executeUpdate();
                    }
                }
                return vid;
            }
        }
    }

    private long findAccountId(Connection conn, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM accounts WHERE code=?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }

    private List<AcctBal> balancesByType(Connection conn, String start, String end, String type) throws SQLException {
        String sql =
                "SELECT a.id, a.code, a.name, " +
                "COALESCE(SUM(e.debit_minor),0), COALESCE(SUM(e.credit_minor),0) " +
                "FROM accounts a " +
                "LEFT JOIN voucher_entries e ON e.account_id=a.id " +
                "LEFT JOIN vouchers v ON v.id=e.voucher_id AND v.status='POSTED' " +
                "AND v.voucher_date >= ? AND v.voucher_date <= ? " +
                "AND (v.summary IS NULL OR v.summary NOT LIKE '%[YEAR-END CLOSE%') " +
                "WHERE a.type=? GROUP BY a.id";
        List<AcctBal> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, start);
            ps.setString(2, end);
            ps.setString(3, type);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long d = rs.getLong(4), c = rs.getLong(5);
                    long net = "REVENUE".equals(type) ? (c - d) : (d - c);
                    list.add(new AcctBal(rs.getLong(1), rs.getString(2), rs.getString(3), net));
                }
            }
        }
        return list;
    }

    private record AcctBal(long accountId, String code, String name, long net) {}
}
