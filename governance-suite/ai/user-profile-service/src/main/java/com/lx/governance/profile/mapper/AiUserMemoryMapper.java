package com.lx.governance.profile.mapper;

import com.lx.governance.profile.entity.AiUserMemoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.util.List;

@Mapper
public interface AiUserMemoryMapper {
    List<AiUserMemoryEntity> selectByCustomerId(@Param("customerId") String customerId);
}
