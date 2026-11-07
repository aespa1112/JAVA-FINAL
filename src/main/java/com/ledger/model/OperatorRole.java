package com.ledger.model;

import com.ledger.exception.BusinessException;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * 系統使用者角色權限定義與安全映射：
 * MAKER: 記帳員 / 制單人（Amy）
 * CHECKER: 會計師 / 審核人（Bob, David - 雙人審核與紅字沖銷覆核）
 * AUDITOR: 查帳員 / 審計員（Charlie，唯讀）
 *
 * 安全設計說明：
 * 1. 嚴格白名單精確比對，杜絕模糊字串比對或泛用名稱繞過內控。
 * 2. 嚴格移除測試用與泛用名稱（如 checker, maker, auditor, bobchecker2），杜絕特權字串濫用。
 * 3. 提供 normalize() 方法將各處傳入的身分字串統一標準化為 "Amy", "Bob", "David", "Charlie"，
 *    確保傳票主檔欄位（preparer, reviewer）與審計日誌一致性。
 * 註：本系統以白名單模擬身分角色，生產環境建議對接 RBAC / 身分驗證系統。
 */
public enum OperatorRole {
    MAKER,
    CHECKER,
    AUDITOR;

    private static final Map<String, OperatorRole> ROLE_MAP;
    private static final Map<String, String> CANONICAL_NAMES;

    static {
        Map<String, OperatorRole> rMap = new HashMap<>();
        Map<String, String> cMap = new HashMap<>();

        // 1. 記帳員 / 制單人 (Amy)
        rMap.put("amy", MAKER);
        rMap.put("amy (記帳員/制單人)", MAKER);
        cMap.put("amy", "Amy");
        cMap.put("amy (記帳員/制單人)", "Amy");

        // 2. 會計師 / 審核人 (Bob, David)
        rMap.put("bob", CHECKER);
        rMap.put("bob (會計師/審核人)", CHECKER);
        cMap.put("bob", "Bob");
        cMap.put("bob (會計師/審核人)", "Bob");

        rMap.put("david", CHECKER);
        rMap.put("david (會計師/審核人)", CHECKER);
        cMap.put("david", "David");
        cMap.put("david (會計師/審核人)", "David");

        // 3. 查帳員 / 審計員 (Charlie)
        rMap.put("charlie", AUDITOR);
        rMap.put("charlie (查帳員/審計員)", AUDITOR);
        cMap.put("charlie", "Charlie");
        cMap.put("charlie (查帳員/審計員)", "Charlie");

        ROLE_MAP = Collections.unmodifiableMap(rMap);
        CANONICAL_NAMES = Collections.unmodifiableMap(cMap);
    }

    /**
     * 規範化使用者名稱為標準系統名稱 ("Amy", "Bob", "David", "Charlie")。
     * 若名稱為空或不在白名單內，嚴格拋出 BusinessException。
     */
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

    /**
     * 依據使用者名稱精確解析其角色。
     * 若名稱為空或不在授權白名單內，嚴格拋出 BusinessException 以確保職責分離不被繞過。
     */
    public static OperatorRole resolve(String operator) {
        String canonical = normalize(operator);
        return ROLE_MAP.get(canonical.toLowerCase());
    }

    /**
     * 查詢指定使用者是否在授權白名單內
     */
    public static boolean isAuthorized(String operator) {
        if (operator == null || operator.trim().isEmpty()) return false;
        try {
            normalize(operator);
            return true;
        } catch (BusinessException e) {
            return false;
        }
    }
}
