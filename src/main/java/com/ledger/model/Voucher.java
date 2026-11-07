package com.ledger.model;

import com.ledger.util.MoneyUtil;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;

public record Voucher(
        Long id,
        Long periodId,
        String voucherNo,
        String voucherDate,
        String summary,
        String status,       // DRAFT, POSTED, VOID
        String preparer,
        String reviewer,
        Long reversedId,
        String createdAt,
        String postedAt,
        List<VoucherEntry> entries
) {
    public Voucher {
        if (entries == null) {
            entries = Collections.emptyList();
        } else {
            entries = List.copyOf(entries);
        }
    }

    public boolean isDraft() {
        return "DRAFT".equalsIgnoreCase(status);
    }

    public boolean isPosted() {
        return "POSTED".equalsIgnoreCase(status);
    }

    public boolean isVoid() {
        return "VOID".equalsIgnoreCase(status);
    }

    public long totalDebitMinor() {
        return entries.stream().mapToLong(VoucherEntry::debitMinor).sum();
    }

    public long totalCreditMinor() {
        return entries.stream().mapToLong(VoucherEntry::creditMinor).sum();
    }

    public BigDecimal totalDebit() {
        return MoneyUtil.toMajor(totalDebitMinor());
    }

    public BigDecimal totalCredit() {
        return MoneyUtil.toMajor(totalCreditMinor());
    }

    public boolean isBalanced() {
        return totalDebitMinor() == totalCreditMinor() && entries.size() >= 2;
    }
}
