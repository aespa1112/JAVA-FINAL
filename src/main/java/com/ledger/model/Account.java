package com.ledger.model;

public record Account(
        Long id,
        String code,
        String name,
        String type,        // ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE
        String normalSide,  // DEBIT, CREDIT
        Long parentId,
        boolean isLeaf,
        boolean enabled
) {
    public Account(String code, String name, String type, String normalSide) {
        this(null, code, name, type, normalSide, null, true, true);
    }

    public String getDisplayName() {
        return code + " " + name;
    }
}
