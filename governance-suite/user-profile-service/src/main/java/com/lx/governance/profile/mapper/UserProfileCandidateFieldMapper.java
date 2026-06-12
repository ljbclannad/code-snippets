package com.lx.governance.profile.mapper;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.lx.governance.profile.entity.UserProfileCandidateFieldEntity;

@Mapper
public interface UserProfileCandidateFieldMapper {

    void insert(UserProfileCandidateFieldEntity entity);

    UserProfileCandidateFieldEntity selectLatest(@Param("customerId") String customerId,
                                                 @Param("fieldPath") String fieldPath,
                                                 @Param("normalizedValue") String normalizedValue);

    List<UserProfileCandidateFieldEntity> selectActiveByCustomerId(@Param("customerId") String customerId);

    UserProfileCandidateFieldEntity selectById(@Param("id") Long id);

    void incrementHitCount(@Param("id") Long id,
                           @Param("confidenceScore") BigDecimal confidenceScore,
                           @Param("source") String source);

    void updateStatus(@Param("id") Long id,
                      @Param("status") String status,
                      @Param("reviewedBy") String reviewedBy,
                      @Param("reviewedAt") LocalDateTime reviewedAt);
}
