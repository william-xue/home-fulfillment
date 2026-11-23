-- DataGrip: home_fulfillment as hf_app. Read-only. Supply the real channel/order pair.
SET @channel = 'DIRECT';
SET @external = 'SAMPLE-001';

SELECT o.order_no,l.line_no,k.code AS sku_code,l.ordered_qty,w.code AS warehouse_code,
       f.fulfillment_no,f.status AS fulfillment_status,fl.qty AS fulfillment_qty,
       b.code AS bin_code,r.reservation_no,r.qty AS reservation_qty,r.status AS reservation_status,
       p.task_no,p.status AS pick_status,p.picked_at,d.dispatch_no,d.dispatched_at,
       s.shipment_no,s.status AS shipment_status,s.delivered_at,e.source_event_no,e.type,e.occurred_at
FROM channel_order o
JOIN channel_order_line l ON l.order_id=o.id
JOIN sku k ON k.id=l.sku_id
LEFT JOIN fulfillment_line fl ON fl.order_line_id=l.id
LEFT JOIN fulfillment_order f ON f.id=fl.fulfillment_id AND f.order_id=o.id
LEFT JOIN warehouse w ON w.id=f.warehouse_id
LEFT JOIN stock_reservation r ON r.fulfillment_line_id=fl.id
LEFT JOIN stock_balance sb ON sb.id=r.balance_id
LEFT JOIN bin b ON b.id=sb.bin_id
LEFT JOIN pick_task p ON p.reservation_id=r.id
LEFT JOIN dispatch d ON d.fulfillment_id=f.id
LEFT JOIN shipment s ON s.dispatch_id=d.id
LEFT JOIN tracking_event e ON e.shipment_id=s.id
WHERE o.channel=@channel AND o.channel_order_no=@external
ORDER BY l.line_no,w.route_priority,r.id,e.occurred_at,e.id;

SELECT w.code AS warehouse_code,d.dispatch_no,m.delta_on_hand,m.delta_reserved,
       m.on_hand_after,m.reserved_after,u.username,o_log.request_id
FROM channel_order o JOIN fulfillment_order f ON f.order_id=o.id
JOIN warehouse w ON w.id=f.warehouse_id JOIN dispatch d ON d.fulfillment_id=f.id
JOIN stock_movement m ON m.biz_no=d.dispatch_no AND m.movement_type='DISPATCH'
JOIN app_user u ON u.id=m.actor_id
LEFT JOIN operation_log o_log ON o_log.entity_type='DISPATCH' AND o_log.entity_no=d.dispatch_no
WHERE o.channel=@channel AND o.channel_order_no=@external
ORDER BY m.id;
