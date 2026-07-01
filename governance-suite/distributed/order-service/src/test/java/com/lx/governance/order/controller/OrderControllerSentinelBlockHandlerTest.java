package com.lx.governance.order.controller;

import com.alibaba.csp.sentinel.slots.block.flow.FlowException;
import com.lx.governance.common.api.ApiResponse;
import com.lx.governance.order.service.OrderAppService;
import com.lx.governance.order.service.OrderTccAppService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class OrderControllerSentinelBlockHandlerTest {

    @Test
    void shouldReturnUnifiedFailureForCreateBlock() {
        OrderController controller = new OrderController(
            Mockito.mock(OrderAppService.class),
            Mockito.mock(OrderTccAppService.class)
        );

        ApiResponse<?> response = controller.handleCreateBlocked(null, null, null, new FlowException("blocked"));

        assertFalse(response.isSuccess());
        assertEquals("ORDER_RATE_LIMITED", response.getCode());
        assertEquals("Order create rate limit exceeded", response.getMessage());
    }

    @Test
    void shouldReturnUnifiedFailureForCreateV2Block() {
        OrderController controller = new OrderController(
            Mockito.mock(OrderAppService.class),
            Mockito.mock(OrderTccAppService.class)
        );

        ApiResponse<Map<String, String>> response =
            controller.handleCreateV2Blocked(null, null, null, new FlowException("blocked"));

        assertFalse(response.isSuccess());
        assertEquals("ORDER_RATE_LIMITED", response.getCode());
        assertEquals("Order create rate limit exceeded", response.getMessage());
    }
}
