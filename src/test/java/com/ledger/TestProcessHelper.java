package com.ledger;

import com.ledger.service.AuthService;
import com.ledger.service.YearEndClosingService;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;

/**
 * 跨程序獨立 JVM 執行輔助工具：供 ProcessBuilder 啟動獨立子程序測試崩潰中斷與並行競爭。
 */
public class TestProcessHelper {

    public static void main(String[] args) {
        if (args.length < 1) {
            System.err.println("Usage: TestProcessHelper <mode> [args...]");
            System.exit(1);
        }

        String mode = args[0];
        try {
            switch (mode) {
                case "crash-simulation" -> {
                    // 模擬年結程序在解鎖 12 月後遭遇硬崩潰（SIGKILL / 斷電）
                    String dbPath = args[1];
                    int year = Integer.parseInt(args[2]);
                    int month = Integer.parseInt(args[3]);

                    try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + dbPath)) {
                        try (Statement st = conn.createStatement()) {
                            st.execute("PRAGMA journal_mode = WAL;");
                            st.execute("PRAGMA busy_timeout = 5000;");
                        }
                        YearEndClosingService ye = new YearEndClosingService();
                        ye.ensureTable(conn);

                        conn.setAutoCommit(false);
                        String opId = "ABRUPT-CRASH-OP-" + year + "-" + System.currentTimeMillis();
                        ye.recordRecoveryIntent(conn, opId, year, month, "CLOSED", "YEAR_END_DRAFT");
                        try (PreparedStatement ps = conn.prepareStatement(
                                "UPDATE fiscal_periods SET status='OPEN' WHERE year=? AND month=?")) {
                            ps.setInt(1, year);
                            ps.setInt(2, month);
                            ps.executeUpdate();
                        }
                        conn.commit();
                    }

                    // 立即硬終止當前 JVM 程序，模擬非正常崩潰（不執行任何 shutdown hook）
                    System.err.println("[TestProcessHelper] Simulating hard process crash with halt(42)...");
                    Runtime.getRuntime().halt(42);
                }
                case "auth-fail" -> {
                    // 跨程序並行登入失敗嘗試
                    String username = args[1];
                    int count = Integer.parseInt(args[2]);
                    AuthService auth = new AuthService();
                    int failed = 0;
                    for (int i = 0; i < count; i++) {
                        try {
                            auth.login(username, "badPassword_" + i + "_" + System.nanoTime());
                        } catch (Exception ignored) {
                            failed++;
                        }
                    }
                    System.out.println("AUTH_FAIL_COUNT: " + failed);
                    System.exit(0);
                }
                default -> {
                    System.err.println("Unknown mode: " + mode);
                    System.exit(2);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.exit(99);
        }
    }
}
