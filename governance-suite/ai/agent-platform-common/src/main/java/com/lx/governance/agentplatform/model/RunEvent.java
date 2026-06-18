package com.lx.governance.agentplatform.model;

import java.util.Map;

import lombok.Builder;
import lombok.Value;

/**
 * 平台运行时事件。
 * <p>
 * 记录状态变化、审批、回退和异常等关键节点。
 */
@Value
@Builder(toBuilder = true)
public class RunEvent {

    /** 事件标识。 */
    String eventId;

    /** 所属 run。 */
    String runId;

    /** 关联步骤。 */
    String stepId;

    /** 事件类型。 */
    RunEventType eventType;

    /** 供人读的事件说明。 */
    String message;

    /** 事件附带的结构化数据。 */
    Map<String, Object> payload;

    /** 事件创建时间。 */
    Long createdAt;
}
