package com.lx.governance.profile.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.lx.governance.profile.entity.UserProfileFieldReviewAuditEntity;

@Mapper
public interface UserProfileFieldReviewAuditMapper {

    void insert(UserProfileFieldReviewAuditEntity entity);

    List<UserProfileFieldReviewAuditEntity> selectByCandidateFieldId(@Param("candidateFieldId") Long candidateFieldId);
}
