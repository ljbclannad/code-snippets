package com.lx.governance.profile.mapper;

import java.util.List;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.lx.governance.profile.entity.UserProfileFieldConflictEntity;

@Mapper
public interface UserProfileFieldConflictMapper {

    void insert(UserProfileFieldConflictEntity entity);

    List<UserProfileFieldConflictEntity> selectByCandidateFieldId(@Param("candidateFieldId") Long candidateFieldId);

    void updateResolutionStatus(@Param("id") Long id, @Param("resolutionStatus") String resolutionStatus);
}
