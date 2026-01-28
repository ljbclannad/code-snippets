package com.example.order.mapper;

import com.example.order.model.OutboxEvent;
import org.apache.ibatis.annotations.*;

import java.util.List;

public interface OutboxMapper {

    @Insert("INSERT INTO t_outbox(aggregate_type, aggregate_id, event_type, payload_json, status, create_time) " +
            "VALUES(#{aggregateType}, #{aggregateId}, #{eventType}, #{payloadJson}, #{status}, NOW())")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OutboxEvent e);

    @Select("SELECT id, aggregate_type, aggregate_id, event_type, payload_json, status, create_time FROM t_outbox WHERE status = 'NEW' ORDER BY id ASC LIMIT #{limit}")
    List<OutboxEvent> findNew(@Param("limit") int limit);

    @Update("UPDATE t_outbox SET status = 'SENT' WHERE id = #{id}")
    int markSent(@Param("id") long id);
}
