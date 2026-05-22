package com.lx.governance.order.helper;

import org.apache.shardingsphere.infra.hint.HintManager;

import java.util.function.Supplier;

/**
 * 读主库辅助工具
 * 提供强制读主库的便捷方法
 */
public class ReadMasterHelper {

    /**
     * 强制从主库读取数据
     * @param supplier 查询逻辑
     * @return 查询结果
     */
    public static <T> T readFromMaster(Supplier<T> supplier) {
        try (HintManager hintManager = HintManager.getInstance()) {
            hintManager.setWriteRouteOnly(); // 强制使用写库（master）
            return supplier.get();
        }
    }
}
