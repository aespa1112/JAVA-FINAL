package com.ledger.model;

import com.ledger.util.MoneyUtil;
import java.math.BigDecimal;

public record GeneralLedgerItem(
        String voucherDate,
        String voucherNo,
        String summary,
        int lineNo,
        String memo,
        long debitMinor,
        long creditMinor,
        long balanceMinor
) {
    public BigDecimal debit() {
        return MoneyUtil.toMajor(debitMinor);
    }

    public BigDecimal credit() {
        return MoneyUtil.toMajor(creditMinor);
    }

    public BigDecimal balance() {
        return MoneyUtil.toMajor(balanceMinor);
    }
}
