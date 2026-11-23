package com.homefulfillment.wms;

import com.homefulfillment.web.ApiProblem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;
import java.util.List;
import java.time.Instant;
import java.util.Optional;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class Fulfillments {
    private final JdbcClient db;
    Fulfillments(JdbcClient db) { this.db = db; }

    public record Reference(long id, long orderId, String fulfillmentNo, String orderNo, String warehouseCode, String status) {}
    public record Dispatch(String dispatchNo, String fulfillmentNo, Instant dispatchedAt) {}
    private record Task(long id, String status, String reservationStatus) {}

    @Transactional(readOnly = true, propagation = Propagation.SUPPORTS)
    public Reference reference(String number) {
        return db.sql("""
                SELECT f.id,f.order_id,f.fulfillment_no,o.order_no,w.code AS warehouse_code,f.status
                FROM fulfillment_order f JOIN channel_order o ON o.id=f.order_id JOIN warehouse w ON w.id=f.warehouse_id
                WHERE f.fulfillment_no=?
                """).param(number).query(Reference.class).optional().orElseThrow(() -> ApiProblem.missing("履约单不存在"));
    }

    public long open(long orderId, long warehouseId) {
        String number = "FO-" + UUID.randomUUID();
        db.sql("""
                INSERT INTO fulfillment_order(fulfillment_no,order_id,warehouse_id,status) VALUES(?,?,?,'READY_TO_PICK')
                """).params(number,orderId,warehouseId).update();
        return db.sql("SELECT id FROM fulfillment_order WHERE fulfillment_no=?").param(number).query(Long.class).single();
    }

    public long addLine(long fulfillmentId, long originalLineId, int quantity) {
        if (db.sql("""
                INSERT INTO fulfillment_line(fulfillment_id,order_line_id,qty)
                SELECT f.id,l.id,? FROM fulfillment_order f JOIN channel_order_line l ON l.order_id=f.order_id
                WHERE f.id=? AND l.id=?
                """).params(quantity,fulfillmentId,originalLineId).update()!=1) {
            throw new IllegalStateException("Fulfillment line does not belong to order");
        }
        return db.sql("SELECT id FROM fulfillment_line WHERE fulfillment_id=? AND order_line_id=?")
                .params(fulfillmentId,originalLineId).query(Long.class).single();
    }

    public void addTask(long reservationId) {
        db.sql("INSERT INTO pick_task(task_no,reservation_id,status) VALUES(?,?,'PENDING')")
                .params("PK-"+UUID.randomUUID(),reservationId).update();
    }

    public void requireAllUnpicked(long orderId) {
        if (db.sql("""
                SELECT COUNT(*) FROM fulfillment_order f WHERE f.order_id=? AND (
                  f.status<>'READY_TO_PICK' OR EXISTS(
                    SELECT 1 FROM fulfillment_line l JOIN stock_reservation r ON r.fulfillment_line_id=l.id
                    JOIN pick_task p ON p.reservation_id=r.id WHERE l.fulfillment_id=f.id AND p.status='PICKED'
                  )
                )
                """).param(orderId).query(Long.class).single()!=0) {
            throw ApiProblem.conflict("INVALID_STATE","已有仓库开始拣货或出库，不能整单取消");
        }
    }

    public void cancel(long orderId) {
        db.sql("""
                UPDATE pick_task p JOIN stock_reservation r ON r.id=p.reservation_id
                JOIN fulfillment_line l ON l.id=r.fulfillment_line_id JOIN fulfillment_order f ON f.id=l.fulfillment_id
                SET p.status='CANCELLED' WHERE f.order_id=? AND p.status='PENDING'
                """).param(orderId).update();
        if (db.sql("UPDATE fulfillment_order SET status='CANCELLED' WHERE order_id=? AND status='READY_TO_PICK'")
                .param(orderId).update()==0) {
            throw new IllegalStateException("Allocated order has no cancelable fulfillment");
        }
    }

    public void pick(Reference fulfillment, String taskNo, long actor, String requestId) {
        var task = db.sql("""
                SELECT p.id,p.status,r.status AS reservation_status
                FROM pick_task p JOIN stock_reservation r ON r.id=p.reservation_id JOIN fulfillment_line l ON l.id=r.fulfillment_line_id
                WHERE l.fulfillment_id=? AND p.task_no=?
                """).params(fulfillment.id(),taskNo).query(Task.class).optional()
                .orElseThrow(() -> ApiProblem.missing("此履约单没有该任务"));
        if ("PICKED".equals(task.status())) return;
        if ("CANCELLED".equals(fulfillment.status()) || !"ACTIVE".equals(task.reservationStatus())
                || !"PENDING".equals(task.status())) {
            throw ApiProblem.conflict("INVALID_STATE","任务已取消或预占无效，不能确认拣货");
        }
        if (db.sql("""
                UPDATE pick_task SET status='PICKED',picked_at=CURRENT_TIMESTAMP(3),picked_by=?
                WHERE id=? AND status='PENDING'
                """).params(actor,task.id()).update()!=1) throw new IllegalStateException("Pick task changed");
        String status = incompleteTasks(fulfillment.id())==0 ? "PICKED" : "PICKING";
        if (!status.equals(fulfillment.status()) && db.sql("UPDATE fulfillment_order SET status=? WHERE id=? AND status IN ('READY_TO_PICK','PICKING')")
                .params(status,fulfillment.id()).update()!=1) throw new IllegalStateException("Fulfillment pick state changed");
        audit("PICK","PICK_TASK",taskNo,fulfillment,actor,requestId,status);
    }

    public List<Long> skuIds(long fulfillmentId) {
        return db.sql("""
                SELECT DISTINCT o.sku_id FROM fulfillment_line l JOIN channel_order_line o ON o.id=l.order_line_id
                WHERE l.fulfillment_id=? ORDER BY o.sku_id
                """).param(fulfillmentId).query(Long.class).list();
    }

    public void requireDispatchable(Reference fulfillment) {
        if (!"PICKED".equals(fulfillment.status())) throw ApiProblem.conflict("INVALID_STATE","所有任务拣完后才能出库");
        if (incompleteTasks(fulfillment.id())!=0 || skuIds(fulfillment.id()).isEmpty()) {
            throw new IllegalStateException("Fulfillment task facts do not match PICKED");
        }
    }

    private long incompleteTasks(long fulfillmentId) {
        return db.sql("""
                SELECT COUNT(*) FROM fulfillment_line l JOIN stock_reservation r ON r.fulfillment_line_id=l.id
                LEFT JOIN pick_task p ON p.reservation_id=r.id WHERE l.fulfillment_id=?
                  AND (p.id IS NULL OR p.status<>'PICKED' OR r.status<>'ACTIVE')
                """).param(fulfillmentId).query(Long.class).single();
    }

    public Optional<Dispatch> dispatchFor(Reference fulfillment) {
        return db.sql("SELECT dispatch_no,dispatched_at FROM dispatch WHERE fulfillment_id=?")
                .param(fulfillment.id()).query((r,n)->new Dispatch(r.getString(1),fulfillment.fulfillmentNo(),
                        r.getTimestamp(2).toInstant())).optional();
    }

    public Dispatch recordDispatch(Reference fulfillment, long actor) {
        db.sql("INSERT INTO dispatch(dispatch_no,fulfillment_id,actor_id) VALUES(?,?,?)")
                .params("DS-"+UUID.randomUUID(),fulfillment.id(),actor).update();
        return dispatchFor(fulfillment).orElseThrow();
    }

    public void markDispatched(Reference fulfillment, Dispatch dispatch, long actor, String requestId) {
        if (db.sql("UPDATE fulfillment_order SET status='DISPATCHED' WHERE id=? AND status='PICKED'")
                .param(fulfillment.id()).update()!=1) throw new IllegalStateException("Fulfillment dispatch state changed");
        audit("DISPATCH","DISPATCH",dispatch.dispatchNo(),fulfillment,actor,requestId,"DISPATCHED");
    }

    private void audit(String action, String type, String number, Reference fulfillment, long actor, String requestId, String status) {
        db.sql("""
                INSERT INTO operation_log(action,entity_type,entity_no,actor_key,request_id,meta_json)
                VALUES(?,?,?,?,?,JSON_OBJECT('fulfillmentNo',?,'previousStatus',?,'status',?))
                """).params(action,type,number,"user:"+actor,requestId,fulfillment.fulfillmentNo(),fulfillment.status(),status).update();
    }
}
