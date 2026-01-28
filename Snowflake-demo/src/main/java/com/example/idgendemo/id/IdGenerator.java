package com.example.idgendemo.id;

public interface IdGenerator {

    /**
     * 生成 ID；grayKey 用于灰度的稳定分流（例如 userId / tenantId），可以为 null。
     */
    long nextId(String grayKey);

    default long nextId() {
        return nextId(null);
    }

    /**
     * 当前发号策略（用于观测与排障）
     */
    String strategyName();
}
