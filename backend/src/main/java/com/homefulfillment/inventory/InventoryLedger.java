package com.homefulfillment.inventory;

import com.homefulfillment.web.ApiProblem;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryLedger {
    private final StockMapper stock;
    private final JdbcClient db;

    InventoryLedger(StockMapper stock, JdbcClient db) { this.stock = stock; this.db = db; }

    public void lockSku(long skuId) {
        if (stock.lockSku(skuId) == null) throw new IllegalStateException("SKU disappeared");
    }

    public void receive(long skuId, long binId, int qty, String receiptNo, long actorId) {
        add(skuId, binId, qty);
        record(skuId, binId, "RECEIVE", receiptNo, qty, actorId);
    }

    public void moveToPickable(long skuId, long fromBin, long toBin, int qty, String putAwayNo, long actorId) {
        if (stock.subtractAvailable(skuId, fromBin, qty) != 1) {
            throw ApiProblem.conflict("INVALID_STATE", "暂存余额不足，未完成上架");
        }
        record(skuId, fromBin, "PUT_AWAY_OUT", putAwayNo, -qty, actorId);
        add(skuId, toBin, qty);
        record(skuId, toBin, "PUT_AWAY_IN", putAwayNo, qty, actorId);
    }

    public record Available(long balanceId, long warehouseId, long quantity) {}
    private record Reservation(long id, long balanceId, String number, int quantity) {}

    public List<Available> availablePickable(long skuId) {
        return db.sql("""
                SELECT sb.id AS balance_id,w.id AS warehouse_id,sb.on_hand-sb.reserved AS quantity
                FROM stock_balance sb JOIN bin b ON b.id=sb.bin_id JOIN warehouse w ON w.id=b.warehouse_id
                WHERE sb.sku_id=? AND b.type='PICKABLE' AND sb.on_hand>sb.reserved
                ORDER BY w.route_priority,b.pick_priority,b.id
                """).param(skuId).query(Available.class).list();
    }

    public long reserve(long lineId, long balanceId, int qty, long actorId) {
        if (stock.incrementReserved(balanceId,qty)!=1) throw new IllegalStateException("Available stock changed");
        String number = "RS-" + UUID.randomUUID();
        db.sql("""
                INSERT INTO stock_reservation(reservation_no,fulfillment_line_id,balance_id,qty,status)
                VALUES(?,?,?,?,'ACTIVE')
                """).params(number,lineId,balanceId,qty).update();
        reservationMovement(balanceId,"RESERVE",number,qty,actorId);
        return db.sql("SELECT id FROM stock_reservation WHERE reservation_no=?").param(number).query(Long.class).single();
    }

    public void release(long orderId, long actorId) {
        List<Reservation> active = db.sql("""
                SELECT r.id,r.balance_id,r.reservation_no AS number,r.qty AS quantity
                FROM stock_reservation r JOIN fulfillment_line l ON l.id=r.fulfillment_line_id
                JOIN fulfillment_order f ON f.id=l.fulfillment_id WHERE f.order_id=? AND r.status='ACTIVE' ORDER BY r.id
                """).param(orderId).query(Reservation.class).list();
        if (active.isEmpty()) throw new IllegalStateException("Allocated order has no active reservations");
        for (var r : active) {
            if (stock.decrementReserved(r.balanceId(),r.quantity())!=1
                    || db.sql("UPDATE stock_reservation SET status='RELEASED' WHERE id=? AND status='ACTIVE'")
                    .param(r.id()).update()!=1) throw new IllegalStateException("Reservation cannot be released");
            reservationMovement(r.balanceId(),"RELEASE",r.number(),-r.quantity(),actorId);
        }
    }

    private record Consumption(long balanceId, long quantity) {}

    public void consume(long fulfillmentId, String dispatchNo, long actorId) {
        // A dispatch books a single movement per balance, even if more than one reservation uses it.
        var parts = db.sql("""
                SELECT r.balance_id,SUM(r.qty) AS quantity
                FROM stock_reservation r JOIN fulfillment_line l ON l.id=r.fulfillment_line_id
                WHERE l.fulfillment_id=? AND r.status='ACTIVE' GROUP BY r.balance_id ORDER BY r.balance_id
                """).param(fulfillmentId).query(Consumption.class).list();
        if (parts.isEmpty()) throw new IllegalStateException("Picked fulfillment has no active reservations");
        for (var part : parts) {
            if (stock.consume(part.balanceId(),part.quantity())!=1 ||
                stock.writeDispatch(part.balanceId(),dispatchNo,part.quantity(),actorId)!=1) {
                throw new IllegalStateException("Dispatch could not debit inventory");
            }
        }
        if (db.sql("""
                UPDATE stock_reservation r JOIN fulfillment_line l ON l.id=r.fulfillment_line_id
                SET r.status='CONSUMED' WHERE l.fulfillment_id=? AND r.status='ACTIVE'
                """).param(fulfillmentId).update()==0) throw new IllegalStateException("No reservations consumed");
    }

    private void reservationMovement(long balanceId, String type, String number, int qty, long actorId) {
        if (stock.writeReservationMovement(balanceId,type,number,qty,actorId)!=1) {
            throw new IllegalStateException("Reservation movement not persisted");
        }
    }

    private void add(long skuId, long binId, int qty) {
        if (stock.add(skuId, binId, qty) < 1) throw new IllegalStateException("Stock addition not persisted");
    }

    private void record(long skuId, long binId, String type, String number, int delta, long actorId) {
        if (stock.writeMovement(skuId, binId, type, number, delta, actorId) != 1) {
            throw new IllegalStateException("Stock movement not persisted");
        }
    }
}
