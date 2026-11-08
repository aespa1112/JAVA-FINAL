package com.ledger.service;

import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 簡易帳號密碼驗證服務。
 * 對應系統既有角色白名單（Amy / Bob / David / Charlie）。
 * 生產環境應改為雜湊密碼 + 資料庫或外部 IdP。
 */
public class AuthService {

    public record UserAccount(String username, String password, String displayName, OperatorRole role) {}

    private static final Map<String, UserAccount> USERS = new LinkedHashMap<>();

    static {
        USERS.put("amy", new UserAccount("amy", "amy123", "Amy (記帳員/制單人)", OperatorRole.MAKER));
        USERS.put("bob", new UserAccount("bob", "bob123", "Bob (會計師/審核人)", OperatorRole.CHECKER));
        USERS.put("david", new UserAccount("david", "david123", "David (會計師/審核人)", OperatorRole.CHECKER));
        USERS.put("charlie", new UserAccount("charlie", "charlie123", "Charlie (查帳員/審計員)", OperatorRole.AUDITOR));
    }

    /**
     * 驗證帳號密碼，成功回傳標準化使用者名稱（Amy / Bob / David / Charlie）。
     */
    public String login(String username, String password) {
        if (username == null || username.trim().isEmpty()) {
            throw new BusinessException("請輸入帳號");
        }
        if (password == null || password.isEmpty()) {
            throw new BusinessException("請輸入密碼");
        }

        String key = username.trim().toLowerCase();
        UserAccount account = USERS.get(key);
        if (account == null || !account.password().equals(password)) {
            throw new BusinessException("帳號或密碼錯誤");
        }

        // 回傳系統標準名稱，與 OperatorRole.normalize 一致
        return OperatorRole.normalize(account.username());
    }

    public UserAccount getAccount(String username) {
        if (username == null) return null;
        return USERS.get(username.trim().toLowerCase());
    }

    public String getDisplayName(String canonicalName) {
        UserAccount acc = getAccount(canonicalName);
        return acc != null ? acc.displayName() : canonicalName;
    }
}
