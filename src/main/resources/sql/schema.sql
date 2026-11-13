-- ========== 0. 系統元資料與序號表 ==========
CREATE TABLE IF NOT EXISTS system_meta (
    meta_key   TEXT PRIMARY KEY,
    meta_value TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS voucher_sequences (
    prefix   TEXT PRIMARY KEY,
    last_seq INTEGER NOT NULL
);

-- ========== 1. 會計科目 ==========
CREATE TABLE IF NOT EXISTS accounts (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    code        TEXT    NOT NULL UNIQUE,
    name        TEXT    NOT NULL,
    type        TEXT    NOT NULL CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
    normal_side TEXT    NOT NULL CHECK (normal_side IN ('DEBIT','CREDIT')),
    parent_id   INTEGER REFERENCES accounts(id),
    is_leaf     INTEGER NOT NULL DEFAULT 1 CHECK (is_leaf IN (0,1)),
    enabled     INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0,1))
);
CREATE INDEX IF NOT EXISTS idx_accounts_parent ON accounts(parent_id);

-- ========== 2. 會計期間 ==========
CREATE TABLE IF NOT EXISTS fiscal_periods (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    year     INTEGER NOT NULL,
    month    INTEGER NOT NULL CHECK (month BETWEEN 1 AND 12),
    status   TEXT    NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN','CLOSED')),
    UNIQUE (year, month)
);

-- ========== 3. 傳票主檔 ==========
CREATE TABLE IF NOT EXISTS vouchers (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    period_id    INTEGER NOT NULL REFERENCES fiscal_periods(id),
    voucher_no   TEXT    NOT NULL,
    voucher_date TEXT    NOT NULL,
    summary      TEXT    NOT NULL,
    status       TEXT    NOT NULL DEFAULT 'DRAFT' CHECK (status IN ('DRAFT','POSTED','VOID')),
    preparer     TEXT    NOT NULL,
    reviewer     TEXT,
    reversed_id  INTEGER REFERENCES vouchers(id),
    created_at   TEXT    NOT NULL DEFAULT (datetime('now','localtime')),
    posted_at    TEXT,
    voucher_type TEXT    NOT NULL DEFAULT 'NORMAL',
    closing_year INTEGER,
    UNIQUE (period_id, voucher_no)
);
CREATE INDEX IF NOT EXISTS idx_vouchers_date ON vouchers(voucher_date);
CREATE INDEX IF NOT EXISTS idx_vouchers_status ON vouchers(status);

-- ========== 4. 傳票分錄明細 ==========
CREATE TABLE IF NOT EXISTS voucher_entries (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    voucher_id   INTEGER NOT NULL REFERENCES vouchers(id) ON DELETE CASCADE,
    line_no      INTEGER NOT NULL,
    account_id   INTEGER NOT NULL REFERENCES accounts(id),
    memo         TEXT,
    debit_minor  INTEGER NOT NULL DEFAULT 0 CHECK (debit_minor  >= 0),
    credit_minor INTEGER NOT NULL DEFAULT 0 CHECK (credit_minor >= 0),
    CHECK (NOT (debit_minor > 0 AND credit_minor > 0)),
    CHECK (debit_minor + credit_minor > 0),
    UNIQUE (voucher_id, line_no)
);
CREATE INDEX IF NOT EXISTS idx_entries_voucher ON voucher_entries(voucher_id);
CREATE INDEX IF NOT EXISTS idx_entries_account ON voucher_entries(account_id);

-- ========== 5. 審計日誌 ==========
CREATE TABLE IF NOT EXISTS audit_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    action      TEXT NOT NULL,
    target      TEXT NOT NULL,
    record_id   INTEGER,
    operator    TEXT,
    old_data    TEXT,
    new_data    TEXT,
    created_at  TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

-- ========== 6. FTS5 全文檢索 ==========
CREATE VIRTUAL TABLE IF NOT EXISTS vouchers_fts
USING fts5(summary, voucher_no UNINDEXED, content='vouchers', content_rowid='id', tokenize='trigram');

-- FTS 同步觸發器
CREATE TRIGGER IF NOT EXISTS trg_vouchers_fts_ai AFTER INSERT ON vouchers BEGIN
    INSERT INTO vouchers_fts(rowid, summary, voucher_no) VALUES (new.id, new.summary, new.voucher_no);
END;

CREATE TRIGGER IF NOT EXISTS trg_vouchers_fts_ad AFTER DELETE ON vouchers BEGIN
    INSERT INTO vouchers_fts(vouchers_fts, rowid, summary, voucher_no) VALUES ('delete', old.id, old.summary, old.voucher_no);
END;

CREATE TRIGGER IF NOT EXISTS trg_vouchers_fts_au AFTER UPDATE ON vouchers BEGIN
    INSERT INTO vouchers_fts(vouchers_fts, rowid, summary, voucher_no) VALUES ('delete', old.id, old.summary, old.voucher_no);
    INSERT INTO vouchers_fts(rowid, summary, voucher_no) VALUES (new.id, new.summary, new.voucher_no);
END;

-- ========== 7. 審計與安全觸發器 ==========
-- (1) 已過帳憑證禁止刪除
CREATE TRIGGER IF NOT EXISTS trg_voucher_no_delete
BEFORE DELETE ON vouchers
WHEN OLD.status = 'POSTED'
BEGIN
    SELECT RAISE(ABORT, '已過帳憑證不得刪除，請使用紅字沖銷');
END;

-- (2) 傳票主檔防竄改（已過帳時禁止修改任何欄位；草稿階段過帳時除外）
CREATE TRIGGER IF NOT EXISTS trg_voucher_no_update
BEFORE UPDATE ON vouchers
WHEN OLD.status = 'POSTED'
BEGIN
    SELECT RAISE(ABORT, '已過帳憑證完全唯讀，嚴禁修改任何欄位，請使用紅字沖銷');
END;

-- (3) 傳票狀態機保護：嚴禁從 POSTED 回退到 DRAFT 或 VOID，亦嚴禁從 VOID 復原
CREATE TRIGGER IF NOT EXISTS trg_voucher_status_lock
BEFORE UPDATE OF status ON vouchers
WHEN (OLD.status = 'POSTED' AND NEW.status <> 'POSTED')
  OR (OLD.status = 'VOID' AND NEW.status <> 'VOID')
BEGIN
    SELECT RAISE(ABORT, '違反憑證狀態機規則：已過帳憑證禁止回退或變更狀態');
END;

-- (4) 過帳瞬間強制借貸平衡 + 至少兩筆分錄
CREATE TRIGGER IF NOT EXISTS trg_voucher_balance_on_post
BEFORE UPDATE OF status ON vouchers
WHEN NEW.status = 'POSTED' AND OLD.status = 'DRAFT'
BEGIN
    SELECT CASE WHEN
        (SELECT COALESCE(SUM(debit_minor),0)  FROM voucher_entries WHERE voucher_id = NEW.id)
     <> (SELECT COALESCE(SUM(credit_minor),0) FROM voucher_entries WHERE voucher_id = NEW.id)
    THEN RAISE(ABORT, '借貸不平衡，無法過帳') END;

    SELECT CASE WHEN
        (SELECT COUNT(*) FROM voucher_entries WHERE voucher_id = NEW.id) < 2
    THEN RAISE(ABORT, '複式簿記至少需兩筆分錄') END;
END;

-- (5) 分錄保護：已過帳傳票嚴禁新增分錄
CREATE TRIGGER IF NOT EXISTS trg_entry_no_insert_on_posted
BEFORE INSERT ON voucher_entries
WHEN (SELECT status FROM vouchers WHERE id = NEW.voucher_id) <> 'DRAFT'
BEGIN
    SELECT RAISE(ABORT, '非草稿傳票嚴禁新增分錄');
END;

-- (6) 分錄保護：已過帳傳票嚴禁修改分錄
CREATE TRIGGER IF NOT EXISTS trg_entry_no_update_on_posted
BEFORE UPDATE ON voucher_entries
WHEN (SELECT status FROM vouchers WHERE id = OLD.voucher_id) <> 'DRAFT'
BEGIN
    SELECT RAISE(ABORT, '非草稿傳票嚴禁修改分錄內容');
END;

-- (7) 分錄保護：已過帳傳票嚴禁刪除分錄
CREATE TRIGGER IF NOT EXISTS trg_entry_no_delete_on_posted
BEFORE DELETE ON voucher_entries
WHEN (SELECT status FROM vouchers WHERE id = OLD.voucher_id) <> 'DRAFT'
BEGIN
    SELECT RAISE(ABORT, '非草稿傳票嚴禁刪除分錄');
END;

-- (8) 已結帳期間禁止新增憑證
CREATE TRIGGER IF NOT EXISTS trg_period_locked
BEFORE INSERT ON vouchers
WHEN (SELECT status FROM fiscal_periods WHERE id = NEW.period_id) = 'CLOSED'
BEGIN
    SELECT RAISE(ABORT, '該會計期間已結帳鎖定，不得新增憑證');
END;

-- (9) 審計日誌本身不可被竄改
CREATE TRIGGER IF NOT EXISTS trg_audit_immutable_update
BEFORE UPDATE ON audit_log
BEGIN
    SELECT RAISE(ABORT, '審計日誌為唯讀，禁止修改或刪除');
END;

CREATE TRIGGER IF NOT EXISTS trg_audit_immutable_delete
BEFORE DELETE ON audit_log
BEGIN
    SELECT RAISE(ABORT, '審計日誌為唯讀，禁止修改或刪除');
END;

-- ========== 8. 預設標準會計科目種子資料 ==========
INSERT OR IGNORE INTO accounts (code, name, type, normal_side, is_leaf, enabled) VALUES
('1101', '庫存現金', 'ASSET', 'DEBIT', 1, 1),
('1102', '銀行存款', 'ASSET', 'DEBIT', 1, 1),
('1103', '零用金', 'ASSET', 'DEBIT', 1, 1),
('1104', '約當現金', 'ASSET', 'DEBIT', 1, 1),
('1122', '應收帳款', 'ASSET', 'DEBIT', 1, 1),
('1141', '預付款項', 'ASSET', 'DEBIT', 1, 1),
('1201', '存貨', 'ASSET', 'DEBIT', 1, 1),
('1401', '不動產、廠房及設備', 'ASSET', 'DEBIT', 1, 1),
('1402', '累計折舊', 'ASSET', 'CREDIT', 1, 1),
('1501', '無形資產', 'ASSET', 'DEBIT', 1, 1),
('1502', '累計攤銷', 'ASSET', 'CREDIT', 1, 1),
('2101', '短期借款', 'LIABILITY', 'CREDIT', 1, 1),
('2121', '應付票據', 'LIABILITY', 'CREDIT', 1, 1),
('2122', '應付帳款', 'LIABILITY', 'CREDIT', 1, 1),
('2123', '應付設備款', 'LIABILITY', 'CREDIT', 1, 1),
('2124', '應付薪資', 'LIABILITY', 'CREDIT', 1, 1),
('2125', '應付稅捐', 'LIABILITY', 'CREDIT', 1, 1),
('2201', '長期借款', 'LIABILITY', 'CREDIT', 1, 1),
('3101', '普通股股本', 'EQUITY', 'CREDIT', 1, 1),
('3201', '資本公積', 'EQUITY', 'CREDIT', 1, 1),
('3301', '保留盈餘', 'EQUITY', 'CREDIT', 1, 1),
('3302', '本期損益', 'EQUITY', 'CREDIT', 1, 1),
('4101', '銷貨收入', 'REVENUE', 'CREDIT', 1, 1),
('4102', '勞務收入', 'REVENUE', 'CREDIT', 1, 1),
('4201', '其他營業收入', 'REVENUE', 'CREDIT', 1, 1),
('4202', '處分資產利益', 'REVENUE', 'CREDIT', 1, 1),
('5101', '銷貨成本', 'EXPENSE', 'DEBIT', 1, 1),
('5201', '薪資支出', 'EXPENSE', 'DEBIT', 1, 1),
('5202', '租金支出', 'EXPENSE', 'DEBIT', 1, 1),
('5203', '水電瓦斯費', 'EXPENSE', 'DEBIT', 1, 1),
('5204', '郵電費', 'EXPENSE', 'DEBIT', 1, 1),
('5205', '差旅費', 'EXPENSE', 'DEBIT', 1, 1),
('5206', '文具用品', 'EXPENSE', 'DEBIT', 1, 1),
('5207', '稅捐', 'EXPENSE', 'DEBIT', 1, 1),
('5208', '折舊費用', 'EXPENSE', 'DEBIT', 1, 1),
('5209', '攤銷費用', 'EXPENSE', 'DEBIT', 1, 1),
('5301', '雜項支出', 'EXPENSE', 'DEBIT', 1, 1),
('5302', '處分資產損失', 'EXPENSE', 'DEBIT', 1, 1);
