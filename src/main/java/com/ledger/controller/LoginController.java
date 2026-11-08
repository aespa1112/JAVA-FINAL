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

            // 進入客戶帳套選擇畫面
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/clientSelect.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) usernameField.getScene().getWindow();
            stage.setTitle("選擇客戶帳套");
            stage.setScene(new Scene(root, 480, 420));
            stage.centerOnScreen();
        } catch (BusinessException e) {
            errorLabel.setText(e.getMessage());
            passwordField.clear();
            passwordField.requestFocus();
        } catch (Exception e) {
            errorLabel.setText("登入失敗: " + e.getMessage());
        }
    }
}
