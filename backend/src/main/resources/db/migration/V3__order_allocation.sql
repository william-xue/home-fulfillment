CREATE TABLE channel_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    channel VARCHAR(16) COLLATE utf8mb4_bin NOT NULL CHECK (channel IN ('DIRECT','DOUYIN')),
    channel_order_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    paid_at DATETIME(3) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    route_status VARCHAR(16) NOT NULL CHECK (route_status IN ('RECEIVED','ALLOCATED','SHORTAGE','CANCELLED')),
    shortage_json JSON,
    route_attempt INT NOT NULL DEFAULT 0 CHECK (route_attempt>=0),
    cancel_reason VARCHAR(200),
    cancelled_at DATETIME(3),
    created_by BIGINT NOT NULL,
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE(channel,channel_order_no),
    FOREIGN KEY(created_by) REFERENCES app_user(id),
    INDEX ix_order_route_status(route_status,id)
);

CREATE TABLE channel_order_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_id BIGINT NOT NULL,
    line_no INT NOT NULL CHECK (line_no>0),
    sku_id BIGINT NOT NULL,
    ordered_qty BIGINT NOT NULL CHECK (ordered_qty>0),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE(order_id,line_no),
    UNIQUE(order_id,sku_id),
    FOREIGN KEY(order_id) REFERENCES channel_order(id),
    FOREIGN KEY(sku_id) REFERENCES sku(id)
);

CREATE TABLE fulfillment_order (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fulfillment_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    order_id BIGINT NOT NULL,
    warehouse_id BIGINT NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('READY_TO_PICK','CANCELLED')),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE(order_id,warehouse_id),
    FOREIGN KEY(order_id) REFERENCES channel_order(id),
    FOREIGN KEY(warehouse_id) REFERENCES warehouse(id),
    INDEX ix_fulfillment_warehouse_status(warehouse_id,status,id)
);

CREATE TABLE fulfillment_line (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    fulfillment_id BIGINT NOT NULL,
    order_line_id BIGINT NOT NULL,
    qty BIGINT NOT NULL CHECK (qty>0),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE(fulfillment_id,order_line_id),
    FOREIGN KEY(fulfillment_id) REFERENCES fulfillment_order(id),
    FOREIGN KEY(order_line_id) REFERENCES channel_order_line(id)
);

CREATE TABLE stock_reservation (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    reservation_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    fulfillment_line_id BIGINT NOT NULL,
    balance_id BIGINT NOT NULL,
    qty BIGINT NOT NULL CHECK (qty>0),
    status VARCHAR(16) NOT NULL CHECK (status IN ('ACTIVE','RELEASED')),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
    UNIQUE(fulfillment_line_id,balance_id),
    FOREIGN KEY(fulfillment_line_id) REFERENCES fulfillment_line(id),
    FOREIGN KEY(balance_id) REFERENCES stock_balance(id),
    INDEX ix_reservation_balance_status(balance_id,status)
);
