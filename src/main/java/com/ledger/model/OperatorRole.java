package com.ledger.model;

import com.ledger.exception.BusinessException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系統使用者角色：
 * ADMIN   — 系統管理員（可新增/停用人員帳號）
 * MAKER   — 記帳員 / 制單人
 * CHECKER — 會計師 / 審核人
 * AUDITOR — 查帳員 / 審計員（唯讀）
 */
public enum OperatorRole {
    ADMIN,
    MAKER,
    CHECKER,
    AUDITOR;

    private static final Map<String, OperatorRole> ROLE_MAP = new ConcurrentHashMap<>();
    private static final Map<String, String> CANONICAL_NAMES = new ConcurrentHashMap<>();

    static {
        registerInternal("admin", "Admin", ADMIN);
        registerInternal("amy", "Amy", MAKER);
        registerInternal("bob", "Bob", CHECKER);
        registerInternal("david", "David", CHECKER);
        registerInternal("charlie", "Charlie", AUDITOR);
    }

    private static void registerInternal(String lowerKey, String canonical, OperatorRole role) {
        ROLE_MAP.put(lowerKey, role);
        CANONICAL_NAMES.put(lowerKey, canonical);
    }

    /**
     * 動態註冊／更新人員到角色白名單（AuthService 載入或新增時呼叫）。
     */
    public static synchronized void register(String username, OperatorRole role) {
        if (username == null || username.trim().isEmpty()) {
            throw new BusinessException("使用者名稱不可為空");
        }
        if (role == null) {
            throw new BusinessException("角色不可為空");
        }
        String key = username.trim().toLowerCase();
        String canonical = username.trim().substring(0, 1).toUpperCase() + username.trim().substring(1).toLowerCase();
        if (key.equals("admin")) {
            canonical = "Admin";
        }
        ROLE_MAP.put(key, role);
        CANONICAL_NAMES.put(key, canonical);
    }

    /** 停用帳號時自白名單移除（內建 admin 不移除 canonical 對照亦可被 disable 擋下） */
    public static synchronized void unregister(String username) {
        if (username == null || username.isBlank()) return;
        String key = username.trim().toLowerCase();
        if ("admin".equals(key)) {
            return; // 保留 admin 對照，實際操作仍看 enabled
        }
        ROLE_MAP.remove(key);
        CANONICAL_NAMES.remove(key);
    }

    /** 重新載入前清空動態部分：保留 static 區塊的內建五人後再由 AuthService 覆寫 */
    public static synchronized void clearDynamic() {
        // 重建為僅內建五人
        ROLE_MAP.clear();
        CANONICAL_NAMES.clear();
        registerInternal("admin", "Admin", ADMIN);
        registerInternal("amy", "Amy", MAKER);
        registerInternal("bob", "Bob", CHECKER);
        registerInternal("david", "David", CHECKER);
        registerInternal("charlie", "Charlie", AUDITOR);
    }

    public static String normalize(String operator) {
        if (operator == null || operator.trim().isEmpty()) {
            throw new BusinessException("使用者身分不可為空，拒絕操作！");
        }
        String key = operator.trim().toLowerCase();
        String canonical = CANONICAL_NAMES.get(key);
        if (canonical == null) {
            int parenIdx = key.indexOf('(');
            if (parenIdx > 0) {
                String pureName = key.substring(0, parenIdx).trim();
                canonical = CANONICAL_NAMES.get(pureName);
            }
        }
        if (canonical == null) {
            throw new BusinessException(String.format("未授權或未知的使用者身分: [%s]，拒絕存取！", operator.trim()));
        }
        return canonical;
    }

    public static OperatorRole resolve(String operator) {
        String canonical = normalize(operator);
        OperatorRole role = ROLE_MAP.get(canonical.toLowerCase());
        if (role == null) {
            throw new BusinessException("無法解析角色: " + operator);
        }
        return role;
    }

    public static boolean isAuthorized(String operator) {
        if (operator == null || operator.trim().isEmpty()) return false;
        try {
            normalize(operator);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }

    public String toChinese() {
        return switch (this) {
            case ADMIN -> "系統管理員";
            case MAKER -> "記帳員/制單人";
            case CHECKER -> "會計師/審核人";
            case AUDITOR -> "查帳員/審計員";
        };
    }
}
