package com.ledger.config;

import com.ledger.exception.BusinessException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 租戶資料庫安全路徑解析器 (R9)：
 * 1. 使用穩定且唯一的 Tenant UUID (UUID v3)，杜絕因字元替換導致的路徑碰撞（例如 tenant/a 與 tenant?a）。
 * 2. 嚴格防止路徑穿越 (Path Traversal)，限制檔案嚴格位於指定資料庫目錄。
 * 3. 完整支援 Unicode 租戶名稱。
 */
public final class TenantPathResolver {

    public static final String TENANT_DIR = "data/tenants";

    private TenantPathResolver() {}

    /**
     * 依據租戶名稱或識別碼計算穩定且唯一的 Tenant UUID。
     * 使用 UUID v3 (name-based UUID with namespace) 確保同一識別碼始終對應相同 UUID，
     * 同時不同識別碼（包含特殊符號、斜線、問號、Unicode 中文字等）絕不產生碰撞。
     */
    public static UUID resolveTenantUuid(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new BusinessException("租戶識別碼不可為空");
        }
        String tid = tenantId.trim();

        if (tid.length() > 255) {
            throw new BusinessException("租戶識別碼過長（最大允許 255 字元）");
        }

        // 嚴格阻擋路徑穿越攻擊
        if (tid.contains("../") || tid.contains("..\\") || tid.contains("/..") || tid.contains("\\..") || tid.equals("..")) {
            throw new BusinessException("安全威脅：偵測到路徑穿越攻擊 (Path Traversal)");
        }

        // 使用標準 UUID v3（依據位元組命名空間計算，絕對唯一不碰撞）
        return UUID.nameUUIDFromBytes(("ledger:tenant:" + tid).getBytes(StandardCharsets.UTF_8));
    }

    public static String resolveTenantDbPath(String tenantId) {
        if (tenantId == null || tenantId.trim().isEmpty()) {
            throw new BusinessException("租戶識別碼不可為空");
        }
        String tid = tenantId.trim();

        // 嚴格阻擋路徑穿越特徵
        if (tid.contains("../") || tid.contains("..\\") || tid.contains("/..") || tid.contains("\\..") || tid.equals("..")) {
            throw new BusinessException("安全威脅：偵測到路徑穿越攻擊 (Path Traversal)");
        }

        // 向下相容預設帳套 client_A
        if ("client_A".equalsIgnoreCase(tid) || "client-A".equalsIgnoreCase(tid)) {
            return "data/client_A.db";
        }

        UUID uuid = resolveTenantUuid(tid);
        String filename = "tenant_" + uuid.toString() + ".db";

        Path baseDir = Paths.get(TENANT_DIR).toAbsolutePath().normalize();
        try {
            if (!Files.exists(baseDir)) {
                Files.createDirectories(baseDir);
            }
        } catch (Exception e) {
            throw new BusinessException("無法建立租戶資料庫目錄: " + e.getMessage(), e);
        }

        Path resolved = baseDir.resolve(filename).normalize().toAbsolutePath();
        if (!resolved.startsWith(baseDir)) {
            throw new BusinessException("安全威脅：偵測到路徑穿越攻擊 (Path Traversal)");
        }

        return resolved.toString();
    }
}
