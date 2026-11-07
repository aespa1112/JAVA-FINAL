package com.ledger.dao;

import com.ledger.config.DbManager;
import com.ledger.model.FiscalPeriod;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class FiscalPeriodDao {

    public List<FiscalPeriod> findAll() throws SQLException {
        try (Connection conn = DbManager.get()) {
            return findAll(conn);
        }
    }

    public List<FiscalPeriod> findAll(Connection conn) throws SQLException {
        String sql = "SELECT id, year, month, status FROM fiscal_periods ORDER BY year DESC, month DESC";
        List<FiscalPeriod> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public Optional<FiscalPeriod> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, year, month, status FROM fiscal_periods WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<FiscalPeriod> findByYearMonth(Connection conn, int year, int month) throws SQLException {
        String sql = "SELECT id, year, month, status FROM fiscal_periods WHERE year = ? AND month = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setInt(1, year);
            ps.setInt(2, month);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public FiscalPeriod getOrCreate(Connection conn, int year, int month) throws SQLException {
        Optional<FiscalPeriod> existing = findByYearMonth(conn, year, month);
        if (existing.isPresent()) {
            return existing.get();
        }

        String insertSql = "INSERT INTO fiscal_periods (year, month, status) VALUES (?, ?, 'OPEN')";
        try (PreparedStatement ps = conn.prepareStatement(insertSql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setInt(1, year);
            ps.setInt(2, month);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    return new FiscalPeriod(keys.getLong(1), year, month, "OPEN");
                }
            }
        }
        return findByYearMonth(conn, year, month).orElseThrow();
    }

    public void updateStatus(Connection conn, long id, String status) throws SQLException {
        String sql = "UPDATE fiscal_periods SET status = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, status);
            ps.setLong(2, id);
            ps.executeUpdate();
        }
    }

    private FiscalPeriod mapRow(ResultSet rs) throws SQLException {
        return new FiscalPeriod(
                rs.getLong("id"),
                rs.getInt("year"),
                rs.getInt("month"),
                rs.getString("status")
        );
    }
}
