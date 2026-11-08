package com.ledger.controller;

import com.ledger.config.SessionContext;
import com.ledger.exception.BusinessException;
import com.ledger.service.AuthService;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
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
            SessionContext.setUser(canonical, display);

            Stage stage = (Stage) usernameField.getScene().getWindow();

            // 管理員進入人員管理；一般使用者進入客戶帳套選擇
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
}
