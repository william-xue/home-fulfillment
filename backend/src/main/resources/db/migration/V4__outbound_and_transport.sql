ALTER TABLE fulfillment_order
    DROP CHECK fulfillment_order_chk_1,
    ADD CONSTRAINT chk_fulfillment_status CHECK (status IN ('READY_TO_PICK','PICKING','PICKED','DISPATCHED','CANCELLED'));

ALTER TABLE stock_reservation
    DROP CHECK stock_reservation_chk_2,
    ADD CONSTRAINT chk_reservation_status CHECK (status IN ('ACTIVE','RELEASED','CONSUMED'));

CREATE TABLE pick_task (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    task_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    reservation_id BIGINT NOT NULL UNIQUE,
    status VARCHAR(16) NOT NULL CHECK (status IN ('PENDING','PICKED','CANCELLED')),
    picked_by BIGINT,
    picked_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY(reservation_id) REFERENCES stock_reservation(id),
    FOREIGN KEY(picked_by) REFERENCES app_user(id)
);

INSERT INTO pick_task(task_no,reservation_id,status)
SELECT CONCAT('PK-',UUID()),r.id,CASE r.status WHEN 'ACTIVE' THEN 'PENDING' ELSE 'CANCELLED' END
FROM stock_reservation r
WHERE r.status IN ('ACTIVE','RELEASED')
  AND NOT EXISTS(SELECT 1 FROM pick_task p WHERE p.reservation_id=r.id);

CREATE TABLE dispatch (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    dispatch_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    fulfillment_id BIGINT NOT NULL UNIQUE,
    actor_id BIGINT NOT NULL,
    dispatched_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    FOREIGN KEY(fulfillment_id) REFERENCES fulfillment_order(id),
    FOREIGN KEY(actor_id) REFERENCES app_user(id)
);

CREATE TABLE shipment (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL UNIQUE,
    dispatch_id BIGINT NOT NULL UNIQUE,
    carrier VARCHAR(16) COLLATE utf8mb4_bin NOT NULL,
    tracking_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    status VARCHAR(16) NOT NULL CHECK (status IN ('CREATED','IN_TRANSIT','DELIVERED')),
    delivered_at DATETIME(3),
    created_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    UNIQUE(carrier,tracking_no),
    FOREIGN KEY(dispatch_id) REFERENCES dispatch(id)
);

CREATE TABLE tracking_event (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    shipment_id BIGINT NOT NULL,
    source_event_no VARCHAR(64) COLLATE utf8mb4_bin NOT NULL,
    type VARCHAR(16) NOT NULL CHECK (type IN ('IN_TRANSIT','DELIVERED')),
    occurred_at DATETIME(3) NOT NULL,
    recorded_at DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    actor_id BIGINT NOT NULL,
    UNIQUE(shipment_id,source_event_no),
    FOREIGN KEY(shipment_id) REFERENCES shipment(id),
    FOREIGN KEY(actor_id) REFERENCES app_user(id),
    INDEX ix_tracking_time(shipment_id,occurred_at,id)
);
