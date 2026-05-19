package com.health.agent.service;

import com.health.agent.mapper.HealthKnowledgeMapper;
import com.health.agent.model.HealthKnowledge;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 健康知识服务
 */
@Slf4j
@Service
public class HealthKnowledgeService {

    @Autowired
    private HealthKnowledgeMapper healthKnowledgeMapper;

    /**
     * 根据主题查询健康知识
     */
    @Cacheable(value = "healthKnowledge", key = "'topic:' + #topic")
    public List<HealthKnowledge> getByTopic(String topic, int limit) {
        log.info("查询健康知识: topic={}, limit={}", topic, limit);
        return healthKnowledgeMapper.findByTopic(topic, limit);
    }

    /**
     * 根据分类查询健康知识
     */
    @Cacheable(value = "healthKnowledge", key = "'category:' + #category")
    public List<HealthKnowledge> getByCategory(String category, int limit) {
        log.info("查询健康知识: category={}, limit={}", category, limit);
        return healthKnowledgeMapper.findByCategory(category, limit);
    }

    /**
     * 全文搜索健康知识
     */
    @Cacheable(value = "healthKnowledge", key = "'search:' + #keyword")
    public List<HealthKnowledge> searchByKeyword(String keyword, int limit) {
        log.info("搜索健康知识: keyword={}, limit={}", keyword, limit);
        return healthKnowledgeMapper.searchByKeyword(keyword, limit);
    }
}
