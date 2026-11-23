package com.homefulfillment.fulfillment;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefulfillment.identity.Identity.Actor;
import com.homefulfillment.oms.Orders.Shortage;
import com.homefulfillment.web.ApiProblem;
import com.homefulfillment.web.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Repository
class OrderRead {
    private final JdbcClient db;
    private final ObjectMapper json;
    OrderRead(JdbcClient db, ObjectMapper json) { this.db = db; this.json = json; }

    record Summary(String orderNo, String channel, String channelOrderNo, Instant paidAt, String routeStatus) {}
    private record Head(long id, Instant paidAt, String channel, String external, String status, String shortageJson, String cancelReason) {}
    record Line(int lineNo, String skuCode, long orderedQty, long reservedQty, long pickedQty, long dispatchedQty) {}
    record Reservation(String reservationNo, String binCode, long quantity, String status) {}
    record FulfillmentLine(int orderLineNo, String skuCode, long quantity, List<Reservation> reservations) {}
    record DispatchFact(String dispatchNo, Instant dispatchedAt) {}
    record ShipmentFact(String shipmentNo, String carrier, String trackingNo, String status, Instant deliveredAt) {}
    record Fulfillment(String fulfillmentNo, String warehouseCode, String status, List<FulfillmentLine> lines,
                       DispatchFact dispatch, ShipmentFact shipment) {}
    private record FulfillmentRow(long id, String fulfillmentNo, String warehouseCode, String status,
                                  DispatchFact dispatch, ShipmentFact shipment) {}
    private record AllocationRow(long fulfillmentId, int lineNo, String skuCode, long quantity, Reservation reservation) {}
    record Event(String type, String bizNo, String warehouseCode, Instant occurredAt) {}
    record Detail(String orderNo, String channel, String channelOrderNo, Instant paidAt, String routeStatus,
                  List<Shortage> shortages, String cancelReason, String fulfillmentStatus, String deliveryStatus,
                  List<Line> lines,List<Fulfillment> fulfillments,List<Event> timeline,List<String> allowedActions) {}

    @Transactional(readOnly = true)
    public Page<Summary> list(Actor actor, String channel, String external, String number, String status, Page.Window page) {
        actor.requireOrdersRead();
        String filter = """
                FROM channel_order WHERE (:channel='' OR channel=:channel)
                AND (:external='' OR channel_order_no=:external) AND (:number='' OR order_no=:number)
                AND (:status='' OR route_status=:status)
                """;
        var params = Map.of("channel",channel.strip(),"external",external.strip(),"number",number.strip(),"status",status.strip());
        long total = db.sql("SELECT COUNT(*) "+filter).params(params).query(Long.class).single();
        var rows = db.sql("""
                SELECT order_no,channel,channel_order_no,paid_at,route_status
                """+filter+" ORDER BY id DESC LIMIT :size OFFSET :offset").params(params)
                .param("size",page.size()).param("offset",page.offset()).query((r,n) ->
                        new Summary(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant(),r.getString(5))).list();
        return page.result(rows,total);
    }

    @Transactional(readOnly = true)
    public Detail detail(String number, Actor actor) {
        actor.requireOrdersRead();
        var order = db.sql("""
                SELECT id,paid_at,channel,channel_order_no,route_status,shortage_json,cancel_reason
                FROM channel_order WHERE order_no=?
                """).param(number).query((r,n)->new Head(r.getLong(1),r.getTimestamp(2).toInstant(),r.getString(3),
                        r.getString(4),r.getString(5),r.getString(6),r.getString(7))).optional()
                .orElseThrow(()->ApiProblem.missing("订单不存在"));
        var lines = db.sql("""
                SELECT l.line_no,s.code,l.ordered_qty,COALESCE(a.reserved,0),COALESCE(a.picked,0),
                  (SELECT COALESCE(SUM(fl.qty),0) FROM fulfillment_line fl JOIN dispatch d ON d.fulfillment_id=fl.fulfillment_id
                    WHERE fl.order_line_id=l.id)
                FROM channel_order_line l JOIN sku s ON s.id=l.sku_id
                LEFT JOIN (
                    SELECT fl.order_line_id,SUM(CASE WHEN r.status='ACTIVE' THEN r.qty ELSE 0 END) AS reserved,
                           SUM(CASE WHEN p.status='PICKED' THEN r.qty ELSE 0 END) AS picked
                    FROM fulfillment_line fl JOIN fulfillment_order f ON f.id=fl.fulfillment_id
                    JOIN stock_reservation r ON r.fulfillment_line_id=fl.id JOIN pick_task p ON p.reservation_id=r.id
                    WHERE f.order_id=? GROUP BY fl.order_line_id
                ) a ON a.order_line_id=l.id WHERE l.order_id=? ORDER BY l.line_no
                """).params(order.id(),order.id()).query((r,n)->new Line(r.getInt(1),r.getString(2),r.getLong(3),r.getLong(4),
                        r.getLong(5),r.getLong(6))).list();
        var fulfillments = fulfillments(order.id());
        String fulfillmentStatus = fulfillmentStatus(order.status(),lines,fulfillments);
        boolean anyDispatch = fulfillments.stream().anyMatch(f->f.dispatch()!=null);
        String deliveryStatus = !anyDispatch ? "NOT_STARTED"
                : "DISPATCHED".equals(fulfillmentStatus) && fulfillments.stream().allMatch(f->f.shipment()!=null &&
                        "DELIVERED".equals(f.shipment().status())) ? "DELIVERED"
                : fulfillments.stream().noneMatch(f->f.shipment()!=null) ? "AWAITING_SHIPMENT" : "IN_PROGRESS";
        List<String> actions = !actor.isOps() ? List.of() : switch (order.status()) {
            case "CANCELLED" -> List.of();
            case "ALLOCATED" -> "READY_TO_PICK".equals(fulfillmentStatus)?List.of("CANCEL"):List.of();
            default -> List.of("ROUTE","CANCEL");
        };
        return new Detail(number,order.channel(),order.external(),order.paidAt(),order.status(),decode(order.shortageJson()),
                order.cancelReason(),fulfillmentStatus,deliveryStatus,lines,fulfillments,timeline(order.id(),number),actions);
    }

    private String fulfillmentStatus(String routeStatus,List<Line> lines,List<Fulfillment> fulfillments) {
        if ("CANCELLED".equals(routeStatus) || "SHORTAGE".equals(routeStatus)) return routeStatus;
        if (!"ALLOCATED".equals(routeStatus)) return "NOT_STARTED";
        long dispatched=lines.stream().mapToLong(Line::dispatchedQty).sum();
        if (dispatched==lines.stream().mapToLong(Line::orderedQty).sum()) return "DISPATCHED";
        if (dispatched>0) return "PARTIALLY_DISPATCHED";
        if (fulfillments.stream().allMatch(f->"PICKED".equals(f.status()))) return "READY_TO_DISPATCH";
        if (lines.stream().anyMatch(line->line.pickedQty()>0)) return "PICKING";
        return "READY_TO_PICK";
    }

    private List<Fulfillment> fulfillments(long orderId) {
        var heads = db.sql("""
                SELECT f.id,f.fulfillment_no,w.code,f.status,d.dispatch_no,d.dispatched_at,
                       s.shipment_no,s.carrier,s.tracking_no,s.status,s.delivered_at
                FROM fulfillment_order f JOIN warehouse w ON w.id=f.warehouse_id LEFT JOIN dispatch d ON d.fulfillment_id=f.id
                LEFT JOIN shipment s ON s.dispatch_id=d.id WHERE f.order_id=? ORDER BY w.route_priority
                """).param(orderId).query((r,n)->new FulfillmentRow(r.getLong(1),r.getString(2),r.getString(3),r.getString(4),
                        r.getString(5)==null?null:new DispatchFact(r.getString(5),instant(r,6)),
                        r.getString(7)==null?null:new ShipmentFact(r.getString(7),r.getString(8),r.getString(9),
                                r.getString(10),instant(r,11)))).list();
        if (heads.isEmpty()) return List.of();
        var rows = db.sql("""
                SELECT f.id,l.line_no,k.code,fl.qty,r.reservation_no,b.code,r.qty,r.status
                FROM fulfillment_order f JOIN fulfillment_line fl ON fl.fulfillment_id=f.id
                JOIN channel_order_line l ON l.id=fl.order_line_id JOIN sku k ON k.id=l.sku_id
                JOIN stock_reservation r ON r.fulfillment_line_id=fl.id
                JOIN stock_balance sb ON sb.id=r.balance_id JOIN bin b ON b.id=sb.bin_id
                WHERE f.order_id=? ORDER BY l.line_no,b.pick_priority,b.id
                """).param(orderId).query((r,n) -> new AllocationRow(r.getLong(1),r.getInt(2),r.getString(3),r.getLong(4),
                        new Reservation(r.getString(5),r.getString(6),r.getLong(7),r.getString(8)))).list();
        var byFulfillment = rows.stream().collect(Collectors.groupingBy(AllocationRow::fulfillmentId));
        return heads.stream().map(f -> {
            var byLine = byFulfillment.getOrDefault(f.id(),List.of()).stream()
                    .collect(Collectors.groupingBy(AllocationRow::lineNo,java.util.LinkedHashMap::new,Collectors.toList()));
            var lines = byLine.values().stream().map(parts -> {
                var first = parts.get(0);
                return new FulfillmentLine(first.lineNo(),first.skuCode(),first.quantity(),
                        parts.stream().map(AllocationRow::reservation).toList());
            }).toList();
            return new Fulfillment(f.fulfillmentNo(),f.warehouseCode(),f.status(),lines,f.dispatch(),f.shipment());
        }).toList();
    }

    private List<Event> timeline(long orderId, String number) {
        return db.sql("""
                WITH order_fulfillments AS (
                    SELECT f.id,w.code FROM fulfillment_order f JOIN warehouse w ON w.id=f.warehouse_id WHERE f.order_id=?
                ), order_reservations AS (
                    SELECT r.id,r.balance_id,r.reservation_no,f.code FROM order_fulfillments f
                    JOIN fulfillment_line l ON l.fulfillment_id=f.id JOIN stock_reservation r ON r.fulfillment_line_id=l.id
                ), order_dispatches AS (
                    SELECT d.id,d.dispatch_no,d.dispatched_at,f.code FROM order_fulfillments f JOIN dispatch d ON d.fulfillment_id=f.id
                ), order_shipments AS (
                    SELECT s.id,s.shipment_no,s.created_at,d.code FROM order_dispatches d JOIN shipment s ON s.dispatch_id=d.id
                )
                SELECT type,biz_no,warehouse_code,occurred_at FROM (
                    SELECT o.action AS type,o.entity_no AS biz_no,NULL AS warehouse_code,o.occurred_at,
                           CASE o.action WHEN 'RECEIVED' THEN 1 WHEN 'CANCELLED' THEN 5 ELSE 2 END AS step,o.id
                    FROM operation_log o WHERE o.entity_type='CHANNEL_ORDER' AND o.entity_no=?
                    UNION ALL
                    SELECT m.movement_type,m.biz_no,r.code,m.occurred_at,
                           CASE m.movement_type WHEN 'RESERVE' THEN 3 ELSE 4 END,m.id
                    FROM order_reservations r
                    JOIN stock_movement m ON m.balance_id=r.balance_id AND m.biz_no=r.reservation_no
                    UNION ALL
                    SELECT 'PICK',p.task_no,r.code,p.picked_at,6,p.id FROM order_reservations r
                    JOIN pick_task p ON p.reservation_id=r.id WHERE p.status='PICKED'
                    UNION ALL
                    SELECT 'DISPATCH',d.dispatch_no,d.code,d.dispatched_at,7,d.id FROM order_dispatches d
                    UNION ALL
                    SELECT 'SHIPMENT_CREATED',s.shipment_no,s.code,s.created_at,8,s.id FROM order_shipments s
                    UNION ALL
                    SELECT e.type,s.shipment_no,s.code,e.occurred_at,9,e.id FROM order_shipments s
                    JOIN tracking_event e ON e.shipment_id=s.id
                ) e ORDER BY occurred_at,step,id
                """).params(orderId,number).query((r,n) ->
                        new Event(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant())).list();
    }

    private static Instant instant(ResultSet result,int column) throws SQLException {
        var timestamp=result.getTimestamp(column);
        return timestamp==null?null:timestamp.toInstant();
    }

    private List<Shortage> decode(String raw) {
        if (raw==null) return List.of();
        try { return json.readValue(raw,new TypeReference<List<Shortage>>() {}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Invalid stored shortage information",ex); }
    }
}
