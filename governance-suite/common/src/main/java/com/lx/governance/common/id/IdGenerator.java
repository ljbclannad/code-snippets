package com.lx.governance.common.id;

/**
 * ID 生成器接口。
 * 提供统一的 ID 生成抽象，支持多种实现（Snowflake、号段模式等）。
 */
public interface IdGenerator {

    /**
     * 生成下一个全局唯一 ID。
     *
     * @return 64 位正整数 ID
     */
    long nextId();

    /**
     * 生成下一个 ID 并以字符串形式返回。
     *
     * @return ID 的十进制字符串表示
     */
    default String nextIdString() {
        return Long.toString(nextId());
    }
}
