-- DataGrip: home_fulfillment, user hf_app, read-only. Set the real channel and channel order number.
SET @channel = 'DIRECT';
SET @external = 'SAMPLE-001';

SELECT o.order_no,o.channel,o.channel_order_no,o.route_status,o.shortage_json,
       l.line_no,s.code AS sku_code,l.ordered_qty,w.code AS warehouse_code,
       f.fulfillment_no,f.status AS fulfillment_status,fl.qty AS fulfillment_qty,
       b.code AS bin_code,r.reservation_no,r.qty AS reserved_fact_qty,r.status AS reservation_status,
       sb.on_hand,sb.reserved,sb.on_hand-sb.reserved AS available
FROM channel_order o
JOIN channel_order_line l ON l.order_id=o.id
JOIN sku s ON s.id=l.sku_id
LEFT JOIN fulfillment_line fl ON fl.order_line_id=l.id
LEFT JOIN fulfillment_order f ON f.id=fl.fulfillment_id AND f.order_id=o.id
LEFT JOIN warehouse w ON w.id=f.warehouse_id
LEFT JOIN stock_reservation r ON r.fulfillment_line_id=fl.id
LEFT JOIN stock_balance sb ON sb.id=r.balance_id
LEFT JOIN bin b ON b.id=sb.bin_id
WHERE o.channel=@channel AND o.channel_order_no=@external
ORDER BY l.line_no,w.route_priority,b.pick_priority,b.id;

SELECT o.order_no,f.fulfillment_no,w.code AS warehouse_code,m.biz_no,m.movement_type,
       m.delta_on_hand,m.delta_reserved,m.on_hand_after,m.reserved_after,m.occurred_at
FROM channel_order o
JOIN fulfillment_order f ON f.order_id=o.id
JOIN warehouse w ON w.id=f.warehouse_id
JOIN fulfillment_line fl ON fl.fulfillment_id=f.id
JOIN stock_reservation r ON r.fulfillment_line_id=fl.id
JOIN stock_movement m ON m.balance_id=r.balance_id AND m.biz_no=r.reservation_no
WHERE o.channel=@channel AND o.channel_order_no=@external
ORDER BY m.id;

-- An empty result means both reconciliation rules hold for each balance row.
WITH movements AS (
    SELECT balance_id,SUM(delta_on_hand) AS on_hand,SUM(delta_reserved) AS reserved FROM stock_movement GROUP BY balance_id
), active AS (
    SELECT balance_id,SUM(qty) AS reserved FROM stock_reservation WHERE status='ACTIVE' GROUP BY balance_id
)
SELECT sb.id,sb.on_hand,sb.reserved,COALESCE(m.on_hand,0) AS movement_on_hand,
       COALESCE(m.reserved,0) AS movement_reserved,COALESCE(a.reserved,0) AS active_reserved
FROM stock_balance sb LEFT JOIN movements m ON m.balance_id=sb.id LEFT JOIN active a ON a.balance_id=sb.id
WHERE sb.on_hand<>COALESCE(m.on_hand,0) OR sb.reserved<>COALESCE(m.reserved,0) OR sb.reserved<>COALESCE(a.reserved,0)
ORDER BY sb.id;
