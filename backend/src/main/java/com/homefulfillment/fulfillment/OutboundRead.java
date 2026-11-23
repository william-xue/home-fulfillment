package com.homefulfillment.fulfillment;

import com.homefulfillment.identity.Identity.Actor;
import com.homefulfillment.tms.Shipments;
import com.homefulfillment.web.Page;
import com.homefulfillment.wms.Fulfillments;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Repository
class OutboundRead {
    private final JdbcClient db;
    private final Fulfillments fulfillments;
    private final Shipments shipments;
    OutboundRead(JdbcClient db,Fulfillments fulfillments,Shipments shipments) {
        this.db=db; this.fulfillments=fulfillments; this.shipments=shipments;
    }

    record Summary(String fulfillmentNo,String orderNo,String warehouseCode,String status) {}
    record Line(int orderLineNo,String skuCode,long quantity) {}
    record Task(String taskNo,String skuCode,String binCode,long quantity,String status,List<String> allowedActions) {}
    record Detail(String fulfillmentNo,String orderNo,String warehouseCode,String status,List<Line> lines,List<Task> tasks,
                  Fulfillments.Dispatch dispatch,Shipments.Summary shipment,List<String> allowedActions) {}

    @Transactional(readOnly=true)
    public Page<Summary> list(Actor actor,String warehouse,String status,String number,Page.Window page) {
        actor.requireInboundRead();
        if (actor.warehouses().isEmpty()) return page.result(List.of(),0);
        String query="""
                FROM fulfillment_order f JOIN channel_order o ON o.id=f.order_id JOIN warehouse w ON w.id=f.warehouse_id
                WHERE w.code IN (:allowed) AND (:warehouse='' OR w.code=:warehouse)
                AND (:status='' OR f.status=:status) AND (:number='' OR o.order_no=:number)
                """;
        var params=Map.of("allowed",actor.warehouses(),"warehouse",warehouse.strip(),"status",status.strip(),"number",number.strip());
        long total=db.sql("SELECT COUNT(*) "+query).params(params).query(Long.class).single();
        var rows=db.sql("SELECT f.fulfillment_no,o.order_no,w.code AS warehouse_code,f.status "+query+" ORDER BY f.id DESC LIMIT :size OFFSET :offset")
                .params(params).param("size",page.size()).param("offset",page.offset()).query(Summary.class).list();
        return page.result(rows,total);
    }

    @Transactional(readOnly=true)
    public Detail detail(String number,Actor actor) {
        actor.requireInboundRead();
        var f=fulfillments.reference(number);
        actor.requireWarehouse(f.warehouseCode(),false);
        var lines=db.sql("""
                SELECT ol.line_no AS order_line_no,s.code AS sku_code,fl.qty AS quantity
                FROM fulfillment_line fl JOIN channel_order_line ol ON ol.id=fl.order_line_id JOIN sku s ON s.id=ol.sku_id
                WHERE fl.fulfillment_id=? ORDER BY ol.line_no
                """).param(f.id()).query(Line.class).list();
        var tasks=db.sql("""
                SELECT p.task_no,s.code,b.code,r.qty,p.status,r.status AS reservation_status
                FROM fulfillment_line l JOIN stock_reservation r ON r.fulfillment_line_id=l.id
                JOIN pick_task p ON p.reservation_id=r.id JOIN stock_balance sb ON sb.id=r.balance_id
                JOIN sku s ON s.id=sb.sku_id JOIN bin b ON b.id=sb.bin_id
                WHERE l.fulfillment_id=? ORDER BY b.pick_priority,b.id,p.id
                """).param(f.id()).query((r,n)->new Task(r.getString(1),r.getString(2),r.getString(3),r.getLong(4),
                        r.getString(5),actor.canWork() && "PENDING".equals(r.getString(5)) && "ACTIVE".equals(r.getString(6))
                        ? List.of("PICK") : List.of())).list();
        var dispatch=fulfillments.dispatchFor(f).orElse(null);
        var shipment=dispatch!=null && actor.isOps()?shipments.forDispatch(dispatch.dispatchNo()).orElse(null):null;
        var actions=actor.canWork() && "PICKED".equals(f.status()) ? List.of("DISPATCH")
                : actor.isOps() && dispatch!=null && shipment==null ? List.of("CREATE_SHIPMENT")
                : tasks.stream().anyMatch(t->t.allowedActions().contains("PICK")) ? List.of("PICK") : List.<String>of();
        return new Detail(number,f.orderNo(),f.warehouseCode(),f.status(),lines,tasks,dispatch,shipment,actions);
    }
}
