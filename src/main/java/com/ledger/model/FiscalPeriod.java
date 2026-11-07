package com.ledger.model;

public record FiscalPeriod(
        Long id,
        int year,
        int month,
        String status       // OPEN, CLOSED
) {
    public boolean isOpen() {
        return "OPEN".equalsIgnoreCase(status);
    }

    public boolean isClosed() {
        return "CLOSED".equalsIgnoreCase(status);
    }

    public String getPeriodName() {
        return String.format("%d-%02d", year, month);
    }

    public String getDisplayName() {
        return String.format("%d年%02d月 [%s]", year, month, isOpen() ? "未結帳" : "已結帳");
    }
}
