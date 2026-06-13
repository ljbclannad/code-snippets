package com.lx.governance.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.governance.knowledgebase.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 知识库 Mapper
 */
@Mapper
public interface KnowledgeBaseMapper extends BaseMapper<KnowledgeBase> {

    @Update("""
            UPDATE knowledge_base
            SET document_count = document_count + #{delta},
                update_time = NOW()
            WHERE kb_id = #{kbId} AND status = 'ACTIVE'
            """)
    int updateDocumentCount(@Param("kbId") String kbId, @Param("delta") int delta);

    @Update("""
            UPDATE knowledge_base
            SET chunk_count = chunk_count + #{delta},
                update_time = NOW()
            WHERE kb_id = #{kbId} AND status = 'ACTIVE'
            """)
    int updateChunkCount(@Param("kbId") String kbId, @Param("delta") int delta);
}
