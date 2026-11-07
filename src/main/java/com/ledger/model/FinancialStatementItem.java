package com.ledger.model;

import com.ledger.util.MoneyUtil;
import java.math.BigDecimal;

public record FinancialStatementItem(
        String section,
        String code,
        String name,
        long amountMinor
) {
    public BigDecimal amount() {
        return MoneyUtil.toMajor(amountMinor);
    }
}
