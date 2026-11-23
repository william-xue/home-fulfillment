-- DataGrip: home_fulfillment as hf_app. Read-only; change these to the real order numbers.
SET @channel = 'DOUYIN';
SET @external = 'SAMPLE-002';

SELECT o.order_no,w.code AS warehouse_code,f.fulfillment_no,d.dispatch_no,d.dispatched_at,
       ev.event_id,ev.status AS publish_status,ev.attempt,ev.last_error,ev.published_at,
       i.consumer_name,i.processed_at,ts.shipment_no,ts.status AS shipment_status
FROM channel_order o
JOIN fulfillment_order f ON f.order_id=o.id
JOIN warehouse w ON w.id=f.warehouse_id
LEFT JOIN dispatch d ON d.fulfillment_id=f.id
LEFT JOIN outbox_event ev ON ev.dedupe_key=CONCAT('FulfillmentDispatched.v1:',d.dispatch_no)
LEFT JOIN consumer_inbox i ON i.event_id=ev.event_id AND i.consumer_name='hf-shipment-consumer-v1'
LEFT JOIN shipment ts ON ts.dispatch_id=d.id
WHERE o.channel=@channel AND o.channel_order_no=@external
ORDER BY w.route_priority;

SELECT ev.event_id,op.action,op.actor_key,op.request_id,op.occurred_at,op.meta_json
FROM channel_order o JOIN fulfillment_order f ON f.order_id=o.id JOIN dispatch d ON d.fulfillment_id=f.id
JOIN outbox_event ev ON ev.dedupe_key=CONCAT('FulfillmentDispatched.v1:',d.dispatch_no)
JOIN operation_log op ON op.entity_type='OUTBOX' AND op.entity_no=ev.event_id
WHERE o.channel=@channel AND o.channel_order_no=@external
ORDER BY op.id;
