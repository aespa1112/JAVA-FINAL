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
        // 啟動時檢查並恢復未完成的年度結帳狀態
        new com.ledger.service.YearEndClosingService().checkAndRecoverOnStartup();

        // 啟動時進入登入畫面
        FXMLLoader loader = new FXMLLoader(getClass().getResource("/fxml/login.fxml"));
        Parent root = loader.load();

        Scene scene = new Scene(root, 420, 500);
        primaryStage.setTitle("登入 — 會計師專業記帳系統");
        primaryStage.setScene(scene);
        primaryStage.setResizable(false);
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
