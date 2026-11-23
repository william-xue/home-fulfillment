package com.homefulfillment.oms;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefulfillment.catalog.OrderItems;
import com.homefulfillment.web.ApiProblem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class Orders {
    private final JdbcClient db;
    private final ObjectMapper json;
    private final OrderItems items;

    Orders(JdbcClient db, ObjectMapper json, OrderItems items) { this.db = db; this.json = json; this.items = items; }

    public record Accepted(String number, boolean created) {}
    private record Identity(long id, String number, String hash) {}
    public record Locked(long id, String routeStatus, String cancelReason) {}
    public record Line(long id, long skuId, String skuCode, int quantity) {}
    public record Shortage(String skuCode, int requestedQty, int availableQty, int missingQty) {}

    public Accepted accept(String channel, String external, Instant paidAt, List<OrderItems.Input> lines,
                           String fingerprint, long actor, String requestId) {
        var resolved = items.resolve(lines);
        String number = "CO-" + UUID.randomUUID();
        db.sql("""
                INSERT INTO channel_order(order_no,channel,channel_order_no,paid_at,request_hash,route_status,created_by)
                VALUES(?,?,?,?,?,'RECEIVED',?) ON DUPLICATE KEY UPDATE id=id
                """).params(number,channel,external,Timestamp.from(paidAt),fingerprint,actor).update();
        var identity = db.sql("""
                SELECT id,order_no AS number,request_hash AS hash FROM channel_order WHERE channel=? AND channel_order_no=?
                """).params(channel,external).query(Identity.class).single();
        if (!identity.hash().equals(fingerprint)) {
            throw ApiProblem.conflict("CHANNEL_ORDER_CONFLICT","相同渠道订单号已用于不同订单内容");
        }
        if (!identity.number().equals(number)) return new Accepted(identity.number(),false);

        for (int index=0; index<resolved.size(); index++) {
            var line = resolved.get(index);
            db.sql("INSERT INTO channel_order_line(order_id,line_no,sku_id,ordered_qty) VALUES(?,?,?,?)")
                    .params(identity.id(),index+1,line.skuId(),line.quantity()).update();
        }
        audit("RECEIVED",number,actor,requestId,Map.of("channel",channel,"channelOrderNo",external));
        return new Accepted(number,true);
    }

    public Locked lock(String number) {
        return db.sql("SELECT id,route_status,cancel_reason FROM channel_order WHERE order_no=? FOR UPDATE")
                .param(number).query(Locked.class).optional().orElseThrow(() -> ApiProblem.missing("订单不存在"));
    }

    public List<Line> lines(long orderId) {
        return db.sql("""
                SELECT l.id,l.sku_id,s.code AS sku_code,l.ordered_qty AS quantity
                FROM channel_order_line l JOIN sku s ON s.id=l.sku_id WHERE l.order_id=? ORDER BY l.line_no
                """).param(orderId).query(Line.class).list();
    }

    public void recordRoute(Locked order, String number, List<Shortage> shortages, long actor, String requestId) {
        String status = shortages.isEmpty() ? "ALLOCATED" : "SHORTAGE";
        if (db.sql("""
                UPDATE channel_order SET route_status=?,shortage_json=?,route_attempt=route_attempt+1
                WHERE id=? AND route_status IN ('RECEIVED','SHORTAGE')
                """).params(status,shortages.isEmpty()?null:json.valueToTree(shortages).toString(),order.id()).update()!=1) {
            throw new IllegalStateException("Order route state changed");
        }
        audit(status,number,actor,requestId,Map.of("previousStatus",order.routeStatus(),"shortages",shortages));
    }

    public void cancel(Locked order, String number, String reason, long actor, String requestId) {
        if (db.sql("""
                UPDATE channel_order SET route_status='CANCELLED',cancel_reason=?,cancelled_at=CURRENT_TIMESTAMP(3),shortage_json=NULL
                WHERE id=? AND route_status IN ('RECEIVED','SHORTAGE','ALLOCATED')
                """).params(reason,order.id()).update()!=1) {
            throw new IllegalStateException("Order cancel state changed");
        }
        audit("CANCELLED",number,actor,requestId,Map.of("previousStatus",order.routeStatus(),"reason",reason));
    }

    private void audit(String action, String number, long actor, String requestId, Map<String, ?> meta) {
        db.sql("""
                INSERT INTO operation_log(action,entity_type,entity_no,actor_key,request_id,meta_json)
                VALUES(?,'CHANNEL_ORDER',?,?,?,?)
                """).params(action,number,"user:"+actor,requestId,json.valueToTree(meta).toString()).update();
    }
}
