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
 * 商業會計法第 38 條：憑證至少 5 年、帳簿及報表至少 10 年；
 * 未結會計事項（如尚有草稿傳票或未完成年結案件）依法延長保存，不得銷毀。
 * 本服務記錄保存政策與銷毀核准，禁止未核准銷毀。
 */
public class RetentionPolicyService {

    public record Policy(String dataType, int years, String description) {}
    public record DestructionRecord(long id, String dataType, String targetRef, String reason,
                                    String approvedBy, String createdAt) {}

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
                "CREATE TABLE IF NOT EXISTS destruction_log (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "data_type TEXT NOT NULL, " +
                "target_ref TEXT NOT NULL, " +
                "reason TEXT NOT NULL, " +
                "approved_by TEXT NOT NULL, " +
                "created_at TEXT NOT NULL DEFAULT (datetime('now','localtime')))"
            );
            st.execute("INSERT OR IGNORE INTO retention_policy VALUES ('VOUCHER', 5, '會計憑證（記帳憑證／原始憑證）')");
            st.execute("INSERT OR IGNORE INTO retention_policy VALUES ('BOOK', 10, '會計帳簿')");
            st.execute("INSERT OR IGNORE INTO retention_policy VALUES ('REPORT', 10, '財務報表')");
            st.execute("INSERT OR IGNORE INTO retention_policy VALUES ('AUDIT_LOG', 10, '審計日誌')");
        }
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
        if (hasUnsettledMatters) {
            return false;
        }
        int years = 5;
        try (Connection conn = DbManager.get()) {
            ensureTables(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT retain_years FROM retention_policy WHERE data_type=?")) {
                ps.setString(1, dataType);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) years = rs.getInt(1);
                }
            }
            if (hasUnsettledMattersInDb(conn, recordDate)) {
                return false;
            }
        } catch (SQLException e) {
            throw new BusinessException(e.getMessage(), e);
        }
        return !recordDate.plusYears(years).isAfter(asOf);
    }

    private static boolean hasUnsettledMattersInDb(Connection conn, LocalDate recordDate) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT 1 FROM vouchers WHERE status = 'DRAFT' AND voucher_date <= ? LIMIT 1")) {
            ps.setString(1, recordDate.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return true;
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
                    "SELECT 1 FROM year_end_closings WHERE fiscal_year = ? " +
                    "AND status IN ('DRAFT_CREATED', 'REOPEN_IN_PROGRESS') LIMIT 1")) {
                ps.setInt(1, recordDate.getYear());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) return true;
                }
            }
        }
        return false;
    }

    public void recordDestructionApproval(String dataType, String targetRef, String reason, String operator) {
        String norm = SessionContext.resolveOperator(operator);
        if (OperatorRole.resolve(norm) != OperatorRole.CHECKER) {
            throw new BusinessException("權限不足：僅會計師（CHECKER）可核准會計檔案銷毀紀錄");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("銷毀必須填寫原因");
        }
        try (Connection conn = DbManager.get()) {
            ensureTables(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "INSERT INTO destruction_log(data_type, target_ref, reason, approved_by) VALUES (?,?,?,?)")) {
                ps.setString(1, dataType);
                ps.setString(2, targetRef);
                ps.setString(3, reason.trim());
                ps.setString(4, norm);
                ps.executeUpdate();
            }
            auditDao.log(conn, "DESTROY_APPROVE", dataType, null, norm, null,
                    targetRef + "; " + reason.trim());
        } catch (SQLException e) {
            throw new BusinessException("記錄銷毀核准失敗: " + e.getMessage(), e);
        }
    }
}
