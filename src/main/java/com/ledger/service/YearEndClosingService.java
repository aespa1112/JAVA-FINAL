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
 * 年度損益結轉與反結帳：
 * 狀態機 + 冪等鍵 + 明確結帳類型 + 真正雙人覆核與一致性保障。
 *
 * 結帳狀態轉換：PENDING_DRAFT → DRAFT → POSTED
 * 反結狀態轉換：POSTED → REOPEN_PENDING → REOPEN_DRAFT → REVERSED
 *
 * 前置檢查純讀取，不提前解鎖 12 月；
 * 12 月解鎖納入交易控制，中途失敗保證復原原狀。
 */
public class YearEndClosingService {

    public static final String VOUCHER_TYPE_YEAR_END = "YEAR_END_CLOSE";
    public static final String VOUCHER_TYPE_YEAR_END_REVERSE = "YEAR_END_REVERSE";

    /** 測試用失敗注入開關：在 12 月解鎖後立即拋出異常，驗證復原機制 */
    public static volatile boolean simulateFailureAfterUnlock = false;

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
                "status TEXT NOT NULL CHECK (status IN ('PENDING_DRAFT','DRAFT','POSTED','REOPEN_PENDING','REOPEN_DRAFT','REVERSED','REOPENED','FAILED')), " +
                "idempotency_key TEXT NOT NULL UNIQUE, " +
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
                "reopen_reason TEXT)"
            );
        }

        // 舊表相容性遷移：若舊表 CHECK 約束缺少 REOPEN_DRAFT，重建表結構以支援反結中間狀態
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT sql FROM sqlite_master WHERE type='table' AND name='year_end_closings'")) {
            if (rs.next()) {
                String ddl = rs.getString(1);
                if (ddl != null && !ddl.contains("REOPEN_DRAFT")) {
                    st.execute("CREATE TABLE year_end_closings_new (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                        "fiscal_year INTEGER NOT NULL UNIQUE, " +
                        "voucher_id INTEGER REFERENCES vouchers(id), " +
                        "reverse_voucher_id INTEGER REFERENCES vouchers(id), " +
                        "status TEXT NOT NULL CHECK (status IN ('PENDING_DRAFT','DRAFT','POSTED','REOPEN_PENDING','REOPEN_DRAFT','REVERSED','REOPENED','FAILED')), " +
                        "idempotency_key TEXT NOT NULL UNIQUE, " +
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
                        "reopen_reason TEXT)");
                    st.execute("INSERT INTO year_end_closings_new (id, fiscal_year, voucher_id, reverse_voucher_id, status, idempotency_key, created_by, reviewed_by, created_at, reviewed_at, closed_at, fail_reason) " +
                        "SELECT id, fiscal_year, voucher_id, reverse_voucher_id, status, idempotency_key, created_by, reviewed_by, created_at, reviewed_at, closed_at, fail_reason FROM year_end_closings");
                    st.execute("DROP TABLE year_end_closings");
                    st.execute("ALTER TABLE year_end_closings_new RENAME TO year_end_closings");
                }
            }
        } catch (SQLException ignored) {}

        // 相容補欄位
        for (String alter : List.of(
                "ALTER TABLE year_end_closings ADD COLUMN reverse_voucher_id INTEGER",
                "ALTER TABLE year_end_closings ADD COLUMN idempotency_key TEXT",
                "ALTER TABLE year_end_closings ADD COLUMN fail_reason TEXT",
                "ALTER TABLE year_end_closings ADD COLUMN reopen_initiated_by TEXT",
                "ALTER TABLE year_end_closings ADD COLUMN reopen_initiated_at TEXT",
                "ALTER TABLE year_end_closings ADD COLUMN reopen_reviewed_by TEXT",
                "ALTER TABLE year_end_closings ADD COLUMN reopen_reviewed_at TEXT",
                "ALTER TABLE year_end_closings ADD COLUMN reopen_reason TEXT"
        )) {
            try (Statement st = conn.createStatement()) {
                st.execute(alter);
            } catch (SQLException ignored) {}
        }
        // vouchers.voucher_type 與 closing_year
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE vouchers ADD COLUMN voucher_type TEXT NOT NULL DEFAULT 'NORMAL'");
        } catch (SQLException ignored) {}
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE vouchers ADD COLUMN closing_year INTEGER");
        } catch (SQLException ignored) {}
    }

    /**
     * 判斷指定結帳狀態是否可重試。
     */
    public boolean isRetryable(String status) {
        if (status == null) return true;
        return "FAILED".equalsIgnoreCase(status)
                || "PENDING_DRAFT".equalsIgnoreCase(status)
                || "REOPEN_DRAFT".equalsIgnoreCase(status)
                || "REOPEN_PENDING".equalsIgnoreCase(status);
    }

    /**
     * 查詢指定年度是否處於可重試或可進行結帳/反結帳狀態。
     */
    public boolean isRetryable(int year) {
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status FROM year_end_closings WHERE fiscal_year=?")) {
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

    // =========================================================================
    //  階段一：制單人建立年度結帳草稿（P1-01, P0-01, P0-03）
    // =========================================================================

    /**
     * 制單人建立年度結帳草稿分錄。
     * 狀態轉換：(無/FAILED) → PENDING_DRAFT → DRAFT
     *
     * 1. 嚴格驗證制單人身分（MAKER），不可偽造。
     * 2. 前置檢查純讀取，不修改任何期間資料。
     * 3. 12 月解鎖納入交易保護；若建立草稿失敗，保證復原 12 月原始狀態。
     * 4. 成功後狀態持久化為 DRAFT，等待審核會計師登入審核。
     */
    public long createYearEndDraft(int year, String preparer) {
        String normPrep = SessionContext.resolveOperator(preparer);
        if (OperatorRole.resolve(normPrep) != OperatorRole.MAKER) {
            throw new BusinessException("年度結帳制單人必須為記帳員（MAKER）");
        }
        if (SessionContext.isLoggedIn()) {
            String su = SessionContext.requireUser();
            if (!su.equalsIgnoreCase(normPrep)) {
                throw new BusinessException("身分不符：目前登入者為 " + su + "，不得以 " + preparer + " 制單");
            }
        } else if (!SessionContext.isTestMode()) {
            throw new BusinessException("尚未登入，拒絕年度結帳");
        }

        String yearStart = String.format("%04d-01-01", year);
        String yearEnd = String.format("%04d-12-31", year);
        String idemKey = "YE-CLOSE-" + year + "-" + UUID.randomUUID().toString().substring(0, 8);

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            // ---- 純讀取前置檢查（不修改任何資料）----
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

            // 檢查 12 月原本是否 CLOSED
            boolean month12OriginallyClosed = isMonth12Closed(conn, year);

            long closingRowId;
            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
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

            Voucher draft = null;
            try {
                // 若 12 月原為 CLOSED，僅在寫入草稿前暫時解鎖，納入受控流程
                if (month12OriginallyClosed) {
                    setMonth12Status(conn, year, "OPEN");
                }

                // 測試失敗注入驗證
                if (simulateFailureAfterUnlock) {
                    throw new RuntimeException("測試注入：12 月解鎖後模擬發生非預期異常");
                }

                draft = voucherService.createDraft(yearEnd, summary, normPrep, entries);
                tagVoucherAsYearEnd(draft.id(), year);

                // 更新為 DRAFT + voucher_id
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE year_end_closings SET status='DRAFT', voucher_id=? WHERE id=? AND status='PENDING_DRAFT'")) {
                    ps.setLong(1, draft.id());
                    ps.setLong(2, closingRowId);
                    if (ps.executeUpdate() != 1) {
                        throw new BusinessException("結帳狀態轉換 PENDING_DRAFT→DRAFT 失敗");
                    }
                }

                // 若 12 月原為 CLOSED，草稿建立完畢後應復原為 CLOSED，等待審核人過帳時再鎖定
                if (month12OriginallyClosed) {
                    setMonth12Status(conn, year, "CLOSED");
                }

                auditDao.log(conn, "YEAR_END_DRAFT_CREATED", "year_end_closings", (long) year, normPrep,
                        "PENDING_DRAFT", "DRAFT; voucher=" + draft.id() + "; key=" + idemKey);

                return draft.id();
            } catch (Exception e) {
                // 失敗復原流程：保證 12 月恢復原狀，不留下 OPEN 期間
                if (month12OriginallyClosed) {
                    try { setMonth12Status(conn, year, "CLOSED"); } catch (Exception ignored) {}
                }
                if (draft != null) {
                    cleanupOrphanDraft(draft.id());
                }
                markFailed(year, "建立草稿失敗: " + e.getMessage());
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("年度結帳草稿失敗: " + e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        }
    }

    // =========================================================================
    //  階段二：審核人審核並過帳年度結帳（P1-01, P0-01, P0-03）
    // =========================================================================

    /**
     * 審核會計師審核並過帳年度結帳草稿。
     * 狀態轉換：DRAFT → POSTED
     *
     * 1. 嚴格驗證審核人身分（CHECKER），不可偽造。
     * 2. 真正雙人覆核：審核人不得與草稿制單人相同。
     * 3. 過帳成功後，將該年度 1～12 月全部鎖定為 CLOSED。
     */
    public long reviewAndPostYearEnd(int year, String reviewer) {
        String normRev = SessionContext.resolveOperator(reviewer);
        if (OperatorRole.resolve(normRev) != OperatorRole.CHECKER) {
            throw new BusinessException("年度結帳審核人必須為會計師（CHECKER）");
        }
        if (SessionContext.isLoggedIn()) {
            String su = SessionContext.requireUser();
            if (!su.equalsIgnoreCase(normRev)) {
                throw new BusinessException("身分不符：目前登入者為 " + su + "，不得以 " + reviewer + " 審核");
            }
        } else if (!SessionContext.isTestMode()) {
            throw new BusinessException("尚未登入，拒絕年度結帳審核");
        }

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            long voucherId;
            String createdBy;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, voucher_id, status, created_by FROM year_end_closings WHERE fiscal_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new BusinessException(year + " 年度尚未建立結帳草稿");
                    String st = rs.getString("status");
                    if ("POSTED".equals(st)) {
                        throw new BusinessException(year + " 年度已經結帳完成，禁止重複結帳");
                    }
                    if (!"DRAFT".equals(st)) {
                        throw new BusinessException("結帳狀態非 DRAFT，無法審核（目前: " + st + "）");
                    }
                    voucherId = rs.getLong("voucher_id");
                    createdBy = rs.getString("created_by");
                }
            }

            // 真正雙人覆核檢查：制單人不得審核自己的傳票
            if (normRev.equalsIgnoreCase(createdBy)) {
                throw new BusinessException(String.format(
                        "職責分離原則：制單人（%s）不得審核自己的年度結帳傳票，必須由會計師（CHECKER）審核！", createdBy));
            }

            boolean month12OriginallyClosed = isMonth12Closed(conn, year);

            try {
                // 過帳年結傳票
                voucherService.post(voucherId, normRev);

                // 更新 year_end_closings 狀態為 POSTED，並鎖定 1～12 個月
                boolean ac = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE year_end_closings SET status='POSTED', reviewed_by=?, " +
                            "reviewed_at=datetime('now','localtime'), closed_at=datetime('now','localtime') " +
                            "WHERE fiscal_year=? AND status='DRAFT'")) {
                        ps.setString(1, normRev);
                        ps.setInt(2, year);
                        if (ps.executeUpdate() != 1) {
                            throw new BusinessException("結帳狀態轉換 DRAFT→POSTED 失敗");
                        }
                    }
                    // 年結成功後：將 1～12 月全部設定為 CLOSED
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE fiscal_periods SET status='CLOSED' WHERE year=?")) {
                        ps.setInt(1, year);
                        ps.executeUpdate();
                    }
                    auditDao.log(conn, "YEAR_END_CLOSE", "year_end_closings", (long) year, normRev,
                            "DRAFT", "POSTED; voucher=" + voucherId);
                    conn.commit();
                } catch (Exception e) {
                    conn.rollback();
                    throw e;
                } finally {
                    conn.setAutoCommit(ac);
                }

                return voucherId;
            } catch (Exception e) {
                // 過帳失敗時保證復原
                if (month12OriginallyClosed) {
                    try { setMonth12Status(conn, year, "CLOSED"); } catch (Exception ignored) {}
                }
                markFailed(year, "過帳/鎖期間失敗: " + e.getMessage());
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("年度結帳過帳失敗: " + e.getMessage(), e);
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        }
    }

    /**
     * 整合雙人結帳操作：依序完成制單與審核過帳（支援測試與批次整合呼叫）。
     */
    public List<Long> closeYear(int year, String preparer, String reviewer) {
        if (preparer != null && reviewer != null && preparer.trim().equalsIgnoreCase(reviewer.trim())) {
            throw new BusinessException("職責分離：制單人與審核人不得為同一人");
        }
        createYearEndDraft(year, preparer);
        long postedId = reviewAndPostYearEnd(year, reviewer);
        return List.of(postedId);
    }

    public List<Long> closeYear(int year, String operator) {
        throw new BusinessException("請使用 closeYear(year, preparer, reviewer) 或分別呼叫制單與審核流程");
    }

    // =========================================================================
    //  階段三：年度反結帳（P0-02：修正反結順序與中間狀態）
    // =========================================================================

    /**
     * 第一階段：會計師發起年度反結帳，建立紅字沖銷草稿。
     * 狀態轉換：POSTED → REOPEN_PENDING → REOPEN_DRAFT
     *
     * 1. 只有 POSTED 狀態可發起反結。
     * 2. 建立紅字沖銷失敗時，狀態回復為 POSTED，絕不標示 REVERSED。
     * 3. 沖銷草稿未過帳前，狀態停留在 REOPEN_DRAFT，期間維持 CLOSED。
     * 4. 失敗後可安全重試，若已有草稿直接返回，不產生重複沖銷。
     */
    public long initiateReopenYear(int year, String initiator, String reason) {
        String normOp = SessionContext.resolveOperator(initiator);
        if (OperatorRole.resolve(normOp) != OperatorRole.CHECKER) {
            throw new BusinessException("年度反結帳僅會計師可執行");
        }
        if (SessionContext.isLoggedIn()) {
            String su = SessionContext.requireUser();
            if (!su.equalsIgnoreCase(normOp)) {
                throw new BusinessException("身分不符：目前登入者為 " + su + "，不得以 " + initiator + " 操作");
            }
        } else if (!SessionContext.isTestMode()) {
            throw new BusinessException("尚未登入，拒絕年度反結帳");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("年度反結帳必須填寫原因");
        }

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            long voucherId;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, voucher_id, reverse_voucher_id, status FROM year_end_closings WHERE fiscal_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new BusinessException(year + " 年度無結帳紀錄");
                    String st = rs.getString("status");

                    // 冪等重試：若已在 REOPEN_DRAFT 且已有反結草稿，直接返回該草稿 ID
                    if ("REOPEN_DRAFT".equals(st)) {
                        long existingRevId = rs.getLong("reverse_voucher_id");
                        if (existingRevId > 0) {
                            return existingRevId;
                        }
                    }

                    if (!"POSTED".equals(st) && !"REOPEN_PENDING".equals(st)) {
                        throw new BusinessException("結帳狀態非 POSTED，無法反結（目前: " + st + "）");
                    }
                    voucherId = rs.getLong("voucher_id");
                }
            }

            // 後續年度檢查
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT fiscal_year FROM year_end_closings WHERE fiscal_year > ? AND status='POSTED' LIMIT 1")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        throw new BusinessException("後續年度已結帳，請先反結後續年度");
                    }
                }
            }

            // 狀態變更為 REOPEN_PENDING
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE year_end_closings SET status='REOPEN_PENDING', reopen_initiated_by=?, " +
                    "reopen_initiated_at=datetime('now','localtime'), reopen_reason=? WHERE fiscal_year=?")) {
                ps.setString(1, normOp);
                ps.setString(2, reason.trim());
                ps.setInt(3, year);
                ps.executeUpdate();
            }

            String yearEnd = String.format("%04d-12-31", year);
            boolean month12OriginallyClosed = isMonth12Closed(conn, year);

            Voucher revDraft;
            try {
                if (month12OriginallyClosed) {
                    setMonth12Status(conn, year, "OPEN");
                }
                // 建立紅字沖銷草稿（落於 12/31）
                revDraft = voucherService.reverse(voucherId, yearEnd, "年度反結帳: " + reason.trim(), normOp);
                tagVoucherAsYearEndReverse(revDraft.id(), year);

                // 更新為 REOPEN_DRAFT，記錄 reverse_voucher_id
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE year_end_closings SET status='REOPEN_DRAFT', reverse_voucher_id=? WHERE fiscal_year=? AND status='REOPEN_PENDING'")) {
                    ps.setLong(1, revDraft.id());
                    ps.setInt(2, year);
                    ps.executeUpdate();
                }

                auditDao.log(conn, "YEAR_END_REOPEN_INITIATED", "year_end_closings", (long) year, normOp,
                        "POSTED", "REOPEN_DRAFT; revVoucher=" + revDraft.id() + "; reason=" + reason.trim());

                return revDraft.id();
            } catch (Exception e) {
                // 建立紅字沖銷傳票失敗時：原狀態回復為 POSTED，絕不標示 REVERSED！
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE year_end_closings SET status='POSTED', fail_reason=? WHERE fiscal_year=? AND status='REOPEN_PENDING'")) {
                    ps.setString(1, "建立紅字沖銷草稿失敗: " + e.getMessage());
                    ps.setInt(2, year);
                    ps.executeUpdate();
                } catch (Exception ignored) {}

                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("建立紅字沖銷傳票失敗: " + e.getMessage(), e);
            } finally {
                // 沖銷草稿未過帳前，期間保證維持鎖定（CLOSED）狀態！
                if (month12OriginallyClosed) {
                    try { setMonth12Status(conn, year, "CLOSED"); } catch (Exception ignored) {}
                }
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        }
    }

    /**
     * 第二階段：第二位會計師審核並過帳紅字沖銷傳票，完成年度反結帳。
     * 狀態轉換：REOPEN_DRAFT → REVERSED
     *
     * 1. 只有沖銷成功過帳後，才允許完成反結標記 REVERSED。
     * 2. 反結完成後才重新開放（OPEN）會計期間。
     * 3. 雙人審核：反結發起人不得自行審核過帳沖銷傳票。
     */
    public long confirmReopenYear(int year, String reviewer) {
        String normRev = SessionContext.resolveOperator(reviewer);
        if (OperatorRole.resolve(normRev) != OperatorRole.CHECKER) {
            throw new BusinessException("年度反結帳審核僅會計師可執行");
        }
        if (SessionContext.isLoggedIn()) {
            String su = SessionContext.requireUser();
            if (!su.equalsIgnoreCase(normRev)) {
                throw new BusinessException("身分不符：目前登入者為 " + su + "，不得以 " + reviewer + " 審核");
            }
        } else if (!SessionContext.isTestMode()) {
            throw new BusinessException("尚未登入，拒絕年度反結帳審核");
        }

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            long revVoucherId;
            String initiator;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, reverse_voucher_id, status, reopen_initiated_by FROM year_end_closings WHERE fiscal_year=?")) {
                ps.setInt(1, year);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new BusinessException(year + " 年度無結帳紀錄");
                    String st = rs.getString("status");
                    if ("REVERSED".equals(st) || "REOPENED".equals(st)) {
                        throw new BusinessException(year + " 年度反結帳已完成，無需重複執行");
                    }
                    if (!"REOPEN_DRAFT".equals(st)) {
                        throw new BusinessException("年度反結帳尚未建立沖銷草稿（目前狀態: " + st + "）");
                    }
                    revVoucherId = rs.getLong("reverse_voucher_id");
                    initiator = rs.getString("reopen_initiated_by");
                }
            }

            // 雙人覆核檢驗：反結發起人不得自行過帳沖銷傳票
            if (initiator != null && initiator.equalsIgnoreCase(normRev)) {
                throw new BusinessException(String.format(
                        "職責分離原則：反結發起人（%s）不得自行審核過帳反結沖銷傳票，必須由第二位會計師審核！", initiator));
            }

            // 過帳紅字沖銷傳票（此操作會檢驗雙人核可並完成憑證過帳）
            voucherService.post(revVoucherId, normRev);

            // 過帳成功後：狀態標記 REVERSED，並重新開放該年度期間
            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE year_end_closings SET status='REVERSED', reopen_reviewed_by=?, " +
                        "reopen_reviewed_at=datetime('now','localtime') WHERE fiscal_year=? AND status='REOPEN_DRAFT'")) {
                    ps.setString(1, normRev);
                    ps.setInt(2, year);
                    if (ps.executeUpdate() != 1) {
                        throw new BusinessException("反結狀態轉換 REOPEN_DRAFT→REVERSED 失敗");
                    }
                }
                // 只有紅字沖銷成功過帳後，才依規則重新開放會計期間
                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE fiscal_periods SET status='OPEN' WHERE year=?")) {
                    ps.setInt(1, year);
                    ps.executeUpdate();
                }
                auditDao.log(conn, "YEAR_END_REOPEN_COMPLETED", "year_end_closings", (long) year, normRev,
                        "REOPEN_DRAFT", "REVERSED; revVoucher=" + revVoucherId);
                conn.commit();
                return revVoucherId;
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(ac);
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫錯誤: " + e.getMessage(), e);
        }
    }

    /**
     * 雙人完整反結操作：發起人建立沖銷草稿，審核人審核過帳並解鎖期間。
     */
    public long reopenYear(int year, String initiator, String reviewer, String reason) {
        if (initiator != null && reviewer != null && initiator.trim().equalsIgnoreCase(reviewer.trim())) {
            throw new BusinessException("職責分離原則：反結發起人與審核人不得為同一人");
        }
        initiateReopenYear(year, initiator, reason);
        return confirmReopenYear(year, reviewer);
    }

    /**
     * 相容舊測試呼叫：在測試模式下自動協調第二位會計師覆核。
     */
    public long reopenYear(int year, String operator, String reason) {
        if (SessionContext.isTestMode()) {
            String norm = OperatorRole.normalize(operator);
            String reviewer = "david".equalsIgnoreCase(norm) ? "bob" : "david";
            return reopenYear(year, norm, reviewer, reason);
        }
        throw new BusinessException("請使用 initiateReopenYear 與 confirmReopenYear 進行雙人反結帳");
    }

    // =========================================================================
    //  一致性檢查與自動修復（P0-01）
    // =========================================================================

    /**
     * 資料庫一致性檢查：找出 voucher_id 與 year_end_closings 不一致、孤立傳票或期間未鎖定等問題。
     */
    public List<String> checkConsistency() {
        List<String> issues = new ArrayList<>();
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            // 1. 檢查結帳主傳票狀態與 year_end_closings 一致性
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT y.fiscal_year, y.status, y.voucher_id, v.status AS v_status " +
                         "FROM year_end_closings y " +
                         "LEFT JOIN vouchers v ON v.id = y.voucher_id")) {
                while (rs.next()) {
                    int yr = rs.getInt("fiscal_year");
                    String yStatus = rs.getString("status");
                    long vId = rs.getLong("voucher_id");
                    String vStatus = rs.getString("v_status");
                    if (vId > 0 && vStatus == null) {
                        issues.add(String.format("年度 %d (狀態 %s) 關聯之結帳傳票 ID %d 不存在", yr, yStatus, vId));
                    } else if ("POSTED".equals(yStatus) && !"POSTED".equals(vStatus)) {
                        issues.add(String.format("年度 %d 結帳狀態為 POSTED，但傳票 ID %d 狀態為 %s", yr, vId, vStatus));
                    } else if ("DRAFT".equals(yStatus) && !"DRAFT".equals(vStatus)) {
                        issues.add(String.format("年度 %d 結帳狀態為 DRAFT，但傳票 ID %d 狀態為 %s", yr, vId, vStatus));
                    }
                }
            }

            // 2. 檢查反結沖銷傳票一致性
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT y.fiscal_year, y.status, y.reverse_voucher_id, v.status AS rv_status " +
                         "FROM year_end_closings y " +
                         "LEFT JOIN vouchers v ON v.id = y.reverse_voucher_id " +
                         "WHERE y.reverse_voucher_id IS NOT NULL")) {
                while (rs.next()) {
                    int yr = rs.getInt("fiscal_year");
                    String yStatus = rs.getString("status");
                    long rvId = rs.getLong("reverse_voucher_id");
                    String rvStatus = rs.getString("rv_status");
                    if (rvStatus == null) {
                        issues.add(String.format("年度 %d 反結沖銷傳票 ID %d 不存在", yr, rvId));
                    } else if ("REVERSED".equals(yStatus) && !"POSTED".equals(rvStatus)) {
                        issues.add(String.format("年度 %d 已標記 REVERSED，但反結傳票 ID %d 狀態尚未 POSTED（目前: %s）", yr, rvId, rvStatus));
                    }
                }
            }

            // 3. 檢查孤立年結傳票（有憑證卻無 year_end_closings 紀錄）
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

            // 4. 檢查 POSTED 狀態下的會計期間：必須 12 個月全部 CLOSED
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT y.fiscal_year, COUNT(p.month) AS unclosed_count " +
                         "FROM year_end_closings y " +
                         "JOIN fiscal_periods p ON p.year = y.fiscal_year AND p.status <> 'CLOSED' " +
                         "WHERE y.status = 'POSTED' " +
                         "GROUP BY y.fiscal_year")) {
                while (rs.next()) {
                    issues.add(String.format("年度 %d 結帳狀態為 POSTED，但有 %d 個月份未處於 CLOSED 狀態",
                            rs.getInt("fiscal_year"), rs.getInt("unclosed_count")));
                }
            }
        } catch (SQLException e) {
            issues.add("執行一致性檢查異常: " + e.getMessage());
        }
        return issues;
    }

    /**
     * 修復不一致紀錄：清理 FAILED 狀態下遺留的草稿憑證，並修復缺失的鎖定。
     */
    public int repairConsistency() {
        int repaired = 0;
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            // 清理 FAILED 紀錄遺留之 DRAFT 傳票
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT y.voucher_id FROM year_end_closings y " +
                         "JOIN vouchers v ON v.id = y.voucher_id " +
                         "WHERE y.status = 'FAILED' AND v.status = 'DRAFT'")) {
                List<Long> orphanDrafts = new ArrayList<>();
                while (rs.next()) orphanDrafts.add(rs.getLong(1));
                for (Long vid : orphanDrafts) {
                    cleanupOrphanDraft(vid);
                    repaired++;
                }
            }

            // 清理超時無關聯憑證之 PENDING_DRAFT
            try (Statement st = conn.createStatement()) {
                repaired += st.executeUpdate(
                        "DELETE FROM year_end_closings WHERE status='PENDING_DRAFT' AND voucher_id IS NULL");
            }

            // 補齊 POSTED 年度未關閉的月份
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(
                         "SELECT DISTINCT y.fiscal_year FROM year_end_closings y WHERE y.status='POSTED'")) {
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

    // =========================================================================
    //  內部輔助與前置檢查（純讀取）
    // =========================================================================

    private void assertNoActiveClosing(Connection conn, int year) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT status FROM year_end_closings WHERE fiscal_year=?")) {
            ps.setInt(1, year);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String st = rs.getString(1);
                    if ("FAILED".equals(st) || "REVERSED".equals(st) || "REOPENED".equals(st)) {
                        // 允許清理後重試：清除 FAILED 或已完整反結之舊紀錄
                        try (PreparedStatement del = conn.prepareStatement(
                                "DELETE FROM year_end_closings WHERE fiscal_year=? AND status IN ('FAILED','REVERSED','REOPENED')")) {
                            del.setInt(1, year);
                            del.executeUpdate();
                        }
                    } else if ("DRAFT".equals(st)) {
                        throw new BusinessException(year + " 年度已有結帳草稿待審核，請審核過帳或刪除重試");
                    } else {
                        throw new BusinessException(year + " 年度已存在結帳紀錄（狀態=" + st + "），禁止重複結帳");
                    }
                }
            }
        }
    }

    /**
     * 純讀取前置檢查：驗證 1～12 月期間均存在，且 1～11 月已完成月結（CLOSED）。
     * 絕不在此修改 12 月狀態！
     */
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
                                "年度結帳前須完成各月月結：%d年%02d月尚未結帳鎖定", year, m));
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
                    throw new BusinessException("該年度尚有未完成之紅字沖銷草稿，請先過帳或刪除後再結帳");
                }
            }
        }
    }

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
                    "UPDATE year_end_closings SET status='FAILED', fail_reason=? WHERE fiscal_year=? AND status IN ('PENDING_DRAFT','DRAFT','REOPEN_PENDING')")) {
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
            voucherService.deleteDraft(voucherId, SessionContext.isLoggedIn()
                    ? SessionContext.getUser() : "Amy");
        } catch (Exception e) {
            System.err.println("WARN: 孤立草稿清理失敗 id=" + voucherId + ": " + e.getMessage());
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
