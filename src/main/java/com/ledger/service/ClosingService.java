package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.dao.AuditDao;
import com.ledger.dao.FiscalPeriodDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.FiscalPeriod;
import com.ledger.model.OperatorRole;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

public class ClosingService {

    private final FiscalPeriodDao periodDao;
    private final AuditDao auditDao;

    public ClosingService() {
        this(new FiscalPeriodDao(), new AuditDao());
    }

    public ClosingService(FiscalPeriodDao periodDao, AuditDao auditDao) {
        this.periodDao = periodDao;
        this.auditDao = auditDao;
    }

    public void closePeriod(int year, int month, String operator) {
        String normOp = OperatorRole.normalize(operator);
        if (OperatorRole.resolve(normOp) != OperatorRole.CHECKER) {
            throw new BusinessException("權限不足：結帳鎖定涉及期間管制，僅會計師有權執行！");
        }

        try (Connection conn = DbManager.get()) {
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                FiscalPeriod period = periodDao.findByYearMonth(conn, year, month)
                        .orElseThrow(() -> new BusinessException(String.format("找不到 %d年%02d月 的會計期間", year, month)));

                if (period.isClosed()) {
                    throw new BusinessException(String.format("%d年%02d月 已結帳鎖定", year, month));
                }

                periodDao.updateStatus(conn, period.id(), "CLOSED");
                auditDao.log(conn, "CLOSE_PERIOD", "fiscal_periods", period.id(), normOp, "OPEN", "CLOSED");

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException("結帳操作失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originAutoCommit);
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫操作異常: " + e.getMessage(), e);
        }
    }

    public void reopenPeriod(int year, int month, String operator) {
        reopenPeriod(year, month, operator, "未填寫原因");
    }

    public void reopenPeriod(int year, int month, String operator, String reason) {
        String normOp = OperatorRole.normalize(operator);
        if (OperatorRole.resolve(normOp) != OperatorRole.CHECKER) {
            throw new BusinessException("權限不足：反結帳操作涉及重新開放帳簿，僅會計師有權執行！");
        }
        if (reason == null || reason.trim().isEmpty()) {
            throw new BusinessException("反結帳必須填寫原因，以利事後稽核");
        }
        String reasonText = reason.trim();

        try (Connection conn = DbManager.get()) {
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                FiscalPeriod period = periodDao.findByYearMonth(conn, year, month)
                        .orElseThrow(() -> new BusinessException(String.format("找不到 %d年%02d月 的會計期間", year, month)));

                if (period.isOpen()) {
                    throw new BusinessException(String.format("%d年%02d月 目前已是開啟狀態", year, month));
                }

                List<FiscalPeriod> all = periodDao.findAll(conn);
                for (FiscalPeriod p : all) {
                    if (p.isClosed()) {
                        if (p.year() > year || (p.year() == year && p.month() > month)) {
                            throw new BusinessException(
                                    String.format("後續期間 %d年%02d月 仍處於結帳鎖定狀態，反結帳必須自最新月份依序倒推！", p.year(), p.month()));
                        }
                    }
                }

                periodDao.updateStatus(conn, period.id(), "OPEN");
                auditDao.log(conn, "REOPEN_PERIOD", "fiscal_periods", period.id(), normOp,
                        "CLOSED", "OPEN; reason=" + reasonText);

                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                if (e instanceof BusinessException be) {
                    throw be;
                }
                throw new BusinessException("反結帳操作失敗: " + e.getMessage(), e);
            } finally {
                conn.setAutoCommit(originAutoCommit);
            }
        } catch (SQLException e) {
            throw new BusinessException("資料庫操作異常: " + e.getMessage(), e);
        }
    }
}
