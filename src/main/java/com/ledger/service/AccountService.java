package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.dao.AccountDao;
import com.ledger.dao.AuditDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.Account;
import com.ledger.model.OperatorRole;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class AccountService {

    private final AccountDao accountDao;
    private final AuditDao auditDao;

    public AccountService() {
        this(new AccountDao(), new AuditDao());
    }

    public AccountService(AccountDao accountDao, AuditDao auditDao) {
        this.accountDao = accountDao;
        this.auditDao = auditDao;
    }

    public List<Account> getAllAccounts() {
        try {
            return accountDao.findAll();
        } catch (SQLException e) {
            throw new BusinessException("查詢會計科目失敗: " + e.getMessage(), e);
        }
    }

    public Account createAccount(String code, String name, String type, String normalSide, Long parentId, String operator) {
        String normOp = SessionContext.resolveOperator(operator);
        if (OperatorRole.resolve(normOp) == OperatorRole.AUDITOR) {
            throw new BusinessException("權限不足：查帳員（" + normOp + "）為唯讀身分，無權新增會計科目");
        }

        if (code == null || code.trim().isEmpty()) {
            throw new BusinessException("科目代碼不可為空");
        }
        if (name == null || name.trim().isEmpty()) {
            throw new BusinessException("科目名稱不可為空");
        }
        if (type == null || type.trim().isEmpty()) {
            throw new BusinessException("科目類別不可為空");
        }
        if (normalSide == null || normalSide.trim().isEmpty()) {
            throw new BusinessException("正常借貸方不可為空");
        }

        String sanitizedType = type.trim().toUpperCase();
        String sanitizedSide = normalSide.trim().toUpperCase();

        String expectedSide = switch (sanitizedType) {
            case "ASSET", "EXPENSE" -> "DEBIT";
            case "LIABILITY", "EQUITY", "REVENUE" -> "CREDIT";
            default -> throw new BusinessException("不支援的科目類別: " + sanitizedType);
        };

        if (!expectedSide.equals(sanitizedSide)) {
            throw new BusinessException(String.format("會計科目類別與正常方向不符：%s 之正常借貸方應為 %s", sanitizedType, expectedSide));
        }

        validateCodePrefix(code.trim(), sanitizedType);

        Account account = new Account(null, code.trim(), name.trim(), sanitizedType, sanitizedSide, parentId, true, true);
        try (Connection conn = DbManager.get()) {
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                if (accountDao.findByCode(conn, code.trim()).isPresent()) {
                    throw new BusinessException("科目代碼已存在: " + code);
                }
                Account created = accountDao.create(conn, account);
                auditDao.log(conn, "CREATE_ACCOUNT", "accounts", created.id(), normOp, null, code + " " + name);
                conn.commit();
                return created;
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException("新增科目失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originAutoCommit);
            }
        } catch (SQLException e) {
            throw new BusinessException("新增科目失敗: " + e.getMessage(), e);
        }
    }

    private void validateCodePrefix(String code, String type) {
        char firstChar = code.charAt(0);
        switch (type) {
            case "ASSET" -> {
                if (firstChar != '1') throw new BusinessException("資產類科目代碼應以 1 開頭");
            }
            case "LIABILITY" -> {
                if (firstChar != '2') throw new BusinessException("負債類科目代碼應以 2 開頭");
            }
            case "EQUITY" -> {
                if (firstChar != '3') throw new BusinessException("權益類科目代碼應以 3 開頭");
            }
            case "REVENUE" -> {
                if (firstChar != '4') throw new BusinessException("營業收入科目代碼應以 4 開頭");
            }
            case "EXPENSE" -> {
                if (firstChar != '5' && firstChar != '6') throw new BusinessException("費用與成本類科目代碼應以 5 或 6 開頭");
            }
        }
    }
}
