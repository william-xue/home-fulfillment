package com.homefulfillment.inbound;

import org.apache.ibatis.annotations.*;

@Mapper
interface InboundMapper {
    @Select("SELECT id FROM purchase_order WHERE id=#{id} FOR UPDATE")
    Long lockPurchase(long id);

    @Update("""
        UPDATE purchase_order_line SET received_qty=received_qty+#{qty}
        WHERE id=#{id} AND ordered_qty-received_qty>=#{qty}
        """)
    int incrementReceived(long id, int qty);

    @Insert("""
        INSERT INTO receipt(receipt_no,po_line_id,receiving_bin_id,qty,actor_id)
        VALUES(#{no},#{lineId},#{binId},#{qty},#{actorId})
        """)
    int insertReceipt(String no, long lineId, long binId, int qty, long actorId);

    @Update("""
        UPDATE purchase_order SET status=CASE WHEN EXISTS(
            SELECT 1 FROM purchase_order_line WHERE po_id=#{id} AND received_qty<ordered_qty
        ) THEN 'PARTIAL' ELSE 'RECEIVED' END WHERE id=#{id}
        """)
    int refreshPurchaseStatus(long id);

    @Update("""
        UPDATE receipt SET put_away_qty=put_away_qty+#{qty}
        WHERE id=#{id} AND qty-put_away_qty>=#{qty}
        """)
    int incrementPutAway(long id, int qty);

    @Insert("""
        INSERT INTO put_away(put_away_no,receipt_id,destination_bin_id,qty,actor_id)
        VALUES(#{no},#{receiptId},#{binId},#{qty},#{actorId})
        """)
    int insertPutAway(String no, long receiptId, long binId, int qty, long actorId);
}
