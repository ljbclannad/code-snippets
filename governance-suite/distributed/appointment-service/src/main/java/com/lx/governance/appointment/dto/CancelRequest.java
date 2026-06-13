package com.lx.governance.appointment.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 取消预约请求
 */
@Data
public class CancelRequest {

    /**
     * 订单号
     */
    @NotBlank(message = "订单号不能为空")
    private String orderNo;

    /**
     * 取消原因（可选）
     */
    private String reason;
}
