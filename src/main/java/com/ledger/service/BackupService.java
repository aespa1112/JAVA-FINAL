package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.dao.AuditDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class BackupService {

    private final AuditDao auditDao = new AuditDao();

    public boolean checkIntegrity() {
        try (Connection conn = DbManager.get();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check;")) {
            if (rs.next()) {
                return "ok".equalsIgnoreCase(rs.getString(1));
            }
        } catch (SQLException e) {
            return false;
        }
        return false;
    }

    public boolean checkFileIntegrity(Path file) {
        if (!Files.exists(file)) return false;
        String url = "jdbc:sqlite:" + file.toAbsolutePath();
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA integrity_check;")) {
            return rs.next() && "ok".equalsIgnoreCase(rs.getString(1));
        } catch (SQLException e) {
            return false;
        }
    }

    public Path backup(Path dbFile, Path backupDir, String operator) throws IOException {
        if (!Files.exists(dbFile)) {
            throw new IOException("資料庫檔案不存在: " + dbFile);
        }

        String normOp = SessionContext.resolveOperator(operator);
        if (OperatorRole.resolve(normOp) == OperatorRole.AUDITOR) {
            throw new BusinessException("權限不足：查帳員為唯讀身分，無權進行備份");
        }

        if (!Files.exists(backupDir)) {
            Files.createDirectories(backupDir);
        }

        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String baseName = dbFile.getFileName().toString().replace(".db", "");
        String backupName = baseName + "_" + timestamp + ".db";
        Path target = backupDir.resolve(backupName);

        try (Connection conn = DbManager.get();
             Statement st = conn.createStatement()) {
            DbManager.getOrInitClientId(conn, dbFile.toString());
            st.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            try (ResultSet rs = st.executeQuery("PRAGMA integrity_check;")) {
                if (!rs.next() || !"ok".equalsIgnoreCase(rs.getString(1))) {
                    throw new IOException("資料庫完整性校驗失敗，中止備份");
                }
            }
            auditDao.log(conn, "BACKUP", "database", null, normOp, null, "File: " + target.getFileName());
            st.execute("PRAGMA wal_checkpoint(TRUNCATE);");

            // 跳脫單引號防護
            String vacuumPath = target.toAbsolutePath().toString().replace("'", "''");
            Files.deleteIfExists(target);
            st.execute("VACUUM INTO '" + vacuumPath + "';");
        } catch (SQLException e) {
            throw new IOException("資料庫備份失敗: " + e.getMessage(), e);
        }

        return target;
    }

    public void restore(Path backupFile, Path targetDbFile, String operator) throws IOException {
        String normOp = SessionContext.resolveOperator(operator);
        if (OperatorRole.resolve(normOp) != OperatorRole.CHECKER) {
            throw new BusinessException("權限不足：資料庫還原涉及重大資料覆蓋，僅會計師（Bob）有權操作！");
        }

        if (!Files.exists(backupFile)) {
            throw new IOException("備份檔案不存在: " + backupFile);
        }

        // 檢查客戶帳套身分匹配性，嚴防跨客戶帳套混用（優先由資料庫內載 system_meta client_id 比對，杜絕改檔名繞過）
        String backupClientId = readClientIdFromDbFile(backupFile);
        String targetClientId = readClientIdFromDbFile(targetDbFile);

        if (backupClientId != null && targetClientId != null && !backupClientId.equalsIgnoreCase(targetClientId)) {
            throw new BusinessException(String.format("帳套身分不符：無法將客戶 [%s] 的備份還原至目標帳套 [%s]，防止跨客戶資料污染！", backupClientId, targetClientId));
        }

        String targetBase = DbManager.extractClientIdFromPath(targetDbFile.toString());
        String backupBase = DbManager.extractClientIdFromPath(backupFile.toString());
        if (targetBase != null && backupBase != null && targetBase.startsWith("client_") && backupBase.startsWith("client_")) {
            if (!targetBase.equalsIgnoreCase(backupBase)) {
                throw new BusinessException(String.format("帳套身分不符：無法將 [%s] 的備份還原至目標帳套 [%s]，防止跨客戶資料污染！", backupBase, targetBase));
            }
        }

        // 1. 先驗證待還原備份檔案完整性
        if (!checkFileIntegrity(backupFile)) {
            throw new IOException("待還原備份檔案完整性校驗未通過，中止還原！");
        }

        // 2. 還原前先合併目標庫 WAL，並建立安全快照
        Path safetyBackup = null;
        if (Files.exists(targetDbFile)) {
            try (Connection conn = DbManager.get();
                 Statement st = conn.createStatement()) {
                st.execute("PRAGMA wal_checkpoint(TRUNCATE);");
            } catch (SQLException ignored) {
            }

            Path backupDir = backupFile.getParent() != null ? backupFile.getParent() : Paths.get("backup");
            if (!Files.exists(backupDir)) {
                Files.createDirectories(backupDir);
            }
            safetyBackup = backupDir.resolve("safety_pre_restore_" + System.currentTimeMillis() + ".db");
            try {
                Files.copy(targetDbFile, safetyBackup, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                throw new IOException("建立還原前安全快照失敗，中止還原操作以保護原資料！", e);
            }
        }

        // 3. 關閉目前連線
        DbManager.closeCurrent();

        // 4. 清理目標庫關聯的舊 -wal 與 -shm
        Path walFile = Paths.get(targetDbFile.toString() + "-wal");
        Path shmFile = Paths.get(targetDbFile.toString() + "-shm");
        Files.deleteIfExists(walFile);
        Files.deleteIfExists(shmFile);

        try {
            // 5. 覆蓋還原
            Files.copy(backupFile, targetDbFile, StandardCopyOption.REPLACE_EXISTING);

            // 6. 重新開啟連線並驗證
            DbManager.switchDatabase(targetDbFile.toString());
            if (!checkIntegrity()) {
                throw new IOException("還原後資料庫完整性驗證失敗！");
            }

            try (Connection conn = DbManager.get()) {
                auditDao.log(conn, "RESTORE", "database", null, normOp, "Source: " + backupFile.getFileName(), "Success");
            }
        } catch (Exception e) {
            // 失敗時由安全快照救回
            if (safetyBackup != null && Files.exists(safetyBackup)) {
                try {
                    DbManager.closeCurrent();
                    Files.copy(safetyBackup, targetDbFile, StandardCopyOption.REPLACE_EXISTING);
                    DbManager.switchDatabase(targetDbFile.toString());
                } catch (Exception ignored) {
                }
            }
            throw new IOException("還原過程發生錯誤: " + e.getMessage(), e);
        }
    }

    public String readClientIdFromDbFile(Path dbFile) {
        if (dbFile == null || !Files.exists(dbFile)) {
            return null;
        }
        String url = "jdbc:sqlite:" + dbFile.toAbsolutePath().toString();
        try (Connection conn = DriverManager.getConnection(url);
             Statement st = conn.createStatement()) {
            st.execute("PRAGMA busy_timeout = 2000;");
            try (ResultSet rs = st.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='table' AND name='system_meta'")) {
                if (!rs.next() || rs.getInt(1) == 0) {
                    return DbManager.extractClientIdFromPath(dbFile.toString());
                }
            }
            try (ResultSet rs = st.executeQuery("SELECT meta_value FROM system_meta WHERE meta_key = 'client_id'")) {
                if (rs.next()) {
                    String val = rs.getString(1);
                    if (val != null && !val.trim().isEmpty()) {
                        return val.trim();
                    }
                }
            }
        } catch (SQLException ignored) {
        }
        return DbManager.extractClientIdFromPath(dbFile.toString());
    }
}
