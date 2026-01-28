package com.example.inventory.mapper;

import com.example.inventory.model.StockEntity;
import org.apache.ibatis.annotations.*;

public interface StockMapper {

    @Select("SELECT id, product_id, available, version FROM t_stock WHERE product_id = #{productId}")
    StockEntity findByProductId(String productId);

    /**
     * 乐观锁扣减：available >= qty 且 version 匹配才扣减成功
     */
    @Update("UPDATE t_stock SET available = available - #{qty}, version = version + 1 " +
            "WHERE product_id = #{productId} AND available >= #{qty} AND version = #{version}")
    int deduct(@Param("productId") String productId, @Param("qty") int qty, @Param("version") int version);
}
