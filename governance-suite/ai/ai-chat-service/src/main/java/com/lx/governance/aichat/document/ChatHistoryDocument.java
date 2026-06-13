package com.lx.governance.aichat.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;
import org.springframework.data.elasticsearch.annotations.FieldType;
import org.springframework.data.elasticsearch.annotations.Setting;

import java.time.LocalDateTime;

/**
 * 对话历史 ES 文档
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "chat_history")
@Setting(settingPath = "elasticsearch/chat-history-settings.json")
public class ChatHistoryDocument {

    @Id
    private String id;

    @Field(type = FieldType.Keyword)
    private String customerId;

    @Field(type = FieldType.Keyword)
    private String conversationId;

    /**
     * 用户消息 - 使用 IK 分词器进行全文搜索
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String userMessage;

    /**
     * AI 回复 - 使用 IK 分词器进行全文搜索
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String aiResponse;

    @Field(type = FieldType.Integer)
    private Integer promptTokens;

    @Field(type = FieldType.Integer)
    private Integer completionTokens;

    @Field(type = FieldType.Integer)
    private Integer totalTokens;

    @Field(type = FieldType.Date, format = {}, pattern = "uuuu-MM-dd'T'HH:mm:ss")
    private LocalDateTime createTime;
}
