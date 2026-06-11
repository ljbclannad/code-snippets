package com.lx.governance.knowledgebase.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.governance.knowledgebase.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识文档 Mapper
 */
@Mapper
public interface KnowledgeDocumentMapper extends BaseMapper<KnowledgeDocument> {
}
