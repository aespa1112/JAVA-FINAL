package com.ledger.config;

import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;

/**
 * Per-thread session context: logged-in user, display name, client path and tenant.
 * All identity-bearing fields are ThreadLocal so concurrent requests / worker
 * threads cannot overwrite each other.
 * <p>
 * Callers MUST clear the context (via clear() or try-finally) when a request
 * or background task finishes, especially when threads are reused from a pool.
 */
public final class SessionContext {

    private static final ThreadLocal<String> currentUser = new ThreadLocal<>();
    private static final ThreadLocal<String> currentUserDisplay = new ThreadLocal<>();
    private static final ThreadLocal<String> currentClientPath = new ThreadLocal<>();
    private static final ThreadLocal<String> currentTenantId = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> enabled = ThreadLocal.withInitial(() -> Boolean.TRUE);

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
        currentUser.set(canonicalName);
        currentUserDisplay.set(displayName);
        enabled.set(Boolean.TRUE);
    }

    public static String getUser() {
        return currentUser.get();
    }

    public static String getUserDisplay() {
        String d = currentUserDisplay.get();
        return d != null ? d : currentUser.get();
    }

    public static void setClientPath(String path) {
        currentClientPath.set(path);
    }

    public static String getClientPath() {
        return currentClientPath.get();
    }

    public static void setTenantId(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            currentTenantId.remove();
            return;
        }
        currentTenantId.set(TenantPathResolver.normalizeTenantId(tenantId));
    }

    public static String getTenantId() {
        return currentTenantId.get();
    }

    public static void clear() {
        currentUser.remove();
        currentUserDisplay.remove();
        currentClientPath.remove();
        currentTenantId.remove();
        enabled.remove();
    }

    public static boolean isLoggedIn() {
        Boolean e = enabled.get();
        String u = currentUser.get();
        return Boolean.TRUE.equals(e) && u != null && !u.isBlank();
    }

    public static void invalidate() {
        enabled.set(Boolean.FALSE);
        currentUser.remove();
        currentUserDisplay.remove();
    }

    public static String requireUser() {
        if (isLoggedIn()) {
            return currentUser.get();
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
            String session = currentUser.get();
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
