package com.lx.governance.vectormemory.mapper;

import com.lx.governance.vectormemory.entity.AiGroupMemoryEntity;
import com.lx.governance.vectormemory.dto.MemorySearchFilter;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * AI群组记忆 Mapper 接口
 */
@Mapper
public interface AiGroupMemoryMapper {

    /**
     * 插入记忆
     *
     * @param entity 记忆实体
     */
    void insert(AiGroupMemoryEntity entity);

    /**
     * 更新向量ID
     *
     * @param id 记忆ID
     * @param embeddingId 向量ID
     */
    void updateEmbeddingId(@Param("id") Long id, @Param("embeddingId") String embeddingId);

    /**
     * 更新正式记忆的核心字段和向量ID
     *
     * @param entity 记忆实体
     */
    void updateConfirmedMemory(AiGroupMemoryEntity entity);

    /**
     * 查询群组记忆
     *
     * @param groupId 群组ID
     * @param customerId 用户ID
     * @return 记忆列表
     */
    List<AiGroupMemoryEntity> selectByGroupIdAndCustomerId(@Param("groupId") String groupId,
                                                            @Param("customerId") String customerId);

    /**
     * 根据ID查询
     *
     * @param id 记忆ID
     * @return 记忆实体
     */
    AiGroupMemoryEntity selectById(@Param("id") Long id);

    /**
     * 根据向量ID批量查询
     *
     * @param embeddingIds 向量ID列表
     * @return 记忆列表
     */
    List<AiGroupMemoryEntity> selectByEmbeddingIds(@Param("embeddingIds") List<String> embeddingIds);

    /**
     * 根据向量ID批量查询，并在 SQL 层执行元数据过滤
     *
     * @param embeddingIds 向量ID列表
     * @param filter 检索过滤条件
     * @return 记忆列表
     */
    List<AiGroupMemoryEntity> selectByEmbeddingIdsWithFilter(
            @Param("embeddingIds") List<String> embeddingIds,
            @Param("filter") MemorySearchFilter filter);

    /**
     * 根据主键批量查询，并在 SQL 层执行元数据过滤
     *
     * @param ids 主键列表
     * @param filter 检索过滤条件
     * @return 记忆列表
     */
    List<AiGroupMemoryEntity> selectByIdsWithFilter(
            @Param("ids") List<Long> ids,
            @Param("filter") MemorySearchFilter filter);

    /**
     * 基于关键词检索记忆，并在 SQL 层执行元数据过滤
     *
     * @param groupId 群组ID
     * @param customerId 用户ID
     * @param query 查询文本
     * @param limit 返回数量
     * @param filter 检索过滤条件
     * @return 记忆列表
     */
    List<AiGroupMemoryEntity> selectByKeyword(
            @Param("groupId") String groupId,
            @Param("customerId") String customerId,
            @Param("query") String query,
            @Param("limit") int limit,
            @Param("filter") MemorySearchFilter filter);

    /**
     * 检查相似记忆
     *
     * @param groupId 群组ID
     * @param customerId 用户ID
     * @param content 记忆内容
     * @return 相似记忆数量
     */
    int countSimilarMemory(@Param("groupId") String groupId,
                          @Param("customerId") String customerId,
                          @Param("content") String content);

    /**
     * 软删除
     *
     * @param id 记忆ID
     */
    void deleteById(@Param("id") Long id);

    /**
     * 基于游标的分页查询待迁移的记忆（embedding_id 为 NULL）
     *
     * @param lastProcessedId 上次处理的最大ID（首次传入0）
     * @param limit 每页数量
     * @return 记忆列表
     */
    List<AiGroupMemoryEntity> selectPendingMigration(@Param("lastProcessedId") long lastProcessedId,
                                                      @Param("limit") int limit);

    /**
     * 统计待迁移的记忆总数（embedding_id 为 NULL）
     *
     * @return 记忆总数
     */
    int countPendingMigration();
}
