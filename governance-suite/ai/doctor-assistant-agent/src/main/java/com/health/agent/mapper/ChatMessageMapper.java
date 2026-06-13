package com.health.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.health.agent.model.ChatMessage;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天记录 Mapper
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessage> {
}
