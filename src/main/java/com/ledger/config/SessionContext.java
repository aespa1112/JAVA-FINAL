package com.ledger.config;

import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;

/**
 * 執行階段工作階段：登入使用者與目前帳套。
 * Service 層應透過 requireUser/requireRole 取得已驗證身分，不可信任 UI 任意字串。
 */
public final class SessionContext {

    private static String currentUser;
    private static String currentUserDisplay;
    private static String currentClientPath;
    private static volatile boolean enabled = true;

    private SessionContext() {
    }

    public static void setUser(String canonicalName, String displayName) {
        currentUser = canonicalName;
        currentUserDisplay = displayName;
        enabled = true;
    }

    public static String getUser() {
        return currentUser;
    }

    public static String getUserDisplay() {
        return currentUserDisplay != null ? currentUserDisplay : currentUser;
    }

    public static void setClientPath(String path) {
        currentClientPath = path;
    }

    public static String getClientPath() {
        return currentClientPath;
    }

    public static void clear() {
        currentUser = null;
        currentUserDisplay = null;
        currentClientPath = null;
        enabled = true;
    }

    public static boolean isLoggedIn() {
        return enabled && currentUser != null && !currentUser.isBlank();
    }

    /** 停用帳號時呼叫，使現有 Session 立即失效 */
    public static void invalidate() {
        enabled = false;
        currentUser = null;
        currentUserDisplay = null;
    }

    public static String requireUser() {
        if (!isLoggedIn()) {
            throw new BusinessException("尚未登入或 Session 已失效，拒絕操作");
        }
        return currentUser;
    }

    public static OperatorRole requireRole(OperatorRole... allowed) {
        String user = requireUser();
        OperatorRole role = OperatorRole.resolve(user);
        if (allowed != null && allowed.length > 0) {
            for (OperatorRole a : allowed) {
                if (role == a) return role;
            }
            throw new BusinessException("權限不足：目前角色 " + role + " 不允許此操作");
        }
        return role;
    }

    /**
     * 若呼叫端傳入 operator 與 Session 不一致，以 Session 為準並拒絕偽造。
     * 測試環境無 Session 時允許使用傳入的 operator。
     */
    public static String resolveOperator(String requested) {
        if (isLoggedIn()) {
            String session = currentUser;
            if (requested != null && !requested.isBlank()) {
                String req = requested.trim();
                if (!session.equalsIgnoreCase(req) && !req.toLowerCase().startsWith(session.toLowerCase())) {
                    throw new BusinessException(
                            "身分不符：已登入為 " + session + "，不可使用 " + requested + " 操作");
                }
            }
            return session;
        }
        if (requested == null || requested.isBlank()) {
            throw new BusinessException("未登入且未指定操作人");
        }
        return OperatorRole.normalize(requested);
    }
}
