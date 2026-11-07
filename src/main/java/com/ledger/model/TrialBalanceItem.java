package com.ledger.model;

import com.ledger.util.MoneyUtil;
import java.math.BigDecimal;

public record TrialBalanceItem(
        String accountCode,
        String accountName,
        String accountType,
        String normalSide,
        long totalDebitMinor,
        long totalCreditMinor,
        long netMinor
) {
    public BigDecimal totalDebit() {
        return MoneyUtil.toMajor(totalDebitMinor);
    }

    public BigDecimal totalCredit() {
        return MoneyUtil.toMajor(totalCreditMinor);
    }

    public BigDecimal net() {
        return MoneyUtil.toMajor(netMinor);
    }
}
