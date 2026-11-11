# 會計資料處理作業手冊（JAVA-FINAL）

> 本文件描述本系統作業程序。系統為教學／實作原型，**不得宣稱已通過正式法規合規認證**。

## 1. 資料輸入授權規範
- 登入後始得操作；正式環境（預設 `LEDGER_ENV=PRODUCTION`）禁止匿名。
- 角色：ADMIN（僅人員管理）、MAKER（制單）、CHECKER（審核／結帳）、AUDITOR（唯讀）。
- 制單與審核不得為同一人（職責分離）。
- 停用帳號立即使 Session 失效。

## 2. 會計科目代碼對照（摘要）
| 代碼前綴 | 類型 |
|---------|------|
| 1xxx | 資產 ASSET |
| 2xxx | 負債 LIABILITY |
| 3xxx | 權益 EQUITY（3301 保留盈餘、3302 本期損益） |
| 4xxx | 收入 REVENUE |
| 5xxx | 費用 EXPENSE |

僅末級（is_leaf）且啟用科目可入帳。

## 3. 傳票與過帳
1. MAKER 建立 DRAFT（借貸必須平衡）。
2. CHECKER 審核過帳 → POSTED（不可刪改，僅紅字沖銷）。
3. 已結帳期間禁止新增／調整草稿至該期間。

## 4. 月結與年結
- 月結：CHECKER 鎖定 `fiscal_periods`。
- 年結前：十二個月均須 CLOSED、無 DRAFT、無未完成沖銷草稿、年度結帳紀錄唯一。
- 年結產生 `voucher_type=YEAR_END_CLOSE` 傳票，並寫入 `year_end_closings` 狀態機。
- 反結：僅 CHECKER；後續年度已結者須先反結；產生紅字草稿待第二人過帳。

## 5. 備份復原程序
1. 使用系統「一鍵備份」或 `BackupService.backup`。
2. 還原前確認僅 CHECKER 操作，並先備份現行庫。
3. 還原後執行 `PRAGMA integrity_check`。

## 6. 資料保存程序
- 帳套檔：`data/client_*.db`；系統帳號：`data/system_users.db`。
- 建議定期離線備份；保留年限依所適用法規自行訂定（見 RetentionPolicyService）。

## 7. 系統定期稽核程序
- 執行「報表勾稽檢查」：試算借貸平衡、試算 vs 總帳、資負平衡、損益 vs 3302。
- 抽查審計日誌 `audit_log` 與 `data/auth_audit.log`。
- 檢視登入失敗鎖定與停用帳號。

## 8. 原始憑證管理
- 傳票可掛載附件（AttachmentService）。
- 附件與傳票 ID 關聯保存，刪除須留審計軌跡。
