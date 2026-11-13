package com.ledger.controller;

import com.ledger.config.SessionContext;
import com.ledger.exception.BusinessException;
import com.ledger.service.AuthService;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Stage;

public class LoginController {

    @FXML private TextField usernameField;
    @FXML private PasswordField passwordField;
    @FXML private Label errorLabel;

    private final AuthService authService = new AuthService();

    @FXML
    public void initialize() {
        errorLabel.setText("");
        usernameField.requestFocus();
    }

    @FXML
    public void onLogin() {
        errorLabel.setText("");
        try {
            String canonical = authService.login(usernameField.getText(), passwordField.getText());
            String display = authService.getDisplayName(canonical);

            if (authService.mustChangePassword(canonical)) {
                if (!promptChangePassword(canonical)) {
                    errorLabel.setText("必須變更密碼後才能進入系統");
                    SessionContext.clear();
                    return;
                }
            }

            Stage stage = (Stage) usernameField.getScene().getWindow();

            if (authService.isAdmin(canonical)) {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/admin.fxml"));
                Parent root = loader.load();
                stage.setTitle("系統管理員 — 人員帳號管理");
                stage.setScene(new Scene(root, 720, 520));
                stage.setResizable(true);
            } else {
                FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/clientSelect.fxml"));
                Parent root = loader.load();
                stage.setTitle("選擇客戶帳套");
                stage.setScene(new Scene(root, 480, 420));
                stage.setResizable(false);
            }
            stage.centerOnScreen();
        } catch (BusinessException e) {
            errorLabel.setText(e.getMessage());
            passwordField.clear();
            passwordField.requestFocus();
        } catch (Exception e) {
            errorLabel.setText("登入失敗：" + e.getMessage());
        }
    }

    private boolean promptChangePassword(String username) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle("強制變更密碼");
        dialog.setHeaderText("此帳號必須變更密碼後才能繼續");

        PasswordField oldPf = new PasswordField();
        oldPf.setPromptText("目前密碼");
        PasswordField newPf = new PasswordField();
        newPf.setPromptText("新密碼（至少 6 字元）");
        PasswordField confirmPf = new PasswordField();
        confirmPf.setPromptText("確認新密碼");

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        grid.addRow(0, new Label("目前密碼"), oldPf);
        grid.addRow(1, new Label("新密碼"), newPf);
        grid.addRow(2, new Label("確認新密碼"), confirmPf);
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        var result = dialog.showAndWait();
        if (result.isEmpty() || result.get() != ButtonType.OK) {
            return false;
        }
        if (!newPf.getText().equals(confirmPf.getText())) {
            errorLabel.setText("兩次新密碼不一致");
            return false;
        }
        try {
            authService.changePassword(username, oldPf.getText(), newPf.getText());
            return true;
        } catch (BusinessException e) {
            errorLabel.setText(e.getMessage());
            return false;
        }
    }
}
