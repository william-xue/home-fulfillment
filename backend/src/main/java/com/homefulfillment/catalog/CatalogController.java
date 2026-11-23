package com.homefulfillment.catalog;

import com.homefulfillment.identity.Identity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/catalog")
class CatalogController {
    private final JdbcClient db;
    private final Identity identity;
    CatalogController(JdbcClient db, Identity identity) { this.db = db; this.identity = identity; }

    record Entry(String code, String name) {}
    record Bin(String code, String name, String type) {}

    @GetMapping("/warehouses")
    List<Entry> warehouses(Authentication auth) {
        var actor = identity.actor(auth);
        actor.requireInboundRead();
        if (actor.warehouses().isEmpty()) return List.of();
        return db.sql("SELECT code,name FROM warehouse WHERE code IN (:allowed) ORDER BY route_priority")
                .param("allowed",actor.warehouses()).query(Entry.class).list();
    }

    @GetMapping("/skus")
    List<Entry> skus(Authentication auth) {
        identity.actor(auth).requireInboundRead();
        return db.sql("SELECT code,name FROM sku ORDER BY code").query(Entry.class).list();
    }

    @GetMapping("/warehouses/{warehouseCode}/bins")
    List<Bin> bins(@PathVariable String warehouseCode,Authentication auth) {
        identity.actor(auth).requireWarehouse(warehouseCode,false);
        return db.sql("""
            SELECT b.code,b.code AS name,b.type FROM bin b JOIN warehouse w ON w.id=b.warehouse_id
            WHERE w.code=? ORDER BY b.pick_priority,b.id
            """).param(warehouseCode).query(Bin.class).list();
    }
}
