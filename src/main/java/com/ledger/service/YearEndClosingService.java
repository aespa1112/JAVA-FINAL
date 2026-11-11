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
import java.util.UUID;

/**
 * 年度損益結轉：狀態機 + 冪等鍵 + 明確結帳類型（不依賴摘要文字）。
 *
 * 狀態：PENDING_DRAFT → DRAFT → POSTED → REVERSED
 * 任一步失敗不留下無法識別的不一致；失敗時清理孤立 DRAFT 或標記 FAILED。
 */
public class YearEndClosingService {

    public static final String VOUCHER_TYPE_YEAR_END = "YEAR_END_CLOSE";
    public static final String VOUCHER_TYPE_YEAR_END_REVERSE = "YEAR_END_REVERSE";

    private final VoucherService voucherService = new VoucherService();
    private final AuditDao auditDao = new AuditDao();

    public void ensureTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS year_end_closings (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "fiscal_year INTEGER NOT NULL UNIQUE, " +
                "voucher_id INTEGER REFERENCES vouchers(id), " +
                "reverse_voucher_id INTEGER REFERENCES vouchers(id), " +
                "status TEXT NOT NULL CHECK (status IN ('PENDING_DRAFT','DRAFT','POSTED','REVERSED','FAILED')), " +
                "idempotency_key TEXT NOT NULL UNIQUE, " +
                "created_by TEXT NOT NULL, " +
                "reviewed_by TEXT, " +
                "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), " +
                "reviewed_at TEXT, " +
                "closed_at TEXT, " +
                "fail_reason TEXT)"
            );
        }
        // 相容舊表：補欄位
        for (String alter : List.of(
                "ALTER TABLE year_end_closings ADD COLUMN reverse_voucher_id INTEGER",
                "ALTER TABLE year_end_closings ADD COLUMN idempotency_key TEXT",
                "ALTER TABLE year_end_closings ADD COLUMN fail_reason TEXT"
        )) {
            try (Statement st = conn.createStatement()) {
                st.execute(alter);
            } catch (SQLException ignored) {
            }
        }
        // vouchers.voucher_type
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE vouchers ADD COLUMN voucher_type TEXT NOT NULL DEFAULT 'NORMAL'");
        } catch (SQLException ignored) {
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE vouchers ADD COLUMN closing_year INTEGER");
        } catch (SQLException ignored) {
        }
    }

    /**
     * 雙人年結：制單人建立草稿並寫入 year_end_closings(DRAFT)，審核人過帳後改 POSTED 並鎖期間。
     * 使用冪等鍵防重複；失敗標記 FAILED 並嘗試清理孤立草稿。
     */
    public List<Long> closeYear(int year, String preparer, String reviewer) {
        String normPrep = SessionContext.resolveOperator(preparer);
        // 審核人不可與制單人相同；正式環境審核人由呼叫端傳入但必須是 CHECKER
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
        // 登入者必須是制單人或審核人之一
        if (SessionContext.isLoggedIn()) {
            String su = SessionContext.requireUser();
            if (!su.equalsIgnoreCase(normPrep) && !su.equalsIgnoreCase(normRev)) {
                throw new BusinessException("目前登入者必須是制單人或審核人之一");
            }
        } else if (!SessionContext.isTestMode()) {
            throw new BusinessException("尚未登入，拒絕年度結帳");
        }

        String yearStart = String.format("%04d-01-01", year);
        String yearEnd = String.format("%04d-12-31", year);
        String idemKey = "YE-CLOSE-" + year + "-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            // ---- 前置檢查（同一連線）----
            assertNoActiveClosing(conn, year);
            assertTwelveMonthsClosed(conn, year);
            assertNoDrafts(conn, yearStart, yearEnd);
            assertNoIncompleteReversals(conn, yearStart, yearEnd);

            List<AcctBal> revenues = balancesByType(conn, yearStart, yearEnd, "REVENUE");
            List<AcctBal> expenses = balancesByType(conn, yearStart, yearEnd, "EXPENSE");
            validateNoAbnormalSkipped(revenues, expenses);

            long accountId3302 = findAccountId(conn, "3302");
            long accountId3301 = findAccountId(conn, "3301");
            if (accountId3302 <= 0 || accountId3301 <= 0) {
                throw new BusinessException("缺少科目 3301 或 3302");
            }

            List<VoucherEntry> entries = buildClosingEntries(revenues, expenses, accountId3302, accountId3301);
            if (entries.isEmpty()) {
                throw new BusinessException(year + " 年度無損益發生額，無需結帳分錄");
            }
            long dr = 0, cr = 0;
            for (VoucherEntry e : entries) {
                dr += e.debitMinor();
                cr += e.creditMinor();
            }
            if (dr != cr) {
                throw new BusinessException(String.format("結帳分錄借貸不平衡：借 %d / 貸 %d", dr, cr));
            }

            String summary = String.format("年度損益結轉 %d（收入/費用結清至保留盈餘）", year);

            // 狀態：先插入 PENDING_DRAFT（冪等鍵）
            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            long closingRowId;
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO year_end_closings(fiscal_year, status, idempotency_key, created_by) VALUES (?,'PENDING_DRAFT',?,?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setInt(1, year);
                    ps.setString(2, idemKey);
                    ps.setString(3, normPrep);
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) throw new BusinessException("無法建立 year_end_closings 列");
                        closingRowId = keys.getLong(1);
                    }
                }
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                if (e.getMessage() != null && e.getMessage().toLowerCase().contains("unique")) {
                    throw new BusinessException(year + " 年度已存在結帳紀錄，禁止重複結帳");
                }
                throw new BusinessException("建立結帳紀錄失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(ac);
            }

            // 建立草稿（VoucherService 自有交易）
            Voucher draft;
            try {
                draft = voucherService.createDraft(yearEnd, summary, normPrep, entries);
                tagVoucherAsYearEnd(draft.id(), year);
            } catch (Exception e) {
                markFailed(year, "建立草稿失敗: " + e.getMessage());
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("年度結帳草稿失敗: " + e.getMessage(), e);
            }

            // 更新為 DRAFT + voucher_id
            try (Connection c2 = DbManager.get()) {
                ensureTable(c2);
                try (PreparedStatement ps = c2.prepareStatement(
                        "UPDATE year_end_closings SET status='DRAFT', voucher_id=? WHERE id=? AND status='PENDING_DRAFT'")) {
                    ps.setLong(1, draft.id());
                    ps.setLong(2, closingRowId);
                    if (ps.executeUpdate() != 1) {
                        throw new BusinessException("結帳狀態轉換 PENDING_DRAFT→DRAFT 失敗（可能重複操作）");
                    }
                }
            } catch (Exception e) {
                markFailed(year, "DRAFT 狀態更新失敗: " + e.getMessage());
                cleanupOrphanDraft(draft.id());
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException(e.getMessage(), e);
            }

            // 過帳
            try {
                voucherService.post(draft.id(), normRev);
            } catch (Exception e) {
                markFailed(year, "過帳失敗: " + e.getMessage());
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("年度結帳過帳失敗: " + e.getMessage(), e);
            }

            // POSTED + 鎖期間
            try (Connection c2 = DbManager.get()) {
                ensureTable(c2);
                boolean ac2 = c2.getAutoCommit();
                c2.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = c2.prepareStatement(
                            "UPDATE year_end_closings SET status='POSTED', reviewed_by=?, " +
                            "reviewed_at=datetime('now','localtime'), closed_at=datetime('now','localtime') " +
                            "WHERE fiscal_year=? AND status='DRAFT'")) {
                        ps.setString(1, normRev);
                        ps.setInt(2, year);
                        if (ps.executeUpdate() != 1) {
                            throw new BusinessException("結帳狀態轉換 DRAFT→POSTED 失敗");
                        }
                    }
                    try (PreparedStatement ps = c2.prepareStatement(
                            "UPDATE fiscal_periods SET status='CLOSED' WHERE year=? AND status='OPEN'")) {
                        ps.setInt(1, year);
                        ps.executeUpdate();
                    }
                    auditDao.log(c2, "YEAR_END_CLOSE", "year_end_closings", (long) year, normRev,
                            "DRAFT", "POSTED; voucher=" + draft.id() + "; key=" + idemKey);
                    c2.commit();
                } catch (Exception e) {
                    c2.rollback();
                    markFailed(year, "POSTED/鎖期間失敗: " + e.getMessage());
                    throw e;
                } finally {
                    c2.setAutoCommit(ac2);
                }
            }

            return List.of(draft.id());
        } catch (SQLException e) {
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("年度結帳失敗: " + e.getMessage(), e);
        }
    }

    public List<Long> closeYear(int year, String operator) {
        throw new BusinessException("請使用 closeYear(year, preparer, reviewer)");
    }

    public long reopenYear(int year, String operator, String reason) {
        String norm = SessionContext.resolveOperator(operator);
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
                            throw new BusinessException("結帳狀態非 POSTED，無法反結（目前: " + rs.getString("status") + ")");
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
                        "UPDATE year_end_closings SET status='REVERSED' WHERE fiscal_year=? AND status='POSTED'")) {
                    ps.setInt(1, year);
                    if (ps.executeUpdate() != 1) {
                        throw new BusinessException("反結狀態轉換失敗");
                    }
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
        tagVoucherAsYearEndReverse(revDraft.id(), year);
        try (Connection c2 = DbManager.get()) {
            ensureTable(c2);
            try (PreparedStatement ps = c2.prepareStatement(
                    "UPDATE year_end_closings SET reverse_voucher_id=? WHERE fiscal_year=?")) {
                ps.setLong(1, revDraft.id());
                ps.setInt(2, year);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // 反結主狀態已完成；關聯更新失敗僅記錄
            System.err.println("WARN: reverse_voucher_id 更新失敗: " + e.getMessage());
        }
        return revDraft.id();
    }

    // ---------- 前置檢查 ----------

    private void assertNoActiveClosing(Connection conn, int year) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status FROM year_end_closings WHERE fiscal_year=?")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String st = rs.getString(1);
                    if ("FAILED".equals(st)) {
                        // 允許清理後重試：刪除 FAILED 列
                        try (PreparedStatement del = conn.prepareStatement(
                                "DELETE FROM year_end_closings WHERE fiscal_year=? AND status='FAILED'")) {
                            del.setInt(1, year);
                            del.executeUpdate();
                        }
                    } else {
                        throw new BusinessException(year + " 年度已存在結帳紀錄（狀態=" + st + "），禁止重複結帳");
                    }
                }
            }
        }
    }

    private void assertTwelveMonthsClosed(Connection conn, int year) throws SQLException {
        // 1–11 月必須 CLOSED；12 月必須存在（年結分錄落在 12/31，可於年結流程中鎖定）
        for (int m = 1; m <= 12; m++) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM fiscal_periods WHERE year=? AND month=?")) {
                ps.setInt(1, year);
                ps.setInt(2, m);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new BusinessException(String.format(
                                "年度結帳前須具備完整十二個月期間：%d年%02d月不存在", year, m));
                    }
                    String st = rs.getString("status");
                    if (m < 12 && !"CLOSED".equals(st)) {
                        throw new BusinessException(String.format(
                                "年度結帳前須完成各月月結：%d年%02d月尚未結帳鎖定", year, m));
                    }
                }
            }
        }
        // 若 12 月已鎖，暫時開啟以允許年結分錄（過帳後會再全部鎖定）
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE fiscal_periods SET status='OPEN' WHERE year=? AND month=12 AND status='CLOSED'")) {
            ps.setInt(1, year);
            ps.executeUpdate();
        }
    }

    private void assertNoDrafts(Connection conn, String start, String end) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COUNT(*) FROM vouchers WHERE status='DRAFT' AND voucher_date >= ? AND voucher_date <= ?")) {
            ps.setString(1, start);
            ps.setString(2, end);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    throw new BusinessException("該年度尚有未過帳草稿傳票，請先處理後再結帳");
                }
            }
        }
    }

    private void assertNoIncompleteReversals(Connection conn, String start, String end) throws SQLException {
        // 已沖銷原票但紅字草稿未過帳
        String sql =
            "SELECT COUNT(*) FROM vouchers origin " +
            "WHERE origin.status='POSTED' AND origin.voucher_date >= ? AND origin.voucher_date <= ? " +
            "AND EXISTS (SELECT 1 FROM vouchers r WHERE r.reversed_id = origin.id AND r.status='DRAFT')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, start);
            ps.setString(2, end);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    throw new BusinessException("該年度尚有未完成之紅字沖銷草稿，請先過帳或刪除後再結帳");
                }
            }
        }
    }

    /**
     * P1-06：不得跳過 net==0 以外的異常方向；異常時停止結帳。
     * 收入正常淨額 >= 0（貸餘）；若 net < 0（借餘）需人工核對。
     * 費用正常淨額 >= 0（借餘）；若 net < 0（貸餘）需人工核對。
     * net==0 可略過（無發生額）。
     */
    private void validateNoAbnormalSkipped(List<AcctBal> revenues, List<AcctBal> expenses) {
        List<String> problems = new ArrayList<>();
        for (AcctBal b : revenues) {
            if (b.net < 0) {
                problems.add(String.format("收入科目 %s (%s) 出現借方餘額 %d 分，請先人工核對（退貨/更正）",
                        b.code, b.name, b.net));
            }
        }
        for (AcctBal b : expenses) {
            if (b.net < 0) {
                problems.add(String.format("費用科目 %s (%s) 出現貸方餘額 %d 分，請先人工核對（更正分錄）",
                        b.code, b.name, b.net));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException("年度結帳中止，發現異常借貸餘額：\n" + String.join("\n", problems));
        }
    }

    private List<VoucherEntry> buildClosingEntries(List<AcctBal> revenues, List<AcctBal> expenses,
                                                    long id3302, long id3301) {
        List<VoucherEntry> entries = new ArrayList<>();
        int line = 1;
        long totalRev = 0, totalExp = 0;
        for (AcctBal b : revenues) {
            if (b.net == 0) continue;
            // 收入貸餘：借收入、貸 3302
            entries.add(new VoucherEntry(line++, b.accountId, "結清收入 " + b.code, b.net, 0));
            totalRev += b.net;
        }
        if (totalRev > 0) {
            entries.add(new VoucherEntry(line++, id3302, "收入結轉本期損益", 0, totalRev));
        }
        for (AcctBal b : expenses) {
            if (b.net == 0) continue;
            totalExp += b.net;
        }
        if (totalExp > 0) {
            entries.add(new VoucherEntry(line++, id3302, "費用結轉本期損益", totalExp, 0));
            for (AcctBal b : expenses) {
                if (b.net == 0) continue;
                entries.add(new VoucherEntry(line++, b.accountId, "結清費用 " + b.code, 0, b.net));
            }
        }
        long netIncome = totalRev - totalExp;
        if (netIncome > 0) {
            entries.add(new VoucherEntry(line++, id3302, "本期損益結轉保留盈餘", netIncome, 0));
            entries.add(new VoucherEntry(line++, id3301, "本期損益轉入", 0, netIncome));
        } else if (netIncome < 0) {
            long loss = -netIncome;
            entries.add(new VoucherEntry(line++, id3301, "本期虧損轉入", loss, 0));
            entries.add(new VoucherEntry(line++, id3302, "本期損益結轉保留盈餘", 0, loss));
        }
        return entries;
    }

    private void tagVoucherAsYearEnd(long voucherId, int year) {
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE vouchers SET voucher_type=?, closing_year=? WHERE id=?")) {
                ps.setString(1, VOUCHER_TYPE_YEAR_END);
                ps.setInt(2, year);
                ps.setLong(3, voucherId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            throw new BusinessException("標記年結傳票類型失敗: " + e.getMessage(), e);
        }
    }

    private void tagVoucherAsYearEndReverse(long voucherId, int year) {
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE vouchers SET voucher_type=?, closing_year=? WHERE id=?")) {
                ps.setString(1, VOUCHER_TYPE_YEAR_END_REVERSE);
                ps.setInt(2, year);
                ps.setLong(3, voucherId);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            System.err.println("WARN: 標記反結傳票類型失敗: " + e.getMessage());
        }
    }

    private void markFailed(int year, String reason) {
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE year_end_closings SET status='FAILED', fail_reason=? WHERE fiscal_year=? AND status IN ('PENDING_DRAFT','DRAFT')")) {
                ps.setString(1, reason != null && reason.length() > 500 ? reason.substring(0, 500) : reason);
                ps.setInt(2, year);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            System.err.println("WARN: markFailed: " + e.getMessage());
        }
    }

    private void cleanupOrphanDraft(long voucherId) {
        try {
            // 僅刪草稿；已過帳不可刪
            voucherService.deleteDraft(voucherId, SessionContext.isLoggedIn()
                    ? SessionContext.getUser() : "Amy");
        } catch (Exception e) {
            System.err.println("WARN: 孤立草稿清理失敗 id=" + voucherId + ": " + e.getMessage());
        }
    }

    /**
     * 依類型取科目餘額。排除 voucher_type 為年結相關傳票（不依賴摘要文字）。
     */
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
            "    AND (v.voucher_type IS NULL OR v.voucher_type = 'NORMAL' OR v.voucher_type = '') " +
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
