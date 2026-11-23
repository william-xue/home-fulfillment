package com.homefulfillment.inbound;

import com.fasterxml.jackson.databind.JsonNode;
import com.homefulfillment.catalog.OrderItems;
import com.homefulfillment.identity.Identity.Actor;
import com.homefulfillment.inventory.InventoryLedger;
import com.homefulfillment.web.ApiProblem;
import com.homefulfillment.web.CommandJournal;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.Map;
import java.util.List;

@Service
class InboundCommands {
    private final InboundRead read;
    private final InboundMapper writes;
    private final InventoryLedger inventory;
    private final CommandJournal journal;
    private final JdbcClient db;
    private final OrderItems items;

    InboundCommands(InboundRead read, InboundMapper writes, InventoryLedger inventory, CommandJournal journal,
                    JdbcClient db, OrderItems items) {
        this.read = read;
        this.writes = writes;
        this.inventory = inventory;
        this.journal = journal;
        this.db = db;
        this.items = items;
    }

    ResponseEntity<JsonNode> createPurchase(Actor actor, String warehouse, String supplier,
                                            List<OrderItems.Input> lines, String key, String requestId) {
        actor.requireOps();
        actor.requireWarehouse(warehouse, false);
        return journal.create(actor.id(),"CREATE_PO",key,Map.of("path","/purchase-orders","warehouseCode",warehouse,
                "supplierName",supplier,"lines",lines),() -> {
            var resolved = items.resolve(lines);
            String no = "PO-" + UUID.randomUUID();
            db.sql("""
                    INSERT INTO purchase_order(po_no,warehouse_id,supplier_name,status,created_by)
                    SELECT ?,id,?,'OPEN',? FROM warehouse WHERE code=?
                    """).params(no,supplier,actor.id(),warehouse).update();
            long id = read.purchase(no).id();
            for (int index=0; index<resolved.size(); index++) {
                var line = resolved.get(index);
                db.sql("INSERT INTO purchase_order_line(po_id,line_no,sku_id,ordered_qty) VALUES(?,?,?,?)")
                        .params(id,index+1,line.skuId(),line.quantity()).update();
            }
            db.sql("""
                    INSERT INTO operation_log(action,entity_type,entity_no,actor_key,request_id,meta_json)
                    VALUES('CREATE_PO','PURCHASE_ORDER',?,?,?,JSON_OBJECT('warehouseCode',?,'lineCount',?))
                    """).params(no,"user:"+actor.id(),requestId,warehouse,resolved.size()).update();
            return read.purchaseDetail(no,actor);
        });
    }

    ResponseEntity<JsonNode> receive(Actor actor, String poNo, int lineNo, String binCode, int qty, String key, String requestId) {
        actor.requireInboundRead();
        var po = read.purchase(poNo);
        actor.requireWarehouse(po.warehouseCode(), true);
        return journal.create(actor.id(),"RECEIVE",key,Map.of(
                "path","/purchase-orders/"+poNo+"/receipts","lineNo",lineNo,"binCode",binCode,"quantity",qty),() -> {
            requireLockedPurchase(po.id());
            var line = read.line(po.id(),lineNo);
            inventory.lockSku(line.skuId());
            var bin = read.bin(po.warehouseCode(),binCode,"RECEIVING");
            if (writes.incrementReceived(line.id(),qty)!=1) {
                throw ApiProblem.conflict("OVER_RECEIPT","收货量超过采购行剩余 " + line.remainingQty() + " 件");
            }
            String no = "RC-"+UUID.randomUUID();
            writes.insertReceipt(no,line.id(),bin.id(),qty,actor.id());
            inventory.receive(line.skuId(),bin.id(),qty,no,actor.id());
            writes.refreshPurchaseStatus(po.id());
            audit("RECEIVE","RECEIPT",no,poNo,actor,requestId,qty);
            return read.receiptDetail(no,actor);
        });
    }

    record PutAwayResult(String putAwayNo, String receiptNo, int quantity, long remainingQty) {}

    ResponseEntity<JsonNode> putAway(Actor actor, String receiptNo, String destination, int qty, String key, String requestId) {
        actor.requireInboundRead();
        var reference = read.receipt(receiptNo);
        actor.requireWarehouse(reference.warehouseCode(),true);
        return journal.create(actor.id(),"PUT_AWAY",key,Map.of(
                "path","/receipts/"+receiptNo+"/put-aways","destinationBinCode",destination,"quantity",qty),() -> {
            requireLockedPurchase(reference.poId());
            var receipt = read.receipt(receiptNo);
            inventory.lockSku(receipt.skuId());
            var bin = read.bin(receipt.warehouseCode(),destination,"PICKABLE");
            long remaining = receipt.quantity()-receipt.putAwayQty();
            if (writes.incrementPutAway(receipt.id(),qty)!=1) {
                throw ApiProblem.conflict("PUT_AWAY_EXCEEDS_RECEIPT","上架量超过该收货单待上架 " + remaining + " 件");
            }
            String no = "PA-"+UUID.randomUUID();
            writes.insertPutAway(no,receipt.id(),bin.id(),qty,actor.id());
            inventory.moveToPickable(receipt.skuId(),receipt.receivingBinId(),bin.id(),qty,no,actor.id());
            audit("PUT_AWAY","PUT_AWAY",no,receiptNo,actor,requestId,qty);
            return new PutAwayResult(no,receiptNo,qty,remaining-qty);
        });
    }

    private void requireLockedPurchase(long id) {
        if (writes.lockPurchase(id)==null) throw ApiProblem.missing("采购单不存在");
    }

    private void audit(String action, String type, String no, String parentNo, Actor actor, String requestId, int qty) {
        db.sql("""
            INSERT INTO operation_log(action,entity_type,entity_no,actor_key,request_id,meta_json)
            VALUES(?,?,?,?,?,JSON_OBJECT('parentNo',?,'quantity',?))
            """).params(action,type,no,"user:"+actor.id(),requestId,parentNo,qty).update();
    }
}
