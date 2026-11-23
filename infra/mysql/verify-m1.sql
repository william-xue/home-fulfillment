-- DataGrip: use home_fulfillment as hf_app. Read-only; change @po_no to follow another PO.
SET @po_no = 'PO-HOME-001';

SELECT p.po_no, w.code AS warehouse_code, p.status, l.line_no, s.code AS sku_code,
       l.ordered_qty, l.received_qty, l.ordered_qty - l.received_qty AS remaining_qty
FROM purchase_order p
JOIN purchase_order_line l ON l.po_id = p.id
JOIN warehouse w ON w.id = p.warehouse_id
JOIN sku s ON s.id = l.sku_id
WHERE p.po_no = @po_no
ORDER BY l.line_no;

SELECT r.receipt_no, l.line_no, src.code AS receiving_bin, r.qty AS received,
       r.put_away_qty, r.qty - r.put_away_qty AS awaiting_put_away,
       a.put_away_no, dest.code AS destination_bin, a.qty AS moved
FROM purchase_order p
JOIN purchase_order_line l ON l.po_id = p.id
JOIN receipt r ON r.po_line_id = l.id
JOIN bin src ON src.id = r.receiving_bin_id
LEFT JOIN put_away a ON a.receipt_id = r.id
LEFT JOIN bin dest ON dest.id = a.destination_bin_id
WHERE p.po_no = @po_no
ORDER BY r.id, a.id;

SELECT w.code AS warehouse_code, s.code AS sku_code, b.code AS bin_code, b.type,
       COALESCE(sb.on_hand, 0) AS on_hand, COALESCE(sb.reserved, 0) AS reserved,
       CASE WHEN b.type = 'PICKABLE' THEN COALESCE(sb.on_hand - sb.reserved, 0) ELSE 0 END AS available
FROM purchase_order p
JOIN purchase_order_line l ON l.po_id = p.id
JOIN sku s ON s.id = l.sku_id
JOIN warehouse w ON w.id = p.warehouse_id
JOIN bin b ON b.warehouse_id = p.warehouse_id
LEFT JOIN stock_balance sb ON sb.sku_id = s.id AND sb.bin_id = b.id
WHERE p.po_no = @po_no
ORDER BY l.line_no, b.pick_priority, b.id;

WITH receipts AS (
    SELECT r.id, r.receipt_no
    FROM purchase_order p
    JOIN purchase_order_line l ON l.po_id = p.id
    JOIN receipt r ON r.po_line_id = l.id
    WHERE p.po_no = @po_no
), business_numbers AS (
    SELECT receipt_no AS biz_no FROM receipts
    UNION ALL
    SELECT a.put_away_no FROM put_away a JOIN receipts r ON r.id = a.receipt_id
)
SELECT m.biz_no, m.movement_type, b.code AS bin_code, m.delta_on_hand, m.delta_reserved,
       m.on_hand_after, m.reserved_after, u.username, o.request_id, m.occurred_at
FROM business_numbers n
JOIN stock_movement m ON m.biz_no = n.biz_no
JOIN stock_balance sb ON sb.id = m.balance_id
JOIN bin b ON b.id = sb.bin_id
JOIN app_user u ON u.id = m.actor_id
LEFT JOIN operation_log o ON o.entity_no = m.biz_no
ORDER BY m.id;
