package com.homefulfillment.events;

import com.homefulfillment.tms.Shipments;
import com.homefulfillment.wms.Fulfillments;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
class DispatchConsumer {
    static final String GROUP="hf-shipment-consumer-v1";
    private final JdbcClient db;
    private final Shipments shipments;
    private final Fulfillments fulfillments;
    DispatchConsumer(JdbcClient db,Shipments shipments,Fulfillments fulfillments) {
        this.db=db; this.shipments=shipments; this.fulfillments=fulfillments;
    }

    @Transactional(isolation=Isolation.READ_COMMITTED)
    public boolean apply(DispatchEvent event) {
        if (!DispatchEvent.TYPE.equals(event.eventType()) || event.eventId()==null ||
                !event.eventId().matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("Unsupported dispatch event");
        try {
            db.sql("INSERT INTO consumer_inbox(consumer_name,event_id) VALUES(?,?)").params(GROUP,event.eventId()).update();
        } catch (DuplicateKeyException alreadyProcessed) {
            return false;
        }
        var ref=fulfillments.reference(event.fulfillmentNo());
        var dispatch=fulfillments.dispatchFor(ref).orElseThrow();
        if (!ref.warehouseCode().equals(event.warehouseCode()) || !dispatch.dispatchNo().equals(event.dispatchNo())
                || !dispatch.dispatchedAt().equals(event.occurredAt())) throw new IllegalStateException("Dispatch facts do not match event");
        shipments.ensureForDispatch(dispatch.dispatchNo(),"system:shipment-consumer",event.eventId());
        return true;
    }
}
