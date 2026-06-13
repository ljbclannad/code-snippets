package com.lx.governance.aichat.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.lx.governance.aichat.entity.AiChatHistory;
import org.apache.ibatis.annotations.Mapper;

/**
 * AI 聊天历史记录 Mapper
 */
@Mapper
public interface AiChatHistoryMapper extends BaseMapper<AiChatHistory> {
}
