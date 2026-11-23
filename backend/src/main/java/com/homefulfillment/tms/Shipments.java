package com.homefulfillment.tms;

import com.fasterxml.jackson.databind.JsonNode;
import com.homefulfillment.identity.Identity.Actor;
import com.homefulfillment.web.ApiProblem;
import com.homefulfillment.web.CommandJournal;
import com.homefulfillment.web.Page;
import com.homefulfillment.wms.Fulfillments;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class Shipments {
    private static final String JOIN = """
            FROM shipment s JOIN dispatch d ON d.id=s.dispatch_id JOIN fulfillment_order f ON f.id=d.fulfillment_id
            JOIN warehouse w ON w.id=f.warehouse_id
            """;
    private static final String COLUMNS = "s.id,s.shipment_no,d.dispatch_no,f.fulfillment_no,w.code,s.carrier,s.tracking_no,s.status,s.delivered_at";

    private final JdbcClient db;
    private final Fulfillments fulfillments;
    private final CommandJournal journal;
    Shipments(JdbcClient db, Fulfillments fulfillments, CommandJournal journal) {
        this.db=db; this.fulfillments=fulfillments; this.journal=journal;
    }

    public record Summary(String shipmentNo, String dispatchNo, String fulfillmentNo, String warehouseCode, String carrier,
                          String trackingNo, String status, Instant deliveredAt) {}
    record Event(String sourceEventNo, String type, Instant occurredAt) {}
    record Detail(String shipmentNo, String dispatchNo, String fulfillmentNo, String warehouseCode, String carrier,
                  String trackingNo, String status, Instant deliveredAt, List<Event> events, List<String> allowedActions) {}
    private record Row(long id, Summary summary) {}
    public record Ensured(String shipmentNo,boolean created) {}

    ResponseEntity<JsonNode> create(Actor actor, String fulfillmentNo, String key, String requestId) {
        actor.requireOps();
        return journal.execute(actor.id(),"CREATE_SHIPMENT",key,Map.of("path","/fulfillments/"+fulfillmentNo+"/shipment"),() -> {
            var reference=fulfillments.reference(fulfillmentNo);
            var dispatch=fulfillments.dispatchFor(reference)
                    .orElseThrow(() -> ApiProblem.conflict("INVALID_STATE","该履约单尚未出库，不能建运单"));
            var ensured=ensureForDispatch(dispatch.dispatchNo(),"user:"+actor.id(),requestId);
            return ResponseEntity.status(ensured.created()?201:200).body(detail(ensured.shipmentNo(),actor));
        });
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public Ensured ensureForDispatch(String dispatchNo,String actorKey,String requestId) {
        String number="TS-"+UUID.randomUUID();
        db.sql("""
                INSERT INTO shipment(shipment_no,dispatch_id,carrier,tracking_no,status)
                SELECT ?,id,'SIM',?,'CREATED' FROM dispatch WHERE dispatch_no=?
                ON DUPLICATE KEY UPDATE shipment.id=shipment.id
                """).params(number,"SIM-"+dispatchNo,dispatchNo).update();
        var shipment=forDispatch(dispatchNo).orElseThrow();
        boolean created=shipment.shipmentNo().equals(number);
        if (created) audit("CREATE_SHIPMENT",number,actorKey,requestId,null,"CREATED",null);
        return new Ensured(shipment.shipmentNo(),created);
    }

    ResponseEntity<JsonNode> addEvent(Actor actor,String number,String source,String type,Instant raw,
                                      String key,String requestId) {
        actor.requireOps();
        Instant occurred=raw.truncatedTo(ChronoUnit.MILLIS);
        return journal.execute(actor.id(),"ADD_TRACKING_EVENT",key,Map.of("path","/shipments/"+number+"/events",
                "sourceEventNo",source,"type",type,"occurredAt",occurred.toString()),() -> {
            // Serializes events per shipment. A replay is checked before the terminal/time checks.
            long id = db.sql("SELECT id FROM shipment WHERE shipment_no=? FOR UPDATE").param(number)
                    .query(Long.class).optional().orElseThrow(() -> ApiProblem.missing("运单不存在"));
            var previous = db.sql("""
                    SELECT source_event_no,type,occurred_at FROM tracking_event WHERE shipment_id=? AND source_event_no=?
                    """).params(id,source).query(Shipments::event).optional();
            if (previous.isPresent()) {
                if (!previous.get().type().equals(type) || !previous.get().occurredAt().equals(occurred)) {
                    throw ApiProblem.conflict("TRACKING_EVENT_CONFLICT","同一事件号已登记不同轨迹");
                }
                return ResponseEntity.ok(detail(number,actor));
            }
            Instant dispatchTime = db.sql("""
                    SELECT d.dispatched_at FROM shipment s JOIN dispatch d ON d.id=s.dispatch_id WHERE s.id=?
                    """).param(id).query((r,n)->r.getTimestamp(1).toInstant()).single();
            Instant latest = db.sql("""
                    SELECT occurred_at FROM tracking_event WHERE shipment_id=? ORDER BY occurred_at DESC,id DESC LIMIT 1
                    """).param(id).query((r,n)->r.getTimestamp(1).toInstant()).optional().orElse(dispatchTime);
            if (occurred.isBefore(dispatchTime) || occurred.isBefore(latest)) {
                throw ApiProblem.conflict("TRACKING_EVENT_OUT_OF_ORDER","轨迹不能早于出库或最新轨迹");
            }
            String currentStatus=row(number).summary().status();
            if ("DELIVERED".equals(currentStatus)) {
                throw ApiProblem.conflict("INVALID_STATE","运单已签收，不接收新轨迹");
            }
            db.sql("""
                    INSERT INTO tracking_event(shipment_id,source_event_no,type,occurred_at,actor_id) VALUES(?,?,?,?,?)
                    """).params(id,source,type,Timestamp.from(occurred),actor.id()).update();
            if (db.sql("UPDATE shipment SET status=?,delivered_at=? WHERE id=? AND status<>'DELIVERED'")
                    .params(type,"DELIVERED".equals(type)?Timestamp.from(occurred):null,id).update()!=1) {
                throw new IllegalStateException("Shipment state changed");
            }
            audit("ADD_TRACKING_EVENT",number,"user:"+actor.id(),requestId,currentStatus,type,source);
            return ResponseEntity.ok(detail(number,actor));
        });
    }

    @Transactional(readOnly=true)
    public Page<Summary> list(Actor actor,String warehouse,String status,String fulfillment,String number,Page.Window page) {
        actor.requireOrdersRead();
        String filter = """
                WHERE (:warehouse='' OR w.code=:warehouse) AND (:status='' OR s.status=:status)
                AND (:fulfillment='' OR f.fulfillment_no=:fulfillment) AND (:number='' OR s.shipment_no=:number)
                """;
        var params=Map.of("warehouse",warehouse.strip(),"status",status.strip(),"fulfillment",fulfillment.strip(),"number",number.strip());
        long total=db.sql("SELECT COUNT(*) "+JOIN+filter).params(params).query(Long.class).single();
        var rows=db.sql("SELECT "+COLUMNS+" "+JOIN+filter+" ORDER BY s.id DESC LIMIT :size OFFSET :offset").params(params)
                .param("size",page.size()).param("offset",page.offset()).query((r,n)->row(r,n).summary()).list();
        return page.result(rows,total);
    }

    @Transactional(readOnly=true)
    public Detail detail(String number,Actor actor) {
        actor.requireOrdersRead();
        var row=row(number);
        var s=row.summary();
        var events=db.sql("SELECT source_event_no,type,occurred_at FROM tracking_event WHERE shipment_id=? ORDER BY occurred_at,id")
                .param(row.id()).query(Shipments::event).list();
        return new Detail(s.shipmentNo(),s.dispatchNo(),s.fulfillmentNo(),s.warehouseCode(),s.carrier(),s.trackingNo(),
                s.status(),s.deliveredAt(),events,actor.isOps() && !"DELIVERED".equals(s.status())?List.of("ADD_TRACKING_EVENT"):List.of());
    }

    public Optional<Summary> forDispatch(String dispatchNo) {
        return db.sql("SELECT "+COLUMNS+" "+JOIN+" WHERE d.dispatch_no=?").param(dispatchNo)
                .query((r,n)->row(r,n).summary()).optional();
    }

    private Row row(String number) {
        return db.sql("SELECT "+COLUMNS+" "+JOIN+" WHERE s.shipment_no=?").param(number).query(Shipments::row).optional()
                .orElseThrow(() -> ApiProblem.missing("运单不存在"));
    }

    private static Row row(ResultSet r,int n) throws SQLException {
        Timestamp delivered=r.getTimestamp(9);
        return new Row(r.getLong(1),new Summary(r.getString(2),r.getString(3),r.getString(4),r.getString(5),
                r.getString(6),r.getString(7),r.getString(8),delivered==null?null:delivered.toInstant()));
    }
    private static Event event(ResultSet r,int n) throws SQLException {
        return new Event(r.getString(1),r.getString(2),r.getTimestamp(3).toInstant());
    }

    private void audit(String action,String number,String actorKey,String requestId,String previous,String status,String source) {
        db.sql("""
                INSERT INTO operation_log(action,entity_type,entity_no,actor_key,request_id,meta_json)
                VALUES(?,'SHIPMENT',?,?,?,JSON_OBJECT('previousStatus',?,'status',?,'sourceEventNo',?))
                """).params(action,number,actorKey,requestId,previous,status,source).update();
    }
}
