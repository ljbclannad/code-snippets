package com.lx.governance.vectormemory.repo;

import com.lx.governance.vectormemory.domain.DeadLetterMessage;
import org.apache.ibatis.annotations.*;

import java.util.List;

@Mapper
public interface DeadLetterMessageMapper {

    @Insert("INSERT INTO t_dead_letter_message (exchange, routing_key, message_body, failure_reason, " +
            "retry_count, status, created_at, updated_at) " +
            "VALUES (#{exchange}, #{routingKey}, #{messageBody}, #{failureReason}, " +
            "#{retryCount}, #{status}, #{createdAt}, #{updatedAt})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(DeadLetterMessage message);

    @Update("UPDATE t_dead_letter_message SET retry_count = #{retryCount}, status = #{status}, " +
            "last_retry_at = #{lastRetryAt}, updated_at = #{updatedAt} WHERE id = #{id}")
    int update(DeadLetterMessage message);

    @Select("SELECT * FROM t_dead_letter_message WHERE status = #{status} ORDER BY created_at DESC LIMIT #{limit}")
    List<DeadLetterMessage> findByStatus(@Param("status") String status, @Param("limit") int limit);

    @Select("SELECT * FROM t_dead_letter_message WHERE id = #{id}")
    DeadLetterMessage findById(Long id);

    @Select("SELECT COUNT(*) FROM t_dead_letter_message WHERE status = #{status}")
    long countByStatus(String status);
}
