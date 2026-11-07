package com.ledger;

import com.ledger.config.DbManager;
import javafx.application.Application;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.stage.Stage;

public class App extends Application {

    @Override
    public void start(Stage primaryStage) throws Exception {
        // 預設初始化第一個客戶帳套
        DbManager.switchDatabase("data/client_A.db");

        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/main.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 1180, 800);
        primaryStage.setTitle("會計師專業記帳與審計軌跡系統 (Professional Bookkeeping & Audit System)");
        primaryStage.setScene(scene);
        primaryStage.show();
    }

    @Override
    public void stop() {
        DbManager.closeCurrent();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
