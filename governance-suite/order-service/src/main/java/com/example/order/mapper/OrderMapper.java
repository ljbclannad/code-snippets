package com.example.order.mapper;

import com.example.order.model.OrderEntity;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface OrderMapper {

    @Insert("INSERT INTO t_order(order_no, customer_id, product_id, quantity, amount_cents, status, create_time) " +
            "VALUES(#{orderNo}, #{customerId}, #{productId}, #{quantity}, #{amountCents}, #{status}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OrderEntity o);

    @Select("SELECT id, order_no, customer_id, product_id, quantity, amount_cents, status, create_time FROM t_order WHERE order_no = #{orderNo}")
    OrderEntity findByOrderNo(String orderNo);

    @Update("UPDATE t_order SET status = #{status} WHERE order_no = #{orderNo}")
    int updateStatus(@Param("orderNo") String orderNo, @Param("status") String status);

    @Select("SELECT id, order_no, customer_id, product_id, quantity, amount_cents, status, create_time FROM t_order ORDER BY id DESC LIMIT #{limit}")
    List<OrderEntity> latest(@Param("limit") int limit);
}
