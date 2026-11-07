package com.ledger.util;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;

/**
 * 財務金額精確運算工具類別。
 * 遵循「資料庫以最小單位（分，INTEGER）儲存，運算使用 BigDecimal」的原則，
 * 避免浮點數精度損失（例如 0.1 + 0.2 != 0.3）。
 */
public final class MoneyUtil {

    private static final DecimalFormat FORMATTER = new DecimalFormat("#,##0.00");

    private MoneyUtil() {
    }

    /**
     * 將元 (BigDecimal) 轉換為分 (long)
     */
    public static long toMinor(BigDecimal major) {
        if (major == null) {
            return 0L;
        }
        return major.setScale(2, RoundingMode.HALF_UP)
                .movePointRight(2)
                .longValueExact();
    }

    /**
     * 將元字串 (String) 轉換為分 (long)
     */
    public static long toMinor(String majorStr) {
        if (majorStr == null || majorStr.trim().isEmpty()) {
            return 0L;
        }
        String cleaned = majorStr.trim().replace(",", "");
        BigDecimal bd = new BigDecimal(cleaned);
        return toMinor(bd);
    }

    /**
     * 將 double 轉為分 (long)
     */
    public static long toMinor(double value) {
        return toMinor(BigDecimal.valueOf(value));
    }

    /**
     * 將分 (long) 轉換為元 (BigDecimal)
     */
    public static BigDecimal toMajor(long minor) {
        return BigDecimal.valueOf(minor, 2);
    }

    /**
     * 格式化輸出分 (long)，如 123456 -> "1,234.56"
     */
    public static String format(long minor) {
        return FORMATTER.format(toMajor(minor));
    }

    /**
     * 格式化輸出元 (BigDecimal)
     */
    public static String format(BigDecimal major) {
        if (major == null) {
            return "0.00";
        }
        return FORMATTER.format(major);
    }
}
