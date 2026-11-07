@echo off
chcp 65001 > nul
echo [會計師專業記帳與審計軌跡系統] 正在啟動...

where mvn >nul 2>nul
if %ERRORLEVEL% equ 0 (
    mvn javafx:run
    goto end
)

if exist "C:\Users\USER\tools\apache-maven-3.9.9\bin\mvn.cmd" (
    "C:\Users\USER\tools\apache-maven-3.9.9\bin\mvn.cmd" javafx:run
    goto end
)

echo [錯誤] 找不到 Maven (mvn) 指令，請確認已安裝 Maven 或將 mvn 加入 PATH 環境變數。
pause

:end
