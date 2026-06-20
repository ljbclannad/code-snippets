package com.lx.governance.vectormemory.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.lx.governance.vectormemory.entity.AiGroupMemoryLifecycleEventEntity;

@Mapper
public interface AiGroupMemoryLifecycleEventMapper {

    void insert(AiGroupMemoryLifecycleEventEntity entity);

    List<AiGroupMemoryLifecycleEventEntity> selectByEntity(@Param("entityType") String entityType,
                                                           @Param("entityId") Long entityId);

    List<AiGroupMemoryLifecycleEventEntity> selectRecentByScope(@Param("customerId") String customerId,
                                                                @Param("groupId") String groupId,
                                                                @Param("memoryType") String memoryType,
                                                                @Param("limit") int limit);
}
