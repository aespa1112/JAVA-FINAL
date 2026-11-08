package com.ledger.controller;

import com.ledger.config.SessionContext;
import com.ledger.exception.BusinessException;
import com.ledger.model.OperatorRole;
import com.ledger.service.AuthService;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.stage.Stage;

import java.util.List;

public class AdminController {

    @FXML private Label adminLabel;
    @FXML private TableView<AuthService.UserAccount> userTable;
    @FXML private TableColumn<AuthService.UserAccount, String> colUser;
    @FXML private TableColumn<AuthService.UserAccount, String> colDisplay;
    @FXML private TableColumn<AuthService.UserAccount, String> colRole;
    @FXML private TableColumn<AuthService.UserAccount, String> colStatus;

    @FXML private TextField newUsername;
    @FXML private PasswordField newPassword;
    @FXML private TextField newDisplay;
    @FXML private ComboBox<OperatorRole> newRole;
    @FXML private Label msgLabel;

    private final AuthService authService = new AuthService();

    @FXML
    public void initialize() {
        adminLabel.setText("目前管理員：" + SessionContext.getUserDisplay());
        msgLabel.setText("");

        colUser.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().username()));
        colDisplay.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().displayName()));
        colRole.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().role().toChinese()));
        colStatus.setCellValueFactory(d -> new SimpleStringProperty(d.getValue().enabled() ? "啟用" : "停用"));

        newRole.setItems(FXCollections.observableArrayList(
                OperatorRole.MAKER, OperatorRole.CHECKER, OperatorRole.AUDITOR
        ));
        newRole.setValue(OperatorRole.MAKER);
        newRole.setCellFactory(lv -> new ListCell<>() {
            @Override protected void updateItem(OperatorRole item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.toChinese());
            }
        });
        newRole.setButtonCell(new ListCell<>() {
            @Override protected void updateItem(OperatorRole item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.toChinese());
            }
        });

        onRefresh();
    }

    @FXML
    public void onRefresh() {
        try {
            List<AuthService.UserAccount> list = authService.listUsers();
            userTable.setItems(FXCollections.observableArrayList(list));
            msgLabel.setText("共 " + list.size() + " 位人員");
            msgLabel.setStyle("-fx-text-fill: #94a3b8;");
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    @FXML
    public void onCreateUser() {
        try {
            authService.createUser(
                    newUsername.getText(),
                    newPassword.getText(),
                    newDisplay.getText(),
                    newRole.getValue(),
                    SessionContext.getUser()
            );
            newUsername.clear();
            newPassword.clear();
            newDisplay.clear();
            msgLabel.setText("新增成功");
            msgLabel.setStyle("-fx-text-fill: #86efac;");
            onRefresh();
        } catch (BusinessException e) {
            showError(e.getMessage());
        } catch (Exception e) {
            showError("新增失敗：" + e.getMessage());
        }
    }

    @FXML
    public void onEnable() {
        AuthService.UserAccount sel = userTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            showError("請先選取帳號");
            return;
        }
        try {
            authService.setEnabled(sel.username(), true, SessionContext.getUser());
            msgLabel.setText("已啟用 " + sel.username());
            msgLabel.setStyle("-fx-text-fill: #86efac;");
            onRefresh();
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    @FXML
    public void onDisable() {
        AuthService.UserAccount sel = userTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            showError("請先選取帳號");
            return;
        }
        try {
            authService.setEnabled(sel.username(), false, SessionContext.getUser());
            msgLabel.setText("已停用 " + sel.username());
            msgLabel.setStyle("-fx-text-fill: #fbbf24;");
            onRefresh();
        } catch (Exception e) {
            showError(e.getMessage());
        }
    }

    @FXML
    public void onResetPassword() {
        AuthService.UserAccount sel = userTable.getSelectionModel().getSelectedItem();
        if (sel == null) {
            showError("請先選取帳號");
            return;
        }
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("重設密碼");
        dialog.setHeaderText("為帳號 「" + sel.username() + "」 設定新密碼");
        dialog.setContentText("新密碼：");
        dialog.showAndWait().ifPresent(pwd -> {
            try {
                authService.resetPassword(sel.username(), pwd, SessionContext.getUser());
                msgLabel.setText("已重設 " + sel.username() + " 的密碼");
                msgLabel.setStyle("-fx-text-fill: #86efac;");
            } catch (Exception e) {
                showError(e.getMessage());
            }
        });
    }

    @FXML
    public void onEnterSystem() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/clientSelect.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) adminLabel.getScene().getWindow();
            stage.setTitle("選擇客戶帳套");
            stage.setScene(new Scene(root, 480, 420));
            stage.setResizable(false);
            stage.centerOnScreen();
        } catch (Exception e) {
            showError("進入系統失敗：" + e.getMessage());
        }
    }

    @FXML
    public void onLogout() {
        SessionContext.clear();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) adminLabel.getScene().getWindow();
            stage.setTitle("登入 — 會計師專業記帳系統");
            stage.setScene(new Scene(root, 420, 500));
            stage.setResizable(false);
            stage.centerOnScreen();
        } catch (Exception e) {
            showError("登出失敗：" + e.getMessage());
        }
    }

    private void showError(String msg) {
        msgLabel.setText(msg);
        msgLabel.setStyle("-fx-text-fill: #f87171;");
    }
}
