CREATE TABLE app_user (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    username VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(16) NOT NULL CHECK (role IN ('OPS','WAREHOUSE','SUPPORT')),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
);
CREATE TABLE user_warehouse (
    user_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    PRIMARY KEY(user_id,warehouse_id),
    FOREIGN KEY(user_id) REFERENCES app_user(id),
    FOREIGN KEY(warehouse_id) REFERENCES warehouse(id)
);
CREATE TABLE purchase_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    warehouse_id BIGINT NOT NULL,
    supplier_name VARCHAR(120) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('OPEN','PARTIAL','RECEIVED')),
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY(warehouse_id) REFERENCES warehouse(id),
    FOREIGN KEY(created_by) REFERENCES app_user(id),
    INDEX ix_po_warehouse_status(warehouse_id,status,id)
);
CREATE TABLE purchase_order_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    po_id BIGINT NOT NULL,
    line_no INT NOT NULL CHECK (line_no>0),
    sku_id BIGINT NOT NULL,
    ordered_qty BIGINT NOT NULL CHECK (ordered_qty>0),
    received_qty BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CHECK (received_qty BETWEEN 0 AND ordered_qty),
    UNIQUE(po_id,line_no),
    UNIQUE(po_id,sku_id),
    FOREIGN KEY(po_id) REFERENCES purchase_order(id),
    FOREIGN KEY(sku_id) REFERENCES sku(id)
);
CREATE TABLE receipt (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    receipt_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    po_line_id BIGINT NOT NULL,
    receiving_bin_id BIGINT NOT NULL,
    qty BIGINT NOT NULL CHECK (qty>0),
    put_away_qty BIGINT NOT NULL DEFAULT 0,
    actor_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CHECK (put_away_qty BETWEEN 0 AND qty),
    FOREIGN KEY(po_line_id) REFERENCES purchase_order_line(id),
    FOREIGN KEY(receiving_bin_id) REFERENCES bin(id),
    FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE TABLE put_away (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    put_away_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    receipt_id BIGINT NOT NULL,
    destination_bin_id BIGINT NOT NULL,
    qty BIGINT NOT NULL CHECK (qty>0),
    actor_id BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY(receipt_id) REFERENCES receipt(id),
    FOREIGN KEY(destination_bin_id) REFERENCES bin(id),
    FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE TABLE stock_balance (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    sku_id BIGINT NOT NULL,
    bin_id BIGINT NOT NULL,
    on_hand BIGINT NOT NULL DEFAULT 0,
    reserved BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    CHECK (reserved BETWEEN 0 AND on_hand),
    UNIQUE(sku_id,bin_id),
    FOREIGN KEY(sku_id) REFERENCES sku(id),
    FOREIGN KEY(bin_id) REFERENCES bin(id)
);
CREATE TABLE stock_movement (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    balance_id BIGINT NOT NULL,
    movement_type VARCHAR(24) NOT NULL,
    biz_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    delta_on_hand BIGINT NOT NULL,
    delta_reserved BIGINT NOT NULL,
    on_hand_after BIGINT NOT NULL,
    reserved_after BIGINT NOT NULL,
    actor_id BIGINT NOT NULL,
    occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    CHECK (delta_on_hand<>0 OR delta_reserved<>0),
    CHECK (reserved_after BETWEEN 0 AND on_hand_after),
    UNIQUE(movement_type,biz_no,balance_id),
    INDEX ix_movement_biz(biz_no,id),
    INDEX ix_movement_balance(balance_id,id),
    FOREIGN KEY(balance_id) REFERENCES stock_balance(id),
    FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE TABLE command_receipt (
    actor_id BIGINT NOT NULL,
    operation VARCHAR(32) COLLATE utf8mb4_bin NOT NULL,
    idempotency_key VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    request_hash CHAR(64) NOT NULL,
    http_status INT,
    result_json JSON,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    PRIMARY KEY(actor_id,operation,idempotency_key),
    FOREIGN KEY(actor_id) REFERENCES app_user(id)
);
CREATE TABLE operation_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    action VARCHAR(32) NOT NULL,
    entity_type VARCHAR(32) NOT NULL,
    entity_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    actor_key VARCHAR(64) NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    occurred_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    meta_json JSON NOT NULL,
    INDEX ix_operation_entity(entity_type,entity_no,id)
);
