package com.ledger.model;

public record AuditLog(
        Long id,
        String action,
        String target,
        Long recordId,
        String operator,
        String oldData,
        String newData,
        String createdAt
) {}
