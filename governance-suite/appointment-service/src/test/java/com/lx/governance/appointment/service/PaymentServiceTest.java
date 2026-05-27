package com.lx.governance.appointment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.appointment.entity.AppointmentOrder;
import com.lx.governance.appointment.mapper.AppointmentOrderMapper;
import com.lx.governance.common.id.SnowflakeIdGenerator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock
    private AppointmentOrderMapper orderMapper;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private SnowflakeIdGenerator snowflakeIdGenerator;

    @Mock
    private JdbcTemplate jdbcTemplate;

    @InjectMocks
    private PaymentService paymentService;

    private AppointmentOrder mockOrder;

    @BeforeEach
    void setUp() {
        mockOrder = new AppointmentOrder();
        mockOrder.setId(1L);
        mockOrder.setOrderNo("ORD123456");
        mockOrder.setUserId(100L);
        mockOrder.setScheduleId(200L);
        mockOrder.setStatus("RESERVED");
        mockOrder.setVersion(1);
    }

    @Test
    void payOrder_success() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);
        when(idempotencyService.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(orderMapper.updateById((AppointmentOrder) any())).thenReturn(1);
        when(snowflakeIdGenerator.nextId()).thenReturn(999L);
        when(jdbcTemplate.update(anyString(), (Object[]) any())).thenReturn(1);

        // When
        paymentService.payOrder(100L, "ORD123456");

        // Then
        verify(orderMapper).selectOne(any(LambdaQueryWrapper.class));
        verify(idempotencyService).tryLock(eq("idempotency:pay:ORD123456"), eq(Duration.ofHours(1)));

        ArgumentCaptor<AppointmentOrder> orderCaptor = ArgumentCaptor.forClass(AppointmentOrder.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        AppointmentOrder updatedOrder = orderCaptor.getValue();
        assertEquals("PAID", updatedOrder.getStatus());
        assertNotNull(updatedOrder.getPaidAt());
        assertEquals(1, updatedOrder.getVersion());

        verify(jdbcTemplate).update(anyString(), (Object[]) any());
    }

    @Test
    void payOrder_orderNotFound_throwsException() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // When & Then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> paymentService.payOrder(100L, "ORD123456"));
        assertEquals("订单不存在", ex.getMessage());

        verify(idempotencyService, never()).tryLock(anyString(), any(Duration.class));
        verify(orderMapper, never()).updateById((AppointmentOrder) any());
    }

    @Test
    void payOrder_wrongUser_throwsException() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);

        // When & Then
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
            () -> paymentService.payOrder(999L, "ORD123456"));
        assertEquals("无权操作该订单", ex.getMessage());

        verify(idempotencyService, never()).tryLock(anyString(), any(Duration.class));
        verify(orderMapper, never()).updateById((AppointmentOrder) any());
    }

    @Test
    void payOrder_wrongStatus_throwsException() {
        // Given
        mockOrder.setStatus("PAID");
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);

        // When & Then
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> paymentService.payOrder(100L, "ORD123456"));
        assertEquals("订单状态不正确，当前状态: PAID", ex.getMessage());

        verify(idempotencyService, never()).tryLock(anyString(), any(Duration.class));
        verify(orderMapper, never()).updateById((AppointmentOrder) any());
    }

    @Test
    void payOrder_idempotencyFailed_returnsWithoutError() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);
        when(idempotencyService.tryLock(anyString(), any(Duration.class))).thenReturn(false);

        // When
        paymentService.payOrder(100L, "ORD123456");

        // Then
        verify(idempotencyService).tryLock(eq("idempotency:pay:ORD123456"), any(Duration.class));
        verify(orderMapper, never()).updateById((AppointmentOrder) any());
        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }

    @Test
    void payOrder_optimisticLockFailed_throwsException() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);
        when(idempotencyService.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(orderMapper.updateById((AppointmentOrder) any())).thenReturn(0);

        // When & Then
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> paymentService.payOrder(100L, "ORD123456"));
        assertEquals("订单更新失败，可能已被其他操作修改", ex.getMessage());

        verify(jdbcTemplate, never()).update(anyString(), (Object[]) any());
    }
}
