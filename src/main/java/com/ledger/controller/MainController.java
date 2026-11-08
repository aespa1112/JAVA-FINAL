package com.ledger.controller;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.dao.*;
import com.ledger.exception.BusinessException;
import com.ledger.model.*;
import com.ledger.service.*;
import com.ledger.util.MoneyUtil;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

public class MainController {

    @FXML private ComboBox<String> clientCombo;
    @FXML private ComboBox<String> operatorCombo;
    @FXML private Label dbPathLabel;
    @FXML private Label statusLabel;

    @FXML private TableView<Voucher> voucherTable;
    @FXML private TableColumn<Voucher, String> colVoucherNo;
    @FXML private TableColumn<Voucher, String> colVoucherDate;
    @FXML private TableColumn<Voucher, String> colSummary;
    @FXML private TableColumn<Voucher, String> colStatus;
    @FXML private TableColumn<Voucher, String> colPreparer;
    @FXML private TableColumn<Voucher, String> colReviewer;
    @FXML private TableColumn<Voucher, String> colTotalDebit;
    @FXML private TableColumn<Voucher, String> colTotalCredit;
    @FXML private TableColumn<Voucher, String> colReversedId;

    @FXML private TableView<VoucherEntry> entryTable;
    @FXML private TableColumn<VoucherEntry, String> colEntryLine;
    @FXML private TableColumn<VoucherEntry, String> colEntryCode;
    @FXML private TableColumn<VoucherEntry, String> colEntryName;
    @FXML private TableColumn<VoucherEntry, String> colEntryMemo;
    @FXML private TableColumn<VoucherEntry, String> colEntryDebit;
    @FXML private TableColumn<VoucherEntry, String> colEntryCredit;

    @FXML private ComboBox<String> voucherStatusFilter;
    @FXML private TextField voucherKeywordFilter;

    @FXML private TableView<Account> accountTable;
    @FXML private TableColumn<Account, String> colAccCode;
    @FXML private TableColumn<Account, String> colAccName;
    @FXML private TableColumn<Account, String> colAccType;
    @FXML private TableColumn<Account, String> colAccSide;
    @FXML private TableColumn<Account, String> colAccStatus;

    @FXML private TableView<FiscalPeriod> periodTable;
    @FXML private TableColumn<FiscalPeriod, String> colPeriodYear;
    @FXML private TableColumn<FiscalPeriod, String> colPeriodMonth;
    @FXML private TableColumn<FiscalPeriod, String> colPeriodStatus;

    @FXML private TableView<TrialBalanceItem> tbTable;
    @FXML private TableColumn<TrialBalanceItem, String> colTbCode;
    @FXML private TableColumn<TrialBalanceItem, String> colTbName;
    @FXML private TableColumn<TrialBalanceItem, String> colTbType;
    @FXML private TableColumn<TrialBalanceItem, String> colTbDebit;
    @FXML private TableColumn<TrialBalanceItem, String> colTbCredit;
    @FXML private TableColumn<TrialBalanceItem, String> colTbNet;

    @FXML private TableView<FinancialStatementItem> bsTable;
    @FXML private TableColumn<FinancialStatementItem, String> colBsSection;
    @FXML private TableColumn<FinancialStatementItem, String> colBsCode;
    @FXML private TableColumn<FinancialStatementItem, String> colBsName;
    @FXML private TableColumn<FinancialStatementItem, String> colBsAmount;

    @FXML private TableView<FinancialStatementItem> isTable;
    @FXML private TableColumn<FinancialStatementItem, String> colIsSection;
    @FXML private TableColumn<FinancialStatementItem, String> colIsCode;
    @FXML private TableColumn<FinancialStatementItem, String> colIsName;
    @FXML private TableColumn<FinancialStatementItem, String> colIsAmount;

    @FXML private TableView<FinancialStatementItem> eqTable;
    @FXML private TableColumn<FinancialStatementItem, String> colEqSection;
    @FXML private TableColumn<FinancialStatementItem, String> colEqCode;
    @FXML private TableColumn<FinancialStatementItem, String> colEqName;
    @FXML private TableColumn<FinancialStatementItem, String> colEqAmount;

    @FXML private TableView<FinancialStatementItem> cfTable;
    @FXML private TableColumn<FinancialStatementItem, String> colCfSection;
    @FXML private TableColumn<FinancialStatementItem, String> colCfCode;
    @FXML private TableColumn<FinancialStatementItem, String> colCfName;
    @FXML private TableColumn<FinancialStatementItem, String> colCfAmount;

    @FXML private ComboBox<Account> glAccountCombo;
    @FXML private TableView<GeneralLedgerItem> glTable;
    @FXML private TableColumn<GeneralLedgerItem, String> colGlDate;
    @FXML private TableColumn<GeneralLedgerItem, String> colGlVoucherNo;
    @FXML private TableColumn<GeneralLedgerItem, String> colGlSummary;
    @FXML private TableColumn<GeneralLedgerItem, String> colGlMemo;
    @FXML private TableColumn<GeneralLedgerItem, String> colGlDebit;
    @FXML private TableColumn<GeneralLedgerItem, String> colGlCredit;
    @FXML private TableColumn<GeneralLedgerItem, String> colGlBalance;

    @FXML private DatePicker reportStartDatePicker;
    @FXML private DatePicker reportEndDatePicker;

    @FXML private TextField ftsField;
    @FXML private TableView<Voucher> ftsTable;
    @FXML private TableColumn<Voucher, String> colFtsDate;
    @FXML private TableColumn<Voucher, String> colFtsNo;
    @FXML private TableColumn<Voucher, String> colFtsSummary;
    @FXML private TableColumn<Voucher, String> colFtsStatus;
    @FXML private TableColumn<Voucher, String> colFtsAmount;

    @FXML private TableView<AuditLog> auditTable;
    @FXML private TableColumn<AuditLog, String> colAuditId;
    @FXML private TableColumn<AuditLog, String> colAuditAction;
    @FXML private TableColumn<AuditLog, String> colAuditTarget;
    @FXML private TableColumn<AuditLog, String> colAuditRecordId;
    @FXML private TableColumn<AuditLog, String> colAuditOperator;
    @FXML private TableColumn<AuditLog, String> colAuditOldData;
    @FXML private TableColumn<AuditLog, String> colAuditNewData;
    @FXML private TableColumn<AuditLog, String> colAuditTime;

    private VoucherService voucherService;
    private VoucherDao voucherDao;
    private AccountDao accountDao;
    private FiscalPeriodDao periodDao;
    private AuditDao auditDao;
    private ClosingService closingService;
    private BackupService backupService;
    private ReportService reportService;
    private AttachmentService attachmentService;
    private FtsDao ftsDao;
    @FXML
    public void initialize() {
        voucherDao = new VoucherDao();
        accountDao = new AccountDao();
        periodDao = new FiscalPeriodDao();
        auditDao = new AuditDao();
        voucherService = new VoucherService(voucherDao, periodDao, auditDao);
        closingService = new ClosingService(periodDao, auditDao);
        backupService = new BackupService();
        reportService = new ReportService();
        attachmentService = new AttachmentService();
        ftsDao = new FtsDao();

        initClientAndOperator();
        initVoucherColumns();
        initAccountColumns();
        initPeriodColumns();
        initReportColumns();
        initFtsColumns();
        initAuditColumns();

        // 預設報表查詢區間為當月 1 日 ~ 今天，提升使用體驗
        LocalDate today = LocalDate.now();
        reportStartDatePicker.setValue(today.withDayOfMonth(1));
        reportEndDatePicker.setValue(today);

        loadAllData();
    }

    private void initClientAndOperator() {
        // 客戶帳套已在登入後選擇畫面決定，這裡顯示目前帳套並允許切換
        String currentClient = SessionContext.getClientPath();
        if (currentClient == null || currentClient.isBlank()) {
            currentClient = "data/client_A.db";
        }
        clientCombo.setItems(FXCollections.observableArrayList(
                "data/client_A.db",
                "data/client_B.db",
                "data/client_C.db"
        ));
        if (!clientCombo.getItems().contains(currentClient)) {
            clientCombo.getItems().add(currentClient);
        }
        clientCombo.setValue(currentClient);
        clientCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && !newVal.equals(oldVal)) {
                switchClient(newVal);
            }
        });

        // 操作員預設為登入使用者；仍可切換以方便口試示範職責分離
        operatorCombo.setItems(FXCollections.observableArrayList(
                "Amy (記帳員/制單人)",
                "Bob (會計師/審核人)",
                "David (會計師/審核人)",
                "Charlie (查帳員/審計員)"
        ));
        String loginDisplay = SessionContext.getUserDisplay();
        if (loginDisplay != null && operatorCombo.getItems().contains(loginDisplay)) {
            operatorCombo.setValue(loginDisplay);
        } else {
            String user = SessionContext.getUser();
            if ("Bob".equalsIgnoreCase(user)) {
                operatorCombo.setValue("Bob (會計師/審核人)");
            } else if ("David".equalsIgnoreCase(user)) {
                operatorCombo.setValue("David (會計師/審核人)");
            } else if ("Charlie".equalsIgnoreCase(user)) {
                operatorCombo.setValue("Charlie (查帳員/審計員)");
            } else {
                operatorCombo.setValue("Amy (記帳員/制單人)");
            }
        }

        voucherStatusFilter.setItems(FXCollections.observableArrayList("ALL", "DRAFT", "POSTED", "VOID"));
        voucherStatusFilter.setValue("ALL");
        voucherStatusFilter.valueProperty().addListener((o, ov, nv) -> loadVouchers());

        updateDbPathDisplay();
    }

    private String getSelectedOperator() {
        String val = operatorCombo.getValue();
        if (val == null) return "Unknown";
        return val.split(" ")[0].trim();
    }

    private void updateDbPathDisplay() {
        dbPathLabel.setText("目前帳套: " + DbManager.getCurrentDbPath());
    }

    private void switchClient(String dbPath) {
        try {
            DbManager.switchDatabase(dbPath);
            updateDbPathDisplay();
            statusLabel.setText("已成功切換至帳套: " + dbPath);
            loadAllData();
        } catch (SQLException e) {
            showError("切換帳套失敗", e.getMessage());
        }
    }

    @FXML
    public void onNewClient() {
        TextInputDialog dialog = new TextInputDialog("client_new.db");
        dialog.setTitle("新增客戶帳套");
        dialog.setHeaderText("請輸入新客戶帳套檔名（一個客戶一個 .db 檔案）");
        dialog.setContentText("檔名:");
        dialog.showAndWait().ifPresent(name -> {
            String sanitized = name.trim();
            if (!sanitized.endsWith(".db")) {
                sanitized += ".db";
            }
            String path = "data/" + sanitized;
            if (!clientCombo.getItems().contains(path)) {
                clientCombo.getItems().add(path);
            }
            clientCombo.setValue(path);
        });
    }

    @FXML
    public void onBackupClient() {
        try {
            Path dbFile = Paths.get(DbManager.getCurrentDbPath());
            Path backupDir = Paths.get("backup");
            Path target = backupService.backup(dbFile, backupDir, getSelectedOperator());
            showInfo("備份成功", "資料庫已通過完整性驗證並備份至:\n" + target.toAbsolutePath());
            statusLabel.setText("備份完成: " + target.getFileName());
            loadAudits();
        } catch (Exception e) {
            showError("備份失敗", e.getMessage());
        }
    }

    @FXML
    public void onRestoreClient() {
        OperatorRole role = OperatorRole.resolve(getSelectedOperator());
        if (role != OperatorRole.CHECKER) { showWarn("權限不足", "資料庫還原涉及資料覆蓋，僅會計師（審核人）有權操作。"); return; }
        FileChooser chooser = new FileChooser();
        chooser.setTitle("選擇還原資料庫備份檔");
        chooser.setInitialDirectory(new File("backup"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("SQLite Database (*.db)", "*.db"));
        File selected = chooser.showOpenDialog(null);
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "還原將覆蓋目前帳套檔案，確認要繼續嗎？", ButtonType.YES, ButtonType.NO);
            confirm.setTitle("確認還原");
            confirm.showAndWait().ifPresent(res -> {
                if (res == ButtonType.YES) {
                    try {
                        Path current = Paths.get(DbManager.getCurrentDbPath());
                        backupService.restore(selected.toPath(), current, getSelectedOperator());
                        showInfo("還原成功", "資料庫已成功恢復，並通過 PRAGMA integrity_check 驗證！");
                        loadAllData();
                    } catch (Exception e) {
                        showError("還原失敗", e.getMessage());
                    }
                }
            });
        }
    }
    private void initVoucherColumns() {
        colVoucherNo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().voucherNo()));
        colVoucherDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().voucherDate()));
        colSummary.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().summary()));
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status()));
        colPreparer.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().preparer()));
        colReviewer.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().reviewer() == null ? "-" : d.getValue().reviewer()));
        colTotalDebit.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().totalDebitMinor())));
        colTotalCredit.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().totalCreditMinor())));
        colReversedId.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().reversedId() == null ? "" : "沖銷 #" + d.getValue().reversedId()));

        colEntryLine.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().lineNo())));
        colEntryCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().accountCode()));
        colEntryName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().accountName()));
        colEntryMemo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().memo()));
        colEntryDebit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().debitMinor() > 0 ? MoneyUtil.format(d.getValue().debitMinor()) : ""));
        colEntryCredit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().creditMinor() > 0 ? MoneyUtil.format(d.getValue().creditMinor()) : ""));

        voucherTable.getSelectionModel().selectedItemProperty().addListener((obs, oldV, newV) -> {
            if (newV != null) {
                entryTable.setItems(FXCollections.observableArrayList(newV.entries()));
            } else {
                entryTable.getItems().clear();
            }
        });

        // 雙擊草稿傳票可直接開啟編輯
        voucherTable.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                onEditVoucher();
            }
        });
    }

    private void initAccountColumns() {
        colAccCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().code()));
        colAccName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
        colAccType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().type()));
        colAccSide.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().normalSide()));
        colAccStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().enabled() ? "正常" : "停用"));
    }

    private void initPeriodColumns() {
        colPeriodYear.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().year())));
        colPeriodMonth.setCellValueFactory(d -> new SimpleStringProperty(String.format("%02d 月", d.getValue().month())));
        colPeriodStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().isOpen() ? "未結帳 (OPEN)" : "已結帳 (CLOSED)"));
    }

    private void initReportColumns() {
        colTbCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().accountCode()));
        colTbName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().accountName()));
        colTbType.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().accountType()));
        colTbDebit.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().totalDebitMinor())));
        colTbCredit.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().totalCreditMinor())));
        colTbNet.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().netMinor())));

        colBsSection.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().section()));
        colBsCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().code()));
        colBsName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
        colBsAmount.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().amountMinor())));

        colIsSection.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().section()));
        colIsCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().code()));
        colIsName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
        colIsAmount.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().amountMinor())));

        colEqSection.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().section()));
        colEqCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().code()));
        colEqName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
        colEqAmount.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().amountMinor())));

        colCfSection.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().section()));
        colCfCode.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().code()));
        colCfName.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().name()));
        colCfAmount.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().amountMinor())));

        colGlDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().voucherDate()));
        colGlVoucherNo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().voucherNo()));
        colGlSummary.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().summary()));
        colGlMemo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().memo()));
        colGlDebit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().debitMinor() > 0 ? MoneyUtil.format(d.getValue().debitMinor()) : ""));
        colGlCredit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().creditMinor() > 0 ? MoneyUtil.format(d.getValue().creditMinor()) : ""));
        colGlBalance.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().balanceMinor())));
    }

    private void initFtsColumns() {
        colFtsDate.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().voucherDate()));
        colFtsNo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().voucherNo()));
        colFtsSummary.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().summary()));
        colFtsStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().status()));
        colFtsAmount.setCellValueFactory(d -> new SimpleStringProperty(MoneyUtil.format(d.getValue().totalDebitMinor())));
    }

    private void initAuditColumns() {
        colAuditId.setCellValueFactory(d -> new SimpleStringProperty(String.valueOf(d.getValue().id())));
        colAuditAction.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().action()));
        colAuditTarget.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().target()));
        colAuditRecordId.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().recordId() == null ? "" : String.valueOf(d.getValue().recordId())));
        colAuditOperator.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().operator()));
        colAuditOldData.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().oldData()));
        colAuditNewData.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().newData()));
        colAuditTime.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().createdAt()));
    }

    public void loadAllData() {
        loadVouchers();
        loadAccounts();
        loadPeriods();
        loadReports();
        loadAudits();
    }

    @FXML
    public void loadVouchers() {
        try (Connection conn = DbManager.get()) {
            String status = voucherStatusFilter.getValue();
            String keyword = voucherKeywordFilter.getText();
            List<Voucher> list = voucherDao.findAll(conn, status, null, null, keyword);
            voucherTable.setItems(FXCollections.observableArrayList(list));
            if (!list.isEmpty()) {
                voucherTable.getSelectionModel().select(0);
            } else {
                entryTable.getItems().clear();
            }
        } catch (SQLException e) {
            showError("載入傳票清單失敗", e.getMessage());
        }
    }

    @FXML
    public void onNewVoucher() {
        OperatorRole role = OperatorRole.resolve(getSelectedOperator());
        if (role == OperatorRole.AUDITOR) { showWarn("權限不足", "查帳員為唯讀身分，無權新增傳票。"); return; }
        if (role == OperatorRole.CHECKER) { showWarn("職責分離限制", "會計師（審核人）不得兼任制單人建立一般傳票。請切換至記帳員建立！"); return; }
        openVoucherEditorDialog(null);
    }

    @FXML
    public void onEditVoucher() {
        Voucher selected = voucherTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarn("提示", "請先選取要修改的草稿傳票");
            return;
        }
        if (!"DRAFT".equalsIgnoreCase(selected.status())) {
            showWarn("提示", "僅草稿狀態的傳票可以修改。已過帳憑證請使用紅字沖銷。");
            return;
        }
        OperatorRole role = OperatorRole.resolve(getSelectedOperator());
        if (role == OperatorRole.AUDITOR) {
            showWarn("權限不足", "查帳員為唯讀身分，無權修改傳票。");
            return;
        }
        if (role == OperatorRole.CHECKER) {
            showWarn("職責分離限制", "會計師（審核人）無權修改制單草稿。請切換至記帳員進行修改！");
            return;
        }
        openVoucherEditorDialog(selected);
    }

    @FXML
    public void onPostVoucher() {
        Voucher selected = voucherTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarn("提示", "請先選取要過帳的傳票");
            return;
        }

        String operator = getSelectedOperator();
        OperatorRole role = OperatorRole.resolve(operator);
        if (role == OperatorRole.MAKER) { showWarn("職責分離限制", "當前身分為記帳員（制單人），無權執行審核過帳。\n請切換至會計師進行審核！"); return; }
        if (role == OperatorRole.AUDITOR) { showWarn("權限不足", "查帳員為唯讀身分，無權審核過帳。"); return; }
        try {
            voucherService.post(selected.id(), operator);
            showInfo("過帳成功", "傳票 " + selected.voucherNo() + " 已成功過帳！\n審核人: " + operator);
            loadVouchers();
            loadReports();
            loadAudits();
        } catch (Exception e) {
            showError("過帳受阻", e.getMessage());
        }
    }

    @FXML
    public void onReverseVoucher() {
        Voucher selected = voucherTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarn("提示", "請先選取要沖銷的已過帳傳票");
            return;
        }

        TextInputDialog dialog = new TextInputDialog("更正分錄/原分錄登打錯誤");
        dialog.setTitle("紅字沖銷");
        dialog.setHeaderText("針對傳票 " + selected.voucherNo() + " 進行紅字沖銷（原憑證保留，產生對沖憑證）");
        dialog.setContentText("沖銷原因:");
        dialog.showAndWait().ifPresent(reason -> {
            try {
                Voucher revDraft = voucherService.reverse(selected.id(), reason, getSelectedOperator());
                showInfo("沖銷成功", "已產生對沖傳票草稿: " + revDraft.voucherNo() + "\n摘要: " + revDraft.summary());
                loadVouchers();
                loadAudits();
            } catch (Exception e) {
                showError("沖銷失敗", e.getMessage());
            }
        });
    }

    @FXML
    public void onDeleteVoucher() {
        Voucher selected = voucherTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarn("提示", "請先選取要刪除的草稿傳票");
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION, "確定要刪除傳票 " + selected.voucherNo() + " 嗎？", ButtonType.YES, ButtonType.NO);
        confirm.showAndWait().ifPresent(res -> {
            if (res == ButtonType.YES) {
                try {
                    voucherService.deleteDraft(selected.id(), getSelectedOperator());
                    showInfo("刪除完成", "已刪除傳票: " + selected.voucherNo());
                    loadVouchers();
                    loadAudits();
                } catch (Exception e) {
                    showError("刪除失敗", e.getMessage());
                }
            }
        });
    }
    private void openVoucherEditorDialog(Voucher existing) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(existing == null ? "新增會計傳票" : "修改草稿傳票");

        VBox root = new VBox(12);
        root.setPadding(new Insets(16));

        GridPane headerGrid = new GridPane();
        headerGrid.setHgap(10);
        headerGrid.setVgap(8);

        DatePicker datePicker = new DatePicker(existing == null ? LocalDate.now() : LocalDate.parse(existing.voucherDate()));
        TextField summaryField = new TextField(existing == null ? "" : existing.summary());
        summaryField.setPromptText("例如：銷售商品取得現金");
        TextField preparerField = new TextField(existing == null ? getSelectedOperator() : existing.preparer()); preparerField.setDisable(true);

        headerGrid.add(new Label("傳票日期:"), 0, 0);
        headerGrid.add(datePicker, 1, 0);
        headerGrid.add(new Label("制單人:"), 2, 0);
        headerGrid.add(preparerField, 3, 0);
        headerGrid.add(new Label("傳票摘要:"), 0, 1);
        headerGrid.add(summaryField, 1, 1, 3, 1);

        TableView<EditableEntry> table = new TableView<>();
        ObservableList<EditableEntry> entryList = FXCollections.observableArrayList();

        List<Account> allAccounts = new ArrayList<>();
        try {
            allAccounts = accountDao.findAll();
        } catch (SQLException ignored) {
        }

        TableColumn<EditableEntry, String> colAcc = new TableColumn<>("會計科目");
        colAcc.setPrefWidth(220);
        List<Account> finalAllAccounts = allAccounts;
        colAcc.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().getAccountDisplay()));

        TableColumn<EditableEntry, String> colMemo = new TableColumn<>("分錄摘要/備註");
        colMemo.setPrefWidth(200);
        colMemo.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().memo));

        TableColumn<EditableEntry, String> colDebit = new TableColumn<>("借方金額");
        colDebit.setPrefWidth(120);
        colDebit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().debit.compareTo(BigDecimal.ZERO) > 0 ? MoneyUtil.format(d.getValue().debit) : ""));

        TableColumn<EditableEntry, String> colCredit = new TableColumn<>("貸方金額");
        colCredit.setPrefWidth(120);
        colCredit.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().credit.compareTo(BigDecimal.ZERO) > 0 ? MoneyUtil.format(d.getValue().credit) : ""));

        table.getColumns().addAll(colAcc, colMemo, colDebit, colCredit);
        table.setItems(entryList);

        if (existing != null) {
            for (VoucherEntry e : existing.entries()) {
                Account acc = finalAllAccounts.stream().filter(a -> a.id().equals(e.accountId())).findFirst().orElse(null);
                entryList.add(new EditableEntry(acc, e.memo(), e.debit(), e.credit()));
            }
        } else {
            if (finalAllAccounts.size() >= 2) {
                entryList.add(new EditableEntry(finalAllAccounts.get(0), "收取現金", new BigDecimal("1000.00"), BigDecimal.ZERO));
                entryList.add(new EditableEntry(finalAllAccounts.get(15), "銷貨收入", BigDecimal.ZERO, new BigDecimal("1000.00")));
            }
        }

        Label balanceLabel = new Label();
        Runnable updateBalance = () -> {
            BigDecimal totalDebit = entryList.stream().map(e -> e.debit).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal totalCredit = entryList.stream().map(e -> e.credit).reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal diff = totalDebit.subtract(totalCredit);
            if (totalDebit.compareTo(totalCredit) == 0 && entryList.size() >= 2 && totalDebit.compareTo(BigDecimal.ZERO) > 0) {
                balanceLabel.setText(String.format("✔ 借貸平衡：借方 %s = 貸方 %s（分錄數：%d）", MoneyUtil.format(totalDebit), MoneyUtil.format(totalCredit), entryList.size()));
                balanceLabel.setStyle("-fx-text-fill: #166534; -fx-font-weight: bold; -fx-background-color: #dcfce7; -fx-padding: 6px 12px; -fx-background-radius: 4px;");
            } else {
                balanceLabel.setText(String.format("✖ 借貸不平衡：借方 %s / 貸方 %s (差額: %s)", MoneyUtil.format(totalDebit), MoneyUtil.format(totalCredit), MoneyUtil.format(diff)));
                balanceLabel.setStyle("-fx-text-fill: #991b1b; -fx-font-weight: bold; -fx-background-color: #fee2e2; -fx-padding: 6px 12px; -fx-background-radius: 4px;");
            }
        };
        updateBalance.run();

        Button btnAddEntry = new Button("+ 新增分錄");
        btnAddEntry.setOnAction(e -> {
            showEntryEditDialog(finalAllAccounts, null, newEntry -> {
                entryList.add(newEntry);
                updateBalance.run();
            });
        });

        Button btnRemoveEntry = new Button("- 移除選取");
        btnRemoveEntry.setOnAction(e -> {
            EditableEntry sel = table.getSelectionModel().getSelectedItem();
            if (sel != null) {
                entryList.remove(sel);
                updateBalance.run();
            }
        });

        HBox entryBtnBox = new HBox(8, btnAddEntry, btnRemoveEntry);

        Button btnSave = new Button("儲存草稿");
        btnSave.getStyleClass().add("button-success");
        btnSave.setOnAction(e -> {
            if (datePicker.getValue() == null) {
                showWarn("提示", "請選擇傳票日期");
                return;
            }
            if (summaryField.getText().trim().isEmpty()) {
                showWarn("提示", "請輸入傳票摘要");
                return;
            }
            if (entryList.size() < 2) {
                showWarn("提示", "複式簿記至少需兩筆分錄");
                return;
            }

            List<VoucherEntry> entries = new ArrayList<>();
            int line = 1;
            for (EditableEntry ee : entryList) {
                if (ee.account == null) {
                    showWarn("提示", "分錄科目不可為空");
                    return;
                }
                entries.add(new VoucherEntry(line++, ee.account.id(), ee.memo, ee.debit, ee.credit));
            }

            try {
                if (existing == null) {
                    voucherService.createDraft(datePicker.getValue().toString(), summaryField.getText(), preparerField.getText(), entries);
                    showInfo("成功", "已成功建立傳票草稿！");
                } else {
                    voucherService.updateDraft(existing.id(), datePicker.getValue().toString(), summaryField.getText(), preparerField.getText(), entries);
                    showInfo("成功", "已更新傳票草稿！");
                }
                stage.close();
                loadVouchers();
                loadAudits();
            } catch (Exception ex) {
                showError("儲存失敗", ex.getMessage());
            }
        });

        Button btnCancel = new Button("取消");
        btnCancel.setOnAction(e -> stage.close());
        HBox bottomBox = new HBox(12, btnSave, btnCancel);
        bottomBox.setAlignment(Pos.CENTER_RIGHT);

        root.getChildren().addAll(headerGrid, entryBtnBox, table, balanceLabel, bottomBox);
        stage.setScene(new Scene(root, 720, 520));
        stage.showAndWait();
    }

    private void showEntryEditDialog(List<Account> accounts, EditableEntry initial, java.util.function.Consumer<EditableEntry> onSave) {
        Stage stage = new Stage();
        stage.initModality(Modality.APPLICATION_MODAL);
        stage.setTitle(initial == null ? "新增分錄" : "修改分錄");

        GridPane grid = new GridPane();
        grid.setPadding(new Insets(16));
        grid.setHgap(10);
        grid.setVgap(10);

        ComboBox<Account> accCombo = new ComboBox<>(FXCollections.observableArrayList(accounts));
        accCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Account item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplayName());
            }
        });
        accCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(Account item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getDisplayName());
            }
        });
        if (!accounts.isEmpty()) accCombo.setValue(accounts.get(0));

        TextField memoField = new TextField();
        TextField debitField = new TextField("0.00");
        TextField creditField = new TextField("0.00");

        grid.add(new Label("會計科目:"), 0, 0);
        grid.add(accCombo, 1, 0);
        grid.add(new Label("備註摘要:"), 0, 1);
        grid.add(memoField, 1, 1);
        grid.add(new Label("借方金額:"), 0, 2);
        grid.add(debitField, 1, 2);
        grid.add(new Label("貸方金額:"), 0, 3);
        grid.add(creditField, 1, 3);

        Button btnOk = new Button("確認");
        btnOk.setOnAction(e -> {
            try {
                BigDecimal d = new BigDecimal(debitField.getText().trim().replace(",", ""));
                BigDecimal c = new BigDecimal(creditField.getText().trim().replace(",", ""));
                if (d.compareTo(BigDecimal.ZERO) > 0 && c.compareTo(BigDecimal.ZERO) > 0) {
                    showWarn("提示", "同一分錄不可同時有借方與貸方金額");
                    return;
                }
                if (d.compareTo(BigDecimal.ZERO) == 0 && c.compareTo(BigDecimal.ZERO) == 0) {
                    showWarn("提示", "借方或貸方金額必須有一方大於 0");
                    return;
                }
                onSave.accept(new EditableEntry(accCombo.getValue(), memoField.getText().trim(), d, c));
                stage.close();
            } catch (Exception ex) {
                showError("輸入格式錯誤", "請輸入正確的金額數值");
            }
        });

        Button btnCancel = new Button("取消");
        btnCancel.setOnAction(e -> stage.close());
        HBox box = new HBox(8, btnOk, btnCancel);
        box.setAlignment(Pos.CENTER_RIGHT);
        grid.add(box, 1, 4);

        stage.setScene(new Scene(grid, 380, 240));
        stage.showAndWait();
    }

    @FXML
    public void loadAccounts() {
        try {
            List<Account> list = accountDao.findAll();
            accountTable.setItems(FXCollections.observableArrayList(list));
            glAccountCombo.setItems(FXCollections.observableArrayList(list));
            if (!list.isEmpty()) {
                glAccountCombo.setValue(list.get(0));
            }
        } catch (SQLException e) {
            showError("載入會計科目失敗", e.getMessage());
        }
    }

    @FXML
    public void onNewAccount() {
        Dialog<Account> dialog = new Dialog<>();
        dialog.setTitle("新增會計科目");
        dialog.setHeaderText("建立新會計科目（符合一級編碼與性別規則）");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        TextField codeField = new TextField();
        codeField.setPromptText("例如：1103");
        TextField nameField = new TextField();
        nameField.setPromptText("例如：零用金");

        ComboBox<String> typeCombo = new ComboBox<>(FXCollections.observableArrayList(
                "ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE"
        ));
        typeCombo.setValue("ASSET");

        ComboBox<String> sideCombo = new ComboBox<>(FXCollections.observableArrayList(
                "DEBIT", "CREDIT"
        ));
        sideCombo.setValue("DEBIT");

        grid.add(new Label("科目代碼:"), 0, 0);
        grid.add(codeField, 1, 0);
        grid.add(new Label("科目名稱:"), 0, 1);
        grid.add(nameField, 1, 1);
        grid.add(new Label("科目類別:"), 0, 2);
        grid.add(typeCombo, 1, 2);
        grid.add(new Label("正常借貸方:"), 0, 3);
        grid.add(sideCombo, 1, 3);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.setResultConverter(b -> {
            if (b == ButtonType.OK) {
                try {
                    AccountService accService = new AccountService(accountDao, auditDao);
                    return accService.createAccount(
                            codeField.getText(), nameField.getText(),
                            typeCombo.getValue(), sideCombo.getValue(),
                            null, getSelectedOperator()
                    );
                } catch (Exception e) {
                    showError("新增失敗", e.getMessage());
                }
            }
            return null;
        });

        dialog.showAndWait().ifPresent(acc -> {
            showInfo("新增成功", "科目 " + acc.code() + " " + acc.name() + " 已成功建立！");
            loadAccounts();
            loadAudits();
        });
    }

    @FXML
    public void loadPeriods() {
        try {
            List<FiscalPeriod> list = periodDao.findAll();
            periodTable.setItems(FXCollections.observableArrayList(list));
        } catch (SQLException e) {
            showError("載入會計期間失敗", e.getMessage());
        }
    }

    @FXML
    public void onClosePeriod() {
        OperatorRole role = OperatorRole.resolve(getSelectedOperator());
        if (role != OperatorRole.CHECKER) { showWarn("權限不足", "期末結帳涉及帳簿鎖定，僅會計師（審核人）有權操作。"); return; }
        FiscalPeriod selected = periodTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarn("提示", "請先選取要結帳的會計期間");
            return;
        }

        try {
            closingService.closePeriod(selected.year(), selected.month(), getSelectedOperator());
            showInfo("結帳完成", String.format("%d年%02d月 期間已成功結帳鎖定！", selected.year(), selected.month()));
            loadPeriods();
            loadAudits();
        } catch (Exception e) {
            showError("結帳未通過", e.getMessage());
        }
    }

    @FXML
    public void onReopenPeriod() {
        OperatorRole role = OperatorRole.resolve(getSelectedOperator());
        if (role != OperatorRole.CHECKER) { showWarn("權限不足", "反結帳操作僅會計師（審核人）有權執行。"); return; }
        FiscalPeriod selected = periodTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarn("提示", "請先選取要反結帳的會計期間");
            return;
        }

        try {
            closingService.reopenPeriod(selected.year(), selected.month(), getSelectedOperator());
            showInfo("反結帳成功", String.format("%d年%02d月 期間已重新開啟！", selected.year(), selected.month()));
            loadPeriods();
            loadAudits();
        } catch (Exception e) {
            showError("反結帳失敗", e.getMessage());
        }
    }

    @FXML
    public void loadReports() {
        String startDate = reportStartDatePicker.getValue() == null ? null : reportStartDatePicker.getValue().toString();
        String endDate = reportEndDatePicker.getValue() == null ? null : reportEndDatePicker.getValue().toString();

        tbTable.setItems(FXCollections.observableArrayList(reportService.getTrialBalance(startDate, endDate)));
        bsTable.setItems(FXCollections.observableArrayList(reportService.getBalanceSheet(endDate)));
        isTable.setItems(FXCollections.observableArrayList(reportService.getIncomeStatement(startDate, endDate)));
        eqTable.setItems(FXCollections.observableArrayList(reportService.getEquityChanges(startDate, endDate)));
        cfTable.setItems(FXCollections.observableArrayList(reportService.getCashFlow(startDate, endDate)));

        Account glAcc = glAccountCombo.getValue();
        if (glAcc != null) {
            glTable.setItems(FXCollections.observableArrayList(reportService.getGeneralLedger(glAcc.id(), startDate, endDate)));
        }
    }

    @FXML
    public void onExportReportCsv() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle("匯出試算表 CSV");
        chooser.setInitialFileName("Trial_Balance_" + LocalDate.now() + ".csv");
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("CSV 檔案 (*.csv)", "*.csv"));
        File file = chooser.showSaveDialog(null);
        if (file != null) {
            try {
                String startDate = reportStartDatePicker.getValue() == null ? null : reportStartDatePicker.getValue().toString();
                String endDate = reportEndDatePicker.getValue() == null ? null : reportEndDatePicker.getValue().toString();
                reportService.exportTrialBalanceCsv(startDate, endDate, file.toPath());
                showInfo("匯出成功", "試算表已成功匯出至:\n" + file.getAbsolutePath());
            } catch (Exception e) {
                showError("匯出失敗", e.getMessage());
            }
        }
    }

    @FXML
    public void onFtsSearch() {
        String query = ftsField.getText();
        if (query == null || query.trim().isEmpty()) {
            ftsTable.getItems().clear();
            return;
        }

        try {
            List<Voucher> list = ftsDao.search(query.trim());
            ftsTable.setItems(FXCollections.observableArrayList(list));
            statusLabel.setText("全文檢索找到 " + list.size() + " 筆符合傳票（SQLite FTS5 加速）");
        } catch (SQLException e) {
            showError("全文檢索異常", e.getMessage());
        }
    }

    @FXML
    public void loadAudits() {
        try {
            List<AuditLog> list = auditDao.findAll(200);
            auditTable.setItems(FXCollections.observableArrayList(list));
        } catch (SQLException e) {
            showError("載入審計日誌失敗", e.getMessage());
        }
    }

    @FXML
    public void onLogout() {
        SessionContext.clear();
        try {
            DbManager.closeCurrent();
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(getClass().getResource("/fxml/login.fxml"));
            javafx.scene.Parent root = loader.load();
            javafx.stage.Stage stage = (javafx.stage.Stage) statusLabel.getScene().getWindow();
            stage.setTitle("登入 — 會計師專業記帳系統");
            stage.setScene(new javafx.scene.Scene(root, 420, 480));
            stage.setResizable(false);
            stage.centerOnScreen();
        } catch (Exception e) {
            showError("登出失敗", e.getMessage());
        }
    }

    @FXML
    public void onSwitchClient() {
        try {
            javafx.fxml.FXMLLoader loader = new javafx.fxml.FXMLLoader(getClass().getResource("/fxml/clientSelect.fxml"));
            javafx.scene.Parent root = loader.load();
            javafx.stage.Stage stage = (javafx.stage.Stage) statusLabel.getScene().getWindow();
            stage.setTitle("選擇客戶帳套");
            stage.setScene(new javafx.scene.Scene(root, 480, 420));
            stage.setResizable(false);
            stage.centerOnScreen();
        } catch (Exception e) {
            showError("切換帳套畫面失敗", e.getMessage());
        }
    }


    @FXML
    public void onManageAttachments() {
        Voucher selected = voucherTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showWarn("請先選取", "請先選擇一張傳票再管理原始憑證。");
            return;
        }
        try {
            var list = attachmentService.listByVoucher(selected.id());
            StringBuilder sb = new StringBuilder();
            sb.append("傳票 ").append(selected.voucherNo()).append(" 的原始憑證附件\n\n");
            if (list.isEmpty()) {
                sb.append("（尚無附件）\n");
            } else {
                for (var a : list) {
                    sb.append("#").append(a.id()).append("  ")
                      .append(a.fileName()).append("  (")
                      .append(a.fileSize()).append(" bytes)  ")
                      .append(a.uploadedBy()).append("  ")
                      .append(a.uploadedAt()).append("\n");
                }
            }
            sb.append("\n請選擇操作：");

            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("原始憑證");
            alert.setHeaderText(sb.toString());
            ButtonType upload = new ButtonType("上傳附件");
            ButtonType openDir = new ButtonType("開啟資料夾");
            ButtonType del = new ButtonType("刪除所選附件");
            ButtonType cancel = new ButtonType("關閉", ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(upload, openDir, del, cancel);

            var result = alert.showAndWait();
            if (result.isEmpty() || result.get() == cancel) return;

            if (result.get() == upload) {
                FileChooser chooser = new FileChooser();
                chooser.setTitle("選擇原始憑證檔案（PDF / 圖片）");
                chooser.getExtensionFilters().addAll(
                        new FileChooser.ExtensionFilter("文件與圖片", "*.pdf", "*.png", "*.jpg", "*.jpeg", "*.gif"),
                        new FileChooser.ExtensionFilter("所有檔案", "*.*")
                );
                File file = chooser.showOpenDialog(statusLabel.getScene().getWindow());
                if (file != null) {
                    attachmentService.addAttachment(selected.id(), file.toPath(), null, getSelectedOperator());
                    showInfo("上傳成功", "已掛載原始憑證：\n" + file.getName());
                    statusLabel.setText("已上傳原始憑證至傳票 " + selected.voucherNo());
                }
            } else if (result.get() == openDir) {
                Path dir = Paths.get("attachments");
                if (!Files.exists(dir)) Files.createDirectories(dir);
                if (java.awt.Desktop.isDesktopSupported()) {
                    java.awt.Desktop.getDesktop().open(dir.toFile());
                } else {
                    showInfo("附件目錄", dir.toAbsolutePath().toString());
                }
            } else if (result.get() == del) {
                if (list.isEmpty()) {
                    showWarn("無附件", "此傳票尚無附件可刪除。");
                    return;
                }
                TextInputDialog dialog = new TextInputDialog();
                dialog.setTitle("刪除附件");
                dialog.setHeaderText("請輸入要刪除的附件編號（id）");
                dialog.setContentText("附件 ID:");
                dialog.showAndWait().ifPresent(idStr -> {
                    try {
                        long id = Long.parseLong(idStr.trim());
                        attachmentService.deleteAttachment(id, getSelectedOperator());
                        showInfo("已刪除", "附件 #" + id + " 已刪除");
                    } catch (Exception ex) {
                        showError("刪除失敗", ex.getMessage());
                    }
                });
            }
        } catch (Exception e) {
            showError("原始憑證操作失敗", e.getMessage());
        }
    }

    private void showInfo(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.INFORMATION, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private void showWarn(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.WARNING, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }

    private void showError(String title, String msg) {
        Alert a = new Alert(Alert.AlertType.ERROR, msg, ButtonType.OK);
        a.setTitle(title);
        a.setHeaderText(null);
        a.showAndWait();
    }

    public static class EditableEntry {
        public Account account;
        public String memo;
        public BigDecimal debit;
        public BigDecimal credit;

        public EditableEntry(Account account, String memo, BigDecimal debit, BigDecimal credit) {
            this.account = account;
            this.memo = memo == null ? "" : memo;
            this.debit = debit == null ? BigDecimal.ZERO : debit;
            this.credit = credit == null ? BigDecimal.ZERO : credit;
        }

        public String getAccountDisplay() {
            return account == null ? "" : account.getDisplayName();
        }
    }
}


