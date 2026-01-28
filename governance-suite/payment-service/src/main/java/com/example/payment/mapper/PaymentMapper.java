package com.example.payment.mapper;

import com.example.payment.model.PaymentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

public interface PaymentMapper {

    @Insert("INSERT INTO t_payment(payment_no, order_no, customer_id, amount_cents, status, create_time) " +
            "VALUES(#{paymentNo}, #{orderNo}, #{customerId}, #{amountCents}, #{status}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(PaymentEntity p);

    @Select("SELECT id, payment_no, order_no, customer_id, amount_cents, status, create_time FROM t_payment WHERE order_no = #{orderNo}")
    PaymentEntity findByOrderNo(String orderNo);

    @Update("UPDATE t_payment SET status = #{status} WHERE order_no = #{orderNo}")
    int updateStatus(@Param("orderNo") String orderNo, @Param("status") String status);
}
