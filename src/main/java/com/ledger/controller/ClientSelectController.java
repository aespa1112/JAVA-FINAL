package com.ledger.controller;

import com.ledger.config.DbManager;
import com.ledger.config.SessionContext;
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

    @FXML
    public void initialize() {
        userLabel.setText("目前登入：" + SessionContext.getUserDisplay());
        errorLabel.setText("");

        clients.add("data/client_A.db");
        clients.add("data/client_B.db");
        clients.add("data/client_C.db");

        // 掃描 data 目錄既有 .db
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
            // 目錄不存在時略過
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

    @FXML
    public void onAddClient() {
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
        // 簡單檔名防護
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
            errorLabel.setText("開啟帳套失敗：