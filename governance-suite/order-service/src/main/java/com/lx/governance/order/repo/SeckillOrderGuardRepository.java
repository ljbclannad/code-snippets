package com.lx.governance.order.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SeckillOrderGuardRepository {
    int insert(@Param("userId") String userId, @Param("skuId") String skuId, @Param("reqId") String reqId);
}
