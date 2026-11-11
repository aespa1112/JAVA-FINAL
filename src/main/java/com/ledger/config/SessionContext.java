package com.ledger.config;

import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;

/**
 * 執行階段工作階段：登入使用者與目前帳套。
 * 正式環境（LEDGER_ENV 預設 PRODUCTION）強制 requireUser，
 * 測試環境僅在明確 LEDGER_ENV=TEST/DEMO 或 -Dledger.test=true 時允許免登入。
 */
public final class SessionContext {

    private static String currentUser;
    private static String currentUserDisplay;
    private static String currentClientPath;
    private static volatile boolean enabled = true;

    private static volatile boolean testIdentityProviderEnabled = false;
    private static volatile String testIdentityUser;

    private SessionContext() {
    }

    public static boolean isProduction() {
        if (Boolean.getBoolean("ledger.test")) return false;
        String env = System.getenv().getOrDefault("LEDGER_ENV", "PRODUCTION");
        return !"DEMO".equalsIgnoreCase(env) && !"TEST".equalsIgnoreCase(env);
    }

    public static boolean isTestMode() {
        if (Boolean.getBoolean("ledger.test")) return true;
        if (testIdentityProviderEnabled) return true;
        String env = System.getenv().getOrDefault("LEDGER_ENV", "PRODUCTION");
        return "TEST".equalsIgnoreCase(env)
                || "DEMO".equalsIgnoreCase(env)
                || "1".equals(System.getenv("LEDGER_ALLOW_ANON"));
    }

    public static void enableTestIdentityProvider(String testUser) {
        if (isProduction()) {
            throw new BusinessException("正式環境禁止啟用測試身分提供者");
        }
        testIdentityProviderEnabled = true;
        testIdentityUser = testUser != null ? OperatorRole.normalize(testUser) : "Amy";
    }

    public static void disableTestIdentityProvider() {
        testIdentityProviderEnabled = false;
        testIdentityUser = null;
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

    public static void invalidate() {
        enabled = false;
        currentUser = null;
        currentUserDisplay = null;
    }

    public static String requireUser() {
        if (isLoggedIn()) {
            return currentUser;
        }
        if (isTestMode() && testIdentityProviderEnabled && testIdentityUser != null) {
            return testIdentityUser;
        }
        throw new BusinessException("尚未登入或 Session 已失效，拒絕操作");
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

    public static String resolveOperator(String requested) {
        if (isLoggedIn()) {
            String session = currentUser;
            if (requested != null && !requested.isBlank()) {
                String req = requested.trim();
                String sl = session.toLowerCase();
                String rl = req.toLowerCase();
                boolean ok = rl.equals(sl)
                        || rl.startsWith(sl + " (")
                        || rl.startsWith(sl + "(");
                if (!ok) {
                    throw new BusinessException(
                            "身分不符：已登入為 " + session + "，不可使用 " + requested + " 操作");
                }
            }
            return session;
        }
        if (isTestMode() && testIdentityProviderEnabled) {
            if (requested != null && !requested.isBlank()) {
                return OperatorRole.normalize(requested);
            }
            return testIdentityUser != null ? testIdentityUser : "Amy";
        }
        if (isTestMode() && requested != null && !requested.isBlank()) {
            return OperatorRole.normalize(requested);
        }
        throw new BusinessException("尚未登入或 Session 已失效，拒絕操作（不可信任未經驗證的操作人名稱）");
    }
}
