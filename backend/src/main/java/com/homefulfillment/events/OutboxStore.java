package com.homefulfillment.events;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.homefulfillment.web.ApiProblem;
import com.homefulfillment.web.Page;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
public class OutboxStore {
    private static final String COLUMNS="""
            event_id,event_type,payload_json->>'$.dispatchNo',payload_json->>'$.fulfillmentNo',
            status,attempt,next_attempt_at,last_error,occurred_at,published_at
            """;
    private final JdbcClient db;
    private final ObjectMapper json;
    public OutboxStore(JdbcClient db,ObjectMapper json) { this.db=db; this.json=json; }

    public record View(String eventId,String eventType,String dispatchNo,String fulfillmentNo,String status,int attempt,
                       Instant nextAttemptAt,String lastError,Instant occurredAt,Instant publishedAt) {}
    record Claim(String id,String eventType,String payload,String token,int attempt) {}
    private record Due(String id,String eventType,String payload,String status,String token,int attempt) {}

    @Transactional(propagation=Propagation.MANDATORY)
    public void recordDispatch(String dispatchNo,String fulfillmentNo,String warehouseCode,Instant occurredAt) {
        var event=new DispatchEvent(UUID.randomUUID().toString(),DispatchEvent.TYPE,occurredAt,dispatchNo,fulfillmentNo,warehouseCode);
        db.sql("""
                INSERT INTO outbox_event(event_id,event_type,dedupe_key,payload_json,status,occurred_at)
                VALUES(?,?,?,?,'PENDING',?)
                """).params(event.eventId(),event.eventType(),event.eventType()+":"+dispatchNo,json.valueToTree(event).toString(),
                        java.sql.Timestamp.from(occurredAt)).update();
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW,isolation=Isolation.READ_COMMITTED)
    public Optional<Claim> claim() {
        var due=db.sql("""
                SELECT event_id AS id,event_type,CAST(payload_json AS CHAR) AS payload,status,claim_token AS token,attempt
                FROM outbox_event WHERE (status='PENDING' AND next_attempt_at<=CURRENT_TIMESTAMP(3))
                    OR (status='PUBLISHING' AND lease_until<=CURRENT_TIMESTAMP(3))
                ORDER BY next_attempt_at,event_id LIMIT 1 FOR UPDATE SKIP LOCKED
                """).query(Due.class).optional();
        if (due.isEmpty()) return Optional.empty();
        var row=due.get();
        if ("PUBLISHING".equals(row.status())) {
            fail(new Claim(row.id(),row.eventType(),row.payload(),row.token(),row.attempt()),"认领租约已超时");
            return Optional.empty();
        }
        String token=UUID.randomUUID().toString();
        db.sql("""
                UPDATE outbox_event SET status='PUBLISHING',attempt=attempt+1,claim_token=?,
                    lease_until=TIMESTAMPADD(MINUTE,2,CURRENT_TIMESTAMP(3)) WHERE event_id=?
                """).params(token,row.id()).update();
        return Optional.of(new Claim(row.id(),row.eventType(),row.payload(),token,row.attempt()+1));
    }

    public void published(Claim claim) {
        db.sql("""
                UPDATE outbox_event SET status='PUBLISHED',published_at=CURRENT_TIMESTAMP(3),
                    claim_token=NULL,lease_until=NULL,last_error=NULL
                WHERE event_id=? AND claim_token=? AND status='PUBLISHING'
                """).params(claim.id(),claim.token()).update();
    }

    public void fail(Claim claim,String error) {
        int[] backoff={2,5,15,60};
        boolean exhausted=claim.attempt()>=5;
        int seconds=exhausted?0:backoff[claim.attempt()-1];
        db.sql("""
                UPDATE outbox_event SET status=?,next_attempt_at=TIMESTAMPADD(SECOND,?,CURRENT_TIMESTAMP(3)),
                    claim_token=NULL,lease_until=NULL,last_error=?
                WHERE event_id=? AND claim_token=? AND status='PUBLISHING'
                """).params(exhausted?"FAILED":"PENDING",seconds,error,claim.id(),claim.token()).update();
    }

    public Page<View> list(String status,String dispatchNo,Page.Window page) {
        String filter="""
                FROM outbox_event WHERE (:status='' OR status=:status)
                    AND (:dispatch='' OR payload_json->>'$.dispatchNo'=:dispatch)
                """;
        var params=Map.of("status",status.strip(),"dispatch",dispatchNo.strip());
        long total=db.sql("SELECT COUNT(*) "+filter).params(params).query(Long.class).single();
        var rows=db.sql("SELECT "+COLUMNS+filter+" ORDER BY occurred_at DESC,event_id DESC LIMIT :size OFFSET :offset")
                .params(params).param("size",page.size()).param("offset",page.offset()).query(OutboxStore::view).list();
        return page.result(rows,total);
    }

    @Transactional(propagation=Propagation.MANDATORY)
    public View retry(String id,long actor,String requestId) {
        if (db.sql("""
                UPDATE outbox_event SET status='PENDING',attempt=0,next_attempt_at=CURRENT_TIMESTAMP(3)
                WHERE event_id=? AND status='FAILED'
                """).param(id).update()!=1) {
            detail(id);
            throw ApiProblem.conflict("INVALID_STATE","只有投递失败的事件可手动重试");
        }
        db.sql("""
                INSERT INTO operation_log(action,entity_type,entity_no,actor_key,request_id,meta_json)
                VALUES('RETRY_OUTBOX','OUTBOX',?,?,?,JSON_OBJECT('previousStatus','FAILED','status','PENDING'))
                """).params(id,"user:"+actor,requestId).update();
        return detail(id);
    }

    private View detail(String id) {
        return db.sql("SELECT "+COLUMNS+" FROM outbox_event WHERE event_id=?").param(id).query(OutboxStore::view).optional()
                .orElseThrow(() -> ApiProblem.missing("事件不存在"));
    }

    private static View view(ResultSet r,int n) throws SQLException {
        var published=r.getTimestamp(10);
        return new View(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getInt(6),
                r.getTimestamp(7).toInstant(),r.getString(8),r.getTimestamp(9).toInstant(),published==null?null:published.toInstant());
    }
}
