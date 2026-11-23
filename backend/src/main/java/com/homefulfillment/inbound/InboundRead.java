package com.homefulfillment.inbound;

import com.homefulfillment.identity.Identity.Actor;
import com.homefulfillment.web.ApiProblem;
import com.homefulfillment.web.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
class InboundRead {
    private final JdbcClient db;
    InboundRead(JdbcClient db) { this.db = db; }

    record PurchaseRow(long id, String poNo, String warehouseCode, String supplierName, String status) {}
    record PurchaseSummary(String poNo, String warehouseCode, String supplierName, String status) {}
    record PurchaseLine(long id, long skuId, int lineNo, String skuCode, long orderedQty, long receivedQty, long remainingQty) {}
    record LineView(int lineNo, String skuCode, long orderedQty, long receivedQty, long remainingQty) {}
    record PurchaseDetail(String poNo, String warehouseCode, String supplierName, String status, List<LineView> lines,
                          List<String> receiptNos, List<String> allowedActions) {}
    record ReceiptRow(long id, long poId, long skuId, long receivingBinId, String receiptNo, String poNo, int lineNo,
                      String skuCode, String warehouseCode, String binCode, long quantity, long putAwayQty) {}
    record PutAwayFact(String putAwayNo, String destinationBinCode, long quantity) {}
    record ReceiptDetail(String receiptNo, String poNo, int lineNo, String skuCode, String warehouseCode, String binCode,
                         long quantity, long putAwayQty, long remainingQty, List<PutAwayFact> putAways, List<String> allowedActions) {}
    record Bin(long id, String type) {}

    private static final String PURCHASES = " FROM purchase_order p JOIN warehouse w ON w.id=p.warehouse_id";
    private static final String PURCHASE_COLUMNS = "p.po_no,w.code AS warehouse_code,p.supplier_name,p.status";

    Page<PurchaseSummary> purchases(Actor actor, String warehouse, String status, String poNo, Page.Window page) {
        actor.requireInboundRead();
        if (actor.warehouses().isEmpty()) return page.result(List.of(),0);
        String where = """
             WHERE w.code IN (:allowed) AND (:warehouse='' OR w.code=:warehouse)
              AND (:status='' OR p.status=:status) AND (:poNo='' OR p.po_no=:poNo)
            """;
        var params = java.util.Map.of("allowed", actor.warehouses(), "warehouse", clean(warehouse),
                "status",clean(status), "poNo",clean(poNo));
        long total = db.sql("SELECT COUNT(*)"+PURCHASES+where).params(params).query(Long.class).single();
        var items = db.sql("SELECT "+PURCHASE_COLUMNS+PURCHASES+where+" ORDER BY p.id DESC LIMIT :size OFFSET :offset")
                .params(params).param("size",page.size()).param("offset",page.offset()).query(PurchaseSummary.class).list();
        return page.result(items,total);
    }

    PurchaseRow purchase(String poNo) {
        return db.sql("SELECT p.id,"+PURCHASE_COLUMNS+PURCHASES+" WHERE p.po_no=?")
                .param(poNo).query(PurchaseRow.class).optional().orElseThrow(() -> ApiProblem.missing("采购单不存在"));
    }

    private static final String LINES = """
        SELECT l.id,l.sku_id,l.line_no,s.code AS sku_code,l.ordered_qty,l.received_qty,
               l.ordered_qty-l.received_qty AS remaining_qty
        FROM purchase_order_line l JOIN sku s ON s.id=l.sku_id WHERE l.po_id=?
        """;

    PurchaseLine line(long poId, int lineNo) {
        return db.sql(LINES+" AND l.line_no=?").params(poId,lineNo).query(PurchaseLine.class).optional()
                .orElseThrow(() -> ApiProblem.missing("采购单行不存在"));
    }

    PurchaseDetail purchaseDetail(String no, Actor actor) {
        actor.requireInboundRead();
        PurchaseRow po = purchase(no);
        actor.requireWarehouse(po.warehouseCode(),false);
        var lines = db.sql(LINES+" ORDER BY l.line_no").param(po.id()).query(PurchaseLine.class).list().stream()
                .map(l -> new LineView(l.lineNo(),l.skuCode(),l.orderedQty(),l.receivedQty(),l.remainingQty())).toList();
        var receipts = db.sql("""
                SELECT r.receipt_no FROM receipt r JOIN purchase_order_line l ON l.id=r.po_line_id
                WHERE l.po_id=? ORDER BY r.id DESC
                """).param(po.id()).query(String.class).list();
        return new PurchaseDetail(po.poNo(),po.warehouseCode(),po.supplierName(),po.status(),lines,receipts,
                actor.canWork() && lines.stream().anyMatch(l->l.remainingQty()>0)?List.of("RECEIVE"):List.of());
    }

    ReceiptRow receipt(String no) {
        return db.sql("""
            SELECT r.id,p.id AS po_id,l.sku_id,r.receiving_bin_id,r.receipt_no,p.po_no,l.line_no,
                   s.code AS sku_code,w.code AS warehouse_code,b.code AS bin_code,r.qty AS quantity,r.put_away_qty
            FROM receipt r JOIN purchase_order_line l ON l.id=r.po_line_id JOIN purchase_order p ON p.id=l.po_id
            JOIN sku s ON s.id=l.sku_id JOIN bin b ON b.id=r.receiving_bin_id JOIN warehouse w ON w.id=p.warehouse_id
            WHERE r.receipt_no=?
            """).param(no).query(ReceiptRow.class).optional().orElseThrow(() -> ApiProblem.missing("收货单不存在"));
    }

    ReceiptDetail receiptDetail(String no, Actor actor) {
        actor.requireInboundRead();
        ReceiptRow r = receipt(no);
        actor.requireWarehouse(r.warehouseCode(),false);
        var facts = db.sql("""
                SELECT a.put_away_no,b.code AS destination_bin_code,a.qty AS quantity
                FROM put_away a JOIN bin b ON b.id=a.destination_bin_id WHERE a.receipt_id=? ORDER BY a.id DESC
                """).param(r.id()).query(PutAwayFact.class).list();
        long remaining = r.quantity()-r.putAwayQty();
        return new ReceiptDetail(r.receiptNo(),r.poNo(),r.lineNo(),r.skuCode(),r.warehouseCode(),r.binCode(),
                r.quantity(),r.putAwayQty(),remaining,facts,actor.canWork() && remaining>0?List.of("PUT_AWAY"):List.of());
    }

    Bin bin(String warehouse, String code, String type) {
        return db.sql("""
            SELECT b.id,b.type FROM bin b JOIN warehouse w ON w.id=b.warehouse_id WHERE w.code=? AND b.code=?
            """).params(warehouse,code).query(Bin.class).optional().filter(b -> type.equals(b.type()))
                .orElseThrow(() -> ApiProblem.invalid("请选择本仓的" + ("RECEIVING".equals(type)?"暂存位":"可拣位")));
    }

    static String clean(String input) { return input==null?"":input.trim(); }
}
