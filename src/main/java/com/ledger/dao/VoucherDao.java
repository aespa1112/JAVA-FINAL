package com.ledger.dao;

import com.ledger.config.DbManager;
import com.ledger.model.Voucher;
import com.ledger.model.VoucherEntry;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class VoucherDao {

    public Voucher create(Connection conn, Voucher v, List<VoucherEntry> entries) throws SQLException {
        String sql = "INSERT INTO vouchers (period_id, voucher_no, voucher_date, summary, status, preparer, reviewer, reversed_id) " +
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        long voucherId;
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setLong(1, v.periodId());
            ps.setString(2, v.voucherNo());
            ps.setString(3, v.voucherDate());
            ps.setString(4, v.summary());
            ps.setString(5, v.status() == null ? "DRAFT" : v.status());
            ps.setString(6, v.preparer());
            ps.setString(7, v.reviewer());
            if (v.reversedId() != null) {
                ps.setLong(8, v.reversedId());
            } else {
                ps.setNull(8, Types.INTEGER);
            }
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    voucherId = keys.getLong(1);
                } else {
                    throw new SQLException("建立傳票失敗，未取得產生之 ID");
                }
            }
        }

        List<VoucherEntry> savedEntries = new ArrayList<>();
        String entrySql = "INSERT INTO voucher_entries (voucher_id, line_no, account_id, memo, debit_minor, credit_minor) " +
                          "VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(entrySql, Statement.RETURN_GENERATED_KEYS)) {
            int lineNo = 1;
            for (VoucherEntry e : entries) {
                ps.setLong(1, voucherId);
                ps.setInt(2, lineNo);
                ps.setLong(3, e.accountId());
                ps.setString(4, e.memo());
                ps.setLong(5, e.debitMinor());
                ps.setLong(6, e.creditMinor());
                ps.executeUpdate();
                long entryId;
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    entryId = keys.next() ? keys.getLong(1) : 0;
                }
                savedEntries.add(new VoucherEntry(
                        entryId, voucherId, lineNo, e.accountId(), e.memo(),
                        e.debitMinor(), e.creditMinor(), e.accountCode(), e.accountName()));
                lineNo++;
            }
        }

        return findById(conn, voucherId).orElseThrow();
    }

    public Optional<Voucher> findById(long id) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return findById(conn, id);
        }
    }

    public Optional<Voucher> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, period_id, voucher_no, voucher_date, summary, status, preparer, reviewer, reversed_id, created_at, posted_at " +
                     "FROM vouchers WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    List<VoucherEntry> entries = findEntriesByVoucherId(conn, id);
                    return Optional.of(mapVoucher(rs, entries));
                }
            }
        }
        return Optional.empty();
    }

    public List<VoucherEntry> findEntriesByVoucherId(Connection conn, long voucherId) throws SQLException {
        String sql = "SELECT e.id, e.voucher_id, e.line_no, e.account_id, e.memo, e.debit_minor, e.credit_minor, " +
                     "a.code AS account_code, a.name AS account_name " +
                     "FROM voucher_entries e " +
                     "JOIN accounts a ON a.id = e.account_id " +
                     "WHERE e.voucher_id = ? ORDER BY e.line_no";
        List<VoucherEntry> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, voucherId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    list.add(new VoucherEntry(
                            rs.getLong("id"),
                            rs.getLong("voucher_id"),
                            rs.getInt("line_no"),
                            rs.getLong("account_id"),
                            rs.getString("memo"),
                            rs.getLong("debit_minor"),
                            rs.getLong("credit_minor"),
                            rs.getString("account_code"),
                            rs.getString("account_name")
                    ));
                }
            }
        }
        return list;
    }

    public String generateNextVoucherNo(Connection conn, long periodId, int year, int month) throws SQLException {
        String prefix = String.format("%04d-%02d-", year, month);

        // 確保序號表存在
        try (Statement st = conn.createStatement()) {
            st.execute("CREATE TABLE IF NOT EXISTS voucher_sequences (prefix TEXT PRIMARY KEY, last_seq INTEGER NOT NULL);");
        }

        int nextSeq = 1;
        String querySeqSql = "SELECT last_seq FROM voucher_sequences WHERE prefix = ?";
        boolean exists = false;
        try (PreparedStatement ps = conn.prepareStatement(querySeqSql)) {
            ps.setString(1, prefix);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    exists = true;
                    nextSeq = rs.getInt(1) + 1;
                }
            }
        }

        if (!exists) {
            // 首次使用該月份前綴：計算歷史最大序號（合併主檔與審計日誌），防止刪除草稿號碼重用
            String sql = "SELECT MAX(seq) FROM ( " +
                         "    SELECT CAST(SUBSTR(voucher_no, LENGTH(?) + 1) AS INTEGER) AS seq " +
                         "    FROM vouchers WHERE period_id = ? AND voucher_no LIKE ? " +
                         "    UNION ALL " +
                         "    SELECT CAST(SUBSTR(new_data, LENGTH('VoucherNo: ' || ?) + 1) AS INTEGER) AS seq " +
                         "    FROM audit_log WHERE action = 'CREATE_DRAFT' AND new_data LIKE ('VoucherNo: ' || ? || '%') " +
                         ")";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, prefix);
                ps.setLong(2, periodId);
                ps.setString(3, prefix + "%");
                ps.setString(4, prefix);
                ps.setString(5, prefix);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) {
                        int maxSeq = rs.getInt(1);
                        if (maxSeq > 0) {
                            nextSeq = maxSeq + 1;
                        }
                    }
                }
            }
            String insertSeqSql = "INSERT INTO voucher_sequences (prefix, last_seq) VALUES (?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(insertSeqSql)) {
                ps.setString(1, prefix);
                ps.setInt(2, nextSeq);
                ps.executeUpdate();
            }
        } else {
            // 已有紀錄：直接遞增更新序號，O(1) 效能，避免每次全表掃描 audit_log
            String updateSeqSql = "UPDATE voucher_sequences SET last_seq = ? WHERE prefix = ?";
            try (PreparedStatement ps = conn.prepareStatement(updateSeqSql)) {
                ps.setInt(1, nextSeq);
                ps.setString(2, prefix);
                ps.executeUpdate();
            }
        }

        if (nextSeq >= 10000) {
            return prefix + nextSeq;
        }
        return String.format("%s%04d", prefix, nextSeq);
    }

    public void lockAndCheckDraft(Connection conn, long voucherId) throws SQLException {
        String sql = "SELECT status FROM vouchers WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, voucherId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    throw new SQLException("傳票不存在: ID " + voucherId);
                }
                String status = rs.getString("status");
                if (!"DRAFT".equalsIgnoreCase(status)) {
                    throw new SQLException("僅草稿狀態傳票可執行此操作，當前狀態為: " + status);
                }
            }
        }
    }

    public void markPosted(Connection conn, long voucherId, String reviewer) throws SQLException {
        String sql = "UPDATE vouchers SET status = 'POSTED', reviewer = ?, posted_at = datetime('now','localtime') " +
                     "WHERE id = ? AND status = 'DRAFT'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, reviewer);
            ps.setLong(2, voucherId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new SQLException("過帳失敗：傳票不存在或非草稿狀態");
            }
        }
    }

    public void updateDraft(Connection conn, Voucher v, List<VoucherEntry> entries) throws SQLException {
        String sql = "UPDATE vouchers SET period_id = ?, voucher_no = ?, voucher_date = ?, summary = ? WHERE id = ? AND status = 'DRAFT'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, v.periodId());
            ps.setString(2, v.voucherNo());
            ps.setString(3, v.voucherDate());
            ps.setString(4, v.summary());
            ps.setLong(5, v.id());
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new SQLException("修改草稿失敗：傳票不存在或非草稿狀態");
            }
        }

        String deleteEntriesSql = "DELETE FROM voucher_entries WHERE voucher_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(deleteEntriesSql)) {
            ps.setLong(1, v.id());
            ps.executeUpdate();
        }

        String entrySql = "INSERT INTO voucher_entries (voucher_id, line_no, account_id, memo, debit_minor, credit_minor) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(entrySql)) {
            int lineNo = 1;
            for (VoucherEntry e : entries) {
                ps.setLong(1, v.id());
                ps.setInt(2, lineNo++);
                ps.setLong(3, e.accountId());
                ps.setString(4, e.memo());
                ps.setLong(5, e.debitMinor());
                ps.setLong(6, e.creditMinor());
                ps.executeUpdate();
            }
        }
    }

    public void deleteDraft(Connection conn, long voucherId) throws SQLException {
        String sql = "DELETE FROM vouchers WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, voucherId);
            int rows = ps.executeUpdate();
            if (rows == 0) {
                throw new SQLException("刪除失敗：傳票不存在");
            }
        }
    }

    public long countAll() throws SQLException {
        try (Connection conn = DbManager.get()) {
            return countAll(conn);
        }
    }

    public long countAll(Connection conn) throws SQLException {
        String sql = "SELECT COUNT(*) FROM vouchers";
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getLong(1) : 0;
        }
    }

    public List<Voucher> findAll(Connection conn, String statusFilter, String startDate, String endDate, String keyword) throws SQLException {
        StringBuilder sql = new StringBuilder(
                "SELECT id, period_id, voucher_no, voucher_date, summary, status, preparer, reviewer, reversed_id, created_at, posted_at " +
                "FROM vouchers WHERE 1=1 ");
        List<Object> params = new ArrayList<>();

        if (statusFilter != null && !statusFilter.trim().isEmpty() && !"ALL".equalsIgnoreCase(statusFilter)) {
            sql.append(" AND status = ?");
            params.add(statusFilter.trim());
        }
        if (startDate != null && !startDate.trim().isEmpty()) {
            sql.append(" AND voucher_date >= ?");
            params.add(startDate.trim());
        }
        if (endDate != null && !endDate.trim().isEmpty()) {
            sql.append(" AND voucher_date <= ?");
            params.add(endDate.trim());
        }
        if (keyword != null && !keyword.trim().isEmpty()) {
            sql.append(" AND (summary LIKE ? ESCAPE '/' OR voucher_no LIKE ? ESCAPE '/')");
            String escaped = keyword.trim().replace("/", "//").replace("%", "/%").replace("_", "/_");
            params.add("%" + escaped + "%");
            params.add("%" + escaped + "%");
        }
        sql.append(" ORDER BY voucher_date DESC, voucher_no DESC");

        List<Voucher> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            for (int i = 0; i < params.size(); i++) {
                ps.setObject(i + 1, params.get(i));
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long id = rs.getLong("id");
                    List<VoucherEntry> entries = findEntriesByVoucherId(conn, id);
                    list.add(mapVoucher(rs, entries));
                }
            }
        }
        return list;
    }

    private Voucher mapVoucher(ResultSet rs, List<VoucherEntry> entries) throws SQLException {
        long reversedVal = rs.getLong("reversed_id");
        Long reversedId = rs.wasNull() ? null : reversedVal;
        return new Voucher(
                rs.getLong("id"),
                rs.getLong("period_id"),
                rs.getString("voucher_no"),
                rs.getString("voucher_date"),
                rs.getString("summary"),
                rs.getString("status"),
                rs.getString("preparer"),
                rs.getString("reviewer"),
                reversedId,
                rs.getString("created_at"),
                rs.getString("posted_at"),
                entries
        );
    }
}
