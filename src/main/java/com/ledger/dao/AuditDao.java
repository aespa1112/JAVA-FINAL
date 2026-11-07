package com.ledger.dao;

import com.ledger.config.DbManager;
import com.ledger.model.AuditLog;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class AuditDao {

    public void log(Connection conn, String action, String target, Long recordId, String operator) throws SQLException {
        log(conn, action, target, recordId, operator, null, null);
    }

    public void log(Connection conn, String action, String target, Long recordId, String operator, String oldData, String newData) throws SQLException {
        String sql = "INSERT INTO audit_log (action, target, record_id, operator, old_data, new_data) VALUES (?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, action);
            ps.setString(2, target);
            if (recordId != null) {
                ps.setLong(3, recordId);
            } else {
                ps.setNull(3, Types.INTEGER);
            }
            ps.setString(4, operator);
            ps.setString(5, oldData);
            ps.setString(6, newData);
            ps.executeUpdate();
        }
    }

    public List<AuditLog> findAll(int limit) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return findAll(conn, limit);
        }
    }

    public List<AuditLog> findAll(Connection conn, int limit) throws SQLException {
        String sql = "SELECT id, action, target, record_id, operator, old_data, new_data, created_at FROM audit_log ORDER BY id DESC LIMIT ?";
        List<AuditLog> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, limit <= 0 ? 100 : limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    long recVal = rs.getLong("record_id");
                    Long recId = rs.wasNull() ? null : recVal;
                    list.add(new AuditLog(
                            rs.getLong("id"),
                            rs.getString("action"),
                            rs.getString("target"),
                            recId,
                            rs.getString("operator"),
                            rs.getString("old_data"),
                            rs.getString("new_data"),
                            rs.getString("created_at")
                    ));
                }
            }
        }
        return list;
    }
}
