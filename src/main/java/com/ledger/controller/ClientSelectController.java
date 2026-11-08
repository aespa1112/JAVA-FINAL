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
        userLabel.setText("目前登入：