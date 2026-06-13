package com.lx.governance.aichat.degradation;

import lombok.Getter;

/**
 * 降级级别
 * 从轻到重，逐层降级
 */
@Getter
public enum DegradationLevel {

    /**
     * 正常服务 - 完整功能
     */
    NORMAL(0, "正常服务",
            "完整功能：记忆检索 + 完整上下文 + 实时推荐"),

    /**
     * 轻度降级 - 关闭非核心功能
     */
    LIGHT(1, "轻度降级",
            "关闭相似对话推荐，保留记忆检索和上下文"),

    /**
     * 中度降级 - 减少外部依赖
     */
    MEDIUM(2, "中度降级",
            "关闭记忆检索，保留会话上下文和对话历史"),

    /**
     * 重度降级 - 最小化上下文
     */
    HEAVY(3, "重度降级",
            "仅保留最近2轮对话历史，关闭记忆和推荐"),

    /**
     * 极限降级 - 无上下文
     */
    CRITICAL(4, "极限降级",
            "无上下文，仅基础问答，使用缓存响应"),

    /**
     * 熔断 - 服务不可用
     */
    CIRCUIT_BREAK(5, "熔断",
            "服务暂时不可用，返回友好提示");

    private final int level;
    private final String name;
    private final String description;

    DegradationLevel(int level, String name, String description) {
        this.level = level;
        this.name = name;
        this.description = description;
    }

    /**
     * 是否比另一个级别更严重
     */
    public boolean isMoreSevereThan(DegradationLevel other) {
        return this.level > other.level;
    }

    /**
     * 是否需要降级（相对于正常状态）
     */
    public boolean isDegraded() {
        return this.level > NORMAL.level;
    }
}
