package com.homefulfillment.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.homefulfillment.events.OutboxStore;
import com.homefulfillment.identity.Identity.Actor;
import com.homefulfillment.inventory.InventoryLedger;
import com.homefulfillment.oms.Orders;
import com.homefulfillment.web.ApiProblem;
import com.homefulfillment.web.CommandJournal;
import com.homefulfillment.wms.Fulfillments;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
class OutboundCommands {
    private final Orders orders;
    private final Fulfillments fulfillments;
    private final InventoryLedger inventory;
    private final CommandJournal journal;
    private final OutboundRead read;
    private final OutboxStore outbox;

    OutboundCommands(Orders orders,Fulfillments fulfillments,InventoryLedger inventory,CommandJournal journal,
                     OutboundRead read,OutboxStore outbox) {
        this.orders=orders; this.fulfillments=fulfillments; this.inventory=inventory; this.journal=journal;
        this.read=read; this.outbox=outbox;
    }

    ResponseEntity<JsonNode> pick(Actor actor,String number,String task,String key,String requestId) {
        var reference=authorized(actor,number);
        return journal.execute(actor.id(),"PICK_TASK",key,Map.of("path","/fulfillments/"+number+"/tasks/"+task+"/pick"),() -> {
            orders.lock(reference.orderNo());
            fulfillments.pick(fulfillments.reference(number),task,actor.id(),requestId);
            return ResponseEntity.ok(read.detail(number,actor));
        });
    }

    ResponseEntity<JsonNode> dispatch(Actor actor,String number,String key,String requestId) {
        var reference=authorized(actor,number);
        return journal.execute(actor.id(),"DISPATCH",key,Map.of("path","/fulfillments/"+number+"/dispatch"),() -> {
            orders.lock(reference.orderNo());
            var f=fulfillments.reference(number);
            var previous=fulfillments.dispatchFor(f);
            if (previous.isPresent()) return ResponseEntity.ok(previous.get());
            fulfillments.requireDispatchable(f);
            fulfillments.skuIds(f.id()).forEach(inventory::lockSku);
            var dispatch=fulfillments.recordDispatch(f,actor.id());
            inventory.consume(f.id(),dispatch.dispatchNo(),actor.id());
            fulfillments.markDispatched(f,dispatch,actor.id(),requestId);
            outbox.recordDispatch(dispatch.dispatchNo(),number,f.warehouseCode(),dispatch.dispatchedAt());
            return ResponseEntity.status(201).body(dispatch);
        });
    }

    private Fulfillments.Reference authorized(Actor actor,String number) {
        if (!actor.canWork()) throw ApiProblem.forbidden();
        var ref=fulfillments.reference(number);
        actor.requireWarehouse(ref.warehouseCode(),true);
        return ref;
    }
}
