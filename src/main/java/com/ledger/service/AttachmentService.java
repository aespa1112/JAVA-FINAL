package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.dao.AuditDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 原始憑證附件管理（教學用）。
 * 權限：AUDITOR 唯讀；已過帳傳票附件不可直接刪除。
 */
public class AttachmentService {

    public record Attachment(
            long id,
            long voucherId,
            String fileName,
            String storedPath,
            String contentType,
            long fileSize,
            String uploadedBy,
            String uploadedAt,
            String remark
    ) {}

    private final AuditDao auditDao = new AuditDao();

    public void ensureTable(Connection conn) throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.execute(
                "CREATE TABLE IF NOT EXISTS voucher_attachments (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                "voucher_id INTEGER NOT NULL REFERENCES vouchers(id) ON DELETE CASCADE, " +
                "file_name TEXT NOT NULL, " +
                "stored_path TEXT NOT NULL, " +
                "content_type TEXT, " +
                "file_size INTEGER NOT NULL DEFAULT 0, " +
                "uploaded_by TEXT, " +
                "uploaded_at TEXT NOT NULL DEFAULT (datetime('now','localtime')), " +
                "remark TEXT" +
                ")"
            );
            st.execute("CREATE INDEX IF NOT EXISTS idx_attach_voucher ON voucher_attachments(voucher_id)");
        }
    }

    public Attachment addAttachment(long voucherId, Path sourceFile, String remark, String operator) {
        String normOp = OperatorRole.normalize(operator);
        OperatorRole role = OperatorRole.resolve(normOp);
        if (role == OperatorRole.AUDITOR) {
            throw new BusinessException("權限不足：查帳員（AUDITOR）僅可檢視，不可上傳原始憑證");
        }
        if (role == OperatorRole.ADMIN) {
            throw new BusinessException("管理員不可操作會計傳票附件");
        }
        if (!Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            throw new BusinessException("附件檔案不存在");
        }

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);

            try (PreparedStatement ps = conn.prepareStatement("SELECT id, status FROM vouchers WHERE id = ?")) {
                ps.setLong(1, voucherId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new BusinessException("找不到傳票 ID: " + voucherId);
                    }
                }
            }

            String clientKey = Paths.get(DbManager.getCurrentDbPath()).getFileName().toString().replace(".db", "");
            Path dir = Paths.get("attachments", clientKey, String.valueOf(voucherId));
            Files.createDirectories(dir);

            String originalName = sourceFile.getFileName().toString();
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String storedName = stamp + "_" + originalName;
            Path target = dir.resolve(storedName);
            Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);

            long size = Files.size(target);
            String contentType = guessContentType(originalName);

            String sql = "INSERT INTO voucher_attachments " +
                    "(voucher_id, file_name, stored_path, content_type, file_size, uploaded_by, remark) " +
                    "VALUES (?,?,?,?,?,?,?)";
            long id;
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, voucherId);
                ps.setString(2, originalName);
                ps.setString(3, target.toString().replace('\\', '/'));
                ps.setString(4, contentType);
                ps.setLong(5, size);
                ps.setString(6, normOp);
                ps.setString(7, remark);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getLong(1) : 0;
                }
            }
            auditDao.log(conn, "ATTACH_ADD", "voucher_attachments", id, normOp,
                    null, "voucherId=" + voucherId + "; file=" + originalName);
            return new Attachment(id, voucherId, originalName, target.toString(),
                    contentType, size, normOp, LocalDateTime.now().toString(), remark);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("上傳原始憑證失敗: " + e.getMessage(), e);
        }
    }

    public List<Attachment> listByVoucher(long voucherId) {
        List<Attachment> list = new ArrayList<>();
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            String sql = "SELECT id, voucher_id, file_name, stored_path, content_type, file_size, " +
                    "uploaded_by, uploaded_at, remark FROM voucher_attachments WHERE voucher_id = ? ORDER BY id";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, voucherId);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        list.add(new Attachment(
                                rs.getLong("id"),
                                rs.getLong("voucher_id"),
                                rs.getString("file_name"),
                                rs.getString("stored_path"),
                                rs.getString("content_type"),
                                rs.getLong("file_size"),
                                rs.getString("uploaded_by"),
                                rs.getString("uploaded_at"),
                                rs.getString("remark")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            throw new BusinessException("查詢附件失敗: " + e.getMessage(), e);
        }
        return list;
    }

    public void deleteAttachment(long attachmentId, String operator) {
        String normOp = OperatorRole.normalize(operator);
        OperatorRole role = OperatorRole.resolve(normOp);
        if (role == OperatorRole.AUDITOR) {
            throw new BusinessException("權限不足：查帳員（AUDITOR）不可刪除原始憑證");
        }
        if (role == OperatorRole.ADMIN) {
            throw new BusinessException("管理員不可操作會計傳票附件");
        }

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            long voucherId = 0;
            String path = null;
            String fileName = null;
            String status = null;
            String sql = "SELECT a.voucher_id, a.stored_path, a.file_name, v.status " +
                    "FROM voucher_attachments a JOIN vouchers v ON v.id = a.voucher_id WHERE a.id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, attachmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new BusinessException("找不到附件 ID: " + attachmentId);
                    }
                    voucherId = rs.getLong(1);
                    path = rs.getString(2);
                    fileName = rs.getString(3);
                    status = rs.getString(4);
                }
            }
            if ("POSTED".equalsIgnoreCase(status)) {
                throw new BusinessException("已過帳傳票之原始憑證不可直接刪除（內控保全）。如需更正請以主管核准流程處理。");
            }
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM voucher_attachments WHERE id = ?")) {
                ps.setLong(1, attachmentId);
                ps.executeUpdate();
            }
            auditDao.log(conn, "ATTACH_DELETE", "voucher_attachments", attachmentId, normOp,
                    "voucherId=" + voucherId + "; file=" + fileName, "DELETED");
            if (path != null) {
                try { Files.deleteIfExists(Paths.get(path)); } catch (IOException ignored) {}
            }
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException("刪除附件失敗: " + e.getMessage(), e);
        }
    }

    private static String guessContentType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf")) return "application/pdf";
        if (lower.endsWith(".png")) return "image/png";
        if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg";
        if (lower.endsWith(".gif")) return "image/gif";
        return "application/octet-stream";
    }
}
