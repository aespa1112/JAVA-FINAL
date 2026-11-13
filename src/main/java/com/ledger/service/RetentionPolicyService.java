package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.dao.AuditDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import java.sql.*;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 商業會計法第 38 條：
 * - 各項會計憑證，除應永久保存或有關未結會計事項者外，應於年度決算程序辦理終了後，至少保存五年。
 * - 各項會計帳簿及財務報表，應於年度決算程序辦理終了後，至少保存十年。
 * - 保存期間自「年度決算程序辦理終了日」起算；有未結會計事項者依法延長保存，不得銷毀。
 * - 銷毀核准須檢核資格、綁定銷毀批次（batchId）、防止重複核准並保留完整審計紀錄。
 */
public class RetentionPolicyService {

    /**
     * 核准紀錄僅代表法定保存期限已滿且完成銷毀核准，不等同於實體檔案／資料列已實際執行銷毀。
     */
    public static final String STATUS_APPROVED_PENDING_EXECUTION = "APPROVED_PENDING_EXECUTION";

    private static final Object CLOSING_LOCK = new Object();
    private static final Object DESTRUCTION_LOCK = new Object();

    public record Policy(String dataType, int years, String description) {}
    public record DestructionRecord(long id, String batchId, String dataType, String targetRef,
                                    int fiscalYear, LocalDate recordDate, LocalDate closingCompletionDate,
                                    String reason, String approvedBy, String createdAt, String status) {
        public DestructionRecord(long id, String batchId, String dataType, String targetRef,
                                 int fiscalYear, LocalDate recordDate, LocalDate closingCompletionDate,
                                 String reason, String approvedBy, String createdAt) {
            this(id, batchId, dataType, targetRef, fiscalYear, recordDate, closingCompletionDate,
                    reason, approvedBy, createdAt, STATUS_APPROVED_PENDING_EXECUTION);
        }
    }
    public record ClosingCompletion(int fiscalYear, LocalDate completionDate, String recordedBy, String recordedAt) {}
    public record ClosingCompletionCorrection(long id, int fiscalYear, LocalDate oldCompletionDate,
                                              LocalDate newCompletionDate, String reason,
                                              String correctedBy, String correctedAt) {}

    private final AuditDao auditDao = new AuditDao();

    public void ensureTables(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS retention_policy (" +
                "data_type TEXT PRIMARY KEY, " +
                "retain_years INTEGER NOT NULL, " +
                "description TEXT)"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS annual_closing_completions (" +
                "fiscal_year INTEGER PRIMARY KEY, " +
                "completion_date TEXT NOT NULL, " +
                "recorded_by TEXT NOT NULL, " +
                "recorded_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS annual_closing_completion_history (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "fiscal_year INTEGER NOT NULL, " +
                "old_completion_date TEXT NOT NULL, " +
                "new_completion_date TEXT NOT NULL, " +
                "reason TEXT NOT NULL, " +
                "corrected_by TEXT NOT NULL, " +
                "corrected_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))"
            );
            st.execute(
                "CREATE TABLE IF NOT EXISTS destruction_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "batch_id TEXT NOT NULL, " +
                "data_type TEXT NOT NULL, " +
                "target_ref TEXT NOT NULL, " +
                "fiscal_year INTEGER NOT NULL, " +
                "record_date TEXT NOT NULL, " +
                "closing_completion_date TEXT NOT NULL, " +
                "reason TEXT NOT NULL, " +
                "approved_by TEXT NOT NULL, " +
                "status TEXT NOT NULL DEFAULT '" + STATUS_APPROVED_PENDING_EXECUTION + "', " +
                "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), " +
                "UNIQUE(data_type, target_ref))"
            );
            st.execute("INSERT OR IGNORE INTO retention_policy VALUES ('VOUCHER', 5, '會計憑證（記帳憑證／原始憑證）')");
            st.execute("INSERT OR IGNORE INTO retention_policy VALUES ('BOOK', 10, '會計帳簿')");
            st.execute("INSERT OR IGNORE INTO retention_policy VALUES ('REPORT', 10, '財務報表')");
            st.execute("INSERT OR IGNORE INTO retention_policy VALUES ('AUDIT_LOG', 10, '審計日誌')");
        }
        ensureDestructionColumns(conn);
    }

    private static void ensureDestructionColumns(Connection conn) throws SQLException {
        ensureColumn(conn, "destruction_log", "batch_id", "TEXT NOT NULL DEFAULT 'LEGACY-BATCH'");
        ensureColumn(conn, "destruction_log", "fiscal_year", "INTEGER NOT NULL DEFAULT 0");
        ensureColumn(conn, "destruction_log", "record_date", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(conn, "destruction_log", "closing_completion_date", "TEXT NOT NULL DEFAULT ''");
        ensureColumn(conn, "destruction_log", "status", "TEXT NOT NULL DEFAULT '" + STATUS_APPROVED_PENDING_EXECUTION + "'");
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_destruct_unique_target " +
                    "ON destruction_log(data_type, target_ref)");
        }
    }

    private static void ensureColumn(Connection conn, String table, String col, String def) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                if (col.equalsIgnoreCase(rs.getString("name"))) return;
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE " + table + " ADD COLUMN " + col + " " + def);
        }
    }

    /**
     * 登記年度決算程序辦理終了日期（Commercial Accounting Act Art. 38 法定起算日）。
     * 已登錄之年度不得直接覆寫或縮短，若確有延後更正必要須呼叫 correctAnnualClosingCompletion。
     */
    public void recordAnnualClosingCompletion(int fiscalYear, LocalDate completionDate, String operator) {
        String norm = SessionContext.resolveOperator(operator);
        if (OperatorRole.resolve(norm) != OperatorRole.CHECKER) {
            throw new BusinessException("權限不足：僅會計師（CHECKER）可登錄年度決算程序完成日");
        }
        if (completionDate == null) {
            throw new BusinessException("年度決算完成日不可為空");
        }
        if (completionDate.isBefore(LocalDate.of(fiscalYear, 12, 31))) {
            throw new BusinessException("年度決算程序完成日不得早於該會計年度終了日（" + fiscalYear + "-12-31）");
        }
        synchronized (CLOSING_LOCK) {
            try (Connection conn = DbManager.get()) {
                ensureTables(conn);
                if (hasUnsettledMattersForYear(conn, fiscalYear)) {
                    throw new BusinessException("會計年度 " + fiscalYear + " 仍有未結會計事項或未過帳草稿，不得標記決算程序完成");
                }
                LocalDate existing = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT completion_date FROM annual_closing_completions WHERE fiscal_year = ?")) {
                    ps.setInt(1, fiscalYear);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            existing = LocalDate.parse(rs.getString(1));
                        }
                    }
                }
                if (existing != null) {
                    throw new BusinessException("會計年度 " + fiscalYear + " 已登錄決算完成日（" + existing
                            + "），禁止直接覆寫或縮短保存起算日；如需更正請使用 correctAnnualClosingCompletion 並留存原因");
                }
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO annual_closing_completions(fiscal_year, completion_date, recorded_by) " +
                        "VALUES (?,?,?)")) {
                    ps.setInt(1, fiscalYear);
                    ps.setString(2, completionDate.toString());
                    ps.setString(3, norm);
                    ps.executeUpdate();
                }
                auditDao.log(conn, "ANNUAL_CLOSING_COMPLETE", "annual_closing_completions", (long) fiscalYear,
                        norm, null, "fiscalYear=" + fiscalYear + "; completionDate=" + completionDate);
            } catch (BusinessException e) {
                throw e;
            } catch (SQLException e) {
                throw new BusinessException("記錄年度決算完成日失敗: " + e.getMessage(), e);
            }
        }
    }

    /**
     * 更正年度決算程序完成日（僅允許延後，嚴禁縮短已登錄之保存起算日，並記錄更正歷程與審計軌跡）。
     */
    public ClosingCompletionCorrection correctAnnualClosingCompletion(int fiscalYear, LocalDate newCompletionDate,
                                                                      String reason, String operator) {
        String norm = SessionContext.resolveOperator(operator);
        if (OperatorRole.resolve(norm) != OperatorRole.CHECKER) {
            throw new BusinessException("權限不足：僅會計師（CHECKER）可更正年度決算程序完成日");
        }
        if (newCompletionDate == null) {
            throw new BusinessException("更正後年度決算完成日不可為空");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("更正年度決算完成日必須填具明確原因");
        }
        if (newCompletionDate.isBefore(LocalDate.of(fiscalYear, 12, 31))) {
            throw new BusinessException("更正後年度決算完成日不得早於該會計年度終了日（" + fiscalYear + "-12-31）");
        }
        synchronized (CLOSING_LOCK) {
            try (Connection conn = DbManager.get()) {
                ensureTables(conn);
                LocalDate oldDate = null;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT completion_date FROM annual_closing_completions WHERE fiscal_year = ?")) {
                    ps.setInt(1, fiscalYear);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            oldDate = LocalDate.parse(rs.getString(1));
                        }
                    }
                }
                if (oldDate == null) {
                    throw new BusinessException("會計年度 " + fiscalYear + " 尚未登錄決算完成日，請先呼叫 recordAnnualClosingCompletion");
                }
                if (newCompletionDate.isBefore(oldDate)) {
                    throw new BusinessException("禁止縮短保存起算日：更正後完成日（" + newCompletionDate
                            + "）不得早於原登錄完成日（" + oldDate + "）");
                }
                if (newCompletionDate.equals(oldDate)) {
                    throw new BusinessException("更正後完成日與原登錄完成日相同（" + oldDate + "），無需變更");
                }

                conn.setAutoCommit(false);
                try {
                    try (PreparedStatement ps = conn.prepareStatement(
                            "UPDATE annual_closing_completions SET completion_date = ?, recorded_by = ?, " +
                            "recorded_at = datetime('now','localtime') WHERE fiscal_year = ?")) {
                        ps.setString(1, newCompletionDate.toString());
                        ps.setString(2, norm);
                        ps.setInt(3, fiscalYear);
                        ps.executeUpdate();
                    }
                    long histId;
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO annual_closing_completion_history" +
                            "(fiscal_year, old_completion_date, new_completion_date, reason, corrected_by) VALUES (?,?,?,?,?)",
                            Statement.RETURN_GENERATED_KEYS)) {
                        ps.setInt(1, fiscalYear);
                        ps.setString(2, oldDate.toString());
                        ps.setString(3, newCompletionDate.toString());
                        ps.setString(4, reason.trim());
                        ps.setString(5, norm);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            histId = keys.next() ? keys.getLong(1) : 0;
                        }
                    }
                    auditDao.log(conn, "ANNUAL_CLOSING_CORRECT", "annual_closing_completions", (long) fiscalYear,
                            norm,
                            "completionDate=" + oldDate,
                            "completionDate=" + newCompletionDate + "; reason=" + reason.trim());
                    conn.commit();
                    return new ClosingCompletionCorrection(histId, fiscalYear, oldDate, newCompletionDate,
                            reason.trim(), norm, LocalDate.now().toString());
                } catch (Exception ex) {
                    conn.rollback();
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (BusinessException e) {
                throw e;
            } catch (SQLException e) {
                throw new BusinessException("更正年度決算完成日失敗: " + e.getMessage(), e);
            }
        }
    }

    public List<ClosingCompletionCorrection> listClosingCorrections(int fiscalYear) {
        List<ClosingCompletionCorrection> list = new ArrayList<>();
        try (Connection conn = DbManager.get()) {
            ensureTables(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT id, fiscal_year, old_completion_date, new_completion_date, reason, corrected_by, corrected_at " +
                    "FROM annual_closing_completion_history WHERE fiscal_year = ? ORDER BY id")) {
                ps.setInt(1, fiscalYear);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new ClosingCompletionCorrection(
                                rs.getLong("id"),
                                rs.getInt("fiscal_year"),
                                LocalDate.parse(rs.getString("old_completion_date")),
                                LocalDate.parse(rs.getString("new_completion_date")),
                                rs.getString("reason"),
                                rs.getString("corrected_by"),
                                rs.getString("corrected_at")));
                    }
                }
            }
        } catch (SQLException e) {
            throw new BusinessException("查詢決算完成日更正紀錄失敗: " + e.getMessage(), e);
        }
        return list;
    }

    public LocalDate getAnnualClosingCompletionDate(int fiscalYear) {
        try (Connection conn = DbManager.get()) {
            ensureTables(conn);
            return getAnnualClosingCompletionDate(conn, fiscalYear);
        } catch (SQLException e) {
            throw new BusinessException("查詢年度決算完成日失敗: " + e.getMessage(), e);
        }
    }

    private static LocalDate getAnnualClosingCompletionDate(Connection conn, int fiscalYear) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT completion_date FROM annual_closing_completions WHERE fiscal_year = ?")) {
            ps.setInt(1, fiscalYear);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return LocalDate.parse(rs.getString(1));
                }
            }
        }
        boolean hasClosings = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='year_end_closings'")) {
            if (rs.next() && rs.getInt(1) > 0) hasClosings = true;
        }
        if (hasClosings) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT closed_at FROM year_end_closings WHERE fiscal_year = ? AND status = 'POSTED' " +
                    "ORDER BY id DESC LIMIT 1")) {
                ps.setInt(1, fiscalYear);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        String ts = rs.getString(1);
                        if (ts != null && ts.length() >= 10) {
                            return LocalDate.parse(ts.substring(0, 10));
                        }
                    }
                }
            }
        }
        return null;
    }

    public List<Policy> listPolicies() {
        try (Connection conn = DbManager.get()) {
            ensureTables(conn);
            List<Policy> list = new ArrayList<>();
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery("SELECT data_type, retain_years, description FROM retention_policy")) {
                while (rs.next()) {
                    list.add(new Policy(rs.getString(1), rs.getInt(2), rs.getString(3)));
                }
            }
            return list;
        } catch (SQLException e) {
            throw new BusinessException("查詢保存政策失敗: " + e.getMessage(), e);
        }
    }

    public boolean isEligibleForDestruction(String dataType, LocalDate recordDate, LocalDate asOf) {
        return isEligibleForDestruction(dataType, recordDate, asOf, false);
    }

    public boolean isEligibleForDestruction(String dataType, LocalDate recordDate, LocalDate asOf,
                                            boolean hasUnsettledMatters) {
        if (recordDate == null || asOf == null || hasUnsettledMatters) {
            return false;
        }
        try (Connection conn = DbManager.get()) {
            ensureTables(conn);
            return isEligibleForDestructionInternal(conn, dataType, recordDate, asOf) != null;
        } catch (SQLException e) {
            throw new BusinessException(e.getMessage(), e);
        }
    }

    /**
     * 若符合法定保存屆滿條件，回傳作為起算點之年度決算完成日；否則回傳 null。
     */
    private static LocalDate isEligibleForDestructionInternal(Connection conn, String dataType,
                                                              LocalDate recordDate, LocalDate asOf) throws SQLException {
        int years = getRetentionYears(conn, dataType);
        int fiscalYear = recordDate.getYear();
        LocalDate closingCompletionDate = getAnnualClosingCompletionDate(conn, fiscalYear);
        if (closingCompletionDate == null) {
            // 商業會計法第 38 條：年度決算程序未辦理終了，保存期間尚未起算，不得銷毀
            return null;
        }
        if (hasUnsettledMattersInDb(conn, recordDate, fiscalYear)) {
            return null;
        }
        if (closingCompletionDate.plusYears(years).isAfter(asOf)) {
            return null;
        }
        return closingCompletionDate;
    }

    private static int getRetentionYears(Connection conn, String dataType) throws SQLException {
        if (dataType == null || dataType.isBlank()) {
            throw new BusinessException("會計檔案類別（dataType）不可為空；合法類別為 VOUCHER, BOOK, REPORT, AUDIT_LOG");
        }
        String normType = dataType.trim().toUpperCase();
        Integer dbYears = null;
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT retain_years FROM retention_policy WHERE data_type = ?")) {
            ps.setString(1, normType);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) dbYears = rs.getInt(1);
            }
        }
        int statutoryFloor = switch (normType) {
            case "VOUCHER" -> 5;
            case "BOOK", "REPORT", "AUDIT_LOG" -> 10;
            default -> {
                if (dbYears != null) {
                    throw new BusinessException("不支援之自訂資料類別 [" + normType
                            + "]：未設定商業會計法最低保存年限基準，拒絕進行銷毀評估");
                }
                throw new BusinessException("未知的會計檔案類別（dataType）: [" + dataType
                        + "]；合法類別為 VOUCHER, BOOK, REPORT, AUDIT_LOG");
            }
        };
        return dbYears != null ? Math.max(dbYears, statutoryFloor) : statutoryFloor;
    }

    private static boolean hasUnsettledMattersInDb(Connection conn, LocalDate recordDate, int fiscalYear) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM vouchers WHERE status = 'DRAFT' AND voucher_date <= ? LIMIT 1")) {
            ps.setString(1, recordDate.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return true;
            }
        }
        return hasUnsettledMattersForYear(conn, fiscalYear);
    }

    private static boolean hasUnsettledMattersForYear(Connection conn, int fiscalYear) throws SQLException {
        boolean hasClosings = false;
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT count(*) FROM sqlite_master WHERE type='table' AND name='year_end_closings'")) {
            if (rs.next() && rs.getInt(1) > 0) hasClosings = true;
        }
        if (hasClosings) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT 1 FROM year_end_closings WHERE fiscal_year = ? " +
                    "AND status IN ('PENDING_DRAFT', 'DRAFT', 'REOPEN_PENDING', 'REOPEN_DRAFT', 'REVERSED', 'REOPENED', 'FAILED') " +
                    "LIMIT 1")) {
                ps.setInt(1, fiscalYear);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return true;
                }
            }
        }
        return false;
    }

    /**
     * 核准銷毀會計檔案（綁定銷毀批次 batchId、檢核法定保存期間與未結事項、防止併發重複核准）。
     * 本方法僅建立「核准待執行（APPROVED_PENDING_EXECUTION）」核准紀錄，並不代表已完成實體檔案銷毀。
     */
    public DestructionRecord recordDestructionApproval(String batchId, String dataType, String targetRef,
                                                       LocalDate recordDate, LocalDate asOfDate,
                                                       String reason, String operator) {
        String norm = SessionContext.resolveOperator(operator);
        if (OperatorRole.resolve(norm) != OperatorRole.CHECKER) {
            throw new BusinessException("權限不足：僅會計師（CHECKER）可核准會計檔案銷毀紀錄");
        }
        if (batchId == null || batchId.isBlank()) {
            throw new BusinessException("銷毀核准必須綁定明確的銷毀批次編號（batchId）");
        }
        if (dataType == null || dataType.isBlank() || targetRef == null || targetRef.isBlank()) {
            throw new BusinessException("資料類別與目標參照不可為空");
        }
        if (recordDate == null || asOfDate == null) {
            throw new BusinessException("憑證／帳簿日期與核准基準日不可為空");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("銷毀必須填寫原因");
        }

        String normBatch = batchId.trim();
        String normType = dataType.trim().toUpperCase();
        String normTarget = targetRef.trim();

        synchronized (DESTRUCTION_LOCK) {
            try (Connection conn = DbManager.get()) {
                ensureTables(conn);

                // 驗證 dataType 是否合法，避免未知類型進到核准流程
                getRetentionYears(conn, normType);

                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT batch_id FROM destruction_log WHERE data_type = ? AND target_ref = ?")) {
                    ps.setString(1, normType);
                    ps.setString(2, normTarget);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            throw new BusinessException("禁止重複銷毀核准：目標 [" + normType + "/" + normTarget
                                    + "] 已於批次 [" + rs.getString(1) + "] 核准銷毀");
                        }
                    }
                }

                LocalDate closingCompletion = isEligibleForDestructionInternal(conn, normType, recordDate, asOfDate);
                if (closingCompletion == null) {
                    throw new BusinessException("不符銷毀資格：資料 [" + normType + "/" + normTarget
                            + "]（年度 " + recordDate.getYear() + "）尚未達法定保存期限（應自年度決算完成日起算）、年度未完成決算或仍有未結會計事項");
                }

                conn.setAutoCommit(false);
                try {
                    long id;
                    String insertSql = "INSERT INTO destruction_log" +
                            "(batch_id, data_type, target_ref, fiscal_year, record_date, closing_completion_date, reason, approved_by, status) " +
                            "VALUES (?,?,?,?,?,?,?,?,?)";
                    try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
                        ps.setString(1, normBatch);
                        ps.setString(2, normType);
                        ps.setString(3, normTarget);
                        ps.setInt(4, recordDate.getYear());
                        ps.setString(5, recordDate.toString());
                        ps.setString(6, closingCompletion.toString());
                        ps.setString(7, reason.trim());
                        ps.setString(8, norm);
                        ps.setString(9, STATUS_APPROVED_PENDING_EXECUTION);
                        ps.executeUpdate();
                        try (ResultSet keys = ps.getGeneratedKeys()) {
                            id = keys.next() ? keys.getLong(1) : 0;
                        }
                    }

                    auditDao.log(conn, "DESTROY_APPROVE", normType, id, norm, null,
                            "batchId=" + normBatch + "; targetRef=" + normTarget + "; fiscalYear=" + recordDate.getYear()
                                    + "; closingCompletion=" + closingCompletion
                                    + "; status=" + STATUS_APPROVED_PENDING_EXECUTION
                                    + "; reason=" + reason.trim());
                    conn.commit();

                    return new DestructionRecord(id, normBatch, normType, normTarget, recordDate.getYear(),
                            recordDate, closingCompletion, reason.trim(), norm, LocalDate.now().toString(),
                            STATUS_APPROVED_PENDING_EXECUTION);
                } catch (SQLException ex) {
                    conn.rollback();
                    String msg = ex.getMessage() == null ? "" : ex.getMessage();
                    if (msg.contains("UNIQUE constraint failed") || msg.contains("SQLITE_CONSTRAINT")) {
                        throw new BusinessException("禁止重複銷毀核准：目標 [" + normType + "/" + normTarget + "] 已核准銷毀", ex);
                    }
                    throw ex;
                } finally {
                    conn.setAutoCommit(true);
                }
            } catch (BusinessException e) {
                throw e;
            } catch (SQLException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage();
                if (msg.contains("UNIQUE constraint failed") || msg.contains("SQLITE_CONSTRAINT")) {
                    throw new BusinessException("禁止重複銷毀核准：目標 [" + normType + "/" + normTarget + "] 已核准銷毀", e);
                }
                throw new BusinessException("記錄銷毀核准失敗: " + e.getMessage(), e);
            }
        }
    }

    public List<DestructionRecord> listDestructionApprovals() {
        List<DestructionRecord> list = new ArrayList<>();
        try (Connection conn = DbManager.get()) {
            ensureTables(conn);
            String sql = "SELECT id, batch_id, data_type, target_ref, fiscal_year, record_date, " +
                    "closing_completion_date, reason, approved_by, created_at, status " +
                    "FROM destruction_log ORDER BY id";
            try (Statement st = conn.createStatement();
                 ResultSet rs = st.executeQuery(sql)) {
                while (rs.next()) {
                    String recStr = rs.getString("record_date");
                    String compStr = rs.getString("closing_completion_date");
                    String stStr = rs.getString("status");
                    list.add(new DestructionRecord(
                            rs.getLong("id"),
                            rs.getString("batch_id"),
                            rs.getString("data_type"),
                            rs.getString("target_ref"),
                            rs.getInt("fiscal_year"),
                            (recStr != null && !recStr.isBlank()) ? LocalDate.parse(recStr) : null,
                            (compStr != null && !compStr.isBlank()) ? LocalDate.parse(compStr) : null,
                            rs.getString("reason"),
                            rs.getString("approved_by"),
                            rs.getString("created_at"),
                            (stStr != null && !stStr.isBlank()) ? stStr : STATUS_APPROVED_PENDING_EXECUTION
                    ));
                }
            }
        } catch (SQLException e) {
            throw new BusinessException("查詢銷毀核准紀錄失敗: " + e.getMessage(), e);
        }
        return list;
    }
}
