package com.health.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.agent.model.HealthKnowledge;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 健康知识 Mapper
 */
@Mapper
public interface HealthKnowledgeMapper extends BaseMapper<HealthKnowledge> {

    @Select("SELECT * FROM health_knowledge WHERE topic = #{topic} LIMIT #{limit}")
    List<HealthKnowledge> findByTopic(@Param("topic") String topic, @Param("limit") int limit);

    @Select("SELECT * FROM health_knowledge WHERE category = #{category} LIMIT #{limit}")
    List<HealthKnowledge> findByCategory(@Param("category") String category, @Param("limit") int limit);

    @Select("SELECT * FROM health_knowledge WHERE MATCH(title, content) AGAINST(#{keyword} IN NATURAL LANGUAGE MODE) LIMIT #{limit}")
    List<HealthKnowledge> searchByKeyword(@Param("keyword") String keyword, @Param("limit") int limit);
}
