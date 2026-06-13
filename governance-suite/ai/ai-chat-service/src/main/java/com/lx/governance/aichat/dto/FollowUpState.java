package com.lx.governance.aichat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 追问状态
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowUpState {

    /**
     * 会话ID
     */
    private String conversationId;

    /**
     * 当前追问轮数
     */
    @Builder.Default
    private int currentRound = 0;

    /**
     * 已追问的问题
     */
    @Builder.Default
    private List<String> askedQuestions = new ArrayList<>();

    /**
     * 已追问的字段路径
     */
    @Builder.Default
    private Set<String> askedFieldPaths = new HashSet<>();

    /**
     * 首次追问时间
     */
    private long firstFollowUpTime;

    /**
     * 最后追问时间
     */
    private long lastFollowUpTime;

    /**
     * 是否完成追问
     */
    private boolean completed;
}
