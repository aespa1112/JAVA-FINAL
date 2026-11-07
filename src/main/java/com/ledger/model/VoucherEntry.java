package com.ledger.model;

import com.ledger.util.MoneyUtil;
import java.math.BigDecimal;

public record VoucherEntry(
        Long id,
        Long voucherId,
        int lineNo,
        long accountId,
        String memo,
        long debitMinor,
        long creditMinor,
        String accountCode,
        String accountName
) {
    public VoucherEntry(int lineNo, long accountId, String memo, long debitMinor, long creditMinor) {
        this(null, null, lineNo, accountId, memo, debitMinor, creditMinor, null, null);
    }

    public VoucherEntry(int lineNo, long accountId, String memo, BigDecimal debit, BigDecimal credit) {
        this(null, null, lineNo, accountId, memo,
                MoneyUtil.toMinor(debit), MoneyUtil.toMinor(credit), null, null);
    }

    public BigDecimal debit() {
        return MoneyUtil.toMajor(debitMinor);
    }

    public BigDecimal credit() {
        return MoneyUtil.toMajor(creditMinor);
    }

    public VoucherEntry withAmount(BigDecimal newDebit, BigDecimal newCredit) {
        return new VoucherEntry(id, voucherId, lineNo, accountId, memo,
                MoneyUtil.toMinor(newDebit), MoneyUtil.toMinor(newCredit),
                accountCode, accountName);
    }

    public VoucherEntry withVoucherId(long newVoucherId) {
        return new VoucherEntry(id, newVoucherId, lineNo, accountId, memo,
                debitMinor, creditMinor, accountCode, accountName);
    }

    public VoucherEntry withLineNo(int newLineNo) {
        return new VoucherEntry(id, voucherId, newLineNo, accountId, memo,
                debitMinor, creditMinor, accountCode, accountName);
    }

    public VoucherEntry withAccountInfo(String code, String name) {
        return new VoucherEntry(id, voucherId, lineNo, accountId, memo,
                debitMinor, creditMinor, code, name);
    }
}
