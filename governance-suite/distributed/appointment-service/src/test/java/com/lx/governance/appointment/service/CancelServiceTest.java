package com.lx.governance.appointment.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.lx.governance.appointment.entity.AppointmentOrder;
import com.lx.governance.appointment.entity.QuotaRefundLog;
import com.lx.governance.appointment.mapper.AppointmentOrderMapper;
import com.lx.governance.appointment.mapper.QuotaRefundLogMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CancelServiceTest {

    @Mock
    private AppointmentOrderMapper orderMapper;

    @Mock
    private QuotaRefundLogMapper refundLogMapper;

    @Mock
    private IdempotencyService idempotencyService;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private RedisScript<String> refundLuaScript;

    @Mock
    private ValueOperations<String, String> valueOperations;

    @InjectMocks
    private CancelService cancelService;

    private AppointmentOrder mockOrder;

    @BeforeEach
    void setUp() {
        mockOrder = new AppointmentOrder();
        mockOrder.setId(1L);
        mockOrder.setOrderNo("ORDER123");
        mockOrder.setUserId(100L);
        mockOrder.setScheduleId(200L);
        mockOrder.setDoctorId(300L);
        mockOrder.setDeptId(400L);
        mockOrder.setAppointmentDate(LocalDate.now().plusDays(1));
        mockOrder.setTimeSlot("09:00-10:00");
        mockOrder.setFeeCents(5000);
        mockOrder.setStatus("RESERVED");
        mockOrder.setReservedAt(LocalDateTime.now());
        mockOrder.setVersion(1);
    }

    @Test
    void testCancelOrder_Success() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);
        when(idempotencyService.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(orderMapper.updateById(any(AppointmentOrder.class))).thenReturn(1);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn("REFUNDED");

        // When
        cancelService.cancelOrder(100L, "ORDER123", "用户主动取消");

        // Then
        verify(orderMapper).selectOne(any(LambdaQueryWrapper.class));
        verify(idempotencyService).tryLock(eq("idempotency:refund:ORDER123"), any(Duration.class));

        ArgumentCaptor<AppointmentOrder> orderCaptor = ArgumentCaptor.forClass(AppointmentOrder.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        AppointmentOrder updatedOrder = orderCaptor.getValue();
        assertEquals("CANCELLED", updatedOrder.getStatus());
        assertEquals("用户主动取消", updatedOrder.getCancelReason());
        assertNotNull(updatedOrder.getCancelledAt());
        assertEquals(1, updatedOrder.getVersion());

        verify(redisTemplate).execute(
            eq(refundLuaScript),
            eq(Arrays.asList("appointment:stock:200", "appointment:buyers:200")),
            eq("100")
        );

        ArgumentCaptor<QuotaRefundLog> logCaptor = ArgumentCaptor.forClass(QuotaRefundLog.class);
        verify(refundLogMapper).insert(logCaptor.capture());
        QuotaRefundLog log = logCaptor.getValue();
        assertEquals(200L, log.getScheduleId());
        assertEquals("ORDER123", log.getOrderNo());
        assertEquals("CANCEL", log.getRefundType());
        assertNotNull(log.getRefundedAt());
    }

    @Test
    void testCancelOrder_WithNullReason_UsesDefaultReason() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);
        when(idempotencyService.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(orderMapper.updateById(any(AppointmentOrder.class))).thenReturn(1);
        when(redisTemplate.execute(any(RedisScript.class), anyList(), anyString())).thenReturn("REFUNDED");

        // When
        cancelService.cancelOrder(100L, "ORDER123", null);

        // Then
        ArgumentCaptor<AppointmentOrder> orderCaptor = ArgumentCaptor.forClass(AppointmentOrder.class);
        verify(orderMapper).updateById(orderCaptor.capture());
        assertEquals("用户主动取消", orderCaptor.getValue().getCancelReason());
    }

    @Test
    void testCancelOrder_OrderNotFound_ThrowsException() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(null);

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            cancelService.cancelOrder(100L, "ORDER123", "取消");
        });
        assertEquals("订单不存在", exception.getMessage());

        verify(orderMapper).selectOne(any(LambdaQueryWrapper.class));
        verify(idempotencyService, never()).tryLock(anyString(), any(Duration.class));
        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
    }

    @Test
    void testCancelOrder_UnauthorizedUser_ThrowsException() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> {
            cancelService.cancelOrder(999L, "ORDER123", "取消");
        });
        assertEquals("无权操作该订单", exception.getMessage());

        verify(orderMapper).selectOne(any(LambdaQueryWrapper.class));
        verify(idempotencyService, never()).tryLock(anyString(), any(Duration.class));
        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
    }

    @Test
    void testCancelOrder_InvalidStatus_ThrowsException() {
        // Given
        mockOrder.setStatus("PAID");
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            cancelService.cancelOrder(100L, "ORDER123", "取消");
        });
        assertEquals("订单状态不正确，当前状态: PAID", exception.getMessage());

        verify(orderMapper).selectOne(any(LambdaQueryWrapper.class));
        verify(idempotencyService, never()).tryLock(anyString(), any(Duration.class));
        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
    }

    @Test
    void testCancelOrder_IdempotencyCheck_ReturnsEarly() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);
        when(idempotencyService.tryLock(anyString(), any(Duration.class))).thenReturn(false);

        // When
        cancelService.cancelOrder(100L, "ORDER123", "取消");

        // Then
        verify(orderMapper).selectOne(any(LambdaQueryWrapper.class));
        verify(idempotencyService).tryLock(eq("idempotency:refund:ORDER123"), any(Duration.class));
        verify(orderMapper, never()).updateById(any(AppointmentOrder.class));
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
        verify(refundLogMapper, never()).insert(any(QuotaRefundLog.class));
    }

    @Test
    void testCancelOrder_OptimisticLockFailure_OrderAlreadyCancelled_ReturnsEarly() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);
        when(idempotencyService.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(orderMapper.updateById(any(AppointmentOrder.class))).thenReturn(0);

        AppointmentOrder cancelledOrder = new AppointmentOrder();
        cancelledOrder.setId(1L);
        cancelledOrder.setStatus("CANCELLED");
        when(orderMapper.selectById(1L)).thenReturn(cancelledOrder);

        // When
        cancelService.cancelOrder(100L, "ORDER123", "取消");

        // Then
        verify(orderMapper).updateById(any(AppointmentOrder.class));
        verify(orderMapper).selectById(1L);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
        verify(refundLogMapper, never()).insert(any(QuotaRefundLog.class));
    }

    @Test
    void testCancelOrder_OptimisticLockFailure_OrderNotCancelled_ThrowsException() {
        // Given
        when(orderMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(mockOrder);
        when(idempotencyService.tryLock(anyString(), any(Duration.class))).thenReturn(true);
        when(orderMapper.updateById(any(AppointmentOrder.class))).thenReturn(0);

        AppointmentOrder stillReservedOrder = new AppointmentOrder();
        stillReservedOrder.setId(1L);
        stillReservedOrder.setStatus("RESERVED");
        when(orderMapper.selectById(1L)).thenReturn(stillReservedOrder);

        // When & Then
        IllegalStateException exception = assertThrows(IllegalStateException.class, () -> {
            cancelService.cancelOrder(100L, "ORDER123", "取消");
        });
        assertEquals("订单更新失败，版本冲突且订单未取消", exception.getMessage());

        verify(orderMapper).updateById(any(AppointmentOrder.class));
        verify(orderMapper).selectById(1L);
        verify(redisTemplate, never()).execute(any(RedisScript.class), anyList(), anyString());
        verify(refundLogMapper, never()).insert(any(QuotaRefundLog.class));
    }
}
