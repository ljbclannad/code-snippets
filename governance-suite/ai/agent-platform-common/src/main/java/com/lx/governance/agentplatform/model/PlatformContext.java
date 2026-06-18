package com.lx.governance.agentplatform.model;

import java.util.ArrayList;
import java.util.List;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

/**
 * 平台统一上下文总线载荷。
 * <p>
 * 这是跨服务透传的标准上下文容器，承载会话、主体、证据和工具输出。
 */
@Value
@Builder(toBuilder = true)
public class PlatformContext {

    /** 上下文结构版本。 */
    String schemaVersion;

    /** 当前上下文所在的业务通道。 */
    PlatformContextChannel channel;

    /** 上下文实例标识。 */
    String contextId;

    /** 对话标识。 */
    String conversationId;

    /** 患者或用户标识。 */
    String customerId;

    /** 医生或处理人标识。 */
    String doctorId;

    /** 已展开的字段列表。 */
    @Singular("field")
    List<PlatformContextField> fields;

    /** 支撑结论的证据列表。 */
    @Builder.Default
    List<PlatformContextEvidence> evidences = new ArrayList<>();

    /** 已调用工具的输出记录。 */
    @Builder.Default
    List<PlatformToolOutput> toolOutputs = new ArrayList<>();
}
