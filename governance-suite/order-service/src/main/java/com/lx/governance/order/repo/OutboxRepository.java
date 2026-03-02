package com.lx.governance.order.repo;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface OutboxRepository {
    int insert(@Param("eventId") String eventId,
               @Param("aggregateType") String aggregateType,
               @Param("aggregateId") String aggregateId,
               @Param("eventType") String eventType,
               @Param("payload") String payload);
}
