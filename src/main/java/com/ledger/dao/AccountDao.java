package com.ledger.dao;

import com.ledger.config.DbManager;
import com.ledger.model.Account;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public class AccountDao {

    public List<Account> findAll() throws SQLException {
        try (Connection conn = DbManager.get()) {
            return findAll(conn);
        }
    }

    public List<Account> findAll(Connection conn) throws SQLException {
        String sql = "SELECT id, code, name, type, normal_side, parent_id, is_leaf, enabled FROM accounts ORDER BY code";
        List<Account> list = new ArrayList<>();
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(mapRow(rs));
            }
        }
        return list;
    }

    public Optional<Account> findById(long id) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return findById(conn, id);
        }
    }

    public Optional<Account> findById(Connection conn, long id) throws SQLException {
        String sql = "SELECT id, code, name, type, normal_side, parent_id, is_leaf, enabled FROM accounts WHERE id = ?";
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

    public Optional<Account> findByCode(String code) throws SQLException {
        try (Connection conn = DbManager.get()) {
            return findByCode(conn, code);
        }
    }

    public Optional<Account> findByCode(Connection conn, String code) throws SQLException {
        String sql = "SELECT id, code, name, type, normal_side, parent_id, is_leaf, enabled FROM accounts WHERE code = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, code);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(mapRow(rs));
                }
            }
        }
        return Optional.empty();
    }

    public Account create(Connection conn, Account a) throws SQLException {
        String sql = "INSERT INTO accounts (code, name, type, normal_side, parent_id, is_leaf, enabled) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, a.code());
            ps.setString(2, a.name());
            ps.setString(3, a.type());
            ps.setString(4, a.normalSide());
            if (a.parentId() != null) {
                ps.setLong(5, a.parentId());
            } else {
                ps.setNull(5, Types.INTEGER);
            }
            ps.setInt(6, a.isLeaf() ? 1 : 0);
            ps.setInt(7, a.enabled() ? 1 : 0);
            ps.executeUpdate();
            try (ResultSet keys = ps.getGeneratedKeys()) {
                if (keys.next()) {
                    long id = keys.getLong(1);
                    return new Account(id, a.code(), a.name(), a.type(), a.normalSide(), a.parentId(), a.isLeaf(), a.enabled());
                }
            }
        }
        return a;
    }

    public void update(Connection conn, Account a) throws SQLException {
        String sql = "UPDATE accounts SET name = ?, type = ?, normal_side = ?, parent_id = ?, is_leaf = ?, enabled = ? WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, a.name());
            ps.setString(2, a.type());
            ps.setString(3, a.normalSide());
            if (a.parentId() != null) {
                ps.setLong(4, a.parentId());
            } else {
                ps.setNull(4, Types.INTEGER);
            }
            ps.setInt(5, a.isLeaf() ? 1 : 0);
            ps.setInt(6, a.enabled() ? 1 : 0);
            ps.setLong(7, a.id());
            ps.executeUpdate();
        }
    }

    private Account mapRow(ResultSet rs) throws SQLException {
        long id = rs.getLong("id");
        String code = rs.getString("code");
        String name = rs.getString("name");
        String type = rs.getString("type");
        String normalSide = rs.getString("normal_side");
        long parentIdVal = rs.getLong("parent_id");
        Long parentId = rs.wasNull() ? null : parentIdVal;
        boolean isLeaf = rs.getInt("is_leaf") == 1;
        boolean enabled = rs.getInt("enabled") == 1;
        return new Account(id, code, name, type, normalSide, parentId, isLeaf, enabled);
    }
}
