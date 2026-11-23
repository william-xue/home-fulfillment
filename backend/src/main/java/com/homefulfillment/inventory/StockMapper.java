package com.homefulfillment.inventory;

import org.apache.ibatis.annotations.*;

@Mapper
interface StockMapper {
    @Select("SELECT id FROM sku WHERE id=#{skuId} FOR UPDATE")
    Long lockSku(long skuId);

    @Insert("""
        INSERT INTO stock_balance(sku_id,bin_id,on_hand) VALUES(#{skuId},#{binId},#{qty})
        ON DUPLICATE KEY UPDATE on_hand=on_hand+#{qty}
        """)
    int add(long skuId, long binId, int qty);

    @Update("""
        UPDATE stock_balance SET on_hand=on_hand-#{qty}
        WHERE sku_id=#{skuId} AND bin_id=#{binId} AND on_hand-reserved>=#{qty}
        """)
    int subtractAvailable(long skuId, long binId, int qty);

    @Insert("""
        INSERT INTO stock_movement(balance_id,movement_type,biz_no,delta_on_hand,delta_reserved,
            on_hand_after,reserved_after,actor_id)
        SELECT id,#{type},#{number},#{delta},0,on_hand,reserved,#{actorId}
        FROM stock_balance WHERE sku_id=#{skuId} AND bin_id=#{binId}
        """)
    int writeMovement(long skuId, long binId, String type, String number, int delta, long actorId);

    @Update("""
        UPDATE stock_balance SET reserved=reserved+#{qty}
        WHERE id=#{balanceId} AND on_hand-reserved>=#{qty}
        """)
    int incrementReserved(long balanceId, int qty);

    @Update("""
        UPDATE stock_balance SET reserved=reserved-#{qty}
        WHERE id=#{balanceId} AND reserved>=#{qty}
        """)
    int decrementReserved(long balanceId, int qty);

    @Insert("""
        INSERT INTO stock_movement(balance_id,movement_type,biz_no,delta_on_hand,delta_reserved,
            on_hand_after,reserved_after,actor_id)
        SELECT id,#{type},#{number},0,#{delta},on_hand,reserved,#{actorId}
        FROM stock_balance WHERE id=#{balanceId}
        """)
    int writeReservationMovement(long balanceId, String type, String number, int delta, long actorId);

    @Update("""
        UPDATE stock_balance SET on_hand=on_hand-#{qty},reserved=reserved-#{qty}
        WHERE id=#{balanceId} AND on_hand>=#{qty} AND reserved>=#{qty}
        """)
    int consume(long balanceId, long qty);

    @Insert("""
        INSERT INTO stock_movement(balance_id,movement_type,biz_no,delta_on_hand,delta_reserved,
            on_hand_after,reserved_after,actor_id)
        SELECT id,'DISPATCH',#{dispatchNo},-#{qty},-#{qty},on_hand,reserved,#{actorId}
        FROM stock_balance WHERE id=#{balanceId}
        """)
    int writeDispatch(long balanceId, String dispatchNo, long qty, long actorId);
}
