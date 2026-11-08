package com.ledger.controller;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
import com.ledger.service.AuthService;
import javafx.collections.FXCollections;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class ClientSelectController {

    @FXML private Label userLabel;
    @FXML private ListView<String> clientList;
    @FXML private TextField newClientField;
    @FXML private Label errorLabel;

    private final List<String> clients = new ArrayList<>();
    private final AuthService authService = new AuthService();

    @FXML
    public void initialize() {
        // 管理員不可進入帳套選擇／會計系統，導回管理畫面
        if (authService.isAdmin(SessionContext.getUser())) {
            javafx.application.Platform.runLater(this::redirectAdminToPanel);
            return;
        }

        userLabel.setText("目前登入：" + SessionContext.getUserDisplay());
        errorLabel.setText("");

        clients.add("data/client_A.db");
        clients.add("data/client_B.db");
        clients.add("data/client_C.db");

        try {
            Path dataDir = Paths.get("data");
            if (Files.isDirectory(dataDir)) {
                Files.list(dataDir)
                        .filter(p -> p.getFileName().toString().endsWith(".db"))
                        .filter(p -> !p.getFileName().toString().contains("-wal"))
                        .filter(p -> !p.getFileName().toString().contains("-shm"))
                        .forEach(p -> {
                            String path = "data/" + p.getFileName().toString();
                            if (!clients.contains(path)) {
                                clients.add(path);
                            }
                        });
            }
        } catch (Exception ignored) {
        }

        clientList.setItems(FXCollections.observableArrayList(clients));
        if (!clients.isEmpty()) {
            clientList.getSelectionModel().select(0);
        }

        clientList.setOnMouseClicked(e -> {
            if (e.getClickCount() == 2) {
                onEnter();
            }
        });
    }

    private void redirectAdminToPanel() {
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/admin.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) clientList.getScene().getWindow();
            stage.setTitle("系統管理員 — 人員帳號管理");
            stage.setScene(new Scene(root, 720, 520));
            stage.setResizable(true);
            stage.centerOnScreen();
        } catch (Exception e) {
            if (errorLabel != null) {
                errorLabel.setText("管理員不可進入會計系統");
            }
        }
    }

    @FXML
    public void onAddClient() {
        if (authService.isAdmin(SessionContext.getUser())) {
            errorLabel.setText("管理員不可操作帳套");
            return;
        }
        errorLabel.setText("");
        String name = newClientField.getText();
        if (name == null || name.trim().isEmpty()) {
            errorLabel.setText("請輸入新帳套檔名");
            return;
        }
        String sanitized = name.trim();
        if (!sanitized.endsWith(".db")) {
            sanitized += ".db";
        }
        if (sanitized.contains("..") || sanitized.contains("/") || sanitized.contains("\\")) {
            errorLabel.setText("檔名不可包含路徑字元");
            return;
        }
        String path = "data/" + sanitized;
        if (!clients.contains(path)) {
            clients.add(path);
            clientList.setItems(FXCollections.observableArrayList(clients));
        }
        clientList.getSelectionModel().select(path);
        newClientField.clear();
    }

    @FXML
    public void onEnter() {
        if (authService.isAdmin(SessionContext.getUser())) {
            errorLabel.setText("管理員不可進入會計系統，僅能管理人員帳號");
            return;
        }
        errorLabel.setText("");
        String selected = clientList.getSelectionModel().getSelectedItem();
        if (selected == null || selected.isBlank()) {
            errorLabel.setText("請先選擇一個客戶帳套");
            return;
        }

        try {
            DbManager.switchDatabase(selected);
            SessionContext.setClientPath(selected);

            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) clientList.getScene().getWindow();
            stage.setTitle("會計師專業記帳系統 — " + selected);
            stage.setScene(new Scene(root, 1180, 800));
            stage.setResizable(true);
            stage.centerOnScreen();
        } catch (Exception e) {
            errorLabel.setText("開啟帳套失敗：" + e.getMessage());
        }
    }

    @FXML
    public void onLogout() {
        SessionContext.clear();
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
            Parent root = loader.load();
            Stage stage = (Stage) clientList.getScene().getWindow();
            stage.setTitle("登入 — 會計師專業記帳系統");
            stage.setScene(new Scene(root, 420, 500));
            stage.setResizable(false);
            stage.centerOnScreen();
        } catch (Exception e) {
            errorLabel.setText("返回登入失敗：" + e.getMessage());
        }
    }
}
