package com.lx.governance.profile.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import com.lx.governance.profile.entity.UserProfileBehaviorPreferenceEntity;

@Mapper
public interface UserProfileBehaviorPreferenceMapper {

    UserProfileBehaviorPreferenceEntity selectByCustomerId(@Param("customerId") String customerId);

    void insert(UserProfileBehaviorPreferenceEntity entity);

    void updateField(@Param("customerId") String customerId,
                     @Param("fieldName") String fieldName,
                     @Param("fieldValue") Object fieldValue);
}
