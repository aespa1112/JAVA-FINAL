package com.ledger;

/**
 * 獨立啟動引導類別。
 * 不繼承 javafx.application.Application，解決 Java 11+ 在 IDE (VS Code) 直接以 Classpath
 * 執行時出現 "JavaFX runtime components are missing" 的限制，支援一鍵直接執行與偵錯。
 */
public class Launcher {
    public static void main(String[] args) {
        App.main(args);
    }
}
