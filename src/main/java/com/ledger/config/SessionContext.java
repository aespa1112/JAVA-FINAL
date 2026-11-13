package com.ledger.config;

import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import com.ledger.service.AuthService;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-thread and lifecycle-managed session context: logged-in user, display name,
 * client path, tenant and revocation state.
 * All identity-bearing fields are ThreadLocal so concurrent requests / worker
 * threads cannot overwrite each other, backed by an active Session registry so
 * disabling an account revokes active sessions across threads.
 * <p>
 * Callers MUST clear or logout the context when a request or background task finishes.
 */
public final class SessionContext {

    public static final class SessionInfo {
        private final String sessionId;
        private final String username;
        private final String displayName;
        private final Instant createdAt;
        private final AtomicBoolean active = new AtomicBoolean(true);

        SessionInfo(String sessionId, String username, String displayName) {
            this.sessionId = sessionId;
            this.username = username;
            this.displayName = displayName;
            this.createdAt = Instant.now();
        }

        public String getSessionId() { return sessionId; }
        public String getUsername() { return username; }
        public String getDisplayName() { return displayName; }
        public Instant getCreatedAt() { return createdAt; }
        public boolean isActive() { return active.get(); }
    }

    private static final ThreadLocal<String> currentUser = new ThreadLocal<>();
    private static final ThreadLocal<String> currentUserDisplay = new ThreadLocal<>();
    private static final ThreadLocal<String> currentClientPath = new ThreadLocal<>();
    private static final ThreadLocal<String> currentTenantId = new ThreadLocal<>();
    private static final ThreadLocal<String> currentSessionId = new ThreadLocal<>();
    private static final ThreadLocal<Boolean> enabled = ThreadLocal.withInitial(() -> Boolean.TRUE);

    private static final Map<String, SessionInfo> activeSessions = new ConcurrentHashMap<>();
    private static final Set<String> revokedUsers = ConcurrentHashMap.newKeySet();

    private static final StackWalker STACK_WALKER =
            StackWalker.getInstance(StackWalker.Option.RETAIN_CLASS_REFERENCE);

    private static volatile boolean testIdentityProviderEnabled = false;
    private static volatile String testIdentityUser;

    private SessionContext() {
    }

    public static boolean isProduction() {
        if (Boolean.getBoolean("ledger.forceProduction")) return true;
        if (Boolean.getBoolean("ledger.test")) return false;
        String env = System.getenv().getOrDefault("LEDGER_ENV", "PRODUCTION");
        return !"DEMO".equalsIgnoreCase(env) && !"TEST".equalsIgnoreCase(env);
    }

    public static boolean isTestMode() {
        if (Boolean.getBoolean("ledger.forceProduction")) return false;
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

    private static String normalizeUsernameKey(String name) {
        if (name == null) return "";
        String s = name.trim();
        int idx = s.indexOf(" (");
        if (idx > 0) s = s.substring(0, idx).trim();
        idx = s.indexOf("(");
        if (idx > 0) s = s.substring(0, idx).trim();
        return s.toLowerCase();
    }

    /**
     * Establish an authenticated session from a credential-verified AuthService token.
     * Direct callers outside AuthService are rejected.
     */
    public static void establishSession(AuthService.SessionToken token) {
        Class<?> caller = STACK_WALKER.getCallerClass();
        if (caller != AuthService.class) {
            throw new SecurityException("Only AuthService may establish authenticated sessions (caller=" + caller.getName() + ")");
        }
        if (token == null || token.username() == null || token.username().isBlank()) {
            throw new BusinessException("無效的驗證憑證，無法建立 Session");
        }
        if (!token.enabled()) {
            throw new BusinessException("已停用帳號不可建立 Session: " + token.username());
        }
        String normLower = normalizeUsernameKey(token.username());
        if (revokedUsers.contains(normLower)) {
            throw new BusinessException("此帳號已停用或憑證已撤銷，禁止建立 Session: " + token.username());
        }
        setUser(token.username(), token.displayName());
    }

    private static void setUser(String canonicalName, String displayName) {
        String previousSid = currentSessionId.get();
        if (previousSid != null) {
            SessionInfo prev = activeSessions.remove(previousSid);
            if (prev != null) prev.active.set(false);
        }
        if (canonicalName == null || canonicalName.isBlank()) {
            currentUser.remove();
            currentUserDisplay.remove();
            currentSessionId.remove();
            enabled.set(Boolean.TRUE);
            return;
        }
        String normLower = normalizeUsernameKey(canonicalName);
        if (revokedUsers.contains(normLower)) {
            throw new BusinessException("此帳號已停用或憑證已撤銷，禁止建立 Session: " + canonicalName);
        }
        String sid = UUID.randomUUID().toString();
        SessionInfo info = new SessionInfo(sid, canonicalName, displayName != null ? displayName : canonicalName);
        activeSessions.put(sid, info);
        currentSessionId.set(sid);
        currentUser.set(canonicalName);
        currentUserDisplay.set(displayName);
        enabled.set(Boolean.TRUE);
    }

    public static String getCurrentSessionId() {
        return currentSessionId.get();
    }

    public static String getUser() {
        if (!isLoggedIn()) return null;
        return currentUser.get();
    }

    public static String getUserDisplay() {
        if (!isLoggedIn()) return null;
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

    public static void logout() {
        clear();
    }

    public static void clear() {
        String sid = currentSessionId.get();
        if (sid != null) {
            SessionInfo info = activeSessions.remove(sid);
            if (info != null) {
                info.active.set(false);
            }
        }
        currentSessionId.remove();
        currentUser.remove();
        currentUserDisplay.remove();
        currentClientPath.remove();
        currentTenantId.remove();
        enabled.remove();
    }

    public static boolean isLoggedIn() {
        Boolean e = enabled.get();
        String u = currentUser.get();
        String sid = currentSessionId.get();
        if (!Boolean.TRUE.equals(e) || u == null || u.isBlank() || sid == null || sid.isBlank()) {
            return false;
        }
        String uLower = normalizeUsernameKey(u);
        if (revokedUsers.contains(uLower)) {
            return false;
        }
        SessionInfo info = activeSessions.get(sid);
        return info != null && info.active.get() && normalizeUsernameKey(info.username).equals(uLower);
    }

    public static void invalidate() {
        String sid = currentSessionId.get();
        if (sid != null) {
            SessionInfo info = activeSessions.remove(sid);
            if (info != null) {
                info.active.set(false);
            }
        }
        enabled.set(Boolean.FALSE);
        currentSessionId.remove();
        currentUser.remove();
        currentUserDisplay.remove();
    }

    /**
     * Immediately revoke all active sessions belonging to the given username
     * across all threads (e.g. when an admin disables the account).
     */
    public static void invalidateUserSessions(String username) {
        if (username == null || username.isBlank()) {
            invalidate();
            return;
        }
        String normLower = normalizeUsernameKey(username);
        revokedUsers.add(normLower);
        activeSessions.entrySet().removeIf(entry -> {
            SessionInfo info = entry.getValue();
            if (info != null && normalizeUsernameKey(info.username).equals(normLower)) {
                info.active.set(false);
                return true;
            }
            return false;
        });
        String cur = currentUser.get();
        if (cur != null && normalizeUsernameKey(cur).equals(normLower)) {
            invalidate();
        }
    }

    public static void clearUserRevocation(String username) {
        Class<?> caller = STACK_WALKER.getCallerClass();
        if (caller != AuthService.class) {
            throw new SecurityException("Only AuthService may clear user revocation");
        }
        if (username != null && !username.isBlank()) {
            revokedUsers.remove(normalizeUsernameKey(username));
        }
    }

    public static boolean isUserRevoked(String username) {
        return username != null && !username.isBlank()
                && revokedUsers.contains(normalizeUsernameKey(username));
    }

    public static String requireUser() {
        if (isLoggedIn()) {
            return currentUser.get();
        }
        if (Boolean.FALSE.equals(enabled.get())) {
            throw new BusinessException("尚未登入或 Session 已失效，拒絕操作");
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
        if (Boolean.FALSE.equals(enabled.get())) {
            throw new BusinessException("尚未登入或 Session 已失效，拒絕操作（Session 已撤銷）");
        }
        if (currentSessionId.get() != null) {
            // Had an active session whose server-side entry was revoked across threads
            throw new BusinessException("尚未登入或 Session 已失效，拒絕操作（帳號已停用或憑證過期）");
        }
        if (requested != null && !requested.isBlank()
                && revokedUsers.contains(normalizeUsernameKey(requested))) {
            throw new BusinessException("帳號已停用或憑證已撤銷，拒絕操作: " + requested);
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
