package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.config.TenantPathResolver;
import com.ledger.dao.AuditDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.UserPrincipal;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 原始憑證附件正式保存與內部完整性驗證（依據商業會計法及電子處理辦法）。
 * <p>
 * 功能與安全設計：
 * - 原始憑證關聯記帳傳票、紀錄憑證字號/日期與 SHA-256 雜湊。
 * - 使用對稱式 HMAC-SHA256 進行內部完整性／來源防偽驗證（注意：HMAC 為對稱金鑰驗證碼，
 *   非 X.509 / PKI 非對稱公開金鑰數位簽章，不具對外第三方憑證機構不可否認性）。
 * - 金鑰存放於資料庫與附件目錄之外的 OS 受保護安全儲存檔（預設 {@code ~/.ledger_security/attachment_hmac_keys.properties}），
 *   金鑰內容以 AES-256-GCM 加密存放（Windows 優先採用 DPAPI CurrentUser 保護主金鑰；非支援環境採明確 PBKDF2＋強制 OS ACL 降級），
 *   禁止置於 {@code data/} 或 {@code attachments/}，且檔案權限設定失敗時直接拒絕執行，不得靜默忽略。
 * - 支援金鑰版本控管（{@code key_version}）與安全輪替（{@link #rotateHmacKey(String)}），保留舊版金鑰及輪替紀錄。
 * - 嚴格驗證附件及金鑰父目錄路徑邊界（防止路徑穿越、符號連結／Junction 與檔案替換 TOCTOU 攻擊）。
 */
public class AttachmentService {

    public enum KeystoreProtectionMode {
        WINDOWS_DPAPI_AES256_GCM,
        FALLBACK_PBKDF2_AES256_GCM_OS_ACL
    }

    private static final Logger LOG = Logger.getLogger(AttachmentService.class.getName());
    public static final String SIGNATURE_MECHANISM = "SYMMETRIC_HMAC_SHA256_INTERNAL_INTEGRITY_NOT_PKI";
    private static final String KEYSTORE_SYS_PROP = "ledger.hmac.keystore.path";
    private static final String FORCE_FALLBACK_PROP = "ledger.hmac.forceFallback";
    private static final String ENC_PREFIX = "ENCv1:";
    private static final Object KEYSTORE_LOCK = new Object();
    private static final Map<Path, byte[]> KEK_CACHE = new ConcurrentHashMap<>();
    private static volatile KeystoreProtectionMode lastProtectionMode;
    private static volatile boolean forcePermissionFailureForTest = false;

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
            String voidedAt,
            String keyVersion
    ) {
        public Attachment(long id, long voucherId, String fileName, String storedPath,
                          String contentType, long fileSize, String uploadedBy, String uploadedAt, String remark) {
            this(id, voucherId, fileName, storedPath, contentType, fileSize, uploadedBy, uploadedAt, remark,
                    null, null, null, null, "ACTIVE", null, null, null, "v1");
        }

        public Attachment(long id, long voucherId, String fileName, String storedPath,
                          String contentType, long fileSize, String uploadedBy, String uploadedAt, String remark,
                          String docNo, String docDate, String sha256Hash, String digitalSignature,
                          String status, String voidReason, String voidedBy, String voidedAt) {
            this(id, voucherId, fileName, storedPath, contentType, fileSize, uploadedBy, uploadedAt, remark,
                    docNo, docDate, sha256Hash, digitalSignature, status, voidReason, voidedBy, voidedAt, "v1");
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
                "key_version TEXT NOT NULL DEFAULT 'v1', " +
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
        ensureAttachmentColumn(conn, "key_version", "TEXT NOT NULL DEFAULT 'v1'");
        ensureAttachmentColumn(conn, "status", "TEXT NOT NULL DEFAULT 'ACTIVE'");
        ensureAttachmentColumn(conn, "void_reason", "TEXT");
        ensureAttachmentColumn(conn, "voided_by", "TEXT");
        ensureAttachmentColumn(conn, "voided_at", "TEXT");
        purgeLegacyDbHmacKey(conn);
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
        if (sourceFile == null || Files.isSymbolicLink(sourceFile)
                || !Files.exists(sourceFile, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(sourceFile, LinkOption.NOFOLLOW_LINKS)) {
            throw new BusinessException("附件檔案不存在或為不安全之符號連結");
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

            String dbNamePart = sanitizePathSegment(DbManager.getCurrentTenant());
            if (dbNamePart == null || dbNamePart.isBlank()) {
                dbNamePart = sanitizePathSegment(
                        Paths.get(DbManager.getCurrentDbPath()).getFileName().toString().replace(".db", ""));
            }
            Path attachmentsRoot = getAttachmentsRoot();
            Path dir = attachmentsRoot.resolve(dbNamePart).resolve(String.valueOf(voucherId)).normalize();
            TenantPathResolver.assertNoSymlinkOrJunction(dir, attachmentsRoot);
            Files.createDirectories(dir);
            TenantPathResolver.assertNoSymlinkOrJunction(dir, attachmentsRoot);

            String originalName = sanitizeFilename(sourceFile.getFileName().toString());
            String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
            String storedName = stamp + "_" + originalName;
            Path target = dir.resolve(storedName).normalize();
            TenantPathResolver.assertNoSymlinkOrJunction(target, attachmentsRoot);
            Files.copy(sourceFile, target, StandardCopyOption.REPLACE_EXISTING);

            byte[] content = Files.readAllBytes(target);
            long size = content.length;
            String contentType = guessContentType(originalName);
            String sha256 = sha256Hex(content);
            VersionedKey activeKey = getActiveSigningKey();
            String keyVersion = activeKey.version();
            String signature = signPayload(activeKey.hexKey(), keyVersion, voucherId, normDocNo, normDocDate, sha256, normOp);
            String uploadedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String relativeStored = Paths.get("attachments", dbNamePart, String.valueOf(voucherId), storedName)
                    .toString().replace('\\', '/');

            String sql = "INSERT INTO voucher_attachments " +
                    "(voucher_id, file_name, stored_path, content_type, file_size, uploaded_by, uploaded_at, remark, " +
                    "doc_no, doc_date, sha256_hash, digital_signature, key_version, status) " +
                    "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,'ACTIVE')";
            long id;
            try (PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setLong(1, voucherId);
                ps.setString(2, originalName);
                ps.setString(3, relativeStored);
                ps.setString(4, contentType);
                ps.setLong(5, size);
                ps.setString(6, normOp);
                ps.setString(7, uploadedAt);
                ps.setString(8, remark);
                ps.setString(9, normDocNo);
                ps.setString(10, normDocDate);
                ps.setString(11, sha256);
                ps.setString(12, signature);
                ps.setString(13, keyVersion);
                ps.executeUpdate();
                try (ResultSet keys = ps.getGeneratedKeys()) {
                    id = keys.next() ? keys.getLong(1) : 0;
                }
            }
            auditDao.log(conn, "ATTACH_ADD", "voucher_attachments", id, normOp,
                    null, "voucherId=" + voucherId + "; docNo=" + normDocNo + "; docDate=" + normDocDate
                            + "; keyVersion=" + keyVersion + "; file=" + originalName + "; sha256=" + sha256);
            return new Attachment(id, voucherId, originalName, relativeStored,
                    contentType, size, normOp, uploadedAt, remark,
                    normDocNo, normDocDate, sha256, signature, "ACTIVE", null, null, null, keyVersion);
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            throw new BusinessException("上傳原始憑證失敗: " + e.getMessage(), e);
        }
    }

    public boolean verifyAttachmentSignature(long attachmentId) {
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            String sql = "SELECT voucher_id, stored_path, uploaded_by, doc_no, doc_date, sha256_hash, digital_signature, " +
                    "COALESCE(key_version, 'v1') AS key_version " +
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
                    String keyVersion = rs.getString("key_version");
                    if (storedPath == null || expectedHash == null || expectedSig == null) return false;

                    Path attachmentsRoot = getAttachmentsRoot();
                    Path p = Paths.get(storedPath).toAbsolutePath().normalize();
                    TenantPathResolver.assertNoSymlinkOrJunction(p, attachmentsRoot);
                    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)
                            || !Files.isRegularFile(p, LinkOption.NOFOLLOW_LINKS)) {
                        return false;
                    }

                    String actualHash = sha256Hex(Files.readAllBytes(p));
                    if (!expectedHash.equalsIgnoreCase(actualHash)) return false;

                    String hexKey = getHmacKeyForVersion(keyVersion);
                    if (hexKey == null) return false;

                    String actualSig = signPayload(hexKey, keyVersion, voucherId, docNo, docDate, actualHash, uploadedBy);
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
                    "status, void_reason, voided_by, voided_at, COALESCE(key_version, 'v1') AS key_version " +
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
                                rs.getString("voided_at"),
                                rs.getString("key_version")
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
                try {
                    Path attachmentsRoot = getAttachmentsRoot();
                    Path resolved = Paths.get(path).toAbsolutePath().normalize();
                    TenantPathResolver.assertNoSymlinkOrJunction(resolved, attachmentsRoot);
                    Files.deleteIfExists(resolved);
                } catch (Exception ignored) {}
            }
        } catch (BusinessException e) {
            throw e;
        } catch (SQLException e) {
            throw new BusinessException("刪除附件失敗: " + e.getMessage(), e);
        }
    }

    /**
     * 輪替外部 HMAC 金鑰（產生新版本 v2/v3...），保留舊版金鑰供既有憑證驗章，新上傳憑證將使用新版本金鑰並保留輪替紀錄。
     */
    public String rotateHmacKey(String operator) {
        String normOp = SessionContext.resolveOperator(operator);
        OperatorRole role = OperatorRole.resolve(normOp);
        if (role != OperatorRole.CHECKER && role != OperatorRole.ADMIN) {
            throw new BusinessException("權限不足：僅會計師（CHECKER）或系統管理員可輪替憑證 HMAC 金鑰");
        }
        String newVersion;
        synchronized (KEYSTORE_LOCK) {
            Path ksPath = getKeystorePath();
            Properties props = loadOrInitKeystore(ksPath);
            String currentVer = props.getProperty("active_version", "v1");
            int nextNum = parseVersionNumber(currentVer) + 1;
            newVersion = "v" + nextNum;
            props.setProperty("key." + newVersion, encryptHmacKeyHex(ksPath, generateRandomHexKey()));
            props.setProperty("active_version", newVersion);
            props.setProperty("history." + newVersion,
                    LocalDateTime.now() + "|operator=" + normOp + "|from=" + currentVer + "|mode=" + lastProtectionMode);
            saveKeystore(ksPath, props);
        }
        try (Connection conn = DbManager.get()) {
            ensureTable(conn);
            auditDao.log(conn, "HMAC_KEY_ROTATE", "attachment_hmac_keystore", null, normOp,
                    null, "newVersion=" + newVersion + "; mechanism=" + SIGNATURE_MECHANISM);
        } catch (SQLException ignored) {
            // Best-effort audit when called outside active tenant
        }
        return newVersion;
    }

    public List<String> getKeyRotationHistory() {
        synchronized (KEYSTORE_LOCK) {
            Path ksPath = getKeystorePath();
            if (!Files.exists(ksPath, LinkOption.NOFOLLOW_LINKS)) {
                return List.of();
            }
            Properties props = loadKeystoreOnly(ksPath);
            List<String> history = new ArrayList<>();
            List<String> keys = new ArrayList<>();
            for (String name : props.stringPropertyNames()) {
                if (name.startsWith("history.")) keys.add(name);
            }
            Collections.sort(keys);
            for (String k : keys) {
                history.add(k.substring("history.".length()) + ": " + props.getProperty(k));
            }
            return history;
        }
    }

    public static KeystoreProtectionMode getLastProtectionMode() {
        return lastProtectionMode;
    }

    public static void setForcePermissionFailureForTest(boolean force) {
        forcePermissionFailureForTest = force;
    }

    public static void clearInMemoryKeyCacheForTest() {
        KEK_CACHE.clear();
    }

    public static boolean verifyKeystoreOsPermissions(Path path) {
        if (path == null || !Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return false;
        try {
            var posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                Set<PosixFilePermission> perms = posix.readAttributes().permissions();
                for (PosixFilePermission p : perms) {
                    if (p.name().startsWith("GROUP_") || p.name().startsWith("OTHERS_")) {
                        return false;
                    }
                }
                return perms.contains(PosixFilePermission.OWNER_READ);
            }
            AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (aclView != null) {
                UserPrincipal owner = aclView.getOwner();
                List<AclEntry> acl = aclView.getAcl();
                if (acl == null || acl.isEmpty()) return false;
                boolean ownerFound = false;
                for (AclEntry entry : acl) {
                    if (entry.type() == AclEntryType.ALLOW) {
                        if (entry.principal().equals(owner)) {
                            ownerFound = true;
                        } else {
                            return false;
                        }
                    }
                }
                return ownerFound;
            }
            return false;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 取得目前啟用中的金鑰版本。
     */
    public static String getCurrentKeyVersion() {
        synchronized (KEYSTORE_LOCK) {
            Properties props = loadOrInitKeystore(getKeystorePath());
            return props.getProperty("active_version", "v1");
        }
    }

    /**
     * 取得目前外部金鑰儲存檔路徑（可供稽核與測試確認未與 SQLite 資料檔存放於同處）。
     */
    public static Path getExternalKeystorePath() {
        return getKeystorePath();
    }

    private record VersionedKey(String version, String hexKey) {}

    private static VersionedKey getActiveSigningKey() {
        synchronized (KEYSTORE_LOCK) {
            Path ksPath = getKeystorePath();
            Properties props = loadOrInitKeystore(ksPath);
            String ver = props.getProperty("active_version", "v1");
            String storedVal = props.getProperty("key." + ver);
            if (storedVal == null || storedVal.isBlank()) {
                String hex = generateRandomHexKey();
                props.setProperty("key." + ver, encryptHmacKeyHex(ksPath, hex));
                saveKeystore(ksPath, props);
                return new VersionedKey(ver, hex);
            }
            return new VersionedKey(ver, decryptHmacKeyHex(ksPath, storedVal));
        }
    }

    private static String getHmacKeyForVersion(String version) {
        String ver = (version == null || version.isBlank()) ? "v1" : version.trim();
        synchronized (KEYSTORE_LOCK) {
            Path ksPath = getKeystorePath();
            if (!Files.exists(ksPath, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Properties props = loadKeystoreOnly(ksPath);
            String val = props.getProperty("key." + ver);
            if (val == null || val.isBlank()) return null;
            return decryptHmacKeyHex(ksPath, val.trim());
        }
    }

    private static Path getKeystorePath() {
        String configured = System.getProperty(KEYSTORE_SYS_PROP);
        Path candidate;
        if (configured != null && !configured.isBlank()) {
            candidate = Paths.get(configured.trim()).toAbsolutePath().normalize();
        } else {
            String home = System.getProperty("user.home", ".");
            candidate = Paths.get(home, ".ledger_security", "attachment_hmac_keys.properties")
                    .toAbsolutePath().normalize();
        }
        Path dataDir = Paths.get("data").toAbsolutePath().normalize();
        Path attachDir = getAttachmentsRoot();
        if (candidate.startsWith(dataDir) || candidate.startsWith(attachDir)) {
            throw new SecurityException("HMAC 金鑰儲存位置禁止與資料庫（data/）或附件目錄（attachments/）共置: " + candidate);
        }
        verifyKeystorePathSafety(candidate);
        return candidate;
    }

    public static void verifyKeystorePathSafety(Path ksPath) {
        try {
            Path abs = ksPath.toAbsolutePath().normalize();
            Path root = abs.getRoot();
            if (root != null) {
                Path curr = root;
                for (Path segment : root.relativize(abs)) {
                    curr = curr.resolve(segment);
                    verifyKeystoreSingleSegment(curr);
                }
            }
            Path parent = abs.getParent();
            if (parent != null) {
                verifyKeystoreSingleSegment(parent.resolve(".kek_store.bin"));
                verifyKeystoreSingleSegment(parent.resolve(".kek_salt.bin"));
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IOException ioe) {
            throw new SecurityException("HMAC 金鑰路徑安全驗證失敗: " + ioe.getMessage(), ioe);
        }
    }

    private static void verifyKeystoreSingleSegment(Path p) throws IOException {
        if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isSymbolicLink(p)) {
            throw new SecurityException("安全異常：HMAC 金鑰路徑或父目錄不得為符號連結: " + p);
        }
        BasicFileAttributes attrs = Files.readAttributes(p, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (attrs.isSymbolicLink() || attrs.isOther()) {
            throw new SecurityException("安全異常：HMAC 金鑰路徑偵測到符號連結或 Windows Junction/Reparse Point: " + p);
        }
        Path rFollow = p.toRealPath();
        Path rNoFollow = p.toRealPath(LinkOption.NOFOLLOW_LINKS);
        if (!rFollow.equals(rNoFollow)) {
            throw new SecurityException("安全異常：HMAC 金鑰實體路徑不一致: " + p);
        }
    }

    private static Properties loadOrInitKeystore(Path ksPath) {
        verifyKeystorePathSafety(ksPath);
        Properties props = Files.exists(ksPath, LinkOption.NOFOLLOW_LINKS)
                ? loadKeystoreOnly(ksPath)
                : new Properties();
        boolean dirty = false;
        if (!props.containsKey("active_version") || !props.containsKey("key." + props.getProperty("active_version"))) {
            String ver = props.getProperty("active_version", "v1");
            props.setProperty("active_version", ver);
            props.setProperty("key." + ver, encryptHmacKeyHex(ksPath, generateRandomHexKey()));
            props.setProperty("history." + ver, LocalDateTime.now() + "|init|mode=" + lastProtectionMode);
            dirty = true;
        }
        // Upgrade any legacy plaintext hex entries from R12 to ENCv1 ciphertext
        for (String name : props.stringPropertyNames()) {
            if (name.startsWith("key.")) {
                String v = props.getProperty(name);
                if (v != null && !v.startsWith(ENC_PREFIX)) {
                    props.setProperty(name, encryptHmacKeyHex(ksPath, v.trim()));
                    dirty = true;
                }
            }
        }
        if (dirty) {
            saveKeystore(ksPath, props);
        }
        return props;
    }

    private static Properties loadKeystoreOnly(Path ksPath) {
        verifyKeystorePathSafety(ksPath);
        Properties props = new Properties();
        try {
            Path expectedReal = ksPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            try (InputStream in = Files.newInputStream(ksPath, LinkOption.NOFOLLOW_LINKS)) {
                props.load(in);
            }
            Path afterReal = ksPath.toRealPath(LinkOption.NOFOLLOW_LINKS);
            if (!expectedReal.equals(afterReal)) {
                throw new SecurityException("偵測到 HMAC 金鑰檔於讀取期間遭到替換（TOCTOU）: " + ksPath);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IOException e) {
            throw new BusinessException("讀取外部 HMAC 金鑰儲存失敗: " + e.getMessage(), e);
        }
        return props;
    }

    private static void saveKeystore(Path ksPath, Properties props) {
        verifyKeystorePathSafety(ksPath);
        try {
            Path parent = ksPath.getParent();
            if (parent != null && !Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(parent);
                hardenFilePermissions(parent, true);
            }
            verifyKeystorePathSafety(ksPath);
            Path tmp = ksPath.resolveSibling(ksPath.getFileName().toString() + ".tmp");
            if (Files.isSymbolicLink(tmp)) {
                throw new SecurityException("安全異常：HMAC 暫存檔不得為符號連結");
            }
            try {
                try (OutputStream out = Files.newOutputStream(tmp)) {
                    props.store(out, "JAVA-FINAL External Encrypted Attachment HMAC Keystore (DO NOT STORE IN SQLITE)");
                }
                hardenFilePermissions(tmp, false);
                verifyKeystorePathSafety(ksPath);
                Files.move(tmp, ksPath, StandardCopyOption.REPLACE_EXISTING);
                hardenFilePermissions(ksPath, false);
                verifyKeystorePathSafety(ksPath);
            } finally {
                Files.deleteIfExists(tmp);
            }
        } catch (SecurityException se) {
            throw se;
        } catch (IOException e) {
            throw new BusinessException("寫入外部 HMAC 金鑰儲存失敗: " + e.getMessage(), e);
        }
    }

    private static byte[] getOrCreateKek(Path ksPath) {
        try {
            Path parent = ksPath.getParent();
            if (parent == null) {
                throw new SecurityException("無效的 HMAC 金鑰父目錄");
            }
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(parent);
                hardenFilePermissions(parent, true);
            }
            verifyKeystorePathSafety(ksPath);
            Path realParent = parent.toRealPath(LinkOption.NOFOLLOW_LINKS);
            byte[] cached = KEK_CACHE.get(realParent);
            if (cached != null) {
                return cached;
            }
            Path kekFile = realParent.resolve(".kek_store.bin");
            verifyKeystoreSingleSegment(kekFile);

            boolean forceFallback = Boolean.getBoolean(FORCE_FALLBACK_PROP);
            boolean isWindows = System.getProperty("os.name", "").toLowerCase().contains("win");

            if (Files.exists(kekFile, LinkOption.NOFOLLOW_LINKS)) {
                String content = Files.readString(kekFile, StandardCharsets.UTF_8).trim();
                byte[] kek;
                if (content.startsWith("DPAPI:")) {
                    kek = unprotectWithWindowsDpapi(content.substring("DPAPI:".length()));
                    lastProtectionMode = KeystoreProtectionMode.WINDOWS_DPAPI_AES256_GCM;
                } else if (content.startsWith("PBKDF2")) {
                    kek = deriveFallbackKek(realParent, false);
                    lastProtectionMode = KeystoreProtectionMode.FALLBACK_PBKDF2_AES256_GCM_OS_ACL;
                } else {
                    throw new SecurityException("無法識別的 HMAC 主金鑰儲存格式");
                }
                KEK_CACHE.put(realParent, kek);
                return kek;
            }

            if (isWindows && !forceFallback) {
                try {
                    byte[] rawKek = new byte[32];
                    new SecureRandom().nextBytes(rawKek);
                    String dpapiBlob = protectWithWindowsDpapi(rawKek);
                    Files.writeString(kekFile, "DPAPI:" + dpapiBlob, StandardCharsets.UTF_8);
                    hardenFilePermissions(kekFile, false);
                    lastProtectionMode = KeystoreProtectionMode.WINDOWS_DPAPI_AES256_GCM;
                    KEK_CACHE.put(realParent, rawKek);
                    return rawKek;
                } catch (Exception dpapiErr) {
                    LOG.warning("Windows DPAPI 保護暫時無法使用，改採安全降級模式 PBKDF2+OS-ACL: " + dpapiErr.getMessage());
                }
            }

            byte[] fallbackKek = deriveFallbackKek(realParent, true);
            Files.writeString(kekFile, "PBKDF2", StandardCharsets.UTF_8);
            hardenFilePermissions(kekFile, false);
            lastProtectionMode = KeystoreProtectionMode.FALLBACK_PBKDF2_AES256_GCM_OS_ACL;
            KEK_CACHE.put(realParent, fallbackKek);
            return fallbackKek;
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("HMAC 主金鑰準備失敗: " + e.getMessage(), e);
        }
    }

    private static byte[] deriveFallbackKek(Path realParent, boolean createSaltIfMissing) throws Exception {
        Path saltFile = realParent.resolve(".kek_salt.bin");
        verifyKeystoreSingleSegment(saltFile);
        byte[] salt;
        if (Files.exists(saltFile, LinkOption.NOFOLLOW_LINKS)) {
            salt = Files.readAllBytes(saltFile);
        } else if (createSaltIfMissing) {
            salt = new byte[32];
            new SecureRandom().nextBytes(salt);
            Files.write(saltFile, salt);
            hardenFilePermissions(saltFile, false);
        } else {
            throw new SecurityException("缺少降級模式金鑰鹽值檔: " + saltFile);
        }
        String material = System.getProperty("user.name", "ledger") + "|" + realParent.toString();
        PBEKeySpec spec = new PBEKeySpec(material.toCharArray(), salt, 120_000, 256);
        SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
        return skf.generateSecret(spec).getEncoded();
    }

    private static String protectWithWindowsDpapi(byte[] rawKek) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Security; " +
                "$b=[Convert]::FromBase64String($env:LEDGER_KEK_PLAIN); " +
                "$p=[System.Security.Cryptography.ProtectedData]::Protect($b,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser); " +
                "[Convert]::ToBase64String($p)");
        pb.environment().put("LEDGER_KEK_PLAIN", Base64.getEncoder().encodeToString(rawKek));
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        if (code != 0 || out.isEmpty()) {
            throw new IOException("PowerShell DPAPI Protect failed (exit=" + code + "): " + out);
        }
        String[] lines = out.split("\\R");
        return lines[lines.length - 1].trim();
    }

    private static byte[] unprotectWithWindowsDpapi(String dpapiBase64) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                "powershell", "-NoProfile", "-NonInteractive", "-Command",
                "Add-Type -AssemblyName System.Security; " +
                "$p=[Convert]::FromBase64String($env:LEDGER_KEK_BLOB); " +
                "$b=[System.Security.Cryptography.ProtectedData]::Unprotect($p,$null,[System.Security.Cryptography.DataProtectionScope]::CurrentUser); " +
                "[Convert]::ToBase64String($b)");
        pb.environment().put("LEDGER_KEK_BLOB", dpapiBase64.trim());
        pb.redirectErrorStream(true);
        Process p = pb.start();
        String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        int code = p.waitFor();
        if (code != 0 || out.isEmpty()) {
            throw new IOException("PowerShell DPAPI Unprotect failed (exit=" + code + "): " + out);
        }
        String[] lines = out.split("\\R");
        return Base64.getDecoder().decode(lines[lines.length - 1].trim());
    }

    private static String encryptHmacKeyHex(Path ksPath, String hexKey) {
        try {
            byte[] kek = getOrCreateKek(ksPath);
            byte[] iv = new byte[12];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(HexFormat.of().parseHex(hexKey));
            return ENC_PREFIX + HexFormat.of().formatHex(iv) + ":" + HexFormat.of().formatHex(ct);
        } catch (Exception e) {
            throw new BusinessException("加密 HMAC 金鑰失敗: " + e.getMessage(), e);
        }
    }

    private static String decryptHmacKeyHex(Path ksPath, String storedValue) {
        if (!storedValue.startsWith(ENC_PREFIX)) {
            return storedValue; // Backward-compatible read before re-encryption upgrade
        }
        try {
            String rest = storedValue.substring(ENC_PREFIX.length());
            int sep = rest.indexOf(':');
            if (sep <= 0) {
                throw new SecurityException("已毀損的加密金鑰格式");
            }
            byte[] iv = HexFormat.of().parseHex(rest.substring(0, sep));
            byte[] ct = HexFormat.of().parseHex(rest.substring(sep + 1));
            byte[] kek = getOrCreateKek(ksPath);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(kek, "AES"), new GCMParameterSpec(128, iv));
            byte[] plain = cipher.doFinal(ct);
            return HexFormat.of().formatHex(plain);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("解密外部 HMAC 金鑰失敗: " + e.getMessage(), e);
        }
    }

    private static void hardenFilePermissions(Path path, boolean isDir) {
        if (forcePermissionFailureForTest) {
            throw new SecurityException("模擬作業系統檔案權限設定失敗，拒絕略過安全保護: " + path);
        }
        try {
            var posix = Files.getFileAttributeView(path, java.nio.file.attribute.PosixFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (posix != null) {
                Set<PosixFilePermission> perms = isDir
                        ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE, PosixFilePermission.OWNER_EXECUTE)
                        : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
                Files.setPosixFilePermissions(path, perms);
                if (!verifyKeystoreOsPermissions(path)) {
                    throw new SecurityException("POSIX 權限驗證未通過: " + path);
                }
                return;
            }
            AclFileAttributeView aclView = Files.getFileAttributeView(path, AclFileAttributeView.class,
                    LinkOption.NOFOLLOW_LINKS);
            if (aclView != null) {
                UserPrincipal owner = aclView.getOwner();
                AclEntry entry = AclEntry.newBuilder()
                        .setType(AclEntryType.ALLOW)
                        .setPrincipal(owner)
                        .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                        .build();
                aclView.setAcl(List.of(entry));
                if (!verifyKeystoreOsPermissions(path)) {
                    throw new SecurityException("Windows ACL 權限驗證未通過: " + path);
                }
                return;
            }
            throw new SecurityException("系統不支援 POSIX 或 Windows ACL 權限控管，無法確保金鑰安全: " + path);
        } catch (SecurityException se) {
            throw se;
        } catch (Exception e) {
            throw new SecurityException("設定 HMAC 金鑰安全權限失敗，禁止靜默略過: " + e.getMessage(), e);
        }
    }

    private static void purgeLegacyDbHmacKey(Connection conn) {
        try {
            DbManager.ensureSystemTables(conn);
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM system_meta WHERE meta_key = 'attachment_hmac_key'")) {
                ps.executeUpdate();
            }
        } catch (SQLException ignored) {
        }
    }

    private static Path getAttachmentsRoot() {
        Path root = Paths.get("attachments").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(root)) {
            throw new SecurityException("安全異常：attachments 根目錄不得為符號連結");
        }
        return root;
    }

    private static String sanitizePathSegment(String value) {
        if (value == null || value.isBlank()) return "";
        String trimmed = value.trim();
        if (trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\") || trimmed.indexOf('\0') >= 0) {
            throw new SecurityException("非法附件目錄名稱: " + value);
        }
        return trimmed;
    }

    private static String sanitizeFilename(String name) {
        if (name == null || name.isBlank()) {
            throw new BusinessException("附件檔名不可為空");
        }
        String trimmed = name.trim();
        if (trimmed.contains("..") || trimmed.contains("/") || trimmed.contains("\\") || trimmed.indexOf('\0') >= 0) {
            throw new SecurityException("非法附件檔名（疑似路徑穿越）: " + name);
        }
        return trimmed;
    }

    private static int parseVersionNumber(String version) {
        if (version != null && version.startsWith("v")) {
            try {
                return Integer.parseInt(version.substring(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return 1;
    }

    private static String generateRandomHexKey() {
        byte[] keyBytes = new byte[32];
        new SecureRandom().nextBytes(keyBytes);
        return HexFormat.of().formatHex(keyBytes);
    }

    private static String sha256Hex(byte[] data) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(data));
    }

    private static String signPayload(String hexKey, String keyVersion, long voucherId, String docNo, String docDate,
                                      String sha256, String operator) throws Exception {
        String payload = (keyVersion != null ? keyVersion : "v1") + "|"
                + voucherId + "|" + (docNo != null ? docNo : "") + "|"
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
