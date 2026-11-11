package com.ledger.config;

import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;

/**
 * 執行階段工作階段：登入使用者與目前帳套。
 * 正式環境（LEDGER_ENV=PRODUCTION 或未明確 DEMO）強制 requireUser，
 * 測試環境僅在明確設定 LEDGER_ENV=TEST 或 LEDGER_ALLOW_ANON=1 時允許免登入。
 */
public final class SessionContext {

    private static String currentUser;
    private static String currentUserDisplay;
    private static String currentClientPath;
    private static final ThreadLocal<String> threadTenant = new ThreadLocal<>();
    private static volatile boolean enabled = true;

    /** 測試專用：明確啟用才允許無 Session 操作 */
    private static volatile boolean testIdentityProviderEnabled = false;
    private static volatile String testIdentityUser;

    private SessionContext() {
    }

    public static boolean isProduction() {
        if (Boolean.getBoolean("ledger.test")) return false;
        String env = System.getenv().getOrDefault("LEDGER_ENV", "PRODUCTION");
        // DEMO / TEST 必須明確設定；預設視為 PRODUCTION
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

    /**
     * 僅測試程式可呼叫。正式環境（未開 ledger.test / LEDGER_ENV=TEST）呼叫會拋例外。
     */
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

    public static void setTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            threadTenant.remove();
            DbManager.clearCurrentTenant();
            return;
        }
        new com.ledger.service.TenantSecurityService().assertAccess(tenantId);
        threadTenant.set(tenantId);
        DbManager.setCurrentTenant(tenantId);
    }

    public static String getTenant() {
        return threadTenant.get();
    }

    public static void clear() {
        currentUser = null;
        currentUserDisplay = null;
        currentClientPath = null;
        threadTenant.remove();
        DbManager.clearCurrentTenant();
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
        threadTenant.remove();
        DbManager.clearCurrentTenant();
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

    /**
     * 正式環境：一律以 Session 為準，拒絕未登入與身分偽造。
     * 測試模式（明確 TEST / 測試身分提供者）：允許傳入 operator。
     * 不可在 Session 為 null 時信任呼叫端名稱（正式環境）。
     */
    public static String resolveOperator(String requested) {
        if (isLoggedIn()) {
            String session = currentUser;
            if (requested != null && !requested.isBlank()) {
                String req = requested.trim();
                String sl = session.toLowerCase();
                String rl = req.toLowerCase();
                // 僅允許完全相同，或 UI 顯示名「amy (記帳員...)」形式；禁止「amy 審核」等偽冒
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
        // 未登入
        if (isTestMode() && testIdentityProviderEnabled) {
            if (requested != null && !requested.isBlank()) {
                return OperatorRole.normalize(requested);
            }
            return testIdentityUser != null ? testIdentityUser : "Amy";
        }
        if (isTestMode() && requested != null && !requested.isBlank()) {
            // LEDGER_ENV=TEST 且未啟用 provider 時仍允許（相容舊測試）
            return OperatorRole.normalize(requested);
        }
        throw new BusinessException("尚未登入或 Session 已失效，拒絕操作（不可信任未經驗證的操作人名稱）");
    }
}
