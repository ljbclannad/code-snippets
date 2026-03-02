package com.lx.governance.order.repo;

import com.lx.governance.order.domain.OrderDto;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OrderRepository {
    int insert(@Param("orderNo") String orderNo,
               @Param("customerId") String customerId,
               @Param("productId") String productId,
               @Param("quantity") int quantity,
               @Param("amountCents") int amountCents,
               @Param("status") String status);

    OrderDto findByOrderNo(@Param("orderNo") String orderNo);

    int updateStatus(@Param("orderNo") String orderNo, @Param("status") String status);
}
