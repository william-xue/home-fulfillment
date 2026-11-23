package com.homefulfillment.fulfillment;

import com.fasterxml.jackson.databind.JsonNode;
import com.homefulfillment.catalog.OrderItems;
import com.homefulfillment.identity.Identity.Actor;
import com.homefulfillment.inventory.InventoryLedger;
import com.homefulfillment.oms.Orders;
import com.homefulfillment.web.ApiProblem;
import com.homefulfillment.web.CommandJournal;
import com.homefulfillment.wms.Fulfillments;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
class OrderCommands {
    private final Orders orders;
    private final Fulfillments fulfillments;
    private final InventoryLedger inventory;
    private final CommandJournal journal;
    private final OrderRead read;

    OrderCommands(Orders orders, Fulfillments fulfillments, InventoryLedger inventory, CommandJournal journal, OrderRead read) {
        this.orders = orders; this.fulfillments = fulfillments; this.inventory = inventory; this.journal = journal; this.read = read;
    }

    ResponseEntity<JsonNode> accept(Actor actor, String channel, String external, Instant paidAt,
                                    List<OrderItems.Input> lines, String key, String requestId) {
        actor.requireOps();
        Instant paid = paidAt.truncatedTo(ChronoUnit.MILLIS);
        if (paid.isBefore(Instant.parse("1000-01-01T00:00:00Z")) || !paid.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) {
            throw ApiProblem.invalid("支付时间不合法");
        }
        var sorted = lines.stream().sorted(Comparator.comparing(OrderItems.Input::skuCode)).toList();
        var business = Map.of("paidAt",paid.toString(),"lines",sorted);
        var request = Map.of("path","/orders","channel",channel,"channelOrderNo",external,"paidAt",paid.toString(),"lines",sorted);
        return journal.execute(actor.id(),"ACCEPT_ORDER",key,request,() -> {
            var accepted = orders.accept(channel,external,paid,lines,journal.fingerprint(business),actor.id(),requestId);
            return ResponseEntity.status(accepted.created()?201:200).body(read.detail(accepted.number(),actor));
        });
    }

    private record Take(long warehouseId, long orderLineId, long balanceId, int quantity) {}

    ResponseEntity<JsonNode> route(Actor actor, String number, String key, String requestId) {
        actor.requireOps();
        return journal.execute(actor.id(),"ROUTE_ORDER",key,Map.of("path","/orders/"+number+"/route"),() -> {
            var order = orders.lock(number);
            if ("CANCELLED".equals(order.routeStatus())) throw ApiProblem.conflict("INVALID_STATE","订单已取消，不能路由");
            if ("ALLOCATED".equals(order.routeStatus())) return ResponseEntity.ok(read.detail(number,actor));

            var lines = orders.lines(order.id());
            lockSkus(lines);
            var plan = new ArrayList<Take>();
            var shortages = new ArrayList<Orders.Shortage>();
            for (var line : lines) {
                int remaining = line.quantity();
                for (var bin : inventory.availablePickable(line.skuId())) {
                    int take = (int)Math.min(remaining,bin.quantity());
                    plan.add(new Take(bin.warehouseId(),line.id(),bin.balanceId(),take));
                    remaining -= take;
                    if (remaining==0) break;
                }
                if (remaining>0) shortages.add(new Orders.Shortage(line.skuCode(),line.quantity(),line.quantity()-remaining,remaining));
            }
            // An in-memory plan is not an inventory promise. Persist only when every line is satisfiable.
            if (shortages.isEmpty()) allocate(order.id(),plan,actor.id());
            orders.recordRoute(order,number,shortages,actor.id(),requestId);
            return ResponseEntity.ok(read.detail(number,actor));
        });
    }

    private void allocate(long orderId, List<Take> plan, long actor) {
        var byWarehouse = plan.stream().collect(Collectors.groupingBy(Take::warehouseId,LinkedHashMap::new,Collectors.toList()));
        for (var warehouse : byWarehouse.entrySet()) {
            long fulfillmentId = fulfillments.open(orderId,warehouse.getKey());
            var byLine = warehouse.getValue().stream().collect(Collectors.groupingBy(Take::orderLineId,LinkedHashMap::new,Collectors.toList()));
            for (var line : byLine.entrySet()) {
                int total = line.getValue().stream().mapToInt(Take::quantity).sum();
                long lineId = fulfillments.addLine(fulfillmentId,line.getKey(),total);
                for (Take take : line.getValue()) {
                    long reservation = inventory.reserve(lineId,take.balanceId(),take.quantity(),actor);
                    fulfillments.addTask(reservation);
                }
            }
        }
    }

    ResponseEntity<JsonNode> cancel(Actor actor, String number, String reason, String key, String requestId) {
        actor.requireOps();
        return journal.execute(actor.id(),"CANCEL_ORDER",key,Map.of("path","/orders/"+number+"/cancel","reason",reason),() -> {
            var order = orders.lock(number);
            if ("CANCELLED".equals(order.routeStatus())) {
                if (!reason.equals(order.cancelReason())) throw ApiProblem.conflict("INVALID_STATE","订单已按另一原因取消");
                return ResponseEntity.ok(read.detail(number,actor));
            }
            if ("ALLOCATED".equals(order.routeStatus())) {
                lockSkus(orders.lines(order.id()));
                fulfillments.requireAllUnpicked(order.id());
                inventory.release(order.id(),actor.id());
                fulfillments.cancel(order.id());
            }
            orders.cancel(order,number,reason,actor.id(),requestId);
            return ResponseEntity.ok(read.detail(number,actor));
        });
    }

    private void lockSkus(List<Orders.Line> lines) {
        lines.stream().mapToLong(Orders.Line::skuId).distinct().sorted().forEach(inventory::lockSku);
    }
}
