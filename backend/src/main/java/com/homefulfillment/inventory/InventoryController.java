package com.homefulfillment.inventory;

import com.homefulfillment.identity.Identity;
import com.homefulfillment.identity.Identity.Actor;
import com.homefulfillment.web.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/inventory")
class InventoryController {
    private static final String BALANCE_JOIN = """
         FROM stock_balance sb JOIN sku s ON s.id=sb.sku_id
         JOIN bin b ON b.id=sb.bin_id JOIN warehouse w ON w.id=b.warehouse_id
        """;
    private static final String SCOPE = """
         WHERE w.code IN (:allowed) AND (:warehouse='' OR w.code=:warehouse)
           AND (:sku='' OR s.code=:sku) AND (:bin='' OR b.code=:bin)
        """;

    private final JdbcClient db;
    private final Identity identity;
    InventoryController(JdbcClient db, Identity identity) { this.db = db; this.identity = identity; }

    record Balance(String warehouseCode, String skuCode, String binCode, String binType, long onHand, long reserved, long available) {}
    record Movement(String warehouseCode, String skuCode, String binCode, String movementType, String bizNo,
                    long deltaOnHand, long deltaReserved, long onHandAfter, long reservedAfter, Instant occurredAt) {}

    @GetMapping("/balances")
    Page<Balance> balances(Authentication auth, @RequestParam(defaultValue="") String warehouseCode,
            @RequestParam(defaultValue="") String skuCode,@RequestParam(defaultValue="") String binCode,
            @RequestParam(defaultValue="1") int page,@RequestParam(defaultValue="20") int size) {
        Actor actor=identity.actor(auth);
        actor.requireInboundRead();
        var window=Page.Window.of(page,size);
        if (actor.warehouses().isEmpty()) return window.result(List.of(),0);
        var params=Map.of("allowed",actor.warehouses(),"warehouse",warehouseCode.trim(),"sku",skuCode.trim(),"bin",binCode.trim());
        String query=BALANCE_JOIN+SCOPE;
        long total=db.sql("SELECT COUNT(*)"+query).params(params).query(Long.class).single();
        var rows=db.sql("""
                SELECT w.code AS warehouse_code,s.code AS sku_code,b.code AS bin_code,b.type AS bin_type,
                       sb.on_hand,sb.reserved,CASE WHEN b.type='PICKABLE' THEN sb.on_hand-sb.reserved ELSE 0 END AS available
                """+query+" ORDER BY w.route_priority,b.pick_priority,b.id,sb.id LIMIT :size OFFSET :offset")
                .params(params).param("size",size).param("offset",window.offset()).query(Balance.class).list();
        return window.result(rows,total);
    }

    @GetMapping("/movements")
    Page<Movement> movements(Authentication auth, @RequestParam(defaultValue="") String warehouseCode,
            @RequestParam(defaultValue="") String skuCode,@RequestParam(defaultValue="") String binCode,
            @RequestParam(defaultValue="") String bizNo,@RequestParam(defaultValue="1") int page,
            @RequestParam(defaultValue="20") int size) {
        Actor actor=identity.actor(auth);
        actor.requireInboundRead();
        var window=Page.Window.of(page,size);
        if (actor.warehouses().isEmpty()) return window.result(List.of(),0);
        var params=Map.of("allowed",actor.warehouses(),"warehouse",warehouseCode.trim(),"sku",skuCode.trim(),
                "bin",binCode.trim(),"biz",bizNo.trim());
        String query=BALANCE_JOIN+" JOIN stock_movement m ON m.balance_id=sb.id"+SCOPE+" AND (:biz='' OR m.biz_no=:biz)";
        long total=db.sql("SELECT COUNT(*)"+query).params(params).query(Long.class).single();
        var rows=db.sql("""
                SELECT w.code,s.code,b.code,m.movement_type,m.biz_no,m.delta_on_hand,m.delta_reserved,
                       m.on_hand_after,m.reserved_after,m.occurred_at
                """+query+" ORDER BY m.id DESC LIMIT :size OFFSET :offset")
                .params(params).param("size",size).param("offset",window.offset()).query((r,n)->new Movement(
                        r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),
                        r.getLong(7),r.getLong(8),r.getLong(9),r.getTimestamp(10).toInstant())).list();
        return window.result(rows,total);
    }
}
