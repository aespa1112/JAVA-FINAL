package com.ledger.config;

/**
 * 執行階段工作階段狀態：登入使用者與目前選取的客戶帳套。
 */
public final class SessionContext {

    private static String currentUser;
    private static String currentUserDisplay;
    private static String currentClientPath;

    private SessionContext() {
    }

    public static void setUser(String canonicalName, String displayName) {
        currentUser = canonicalName;
        currentUserDisplay = displayName;
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
    }

    public static boolean isLoggedIn() {
        return currentUser != null && !currentUser.isBlank();
    }
}
