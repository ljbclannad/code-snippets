package com.lx.governance.vectormemory.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.lx.governance.vectormemory.entity.AiGroupMemoryConflictEntity;

@Mapper
public interface AiGroupMemoryConflictMapper {

    void insert(AiGroupMemoryConflictEntity entity);

    List<AiGroupMemoryConflictEntity> selectByCandidateId(@Param("candidateId") Long candidateId);

    void updateResolutionStatus(@Param("id") Long id,
                                @Param("resolutionStatus") String resolutionStatus,
                                @Param("resolutionReason") String resolutionReason,
                                @Param("resolutionNote") String resolutionNote);
}
