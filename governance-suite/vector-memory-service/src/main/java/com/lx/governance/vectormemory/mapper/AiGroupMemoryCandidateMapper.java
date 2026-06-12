package com.lx.governance.vectormemory.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.lx.governance.vectormemory.entity.AiGroupMemoryCandidateEntity;

@Mapper
public interface AiGroupMemoryCandidateMapper {

    void insert(AiGroupMemoryCandidateEntity entity);

    void updateStatus(@Param("id") Long id,
                      @Param("status") String status,
                      @Param("reviewedBy") String reviewedBy,
                      @Param("reviewedAt") java.time.LocalDateTime reviewedAt,
                      @Param("confirmedMemoryId") Long confirmedMemoryId);

    void incrementHitCount(@Param("id") Long id,
                           @Param("confidenceScore") java.math.BigDecimal confidenceScore,
                           @Param("sourceMessage") String sourceMessage,
                           @Param("lastSeenAt") java.time.LocalDateTime lastSeenAt);

    AiGroupMemoryCandidateEntity selectById(@Param("id") Long id);

    AiGroupMemoryCandidateEntity selectLatestByNormalized(@Param("customerId") String customerId,
                                                          @Param("groupId") String groupId,
                                                          @Param("normalizedKey") String normalizedKey,
                                                          @Param("normalizedValue") String normalizedValue);

    List<AiGroupMemoryCandidateEntity> selectActiveByCustomerId(@Param("customerId") String customerId,
                                                                @Param("groupId") String groupId);
}
