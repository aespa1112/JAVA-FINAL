package com.ledger.service;

import com.ledger.config.DbManager;
import com.ledger.dao.AuditDao;
import com.ledger.dao.FiscalPeriodDao;
import com.ledger.exception.BusinessException;
import com.ledger.model.FiscalPeriod;
import com.ledger.model.OperatorRole;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
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
            throw new BusinessException("權限不足：期末結帳涉及財務帳簿鎖定，僅會計師（Bob）有權執行！");
        }

        try (Connection conn = DbManager.get()) {
            boolean originAutoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try {
                FiscalPeriod period = periodDao.findByYearMonth(conn, year, month)
                        .orElseThrow(() -> new BusinessException(String.format("找不到 %d年%02d月 的會計期間", year, month)));

                if (period.isClosed()) {
                    throw new BusinessException(String.format("%d年%02d月 已經是結帳鎖定狀態", year, month));
                }

                // 檢查是否還有未過帳草稿傳票
                String checkDraftSql = "SELECT COUNT(*) FROM vouchers WHERE period_id = ? AND status = 'DRAFT'";
                try (PreparedStatement ps = conn.prepareStatement(checkDraftSql)) {
                    ps.setLong(1, period.id());
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next() && rs.getInt(1) > 0) {
                            throw new BusinessException(
                                    String.format("該期間尚有 %d 張未過帳草稿傳票，需全數過帳後方可結帳", rs.getInt(1)));
                        }
                    }
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
        String normOp = OperatorRole.normalize(operator);
        if (OperatorRole.resolve(normOp) != OperatorRole.CHECKER) {
            throw new BusinessException("權限不足：反結帳操作涉及重新開放帳簿，僅會計師（Bob）有權執行！");
        }

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
                auditDao.log(conn, "REOPEN_PERIOD", "fiscal_periods", period.id(), normOp, "CLOSED", "OPEN");

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
