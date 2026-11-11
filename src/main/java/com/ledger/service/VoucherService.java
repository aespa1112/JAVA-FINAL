package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.dao.AccountDao;
import com.ledger.dao.AuditDao;
import com.ledger.dao.FiscalPeriodDao;
import com.ledger.dao.VoucherDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.Account;
import com.ledger.model.FiscalPeriod;
import com.ledger.model.OperatorRole;
import com.ledger.model.Voucher;
import com.ledger.model.VoucherEntry;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class VoucherService {

    private final VoucherDao voucherDao;
    private final FiscalPeriodDao periodDao;
    private final AuditDao auditDao;
    private final AccountDao accountDao;

    public VoucherService() {
        this(new VoucherDao(), new FiscalPeriodDao(), new AuditDao(), new AccountDao());
    }

    public VoucherService(VoucherDao voucherDao, FiscalPeriodDao periodDao, AuditDao auditDao) {
        this(voucherDao, periodDao, auditDao, new AccountDao());
    }

    public VoucherService(VoucherDao voucherDao, FiscalPeriodDao periodDao, AuditDao auditDao, AccountDao accountDao) {
        this.voucherDao = voucherDao;
        this.periodDao = periodDao;
        this.auditDao = auditDao;
        this.accountDao = accountDao;
    }

    public void validateBalance(List<VoucherEntry> entries) {
        if (entries == null || entries.size() < 2) {
            throw new BusinessException("複式簿記至少需兩筆分錄");
        }

        BigDecimal debit = entries.stream()
                .map(VoucherEntry::debit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal credit = entries.stream()
                .map(VoucherEntry::credit)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (debit.compareTo(credit) != 0) {
            throw new BusinessException(
                    String.format("借貸不平衡：借方 %s，貸方 %s，差額 %s",
                            debit, credit, debit.subtract(credit)));
        }

        if (debit.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BusinessException("分錄金額總計必須大於零");
        }
    }

    private void validateAccountsUsable(Connection conn, List<VoucherEntry> entries) throws SQLException {
        for (VoucherEntry e : entries) {
            Account acc = accountDao.findById(conn, e.accountId())
                    .orElseThrow(() -> new BusinessException("分錄指定之會計科目不存在: ID " + e.accountId()));
            if (!acc.enabled()) {
                throw new BusinessException(String.format("科目 %s (%s) 已停用，不得錄入分錄", acc.code(), acc.name()));
            }
            if (!acc.isLeaf()) {
                throw new BusinessException(String.format("科目 %s (%s) 為非明細/非末級統制科目，不得直接記帳", acc.code(), acc.name()));
            }
        }
    }

    public Voucher createDraft(String voucherDate, String summary, String preparer, List<VoucherEntry> entries) {
        return createDraft(voucherDate, summary, null, preparer, entries);
    }

    public Voucher createDraft(String voucherDate, String summary, Long reversedId, String preparer, List<VoucherEntry> entries) {
        String normPreparer = SessionContext.resolveOperator(preparer);
        OperatorRole role = OperatorRole.resolve(normPreparer);
        if (role == OperatorRole.AUDITOR) {
            throw new BusinessException("權限不足：查帳員（" + normPreparer + "）為唯讀身分，無權建立傳票");
        }
        if (reversedId == null && role == OperatorRole.CHECKER) {
            throw new BusinessException("職責分離原則：會計師（審核人 " + normPreparer + "）不得兼任制單人建立一般傳票");
        }

        return createDraftInternal(voucherDate, summary, reversedId, normPreparer, entries, null, null);
    }

    /**
     * 內部建立草稿。
     * @param reverseInitiator 若為紅字沖銷，傳入發起人標準名稱，會在同一交易中寫入 REVERSE_INITIATED 日誌。
     * @param originVoucherNo  原傳票編號（僅用於審計日誌說明）。
     */
    private Voucher createDraftInternal(String voucherDate, String summary, Long reversedId,
                                        String preparer, List<VoucherEntry> entries,
                                        String reverseInitiator, String originVoucherNo) {
        if (preparer == null || preparer.trim().isEmpty()) {
            throw new BusinessException("制單人不可為空");
        }
        if (summary == null || summary.trim().isEmpty()) {
            throw new BusinessException("傳票摘要不可為空");
        }
        if (voucherDate == null || voucherDate.trim().isEmpty()) {
            throw new BusinessException("傳票日期不可為空");
        }

        validateBalance(entries);

        LocalDate date = LocalDate.parse(voucherDate.trim());
        int year = date.getYear();
        int month = date.getMonthValue();

        try (Connection conn = DbManager.get()) {
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                FiscalPeriod period = periodDao.getOrCreate(conn, year, month);
                if (period.isClosed()) {
                    throw new BusinessException("該會計期間 (" + period.getPeriodName() + ") 已結帳鎖定，不得新增憑證");
                }

                validateAccountsUsable(conn, entries);

                String voucherNo = voucherDao.generateNextVoucherNo(conn, period.id(), year, month);
                Voucher draft = new Voucher(
                        null,
                        period.id(),
                        voucherNo,
                        voucherDate.trim(),
                        summary.trim(),
                        "DRAFT",
                        preparer.trim(),
                        null,
                        reversedId,
                        null,
                        null,
                        entries
                );

                Voucher created = voucherDao.create(conn, draft, entries);
                auditDao.log(conn, "CREATE_DRAFT", "vouchers", created.id(), preparer, null, "VoucherNo: " + voucherNo);

                // 紅字沖銷：在同一交易中寫入發起紀錄，確保草稿與審計日誌原子性
                if (reversedId != null && reverseInitiator != null && !reverseInitiator.isBlank()) {
                    String detail = "Created Draft: " + voucherNo;
                    if (originVoucherNo != null && !originVoucherNo.isBlank()) {
                        detail = "Origin: " + originVoucherNo + " | " + detail;
                    }
                    auditDao.log(conn, "REVERSE_INITIATED", "vouchers", created.id(),
                            reverseInitiator, originVoucherNo, detail);
                }

                conn.commit();
                return created;
            } catch (Exception e) {
                conn.rollback();
                logDeniedAttempt(conn, "CREATE_DRAFT", null, preparer, e.getMessage());
                if (e instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException("建立傳票草稿失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originAutoCommit);
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫操作異常: " + e.getMessage(), e);
        }
    }

    public void post(long voucherId, String reviewer) {
        if (reviewer == null || reviewer.trim().isEmpty()) {
            throw new BusinessException("審核人不可為空");
        }

        String normReviewer = SessionContext.resolveOperator(reviewer);
        OperatorRole role = OperatorRole.resolve(normReviewer);
        if (role != OperatorRole.CHECKER) {
            throw new BusinessException("權限不足：僅會計師（審核人）有權審核過帳傳票，當前身分無此權限！");
        }

        try (Connection conn = DbManager.get()) {
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Voucher voucher = voucherDao.findById(conn, voucherId)
                        .orElseThrow(() -> new BusinessException("傳票不存在: ID " + voucherId));

                if (!voucher.isDraft()) {
                    throw new BusinessException("僅草稿狀態憑證可進行過帳，目前狀態: " + voucher.status());
                }

                if (voucher.preparer().equalsIgnoreCase(normReviewer)) {
                    throw new BusinessException(
                            String.format("職責分離原則：制單人與審核人不得為同一人（制單人：%s，審核人：%s）",
                                    voucher.preparer(), normReviewer));
                }

                // 針對紅字沖銷傳票執行雙人審核控制（Fail-Closed）
                if (voucher.reversedId() != null) {
                    String checkInitiatorSql =
                            "SELECT operator FROM audit_log " +
                            "WHERE action = 'REVERSE_INITIATED' AND (record_id = ? OR record_id = ?) " +
                            "ORDER BY id DESC LIMIT 1";
                    String initiator = null;
                    try (PreparedStatement ps = conn.prepareStatement(checkInitiatorSql)) {
                        ps.setLong(1, voucherId);
                        ps.setLong(2, voucher.reversedId());
                        try (ResultSet rs = ps.executeQuery()) {
                            if (rs.next()) {
                                initiator = rs.getString(1);
                            }
                        }
                    }

                    // Fail-Closed：缺少發起紀錄時拒絕過帳，防止雙人審核被繞過
                    if (initiator == null || initiator.isBlank()) {
                        throw new BusinessException(
                                "紅字沖銷傳票缺少 REVERSE_INITIATED 發起紀錄，無法過帳。請重新發起沖銷以確保雙人審核可追蹤！");
                    }

                    if (initiator.equalsIgnoreCase(normReviewer)) {
                        throw new BusinessException(
                                String.format("職責分離原則：紅字沖銷發起人（%s）不得自行審核過帳該沖銷傳票，必須由第二位會計師審核！",
                                        initiator));
                    }
                }

                validateBalance(voucher.entries());
                validateAccountsUsable(conn, voucher.entries());

                voucherDao.lockAndCheckDraft(conn, voucherId);
                voucherDao.markPosted(conn, voucherId, normReviewer);
                auditDao.log(conn, "POST", "vouchers", voucherId, normReviewer, "DRAFT", "POSTED");

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                logDeniedAttempt(conn, "POST", voucherId, normReviewer, e.getMessage());
                if (e instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException("過帳失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originAutoCommit);
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫連線失敗: " + e.getMessage(), e);
        }
    }

    public Voucher reverse(long originId, String reason, String operator) {
        if (operator == null || operator.trim().isEmpty()) {
            throw new BusinessException("沖銷操作人不可為空");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException("請填寫沖銷原因");
        }

        String normOp = SessionContext.resolveOperator(operator);
        OperatorRole role = OperatorRole.resolve(normOp);
        if (role == OperatorRole.AUDITOR) {
            throw new BusinessException("權限不足：查帳員為唯讀身分，無權進行紅字沖銷");
        }
        if (role == OperatorRole.MAKER) {
            throw new BusinessException("權限不足：紅字沖銷屬於重大更正分錄，需由會計師（Bob）發起！");
        }

        try (Connection conn = DbManager.get()) {
            Voucher origin = voucherDao.findById(conn, originId)
                    .orElseThrow(() -> new BusinessException("找不到指定原憑證 ID: " + originId));

            if (!origin.isPosted()) {
                throw new BusinessException("僅已過帳憑證可進行紅字沖銷，當前狀態為: " + origin.status());
            }

            String checkReversedSql = "SELECT COUNT(*) FROM vouchers WHERE reversed_id = ? AND status <> 'VOID'";
            try (PreparedStatement ps = conn.prepareStatement(checkReversedSql)) {
                ps.setLong(1, originId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next() && rs.getInt(1) > 0) {
                        throw new BusinessException("傳票 " + origin.voucherNo() + " 已經沖銷過，不可重複沖銷！");
                    }
                }
            }

            List<VoucherEntry> reversedEntries = new ArrayList<>();
            int lineNo = 1;
            for (VoucherEntry e : origin.entries()) {
                reversedEntries.add(new VoucherEntry(
                        lineNo++,
                        e.accountId(),
                        "沖銷: " + (e.memo() == null ? "" : e.memo()),
                        e.creditMinor(),
                        e.debitMinor()
                ));
            }

            String summary = "【沖銷】" + reason.trim();
            String today = LocalDate.now().toString();

            // 沖銷傳票之制單人保留原傳票制單人（MAKER），以符合會計內控職責分離原則；
            // 審核時必須由另一位會計師審核，杜絕一人沖銷加過帳的風險
            String preparer = (origin.preparer() != null && !origin.preparer().trim().isEmpty())
                    ? OperatorRole.normalize(origin.preparer())
                    : "Amy";

            // 在同一交易中建立草稿並寫入 REVERSE_INITIATED，確保原子性
            return createDraftInternal(today, summary, originId, preparer, reversedEntries,
                    normOp, origin.voucherNo());
        } catch (SQLException e) {
            throw new BusinessException("沖銷操作異常: " + e.getMessage(), e);
        }
    }

    public void updateDraft(long voucherId, String voucherDate, String summary, String operator, List<VoucherEntry> entries) {
        String normOp = SessionContext.resolveOperator(operator);
        OperatorRole role = OperatorRole.resolve(normOp);
        if (role == OperatorRole.AUDITOR) {
            throw new BusinessException("權限不足：查帳員為唯讀身分，無權修改草稿傳票");
        }
        if (role == OperatorRole.CHECKER) {
            throw new BusinessException("職責分離原則：會計師（審核人）無權修改制單草稿");
        }

        validateBalance(entries);

        LocalDate date = LocalDate.parse(voucherDate.trim());
        int year = date.getYear();
        int month = date.getMonthValue();

        try (Connection conn = DbManager.get()) {
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Voucher origin = voucherDao.findById(conn, voucherId)
                        .orElseThrow(() -> new BusinessException("傳票不存在: ID " + voucherId));
                if (!origin.isDraft()) {
                    throw new BusinessException("非草稿憑證不得修改，違反審計軌跡原則");
                }

                FiscalPeriod targetPeriod = periodDao.getOrCreate(conn, year, month);
                if (targetPeriod.isClosed()) {
                    throw new BusinessException(String.format("目標日期所屬期間 %d年%02d月 已結帳鎖定，禁止將草稿調整至此期間", year, month));
                }

                validateAccountsUsable(conn, entries);

                // 檢查是否跨月：若修改後的日期所屬年月與原傳票編號月份不符，需重新生成該期間的傳票編號
                String expectedPrefix = String.format("%04d-%02d-", year, month);
                String updatedVoucherNo = origin.voucherNo();
                if (!updatedVoucherNo.startsWith(expectedPrefix)) {
                    updatedVoucherNo = voucherDao.generateNextVoucherNo(conn, targetPeriod.id(), year, month);
                }

                Voucher updated = new Voucher(
                        voucherId,
                        targetPeriod.id(),
                        updatedVoucherNo,
                        voucherDate.trim(),
                        summary.trim(),
                        "DRAFT",
                        origin.preparer(),
                        null,
                        origin.reversedId(),
                        origin.createdAt(),
                        null,
                        entries
                );

                voucherDao.updateDraft(conn, updated, entries);
                String auditDetail = origin.voucherNo().equals(updatedVoucherNo)
                        ? "Summary: " + summary.trim()
                        : String.format("Summary: %s | VoucherNo changed: %s -> %s", summary.trim(), origin.voucherNo(), updatedVoucherNo);
                auditDao.log(conn, "UPDATE_DRAFT", "vouchers", voucherId, normOp, origin.summary(), auditDetail);

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                logDeniedAttempt(conn, "UPDATE_DRAFT", voucherId, normOp, e.getMessage());
                if (e instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException("更新草稿失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originAutoCommit);
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫連線失敗: " + e.getMessage(), e);
        }
    }

    public void deleteDraft(long voucherId, String operator) {
        String normOp = SessionContext.resolveOperator(operator);
        OperatorRole role = OperatorRole.resolve(normOp);
        if (role != OperatorRole.MAKER) {
            throw new BusinessException("權限不足：僅記帳員（制單人）有權刪除草稿傳票");
        }

        try (Connection conn = DbManager.get()) {
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                Voucher origin = voucherDao.findById(conn, voucherId)
                        .orElseThrow(() -> new BusinessException("傳票不存在: ID " + voucherId));
                if (!origin.isDraft()) {
                    throw new BusinessException("已過帳憑證不得刪除，請使用紅字沖銷");
                }

                voucherDao.deleteDraft(conn, voucherId);
                auditDao.log(conn, "DELETE_DRAFT", "vouchers", voucherId, normOp, origin.summary(), null);

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                logDeniedAttempt(conn, "DELETE_DRAFT", voucherId, normOp, e.getMessage());
                if (e instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException("刪除草稿失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originAutoCommit);
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫連線失敗: " + e.getMessage(), e);
        }
    }

    private void logDeniedAttempt(Connection conn, String action, Long voucherId, String operator, String errorMsg) {
        try {
            boolean auto = conn.getAutoCommit();
            conn.setAutoCommit(true);
            auditDao.log(conn, "DENIED_" + action, "vouchers", voucherId, operator, null, "Reason: " + errorMsg);
            conn.setAutoCommit(auto);
        } catch (Exception err) {
            System.err.println("WARN: 審計日誌寫入失敗: " + err.getMessage());
        }
    }
}
