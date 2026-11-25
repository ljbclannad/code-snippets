package com.lx.governance.aichat.service;

import java.util.concurrent.atomic.AtomicLong;
import com.lx.governance.aichat.dto.ChatRequest;

/** 单实例内的服务端轮次顺序，同一轮所有异步回调共用一个时间值。 */
final class ChatTurnOrder {
    private static final AtomicLong LAST_RECEIVED = new AtomicLong();

    private ChatTurnOrder() {}

    static void begin(ChatRequest request) {
        long receivedAt = LAST_RECEIVED.updateAndGet(previous -> Math.max(System.currentTimeMillis(), previous + 1));
        request.setMessageTimestamp(receivedAt);
    }
}
