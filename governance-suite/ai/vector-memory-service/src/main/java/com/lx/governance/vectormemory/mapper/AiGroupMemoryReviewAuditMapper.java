package com.lx.governance.vectormemory.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.lx.governance.vectormemory.entity.AiGroupMemoryReviewAuditEntity;

@Mapper
public interface AiGroupMemoryReviewAuditMapper {

    void insert(AiGroupMemoryReviewAuditEntity entity);

    List<AiGroupMemoryReviewAuditEntity> selectByCandidateId(@Param("candidateId") Long candidateId);
}
