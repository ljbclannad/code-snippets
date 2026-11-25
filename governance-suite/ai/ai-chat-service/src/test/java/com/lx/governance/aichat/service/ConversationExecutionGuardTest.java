package com.lx.governance.aichat.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import org.junit.jupiter.api.Test;

import com.lx.governance.aichat.exception.ConversationException;

class ConversationExecutionGuardTest {

    @Test
    void cancellingResultKeepsConversationBusyUntilWorkFinishes() {
        var guard = new ConversationExecutionGuard(0);
        var running = new CompletableFuture<String>();
        var result = guard.tryExecute("conversation", () -> running);

        assertTrue(result.cancel(false));
        var busy = assertThrows(CompletionException.class, () -> guard.tryExecute("conversation", () -> {
            throw new AssertionError("原请求仍在执行，不能开始同一会话的下一轮");
        }).join());
        assertInstanceOf(ConversationException.class, busy.getCause());

        running.complete("first");
        assertEquals("second", guard.tryExecute("conversation", () -> CompletableFuture.completedFuture("second")).join());
    }
}
