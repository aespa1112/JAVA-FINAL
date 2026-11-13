package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.dao.AuditDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 原始憑證附件正式保存與電子簽章授權管理（依據商業會計法及電子處理辦法）。
 * <p>
 * 功能：
 * - 原始憑證關聯記帳傳票、紀錄憑證字號/日期、SHA-256 雜湊與 HMAC-SHA256 電子簽章
 * - 驗章（verifyAttachmentSignature）防範檔案竄改
 * - 已過帳傳票之原始憑證禁止直接刪除；支援核准作廢歷程（status='VOID'）並保留完整審計軌跡
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
            String remark,
            String docNo,
            String docDate,
            String sha256Hash,
            String digitalSignature,
            String status,
            String voidReason,
            String voidedBy,
            String voidedAt
    ) {
        public Attachment(long id, long voucherId, String fileName, String storedPath,
                          String contentType, long fileSize, String uploadedBy, String uploadedAt, String remark) {
            this(id, voucherId, fileName, storedPath, contentType, fileSize, uploadedBy, uploadedAt, remark,
                    null, null, null, null, "ACTIVE", null, null, null);
        }
    }

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
                "remark TEXT, " +
                "doc_no TEXT, " +
                "doc_date TEXT, " +
                "sha256_hash TEXT, " +
                "digital_signature TEXT, " +
                "status TEXT NOT NULL DEFAULT 'ACTIVE', " +
                "void_reason TEXT, " +
                "voided_by TEXT, " +
                "voided_at TEXT" +
                ")"
            );
            st.execute("CREATE INDEX IF NOT EXISTS idx_attach_voucher ON voucher_attachments(voucher_id)");
        }
        ensureAttachmentColumn(conn, "doc_no", "TEXT");
        ensureAttachmentColumn(conn, "doc_date", "TEXT");
        ensureAttachmentColumn(conn, "sha256_hash", "TEXT");
        ensureAttachmentColumn(conn, "digital_signature", "TEXT");
        ensureAttachmentColumn(conn, "status", "TEXT NOT NULL DEFAULT 'ACTIVE'");
        ensureAttachmentColumn(conn, "void_reason", "TEXT");
        ensureAttachmentColumn(conn, "voided_by", "TEXT");
        ensureAttachmentColumn(conn, "voided_at", "TEXT");
    }

    private static void ensureAttachmentColumn(Connection conn, String colName, String colDef) throws SQLException {
        try (Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(voucher_attachments)")) {
            while (rs.next()) {
                if (colName.equalsIgnoreCase(rs.getString("name"))) {
                    return;
                }
            }
        }
        try (Statement st = conn.createStatement()) {
            st.execute("ALTER TABLE voucher_attachments ADD COLUMN " + colName + " " + colDef);
        }
    }

    public Attachment addAttachment(long voucherId, Path sourceFile, String remark, String operator) {
        return addAttachment(voucherId, sourceFile, "DOC-" + voucherId, LocalDate.now().toString(), remark, operator);
    }

    public Attachment addAttachment(long voucherId, Path sourceFile, String docNo, String docDate,
                                    String remark, String operator) {
        String normOp = SessionContext.resolveOperator(operator);
        OperatorRole role = OperatorRole.resolve(normOp);
        if (role == OperatorRole.AUDITOR) {
            throw new BusinessException("權限不足：查帳員（AUDITOR）僅可檢視，不可上傳原始憑證");
        }
        if (role == OperatorRole.ADMIN) {
            throw new BusinessException("管理員不可操作會計傳票附件");
        }
        if (sourceFile == null || !Files.exists(sourceFile) || !Files.isRegularFile(sourceFile)) {
            throw new BusinessException("附件檔案不存在");
        }
        String normDocNo = (docNo == null || docNo.isBlank()) ? "DOC-" + voucherId : docNo.trim();
        String normDocDate = (docDate == null || docDate.isBlank()) ? LocalDate.now().toString() : docDate.trim();
        try {
            LocalDate.parse(normDocDate);
        } catch (Exception e) {
            throw new BusinessException("原始憑證日期格式無效（需為 YYYY-MM-DD）: " + docDate);
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

            String dbNamePart = DbManager.getCurrentTenant();
            if (dbNamePart == null || dbNamePart.isBlank()) {
                dbNamePart = Paths.get(DbManager.getCurrentDbPath()).getFileName().toString().replace(".db", "");
            }
            Path dir = Paths.get("attachments", dbNamePart, String.valueOf(voucherId));
            Files.createDirectories(dir);

            String originalName = sourceFile.getFileName().toString();
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String storedName = stamp + "_" + originalName;
            Path target = dir.resolve(storedName);
            Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);

            byte[] content = Files.readAllBytes(target);
            long size = content.length;
            String contentType = guessContentType(originalName);
            String sha256 = sha256Hex(content);
            String hmacKey = getOrCreateHmacKey(conn);
            String signature = signPayload(hmacKey, voucherId, normDocNo, normDocDate, sha256, normOp);
            String uploadedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

            String sql = "INSERT INTO voucher_attachments " +
                    "(voucher_id, file_name, stored_path, content_type, file_size, uploaded_by, uploaded_at, remark, " +
                    "doc_no, doc_date, sha256_hash, digital_signature, status) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE')";
            long id;
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, voucherId);
                ps.setString(2, originalName);
                ps.setString(3, target.toString().replace('\\', '/'));
                ps.setString(4, contentType);
                ps.setLong(5, size);
                ps.setString(6, normOp);
                ps.setString(7, uploadedAt);
                ps.setString(8, remark);
                ps.setString(9, normDocNo);
                ps.setString(10, normDocDate);
                ps.setString(11, sha256);
                ps.setString(12, signature);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getLong(1) : 0;
                }
            }
            auditDao.log(conn, "ATTACH_ADD", "voucher_attachments", id, normOp,
                    null, "voucherId=" + voucherId + "; docNo=" + normDocNo + "; docDate=" + normDocDate
                            + "; file=" + originalName + "; sha256=" + sha256);
            return new Attachment(id, voucherId, originalName, target.toString().replace('\\', '/'),
                    contentType, size, normOp, uploadedAt, remark,
                    normDocNo, normDocDate, sha256, signature, "ACTIVE", null, null, null);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("上傳原始憑證失敗: " + e.getMessage(), e);
        }
    }

    public boolean verifyAttachmentSignature(long attachmentId) {
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            String sql = "SELECT voucher_id, stored_path, uploaded_by, doc_no, doc_date, sha256_hash, digital_signature " +
                    "FROM voucher_attachments WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, attachmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    long voucherId = rs.getLong("voucher_id");
                    String storedPath = rs.getString("stored_path");
                    String uploadedBy = rs.getString("uploaded_by");
                    String docNo = rs.getString("doc_no");
                    String docDate = rs.getString("doc_date");
                    String expectedHash = rs.getString("sha256_hash");
                    String expectedSig = rs.getString("digital_signature");
                    if (storedPath == null || expectedHash == null || expectedSig == null) return false;
                    Path p = Paths.get(storedPath);
                    if (!Files.exists(p) || !Files.isRegularFile(p)) return false;
                    String actualHash = sha256Hex(Files.readAllBytes(p));
                    if (!expectedHash.equalsIgnoreCase(actualHash)) return false;
                    String hmacKey = getOrCreateHmacKey(conn);
                    String actualSig = signPayload(hmacKey, voucherId, docNo, docDate, actualHash, uploadedBy);
                    return MessageDigest.isEqual(
                            expectedSig.getBytes(StandardCharsets.UTF_8),
                            actualSig.getBytes(StandardCharsets.UTF_8));
                }
            }
        } catch (Exception e) {
            return false;
        }
    }

    public List<Attachment> listByVoucher(long voucherId) {
        List<Attachment> list = new ArrayList<>();
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            String sql = "SELECT id, voucher_id, file_name, stored_path, content_type, file_size, " +
                    "uploaded_by, uploaded_at, remark, doc_no, doc_date, sha256_hash, digital_signature, " +
                    "status, void_reason, voided_by, voided_at " +
                    "FROM voucher_attachments WHERE voucher_id = ? ORDER BY id";
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
                                rs.getString("remark"),
                                rs.getString("doc_no"),
                                rs.getString("doc_date"),
                                rs.getString("sha256_hash"),
                                rs.getString("digital_signature"),
                                rs.getString("status"),
                                rs.getString("void_reason"),
                                rs.getString("voided_by"),
                                rs.getString("voided_at")
                        ));
                    }
                }
            }
        } catch (SQLException e) {
            throw new BusinessException("查詢附件失敗: " + e.getMessage(), e);
        }
        return list;
    }

    public void voidAttachment(long attachmentId, String reason, String operator) {
        String normOp = SessionContext.resolveOperator(operator);
        OperatorRole role = OperatorRole.resolve(normOp);
        if (role == OperatorRole.AUDITOR) {
            throw new BusinessException("權限不足：查帳員（AUDITOR）不可作廢原始憑證");
        }
        if (role == OperatorRole.ADMIN) {
            throw new BusinessException("管理員不可操作會計傳票附件");
        }
        if (reason == null || reason.isBlank()) {
            throw new BusinessException("作廢原始憑證必須填寫原因");
        }

        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            String oldStatus;
            String docNo;
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT status, doc_no FROM voucher_attachments WHERE id = ?")) {
                ps.setLong(1, attachmentId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) {
                        throw new BusinessException("找不到附件 ID: " + attachmentId);
                    }
                    oldStatus = rs.getString(1);
                    docNo = rs.getString(2);
                }
            }
            if ("VOID".equalsIgnoreCase(oldStatus)) {
                throw new BusinessException("此原始憑證已為作廢狀態，不可重複作廢");
            }
            String voidedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE voucher_attachments SET status='VOID', void_reason=?, voided_by=?, voided_at=? WHERE id=?")) {
                ps.setString(1, reason.trim());
                ps.setString(2, normOp);
                ps.setString(3, voidedAt);
                ps.setLong(4, attachmentId);
                ps.executeUpdate();
            }
            auditDao.log(conn, "ATTACH_VOID", "voucher_attachments", attachmentId, normOp,
                    "status=" + oldStatus + "; docNo=" + docNo,
                    "status=VOID; reason=" + reason.trim());
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException("作廢原始憑證失敗: " + e.getMessage(), e);
        }
    }

    public void deleteAttachment(long attachmentId, String operator) {
        String normOp = SessionContext.resolveOperator(operator);
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
                throw new BusinessException("已過帳傳票之原始憑證不可直接刪除（內控保全）。如需作廢或更正請使用作廢流程保留歷程。");
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

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    private static String getOrCreateHmacKey(Connection conn) throws SQLException {
        DbManager.ensureSystemTables(conn);
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT meta_value FROM system_meta WHERE meta_key = 'attachment_hmac_key'")) {
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String val = rs.getString(1);
                    if (val != null && !val.isBlank()) return val;
                }
            }
        }
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        String hex = HexFormat.of().formatHex(keyBytes);
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT OR IGNORE INTO system_meta (meta_key, meta_value) VALUES ('attachment_hmac_key', ?)")) {
            ps.setString(1, hex);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement(
                "SELECT meta_value FROM system_meta WHERE meta_key = 'attachment_hmac_key'");
             ResultSet rs = ps.executeQuery()) {
            return rs.next() ? rs.getString(1) : hex;
        }
    }

    private static String signPayload(String hexKey, long voucherId, String docNo, String docDate,
                                      String sha256, String operator) throws Exception {
        String payload = voucherId + "|" + (docNo != null ? docNo : "") + "|"
                + (docDate != null ? docDate : "") + "|" + sha256 + "|" + (operator != null ? operator : "");
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(HexFormat.of().parseHex(hexKey), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
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
