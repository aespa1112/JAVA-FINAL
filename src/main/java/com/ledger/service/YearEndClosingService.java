package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.dao.AuditDao;
import com.ledger.dao.VoucherDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import com.ledger.model.Voucher;
import com.ledger.model.VoucherEntry;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 年度結帳與反結帳服務：
 * 交易原子性 + 狀態機 + 雙人審核 + 完整歷史保留 + 12月解鎖防護與崩潰復原。
 */
public class YearEndClosingService {

    public static final String VOUCHER_TYPE_YEAR_END = "YEAR_END_CLOSE";
    public static final String VOUCHER_TYPE_YEAR_END_REVERSE = "YEAR_END_REVERSE";

    /** 測試故障注入標記：在 12 月解鎖後立即拋出異常，模擬程序中斷 */
    public static volatile boolean simulateFailureAfterUnlock = false;

    /** 測試故障注入標記：年結傳票建立後，狀態更新失敗 */
    public static volatile boolean simulateFailureAfterYearEndDraftCreate = false;

    /** 測試故障注入標記：年結傳票過帳後，年度狀態更新失敗 */
    public static volatile boolean simulateFailureAfterYearEndVoucherPost = false;

    /** 測試故障注入標記：年結成功後，期間鎖定失敗 */
    public static volatile boolean simulateFailureAfterYearEndPeriodLock = false;

    /** 測試故障注入標記：反結傳票建立後，狀態更新失敗 */
    public static volatile boolean simulateFailureAfterReopenDraftCreate = false;

    /** 測試故障注入標記：反結傳票過帳後，年度狀態更新失敗 */
    public static volatile boolean simulateFailureAfterReopenVoucherPost = false;

    private final VoucherService voucherService = new VoucherService();
    private final VoucherDao voucherDao = new VoucherDao();
    private final AuditDao auditDao = new AuditDao();

    public YearEndClosingService() {
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
        } catch (SQLException ignored) {}
        checkAndRecoverOnStartup();
    }

    public void ensureTable(Connection conn) throws SQLException {
        boolean ac = conn.getAutoCommit();
        conn.setAutoCommit(false);
        try {
            // 1. 建立年結主表與持久化復原表（若不存在）
            try (Statement st = conn.createStatement()) {
                st.execute(
                    "CREATE TABLE IF NOT EXISTS year_end_closings (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "fiscal_year INTEGER NOT NULL, " +
                    "closing_sequence INTEGER NOT NULL DEFAULT 1, " +
                    "closing_version INTEGER NOT NULL DEFAULT 1, " +
                    "voucher_id INTEGER REFERENCES vouchers(id), " +
                    "reverse_voucher_id INTEGER REFERENCES vouchers(id), " +
                    "status TEXT NOT NULL CHECK (status IN ('PENDING_DRAFT','DRAFT','POSTED','REOPEN_PENDING','REOPEN_DRAFT','REVERSED','REOPENED','FAILED')), " +
                    "idempotency_key TEXT NOT NULL, " +
                    "created_by TEXT NOT NULL, " +
                    "reviewed_by TEXT, " +
                    "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), " +
                    "reviewed_at TEXT, " +
                    "closed_at TEXT, " +
                    "fail_reason TEXT, " +
                    "reopen_initiated_by TEXT, " +
                    "reopen_initiated_at TEXT, " +
                    "reopen_reviewed_by TEXT, " +
                    "reopen_reviewed_at TEXT, " +
                    "reopen_reason TEXT, " +
                    "UNIQUE(fiscal_year, closing_sequence))"
                );

                st.execute(
                    "CREATE TABLE IF NOT EXISTS closing_recovery (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "operation_id TEXT NOT NULL UNIQUE, " +
                    "fiscal_year INTEGER NOT NULL, " +
                    "month INTEGER NOT NULL, " +
                    "original_status TEXT NOT NULL, " +
                    "action_type TEXT NOT NULL, " +
                    "recovery_status TEXT NOT NULL CHECK (recovery_status IN ('IN_PROGRESS','COMPLETED','RECOVERED')), " +
                    "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), " +
                    "recovered_at TEXT)"
                );
            }

            // 2. 檢查 year_end_closings 結構遷移（區分欄位已存在與真正遷移失敗）
            java.util.Set<String> yeCols = getTableColumns(conn, "year_end_closings");
            String ddl = null;
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='year_end_closings'")) {
                if (rs.next()) ddl = rs.getString(1);
            }

            if (ddl != null && (ddl.contains("fiscal_year INTEGER NOT NULL UNIQUE") || !yeCols.contains("closing_sequence"))) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE year_end_closings_v3 (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "fiscal_year INTEGER NOT NULL, " +
                        "closing_sequence INTEGER NOT NULL DEFAULT 1, " +
                        "closing_version INTEGER NOT NULL DEFAULT 1, " +
                        "voucher_id INTEGER REFERENCES vouchers(id), " +
                        "reverse_voucher_id INTEGER REFERENCES vouchers(id), " +
                        "status TEXT NOT NULL CHECK (status IN ('PENDING_DRAFT','DRAFT','POSTED','REOPEN_PENDING','REOPEN_DRAFT','REVERSED','REOPENED','FAILED')), " +
                        "idempotency_key TEXT NOT NULL, " +
                        "created_by TEXT NOT NULL, " +
                        "reviewed_by TEXT, " +
                        "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), " +
                        "reviewed_at TEXT, " +
                        "closed_at TEXT, " +
                        "fail_reason TEXT, " +
                        "reopen_initiated_by TEXT, " +
                        "reopen_initiated_at TEXT, " +
                        "reopen_reviewed_by TEXT, " +
                        "reopen_reviewed_at TEXT, " +
                        "reopen_reason TEXT, " +
                        "UNIQUE(fiscal_year, closing_sequence))");

                    String copySql = "INSERT INTO year_end_closings_v3 (" +
                        "id, fiscal_year, closing_sequence, closing_version, voucher_id, reverse_voucher_id, " +
                        "status, idempotency_key, created_by, reviewed_by, created_at, reviewed_at, closed_at, " +
                        "fail_reason, reopen_initiated_by, reopen_initiated_at, reopen_reviewed_by, reopen_reviewed_at, reopen_reason) " +
                        "SELECT id, fiscal_year, " +
                        (yeCols.contains("closing_sequence") ? "closing_sequence" : "1") + ", " +
                        (yeCols.contains("closing_version") ? "closing_version" : "1") + ", " +
                        (yeCols.contains("voucher_id") ? "voucher_id" : "NULL") + ", " +
                        (yeCols.contains("reverse_voucher_id") ? "reverse_voucher_id" : "NULL") + ", " +
                        (yeCols.contains("status") ? "status" : "'DRAFT'") + ", " +
                        (yeCols.contains("idempotency_key") ? "idempotency_key" : "('MIG-' || id)") + ", " +
                        (yeCols.contains("created_by") ? "created_by" : "'SYSTEM'") + ", " +
                        (yeCols.contains("reviewed_by") ? "reviewed_by" : "NULL") + ", " +
                        (yeCols.contains("created_at") ? "created_at" : "datetime('now','localtime')") + ", " +
                        (yeCols.contains("reviewed_at") ? "reviewed_at" : "NULL") + ", " +
                        (yeCols.contains("closed_at") ? "closed_at" : "NULL") + ", " +
                        (yeCols.contains("fail_reason") ? "fail_reason" : "NULL") + ", " +
                        (yeCols.contains("reopen_initiated_by") ? "reopen_initiated_by" : "NULL") + ", " +
                        (yeCols.contains("reopen_initiated_at") ? "reopen_initiated_at" : "NULL") + ", " +
                        (yeCols.contains("reopen_reviewed_by") ? "reopen_reviewed_by" : "NULL") + ", " +
                        (yeCols.contains("reopen_reviewed_at") ? "reopen_reviewed_at" : "NULL") + ", " +
                        (yeCols.contains("reopen_reason") ? "reopen_reason" : "NULL") +
                        " FROM year_end_closings";
                    st.execute(copySql);
                    st.execute("DROP TABLE year_end_closings");
                    st.execute("ALTER TABLE year_end_closings_v3 RENAME TO year_end_closings");
                }
                yeCols = getTableColumns(conn, "year_end_closings");
            }

            safeAddColumn(conn, "year_end_closings", "closing_sequence", "INTEGER NOT NULL DEFAULT 1");
            safeAddColumn(conn, "year_end_closings", "closing_version", "INTEGER NOT NULL DEFAULT 1");
            safeAddColumn(conn, "year_end_closings", "reverse_voucher_id", "INTEGER");
            safeAddColumn(conn, "year_end_closings", "idempotency_key", "TEXT");
            safeAddColumn(conn, "year_end_closings", "fail_reason", "TEXT");
            safeAddColumn(conn, "year_end_closings", "reopen_initiated_by", "TEXT");
            safeAddColumn(conn, "year_end_closings", "reopen_initiated_at", "TEXT");
            safeAddColumn(conn, "year_end_closings", "reopen_reviewed_by", "TEXT");
            safeAddColumn(conn, "year_end_closings", "reopen_reviewed_at", "TEXT");
            safeAddColumn(conn, "year_end_closings", "reopen_reason", "TEXT");

            // 3. 檢查 closing_recovery 結構升級（升級 operation_id 欄位）
            java.util.Set<String> recCols = getTableColumns(conn, "closing_recovery");
            if (!recCols.contains("operation_id")) {
                try (Statement st = conn.createStatement()) {
                    st.execute("CREATE TABLE closing_recovery_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "operation_id TEXT NOT NULL UNIQUE, " +
                        "fiscal_year INTEGER NOT NULL, " +
                        "month INTEGER NOT NULL, " +
                        "original_status TEXT NOT NULL, " +
                        "action_type TEXT NOT NULL, " +
                        "recovery_status TEXT NOT NULL CHECK (recovery_status IN ('IN_PROGRESS','COMPLETED','RECOVERED')), " +
                        "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), " +
                        "recovered_at TEXT)");
                    st.execute("INSERT INTO closing_recovery_new (id, operation_id, fiscal_year, month, original_status, action_type, recovery_status, created_at) " +
                        "SELECT id, 'LEGACY-' || id, fiscal_year, month, original_status, " +
                        (recCols.contains("action_type") ? "action_type" : (recCols.contains("action") ? "action" : "'YEAR_END_DRAFT'")) + ", " +
                        (recCols.contains("recovery_status") ? "recovery_status" : (recCols.contains("state") ? "state" : "'COMPLETED'")) + ", " +
                        "created_at FROM closing_recovery");
                    st.execute("DROP TABLE closing_recovery");
                    st.execute("ALTER TABLE closing_recovery_new RENAME TO closing_recovery");
                }
            }

            // 4. 檢查 vouchers 欄位
            safeAddColumn(conn, "vouchers", "voucher_type", "TEXT NOT NULL DEFAULT 'NORMAL'");
            safeAddColumn(conn, "vouchers", "closing_year", "INTEGER");

            // 5. 索引安全建立
            try (Statement st = conn.createStatement()) {
                st.execute("CREATE INDEX IF NOT EXISTS idx_year_end_fiscal ON year_end_closings(fiscal_year, closing_sequence)");
            }

            conn.commit();
        } catch (SQLException e) {
            conn.rollback();
            throw new SQLException("資料庫年結相關表格遷移升級失敗: " + e.getMessage(), e);
        } finally {
            conn.setAutoCommit(ac);
        }
    }

    public boolean isRetryable(String status) {
        if (status == null) return true;
        return "FAILED".equalsIgnoreCase(status)
                || "PENDING_DRAFT".equalsIgnoreCase(status)
                || "REOPEN_DRAFT".equalsIgnoreCase(status)
                || "REOPEN_PENDING".equalsIgnoreCase(status);
    }

    public boolean isRetryable(int year) {
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM year_end_closings WHERE fiscal_year=? ORDER BY closing_sequence DESC, id DESC LIMIT 1")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return true;
                    return isRetryable(rs.getString("status"));
                }
            }
        } catch (SQLException e) {
            return false;
        }
    }

    public long createYearEndDraft(int year, String preparer) {
        String normPrep = SessionContext.resolveOperator(preparer);
        if (OperatorRole.resolve(normPrep) != OperatorRole.MAKER) {
            throw new BusinessException("年度結帳製單人必須為記帳員（MAKER）");
        }
        if (SessionContext.isLoggedIn()) {
            String su = SessionContext.requireUser();
            if (!su.equalsIgnoreCase(normPrep)) {
                throw new BusinessException("身分不符：當前登入者為 " + su + "，不可代 " + preparer + " 製單");
            }
        } else if (!SessionContext.isTestMode()) {
            throw new BusinessException("尚未登入，無法執行年度結帳製單");
        }

        String yearStart = String.format("%04d-01-01", year);
        String yearEnd = String.format("%04d-12-31", year);

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

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
                throw new BusinessException("缺少會計科目 3301 或 3302");
            }

            List<VoucherEntry> entries = buildClosingEntries(revenues, expenses, accountId3302, accountId3301);
            if (entries.isEmpty()) {
                throw new BusinessException(year + " 年度無收支餘額，無須結帳分錄");
            }
            long dr = 0, cr = 0;
            for (VoucherEntry e : entries) {
                dr += e.debitMinor();
                cr += e.creditMinor();
            }
            if (dr != cr) {
                throw new BusinessException(String.format("結帳分錄借貸不平衡：借 %d / 貸 %d", dr, cr));
            }

            String summary = String.format("年度結帳分錄 %d（收入費用結轉本期損益）", year);
            boolean month12OriginallyClosed = isMonth12Closed(conn, year);

            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                int nextSeq = getNextClosingSequence(conn, year);
                String idemKey = "YE-CLOSE-" + year + "-v" + nextSeq + "-" + UUID.randomUUID().toString().substring(0, 8);

                if (month12OriginallyClosed) {
                    recordRecoveryIntent(conn, year, 12, "CLOSED", "YEAR_END_DRAFT");
                    setMonth12Status(conn, year, "OPEN");
                }

                if (simulateFailureAfterUnlock) {
                    throw new RuntimeException("測試注入：12 月解鎖後模擬程序中斷異常");
                }

                Voucher draft = voucherService.createDraft(conn, yearEnd, summary, normPrep, entries);
                tagVoucherAsYearEnd(conn, draft.id(), year);

                if (simulateFailureAfterYearEndDraftCreate) {
                    throw new RuntimeException("測試注入：年結傳票建立後，狀態更新失敗");
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO year_end_closings (fiscal_year, closing_sequence, closing_version, voucher_id, status, idempotency_key, created_by, created_at) " +
                        "VALUES (?, ?, ?, ?, 'DRAFT', ?, ?, datetime('now','localtime'))")) {
                    ps.setInt(1, year);
                    ps.setInt(2, nextSeq);
                    ps.setInt(3, nextSeq);
                    ps.setLong(4, draft.id());
                    ps.setString(5, idemKey);
                    ps.setString(6, normPrep);
                    ps.executeUpdate();
                }

                if (month12OriginallyClosed) {
                    setMonth12Status(conn, year, "CLOSED");
                    completeRecoveryIntent(conn, year, 12);
                }

                auditDao.log(conn, "YEAR_END_DRAFT_CREATED", "year_end_closings", (long) year, normPrep,
                        null, "DRAFT; seq=" + nextSeq + "; voucher=" + draft.id() + "; key=" + idemKey);

                conn.commit();
                return draft.id();
            } catch (Exception e) {
                conn.rollback();
                handleSqlException("建立年結草稿失敗", e);
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("年度結帳草稿失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(ac);
            }
        } catch (SQLException e) {
            handleSqlException("資料庫連線或操作失敗", e);
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        }
    }

    public long reviewAndPostYearEnd(int year, String reviewer) {
        String normRev = SessionContext.resolveOperator(reviewer);
        if (OperatorRole.resolve(normRev) != OperatorRole.CHECKER) {
            throw new BusinessException("年度結帳審核人必須為會計師（CHECKER）");
        }
        if (SessionContext.isLoggedIn()) {
            String su = SessionContext.requireUser();
            if (!su.equalsIgnoreCase(normRev)) {
                throw new BusinessException("身分不符：當前登入者為 " + su + "，不可代 " + reviewer + " 覆核");
            }
        } else if (!SessionContext.isTestMode()) {
            throw new BusinessException("尚未登入，無法執行年度結帳覆核");
        }

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            long closingId;
            long voucherId;
            String createdBy;
            int closingSeq;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, voucher_id, status, created_by, closing_sequence " +
                    "FROM year_end_closings WHERE fiscal_year=? AND status='DRAFT' " +
                    "ORDER BY closing_sequence DESC, id DESC LIMIT 1")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        try (PreparedStatement ps2 = conn.prepareStatement(
                                "SELECT status FROM year_end_closings WHERE fiscal_year=? ORDER BY closing_sequence DESC, id DESC LIMIT 1")) {
                            ps2.setInt(1, year);
                            try (ResultSet rs2 = ps2.executeQuery()) {
                                if (rs2.next() && "POSTED".equals(rs2.getString(1))) {
                                    throw new BusinessException(year + " 年度已存在結帳紀錄（已完成結帳），禁止重複結帳");
                                }
                            }
                        }
                        throw new BusinessException(year + " 年度尚未建立結帳草稿");
                    }
                    closingId = rs.getLong("id");
                    voucherId = rs.getLong("voucher_id");
                    createdBy = rs.getString("created_by");
                    closingSeq = rs.getInt("closing_sequence");
                }
            }

            if (normRev.equalsIgnoreCase(createdBy)) {
                throw new BusinessException(String.format(
                        "職責分離原則：制單人（%s）不得覆核自己的年度結帳傳票，必須由會計師（CHECKER）覆核！", createdBy));
            }

            Voucher voucher = voucherDao.findById(conn, voucherId)
                    .orElseThrow(() -> new BusinessException("結帳傳票不存在 ID " + voucherId));

            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (!voucher.isPosted()) {
                    voucherService.post(conn, voucherId, normRev);
                }

                Voucher checked = voucherDao.findById(conn, voucherId)
                        .orElseThrow(() -> new BusinessException("結帳傳票不存在 ID " + voucherId));
                if (!checked.isPosted()) {
                    throw new BusinessException("結帳傳票尚未過帳，不得將年度標記為 POSTED");
                }

                if (simulateFailureAfterYearEndVoucherPost) {
                    throw new RuntimeException("測試注入：年結傳票過帳後，年度狀態更新失敗");
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE year_end_closings SET status='POSTED', reviewed_by=?, " +
                        "reviewed_at=datetime('now','localtime'), closed_at=datetime('now','localtime') " +
                        "WHERE id=? AND status='DRAFT'")) {
                    ps.setString(1, normRev);
                    ps.setLong(2, closingId);
                    if (ps.executeUpdate() != 1) {
                        throw new BusinessException("結帳狀態更新為 POSTED 失敗");
                    }
                }

                if (simulateFailureAfterYearEndPeriodLock) {
                    throw new RuntimeException("測試注入：年結成功後，期間鎖定失敗");
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE fiscal_periods SET status='CLOSED' WHERE year=?")) {
                    ps.setInt(1, year);
                    ps.executeUpdate();
                }

                auditDao.log(conn, "YEAR_END_CLOSE", "year_end_closings", (long) year, normRev,
                        "DRAFT", "POSTED; voucher=" + voucherId + "; seq=" + closingSeq);

                conn.commit();
                return voucherId;
            } catch (Exception e) {
                conn.rollback();
                handleSqlException("年度結帳審核過帳失敗", e);
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("年度結帳過帳失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(ac);
            }
        } catch (SQLException e) {
            handleSqlException("資料庫操作異常", e);
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        }
    }

    public List<Long> closeYear(int year, String preparer, String reviewer) {
        if (preparer != null && reviewer != null && preparer.trim().equalsIgnoreCase(reviewer.trim())) {
            throw new BusinessException("職責分離原則：制單人與審核人不可為同一人");
        }
        createYearEndDraft(year, preparer);
        long postedId = reviewAndPostYearEnd(year, reviewer);
        return List.of(postedId);
    }

    public List<Long> closeYear(int year, String operator) {
        throw new BusinessException("請使用 closeYear(year, preparer, reviewer) 進行完整雙人結帳流程");
    }

    public long initiateReopenYear(int year, String initiator, String reason) {
        String normOp = SessionContext.resolveOperator(initiator);
        if (OperatorRole.resolve(normOp) != OperatorRole.CHECKER) {
            throw new BusinessException("年度反結帳僅會計師可發起");
        }
        if (SessionContext.isLoggedIn()) {
            String su = SessionContext.requireUser();
            if (!su.equalsIgnoreCase(normOp)) {
                throw new BusinessException("身分不符：當前登入者為 " + su + "，不可代 " + initiator + " 執行");
            }
        } else if (!SessionContext.isTestMode()) {
            throw new BusinessException("尚未登入，無法執行年度反結帳");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("年度反結帳必須填寫原因");
        }

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            long closingId;
            long voucherId;
            int closingSeq;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, voucher_id, reverse_voucher_id, status, closing_sequence " +
                    "FROM year_end_closings WHERE fiscal_year=? " +
                    "ORDER BY closing_sequence DESC, id DESC LIMIT 1")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new BusinessException(year + " 年度未結帳");
                    String st = rs.getString("status");

                    if ("REOPEN_DRAFT".equals(st)) {
                        long existingRevId = rs.getLong("reverse_voucher_id");
                        if (existingRevId > 0) {
                            return existingRevId;
                        }
                    }

                    if ("REVERSED".equals(st) || "REOPENED".equals(st)) {
                        throw new BusinessException(year + " 年度反結帳已完成，無需重複執行");
                    }

                    if (!"POSTED".equals(st) && !"REOPEN_PENDING".equals(st)) {
                        throw new BusinessException("結帳狀態非 POSTED，無法反結，當前: " + st);
                    }
                    closingId = rs.getLong("id");
                    voucherId = rs.getLong("voucher_id");
                    closingSeq = rs.getInt("closing_sequence");
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

            String yearEnd = String.format("%04d-12-31", year);
            boolean month12OriginallyClosed = isMonth12Closed(conn, year);

            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (month12OriginallyClosed) {
                    setMonth12Status(conn, year, "OPEN");
                }

                Voucher revDraft = voucherService.reverse(conn, voucherId, yearEnd, "年度反結帳: " + reason.trim(), normOp);
                tagVoucherAsYearEndReverse(conn, revDraft.id(), year);

                if (simulateFailureAfterReopenDraftCreate) {
                    throw new RuntimeException("測試注入：反結傳票建立後，狀態更新失敗");
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE year_end_closings SET status='REOPEN_DRAFT', reverse_voucher_id=?, reopen_initiated_by=?, " +
                        "reopen_initiated_at=datetime('now','localtime'), reopen_reason=? WHERE id=? AND status IN ('POSTED','REOPEN_PENDING','REOPEN_DRAFT')")) {
                    ps.setLong(1, revDraft.id());
                    ps.setString(2, normOp);
                    ps.setString(3, reason.trim());
                    ps.setLong(4, closingId);
                    if (ps.executeUpdate() != 1) {
                        throw new BusinessException("反結狀態更新為 REOPEN_DRAFT 失敗");
                    }
                }

                if (month12OriginallyClosed) {
                    setMonth12Status(conn, year, "CLOSED");
                }

                auditDao.log(conn, "YEAR_END_REOPEN_INITIATED", "year_end_closings", (long) year, normOp,
                        "POSTED", "REOPEN_DRAFT; revVoucher=" + revDraft.id() + "; seq=" + closingSeq + "; reason=" + reason.trim());

                conn.commit();
                return revDraft.id();
            } catch (Exception e) {
                conn.rollback();
                handleSqlException("發起反結失敗", e);
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("建立紅字沖銷傳票失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(ac);
            }
        } catch (SQLException e) {
            handleSqlException("資料庫操作異常", e);
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        }
    }

    public long confirmReopenYear(int year, String reviewer) {
        String normRev = SessionContext.resolveOperator(reviewer);
        if (OperatorRole.resolve(normRev) != OperatorRole.CHECKER) {
            throw new BusinessException("年度反結帳審核人必須為會計師");
        }
        if (SessionContext.isLoggedIn()) {
            String su = SessionContext.requireUser();
            if (!su.equalsIgnoreCase(normRev)) {
                throw new BusinessException("身分不符：當前登入者為 " + su + "，不可代 " + reviewer + " 覆核");
            }
        } else if (!SessionContext.isTestMode()) {
            throw new BusinessException("尚未登入，無法執行年度反結帳覆核");
        }

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            long closingId;
            long revVoucherId;
            String initiator;
            int closingSeq;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, reverse_voucher_id, status, reopen_initiated_by, closing_sequence " +
                    "FROM year_end_closings WHERE fiscal_year=? AND status='REOPEN_DRAFT' " +
                    "ORDER BY closing_sequence DESC, id DESC LIMIT 1")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        try (PreparedStatement ps2 = conn.prepareStatement(
                                "SELECT status FROM year_end_closings WHERE fiscal_year=? ORDER BY closing_sequence DESC, id DESC LIMIT 1")) {
                            ps2.setInt(1, year);
                            try (ResultSet rs2 = ps2.executeQuery()) {
                                if (rs2.next()) {
                                    String st = rs2.getString(1);
                                    if ("REVERSED".equals(st) || "REOPENED".equals(st)) {
                                        throw new BusinessException(year + " 年度反結帳已完成，無需重複執行");
                                    }
                                    throw new BusinessException("年度反結帳尚未建立反結草稿，當前狀態: " + st);
                                }
                            }
                        }
                        throw new BusinessException(year + " 年度未結帳");
                    }
                    closingId = rs.getLong("id");
                    revVoucherId = rs.getLong("reverse_voucher_id");
                    initiator = rs.getString("reopen_initiated_by");
                    closingSeq = rs.getInt("closing_sequence");
                }
            }

            if (initiator != null && initiator.equalsIgnoreCase(normRev)) {
                throw new BusinessException(String.format(
                        "職責分離原則：反結發起人（%s）不得自行審核結帳反結沖銷傳票，必須由第二位會計師審核！", initiator));
            }

            Voucher rv = voucherDao.findById(conn, revVoucherId)
                    .orElseThrow(() -> new BusinessException("沖銷傳票不存在 ID " + revVoucherId));

            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (!rv.isPosted()) {
                    voucherService.post(conn, revVoucherId, normRev);
                }

                Voucher checked = voucherDao.findById(conn, revVoucherId)
                        .orElseThrow(() -> new BusinessException("沖銷傳票不存在 ID " + revVoucherId));
                if (!checked.isPosted()) {
                    throw new BusinessException("沖銷傳票尚未過帳，不得將年度標記為 REVERSED");
                }

                if (simulateFailureAfterReopenVoucherPost) {
                    throw new RuntimeException("測試注入：反結傳票過帳後，年度狀態更新失敗");
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE year_end_closings SET status='REVERSED', reopen_reviewed_by=?, " +
                        "reopen_reviewed_at=datetime('now','localtime') WHERE id=? AND status='REOPEN_DRAFT'")) {
                    ps.setString(1, normRev);
                    ps.setLong(2, closingId);
                    if (ps.executeUpdate() != 1) {
                        throw new BusinessException("反結狀態更新為 REVERSED 失敗");
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE fiscal_periods SET status='OPEN' WHERE year=?")) {
                    ps.setInt(1, year);
                    ps.executeUpdate();
                }

                auditDao.log(conn, "YEAR_END_REOPEN_COMPLETED", "year_end_closings", (long) year, normRev,
                        "REOPEN_DRAFT", "REVERSED; revVoucher=" + revVoucherId + "; seq=" + closingSeq);

                conn.commit();
                return revVoucherId;
            } catch (Exception e) {
                conn.rollback();
                handleSqlException("確認反結失敗", e);
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("確認年度反結帳失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(ac);
            }
        } catch (SQLException e) {
            handleSqlException("資料庫操作異常", e);
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        }
    }

    public long reopenYear(int year, String initiator, String reviewer, String reason) {
        if (initiator != null && reviewer != null && initiator.trim().equalsIgnoreCase(reviewer.trim())) {
            throw new BusinessException("職責分離原則：反結發起人與審核人不可為同一人");
        }
        initiateReopenYear(year, initiator, reason);
        return confirmReopenYear(year, reviewer);
    }

    public long reopenYear(int year, String operator, String reason) {
        if (SessionContext.isTestMode()) {
            String norm = OperatorRole.normalize(operator);
            String reviewer = "david".equalsIgnoreCase(norm) ? "bob" : "david";
            return reopenYear(year, norm, reviewer, reason);
        }
        throw new BusinessException("請使用 initiateReopenYear 與 confirmReopenYear 進行雙人反結帳");
    }

    public List<String> checkConsistency() {
        List<String> issues = new ArrayList<>();
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            // 1. 檢查結帳主傳票狀態與 year_end_closings 一致性（涵蓋歷史所有版本）
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT y.fiscal_year, y.closing_sequence, y.status, y.voucher_id, v.status AS v_status " +
                         "FROM year_end_closings y " +
                         "LEFT JOIN vouchers v ON v.id = y.voucher_id " +
                         "WHERE y.voucher_id IS NOT NULL")) {
                while (rs.next()) {
                    int yr = rs.getInt("fiscal_year");
                    int seq = rs.getInt("closing_sequence");
                    String yStatus = rs.getString("status");
                    long vId = rs.getLong("voucher_id");
                    String vStatus = rs.getString("v_status");
                    if (vStatus == null) {
                        issues.add(String.format("年度 %d (序號 %d, 狀態 %s) 關聯之結帳傳票 ID %d 不存在", yr, seq, yStatus, vId));
                    } else if (("POSTED".equals(yStatus) || "REVERSED".equals(yStatus) || "REOPENED".equals(yStatus)) && !"POSTED".equals(vStatus)) {
                        issues.add(String.format("年度 %d (序號 %d) 結帳狀態為 %s，但傳票 ID %d 狀態為 %s", yr, seq, yStatus, vId, vStatus));
                    } else if ("DRAFT".equals(yStatus) && !"DRAFT".equals(vStatus)) {
                        issues.add(String.format("年度 %d (序號 %d) 結帳狀態為 DRAFT，但傳票 ID %d 狀態為 %s", yr, seq, vId, vStatus));
                    }
                }
            }

            // 2. 檢查反結沖銷傳票一致性
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT y.fiscal_year, y.closing_sequence, y.status, y.reverse_voucher_id, v.status AS rv_status " +
                         "FROM year_end_closings y " +
                         "LEFT JOIN vouchers v ON v.id = y.reverse_voucher_id " +
                         "WHERE y.reverse_voucher_id IS NOT NULL")) {
                while (rs.next()) {
                    int yr = rs.getInt("fiscal_year");
                    int seq = rs.getInt("closing_sequence");
                    String yStatus = rs.getString("status");
                    long rvId = rs.getLong("reverse_voucher_id");
                    String rvStatus = rs.getString("rv_status");
                    if (rvStatus == null) {
                        issues.add(String.format("年度 %d (序號 %d) 反結沖銷傳票 ID %d 不存在", yr, seq, rvId));
                    } else if (("REVERSED".equals(yStatus) || "REOPENED".equals(yStatus)) && !"POSTED".equals(rvStatus)) {
                        issues.add(String.format("年度 %d (序號 %d) 已標記 %s，但反結傳票 ID %d 狀態非 POSTED（當前: %s）", yr, seq, yStatus, rvId, rvStatus));
                    } else if ("REOPEN_DRAFT".equals(yStatus) && !"DRAFT".equals(rvStatus) && !"POSTED".equals(rvStatus)) {
                        issues.add(String.format("年度 %d (序號 %d) 狀態為 REOPEN_DRAFT，但反結傳票 ID %d 狀態異常: %s", yr, seq, rvId, rvStatus));
                    }
                }
            }

            // 3. 檢查孤立年結傳票（有憑證卻無任何 year_end_closings 紀錄關聯）
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT v.id, v.voucher_no, v.closing_year FROM vouchers v " +
                         "WHERE v.voucher_type IN ('YEAR_END_CLOSE','YEAR_END_REVERSE') " +
                         "AND NOT EXISTS (SELECT 1 FROM year_end_closings y WHERE y.voucher_id = v.id OR y.reverse_voucher_id = v.id)")) {
                while (rs.next()) {
                    issues.add(String.format("孤立年結傳票 ID %d (%s, 年度 %d) 缺少 year_end_closings 紀錄",
                            rs.getLong("id"), rs.getString("voucher_no"), rs.getInt("closing_year")));
                }
            }

            // 4. 檢查當前有效 POSTED 年度之期間狀態：12 個月必須全為 CLOSED
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT y.fiscal_year, COUNT(p.month) AS unclosed_count " +
                         "FROM year_end_closings y " +
                         "JOIN fiscal_periods p ON p.year = y.fiscal_year AND p.status <> 'CLOSED' " +
                         "WHERE y.status = 'POSTED' " +
                         "  AND y.closing_sequence = (SELECT MAX(y2.closing_sequence) FROM year_end_closings y2 WHERE y2.fiscal_year = y.fiscal_year) " +
                         "GROUP BY y.fiscal_year")) {
                while (rs.next()) {
                    issues.add(String.format("年度 %d 已結帳（POSTED），但仍有 %d 個月份期間未鎖定（非 CLOSED）",
                            rs.getInt("fiscal_year"), rs.getInt("unclosed_count")));
                }
            }
        } catch (SQLException e) {
            issues.add("檢查一致性資料庫異常: " + e.getMessage());
        }
        return issues;
    }

    public int repairConsistency() {
        int repaired = 0;
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            // 修正 POSTED 年度若有未鎖定的月份
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT DISTINCT y.fiscal_year FROM year_end_closings y WHERE y.status='POSTED' " +
                         "AND y.closing_sequence = (SELECT MAX(y2.closing_sequence) FROM year_end_closings y2 WHERE y2.fiscal_year = y.fiscal_year)")) {
                List<Integer> years = new ArrayList<>();
                while (rs.next()) years.add(rs.getInt(1));
                for (int y : years) {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE fiscal_periods SET status='CLOSED' WHERE year=? AND status<>'CLOSED'")) {
                        ps.setInt(1, y);
                        repaired += ps.executeUpdate();
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("WARN: 一致性修復異常: " + e.getMessage());
        }
        return repaired;
    }

    public int checkAndRecoverOnStartup() {
        return recoverUnfinishedClosing();
    }

    public int recoverUnfinishedClosing() {
        int repaired = 0;
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                // 依據明確的持久化操作識別紀錄 (closing_recovery) 進行受控復原，移除僅依月份狀態推定崩潰的猜測式邏輯
                String sql = "SELECT id, operation_id, fiscal_year, month, original_status, action_type " +
                             "FROM closing_recovery WHERE recovery_status = 'IN_PROGRESS' ORDER BY id ASC";
                List<RecoveryItem> inProgressList = new ArrayList<>();
                try (Statement st = conn.createStatement();
                     ResultSet rs = st.executeQuery(sql)) {
                    while (rs.next()) {
                        inProgressList.add(new RecoveryItem(
                                rs.getLong("id"),
                                rs.getString("operation_id"),
                                rs.getInt("fiscal_year"),
                                rs.getInt("month"),
                                rs.getString("original_status"),
                                rs.getString("action_type")
                        ));
                    }
                }

                for (RecoveryItem item : inProgressList) {
                    // 已完成年結不得被復原程式解除鎖定
                    boolean yearCompleted = false;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "SELECT 1 FROM year_end_closings WHERE fiscal_year=? AND status='POSTED'")) {
                        ps.setInt(1, item.year);
                        try (ResultSet rs = ps.executeQuery()) {
                            yearCompleted = rs.next();
                        }
                    }

                    if (yearCompleted) {
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE closing_recovery SET recovery_status='COMPLETED', recovered_at=datetime('now','localtime') WHERE id=?")) {
                            ps.setLong(1, item.id);
                            ps.executeUpdate();
                        }
                    } else {
                        // 只能復原確定屬於未完成年結的操作
                        if ("YEAR_END_DRAFT".equals(item.actionType) || "YEAR_END_CLOSE".equals(item.actionType)) {
                            try (PreparedStatement ps = conn.prepareStatement(
                                    "UPDATE fiscal_periods SET status=? WHERE year=? AND month=?")) {
                                ps.setString(1, item.originalStatus);
                                ps.setInt(2, item.year);
                                ps.setInt(3, item.month);
                                repaired += ps.executeUpdate();
                            }

                            try (PreparedStatement ps = conn.prepareStatement(
                                    "UPDATE closing_recovery SET recovery_status='RECOVERED', recovered_at=datetime('now','localtime') WHERE id=?")) {
                                ps.setLong(1, item.id);
                                ps.executeUpdate();
                            }

                            auditDao.log(conn, "STARTUP_RECOVERY", "fiscal_periods", (long) item.year, "SYSTEM",
                                    "OPEN", "Restored month " + item.month + " to " + item.originalStatus + " for unfinished op " + item.operationId);
                        }
                    }
                }

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(ac);
            }
        } catch (SQLException e) {
            System.err.println("WARN: checkAndRecoverOnStartup 異常: " + e.getMessage());
        }
        return repaired;
    }

    private static class RecoveryItem {
        final long id;
        final String operationId;
        final int year;
        final int month;
        final String originalStatus;
        final String actionType;

        RecoveryItem(long id, String operationId, int year, int month, String originalStatus, String actionType) {
            this.id = id;
            this.operationId = operationId;
            this.year = year;
            this.month = month;
            this.originalStatus = originalStatus;
            this.actionType = actionType;
        }
    }

        public record YearEndClosingRecord(
            long id,
            int fiscalYear,
            int closingSequence,
            int closingVersion,
            Long voucherId,
            Long reverseVoucherId,
            String status,
            String idempotencyKey,
            String createdBy,
            String reviewedBy,
            String createdAt,
            String reviewedAt,
            String closedAt,
            String failReason,
            String reopenInitiatedBy,
            String reopenInitiatedAt,
            String reopenReviewedBy,
            String reopenReviewedAt,
            String reopenReason
    ) {}

    public List<YearEndClosingRecord> getClosingHistory(int year) {
        List<YearEndClosingRecord> list = new ArrayList<>();
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, fiscal_year, closing_sequence, closing_version, voucher_id, reverse_voucher_id, " +
                    "status, idempotency_key, created_by, reviewed_by, created_at, reviewed_at, closed_at, " +
                    "fail_reason, reopen_initiated_by, reopen_initiated_at, reopen_reviewed_by, reopen_reviewed_at, reopen_reason " +
                    "FROM year_end_closings WHERE fiscal_year=? ORDER BY closing_sequence ASC, id ASC")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(mapRecord(rs));
                    }
                }
            }
        } catch (SQLException e) {
            throw new BusinessException("讀取年結歷史失敗: " + e.getMessage(), e);
        }
        return list;
    }

    public YearEndClosingRecord getLatestClosing(int year) {
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, fiscal_year, closing_sequence, closing_version, voucher_id, reverse_voucher_id, " +
                    "status, idempotency_key, created_by, reviewed_by, created_at, reviewed_at, closed_at, " +
                    "fail_reason, reopen_initiated_by, reopen_initiated_at, reopen_reviewed_by, reopen_reviewed_at, reopen_reason " +
                    "FROM year_end_closings WHERE fiscal_year=? ORDER BY closing_sequence DESC, id DESC LIMIT 1")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() ? mapRecord(rs) : null;
                }
            }
        } catch (SQLException e) {
            throw new BusinessException("讀取年結紀錄失敗: " + e.getMessage(), e);
        }
    }

    private YearEndClosingRecord mapRecord(ResultSet rs) throws SQLException {
        long vId = rs.getLong("voucher_id");
        Long voucherId = rs.wasNull() ? null : vId;
        long rvId = rs.getLong("reverse_voucher_id");
        Long reverseVoucherId = rs.wasNull() ? null : rvId;
        return new YearEndClosingRecord(
                rs.getLong("id"),
                rs.getInt("fiscal_year"),
                rs.getInt("closing_sequence"),
                rs.getInt("closing_version"),
                voucherId,
                reverseVoucherId,
                rs.getString("status"),
                rs.getString("idempotency_key"),
                rs.getString("created_by"),
                rs.getString("reviewed_by"),
                rs.getString("created_at"),
                rs.getString("reviewed_at"),
                rs.getString("closed_at"),
                rs.getString("fail_reason"),
                rs.getString("reopen_initiated_by"),
                rs.getString("reopen_initiated_at"),
                rs.getString("reopen_reviewed_by"),
                rs.getString("reopen_reviewed_at"),
                rs.getString("reopen_reason")
        );
    }

    private void assertNoActiveClosing(Connection conn, int year) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT id, status, closing_sequence FROM year_end_closings " +
                "WHERE fiscal_year=? AND status IN ('PENDING_DRAFT','DRAFT','POSTED','REOPEN_PENDING','REOPEN_DRAFT') " +
                "ORDER BY closing_sequence DESC, id DESC LIMIT 1")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String st = rs.getString("status");
                    if ("POSTED".equals(st)) {
                        throw new BusinessException(year + " 年度已存在結帳紀錄（已完成結帳），禁止重複結帳");
                    } else if ("DRAFT".equals(st)) {
                        throw new BusinessException(year + " 年度已有結帳草稿待審核，請審核結帳或取消重試");
                    } else {
                        throw new BusinessException(year + " 年度已有進行中之結帳/反結流程（狀態: " + st + "），請先完成或重試");
                    }
                }
            }
        }
    }

    private int getNextClosingSequence(Connection conn, int year) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT COALESCE(MAX(closing_sequence), 0) + 1 FROM year_end_closings WHERE fiscal_year = ?")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 1;
            }
        }
    }

    public void recordRecoveryIntent(Connection conn, String operationId, int year, int month, String originalStatus, String actionType) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO closing_recovery (operation_id, fiscal_year, month, original_status, action_type, recovery_status, created_at) " +
                "VALUES (?, ?, ?, ?, ?, 'IN_PROGRESS', datetime('now','localtime'))")) {
            ps.setString(1, operationId);
            ps.setInt(2, year);
            ps.setInt(3, month);
            ps.setString(4, originalStatus);
            ps.setString(5, actionType);
            ps.executeUpdate();
        }
    }

    public void recordRecoveryIntent(Connection conn, int year, int month, String originalStatus, String actionType) throws SQLException {
        String opId = "REC-" + year + "-" + UUID.randomUUID().toString().substring(0, 8);
        recordRecoveryIntent(conn, opId, year, month, originalStatus, actionType);
    }

    public void completeRecoveryIntent(Connection conn, String operationId) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE closing_recovery SET recovery_status='COMPLETED', recovered_at=datetime('now','localtime') " +
                "WHERE operation_id=? AND recovery_status='IN_PROGRESS'")) {
            ps.setString(1, operationId);
            ps.executeUpdate();
        }
    }

    public void completeRecoveryIntent(Connection conn, int year, int month) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE closing_recovery SET recovery_status='COMPLETED', recovered_at=datetime('now','localtime') " +
                "WHERE fiscal_year=? AND month=? AND recovery_status='IN_PROGRESS'")) {
            ps.setInt(1, year);
            ps.setInt(2, month);
            ps.executeUpdate();
        }
    }

    private void safeAddColumn(Connection conn, String table, String column, String definition) throws SQLException {
        java.util.Set<String> cols = getTableColumns(conn, table);
        if (!cols.contains(column.toLowerCase())) {
            try (Statement st = conn.createStatement()) {
                st.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
            } catch (SQLException e) {
                java.util.Set<String> recheck = getTableColumns(conn, table);
                if (!recheck.contains(column.toLowerCase())) {
                    throw new SQLException("資料表 [" + table + "] 結構升級失敗，無法新增欄位 [" + column + "]: " + e.getMessage(), e);
                }
            }
        }
    }

    private java.util.Set<String> getTableColumns(Connection conn, String table) throws SQLException {
        java.util.Set<String> set = new java.util.HashSet<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                set.add(rs.getString("name").toLowerCase());
            }
        }
        return set;
    }

    private void assertTwelveMonthsClosed(Connection conn, int year) throws SQLException {
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
                                "年度結帳前須先完成各月份關帳：%d年%02d月尚未結帳鎖定", year, m));
                    }
                }
            }
        }
    }

    private boolean isMonth12Closed(Connection conn, int year) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status FROM fiscal_periods WHERE year=? AND month=12")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && "CLOSED".equals(rs.getString("status"));
            }
        }
    }

    private void setMonth12Status(Connection conn, int year, String status) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE fiscal_periods SET status=? WHERE year=? AND month=12")) {
            ps.setString(1, status);
            ps.setInt(2, year);
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
        String sql =
            "SELECT COUNT(*) FROM vouchers origin " +
            "WHERE origin.status='POSTED' AND origin.voucher_date >= ? AND origin.voucher_date <= ? " +
            "AND EXISTS (SELECT 1 FROM vouchers r WHERE r.reversed_id = origin.id AND r.status='DRAFT')";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, start);
            ps.setString(2, end);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next() && rs.getInt(1) > 0) {
                    throw new BusinessException("該年度尚有未完成之沖銷更正分錄，請先過帳或刪除後再結帳");
                }
            }
        }
    }

    private void validateNoAbnormalSkipped(List<AcctBal> revenues, List<AcctBal> expenses) {
        List<String> problems = new ArrayList<>();
        for (AcctBal b : revenues) {
            if (b.net < 0) {
                problems.add(String.format("收入科目 %s (%s) 出現借方餘額 %d 分，請人工查核（退款？更正？）",
                        b.code, b.name, b.net));
            }
        }
        for (AcctBal b : expenses) {
            if (b.net < 0) {
                problems.add(String.format("費用科目 %s (%s) 出現貸方餘額 %d 分，請人工查核（折讓？沖回？）",
                        b.code, b.name, b.net));
            }
        }
        if (!problems.isEmpty()) {
            throw new BusinessException("年度結帳中斷，發現異常借貸餘額：\n" + String.join("\n", problems));
        }
    }

    private List<VoucherEntry> buildClosingEntries(List<AcctBal> revenues, List<AcctBal> expenses,
                                                    long id3302, long id3301) {
        List<VoucherEntry> entries = new ArrayList<>();
        int line = 1;
        long totalRev = 0, totalExp = 0;
        for (AcctBal b : revenues) {
            if (b.net == 0) continue;
            entries.add(new VoucherEntry(line++, b.accountId, "結轉收入 " + b.code, b.net, 0));
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
                entries.add(new VoucherEntry(line++, b.accountId, "結轉費用 " + b.code, 0, b.net));
            }
        }
        long netIncome = totalRev - totalExp;
        if (netIncome > 0) {
            entries.add(new VoucherEntry(line++, id3302, "本期損益結轉累積盈虧", netIncome, 0));
            entries.add(new VoucherEntry(line++, id3301, "本期純益轉入", 0, netIncome));
        } else if (netIncome < 0) {
            long loss = -netIncome;
            entries.add(new VoucherEntry(line++, id3301, "本期純損轉入", loss, 0));
            entries.add(new VoucherEntry(line++, id3302, "本期損益結轉累積盈虧", 0, loss));
        }
        return entries;
    }

    private void tagVoucherAsYearEnd(Connection conn, long voucherId, int year) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE vouchers SET voucher_type=?, closing_year=? WHERE id=?")) {
            ps.setString(1, VOUCHER_TYPE_YEAR_END);
            ps.setInt(2, year);
            ps.setLong(3, voucherId);
            ps.executeUpdate();
        }
    }

    private void tagVoucherAsYearEndReverse(Connection conn, long voucherId, int year) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE vouchers SET voucher_type=?, closing_year=? WHERE id=?")) {
            ps.setString(1, VOUCHER_TYPE_YEAR_END_REVERSE);
            ps.setInt(2, year);
            ps.setLong(3, voucherId);
            ps.executeUpdate();
        }
    }

    private void handleSqlException(String context, Exception e) {
        if (e instanceof SQLException sqle) {
            String msg = sqle.getMessage() == null ? "" : sqle.getMessage().toLowerCase();
            int errorCode = sqle.getErrorCode();
            if (msg.contains("busy") || msg.contains("locked") || errorCode == 5 || errorCode == 6) {
                throw new BusinessException(context + "：資料庫忙碌 (BUSY/LOCKED)，請稍後再試", sqle);
            }
            if (msg.contains("full") || errorCode == 13) {
                throw new BusinessException(context + "：磁碟空間不足 (DISK FULL)，無法完成交易", sqle);
            }
            if (msg.contains("closed") || msg.contains("connection")) {
                throw new BusinessException(context + "：資料庫連線中斷，請重新連線", sqle);
            }
        }
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
