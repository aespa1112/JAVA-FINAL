package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.dao.AuditDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import java.sql.*;
import java.util.List;

/**
 * 期初餘額：以一張標記 [OPENING BALANCE] 的 POSTED 傳票寫入真實分錄。
 * 借方合計必須等於貸方合計（複式）。
 */
public class OpeningBalanceService {

    public record OpeningLine(String accountCode, long debitMinor, long creditMinor, String memo) {}

    private final AuditDao auditDao = new AuditDao();

    public long importOpening(String openingDate, List<OpeningLine> lines, String operator) {
        String norm = OperatorRole.normalize(operator);
        if (OperatorRole.resolve(norm) != OperatorRole.CHECKER) {
            throw new BusinessException("期初餘額匯入僅會計師可執行");
        }
        if (lines == null || lines.isEmpty()) {
            throw new BusinessException("請至少提供一筆期初分錄");
        }
        long dr = 0, cr = 0;
        for (OpeningLine l : lines) {
            if (l.debitMinor() < 0 || l.creditMinor() < 0) {
                throw new BusinessException("金額不可為負");
            }
            if (l.debitMinor() > 0 && l.creditMinor() > 0) {
                throw new BusinessException("同一列不可同時有借貸: " + l.accountCode());
            }
            dr += l.debitMinor();
            cr += l.creditMinor();
        }
        if (dr != cr) {
            throw new BusinessException(String.format("期初借貸不平衡：借 %d 分 / 貸 %d 分", dr, cr));
        }

        try (Connection conn = DbManager.get()) {
            boolean ac = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT COUNT(*) FROM vouchers WHERE status='POSTED' AND summary LIKE '%[OPENING BALANCE]%' AND voucher_date=?")) {
                    ps.setString(1, openingDate);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            throw new BusinessException("該日已存在期初餘額傳票，禁止重複匯入");
                        }
                    }
                }

                int year = Integer.parseInt(openingDate.substring(0, 4));
                int month = Integer.parseInt(openingDate.substring(5, 7));
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT OR IGNORE INTO fiscal_periods(year, month, status) VALUES (?,?, 'OPEN')")) {
                    ps.setInt(1, year);
                    ps.setInt(2, month);
                    ps.executeUpdate();
                }
                long periodId;
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT id FROM fiscal_periods WHERE year=? AND month=?")) {
                    ps.setInt(1, year);
                    ps.setInt(2, month);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (!rs.next()) throw new SQLException("期間不存在");
                        periodId = rs.getLong(1);
                    }
                }

                String voucherNo = String.format("OB%s%02d-001", year, month);
                long vid;
                try (PreparedStatement ps = conn.prepareStatement(
                        "INSERT INTO vouchers(period_id, voucher_no, voucher_date, summary, status, preparer) " +
                                "VALUES (?,?,?,?, 'DRAFT', ?)", Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, periodId);
                    ps.setString(2, voucherNo);
                    ps.setString(3, openingDate);
                    ps.setString(4, "[OPENING BALANCE] 期初餘額匯入");
                    ps.setString(5, "SYSTEM");
                    ps.executeUpdate();
                    try (ResultSet keys = ps.getGeneratedKeys()) {
                        if (!keys.next()) throw new SQLException("無傳票ID");
                        vid = keys.getLong(1);
                    }
                }

                int line = 1;
                for (OpeningLine l : lines) {
                    long accId = findAccount(conn, l.accountCode());
                    if (accId <= 0) throw new BusinessException("找不到科目: " + l.accountCode());
                    try (PreparedStatement ps = conn.prepareStatement(
                            "INSERT INTO voucher_entries(voucher_id, line_no, account_id, memo, debit_minor, credit_minor) VALUES (?,?,?,?,?,?)")) {
                        ps.setLong(1, vid);
                        ps.setInt(2, line++);
                        ps.setLong(3, accId);
                        ps.setString(4, l.memo() != null ? l.memo() : "期初");
                        ps.setLong(5, l.debitMinor());
                        ps.setLong(6, l.creditMinor());
                        ps.executeUpdate();
                    }
                }

                try (PreparedStatement ps = conn.prepareStatement(
                        "UPDATE vouchers SET status='POSTED', reviewer=?, posted_at=datetime('now','localtime') WHERE id=?")) {
                    ps.setString(1, norm);
                    ps.setLong(2, vid);
                    ps.executeUpdate();
                }
                auditDao.log(conn, "OPENING_BALANCE", "vouchers", vid, norm, null, openingDate);
                conn.commit();
                return vid;
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof BusinessException be) throw be;
                throw new BusinessException("期初匯入失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(ac);
            }
        } catch (SQLException e) {
            throw new BusinessException(e.getMessage(), e);
        }
    }

    private long findAccount(Connection conn, String code) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("SELECT id FROM accounts WHERE code=?")) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        }
    }
}
