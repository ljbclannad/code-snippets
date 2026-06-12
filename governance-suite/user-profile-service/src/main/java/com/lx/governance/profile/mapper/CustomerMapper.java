package com.lx.governance.profile.mapper;

import com.lx.governance.profile.entity.CustomerEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface CustomerMapper {
    CustomerEntity selectByCustomerId(@Param("customerId") String customerId);

    void updateField(@Param("customerId") String customerId,
                     @Param("fieldName") String fieldName,
                     @Param("fieldValue") Object fieldValue);
}
