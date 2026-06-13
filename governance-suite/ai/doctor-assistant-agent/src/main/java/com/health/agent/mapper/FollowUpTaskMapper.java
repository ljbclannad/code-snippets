package com.health.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.agent.model.FollowUpTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 随访任务 Mapper
 */
@Mapper
public interface FollowUpTaskMapper extends BaseMapper<FollowUpTask> {

    @Select("SELECT * FROM follow_up_task WHERE customer_id = #{customerId} AND status = #{status} ORDER BY due_date ASC")
    List<FollowUpTask> findByCustomerIdAndStatus(@Param("customerId") String customerId, @Param("status") String status);

    @Select("SELECT * FROM follow_up_task WHERE customer_id = #{customerId} ORDER BY created_at DESC LIMIT #{limit}")
    List<FollowUpTask> findByCustomerId(@Param("customerId") String customerId, @Param("limit") int limit);

    @Select("SELECT * FROM follow_up_task WHERE task_id = #{taskId}")
    FollowUpTask findByTaskId(@Param("taskId") String taskId);
}
