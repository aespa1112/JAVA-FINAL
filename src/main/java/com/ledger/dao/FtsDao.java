package com.ledger.dao;

import com.ledger.config.DbManager;
import com.ledger.model.Voucher;
import com.ledger.model.VoucherEntry;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class FtsDao {

    private final VoucherDao voucherDao = new VoucherDao();

    public List<Voucher> search(String query) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return search(conn, query);
        }
    }

    public List<Voucher> search(Connection conn, String query) throws SQLException {
        if (query == null || query.trim().isEmpty()) {
            return List.of();
        }

        String sanitized = query.trim().replace("\"", "").replace("'", "");
        List<Voucher> list = new ArrayList<>();

        if (sanitized.length() >= 3) {
            String sql = "SELECT v.id, v.period_id, v.voucher_no, v.voucher_date, v.summary, v.status, " +
                         "       v.preparer, v.reviewer, v.reversed_id, v.created_at, v.posted_at " +
                         "FROM vouchers_fts f " +
                         "JOIN vouchers v ON v.id = f.rowid " +
                         "WHERE vouchers_fts MATCH ? " +
                         "ORDER BY v.voucher_date DESC, v.voucher_no DESC";

            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, "\"" + sanitized + "\"");
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        List<VoucherEntry> entries = voucherDao.findEntriesByVoucherId(conn, id);
                        long reversedVal = rs.getLong("reversed_id");
                        Long reversedId = rs.wasNull() ? null : reversedVal;
                        list.add(new Voucher(
                                id,
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
                        ));
                    }
                }
            } catch (SQLException ftsEx) {
                // FTS 匹配若有語法問題則由後續 LIKE 安全備援機制處理
            }
        }

        // 若無匹配或查詢字元少於 3 個，使用帶跳脫保護的 LIKE 查詢
        if (list.isEmpty()) {
            String escapedLike = sanitized.replace("/", "//").replace("%", "/%").replace("_", "/_");
            String likeSql = "SELECT v.id, v.period_id, v.voucher_no, v.voucher_date, v.summary, v.status, " +
                             "       v.preparer, v.reviewer, v.reversed_id, v.created_at, v.posted_at " +
                             "FROM vouchers v " +
                             "WHERE (v.summary LIKE ? ESCAPE '/' OR v.voucher_no LIKE ? ESCAPE '/') " +
                             "ORDER BY v.voucher_date DESC, v.voucher_no DESC";
            try (PreparedStatement ps = conn.prepareStatement(likeSql)) {
                String pattern = "%" + escapedLike + "%";
                ps.setString(1, pattern);
                ps.setString(2, pattern);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        long id = rs.getLong("id");
                        List<VoucherEntry> entries = voucherDao.findEntriesByVoucherId(conn, id);
                        long reversedVal = rs.getLong("reversed_id");
                        Long reversedId = rs.wasNull() ? null : reversedVal;
                        list.add(new Voucher(
                                id,
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
                        ));
                    }
                }
            }
        }
        return list;
    }
}
