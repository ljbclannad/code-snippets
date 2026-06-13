package com.lx.governance.aichat.service;

import com.lx.governance.aichat.exception.ConversationException;
import com.lx.governance.aichat.exception.ErrorCode;

/**
 * @deprecated 使用 {@link ConversationException#busy(String)} 替代
 */
@Deprecated
public class ConversationBusyException extends ConversationException {

    public ConversationBusyException(String message) {
        super(ErrorCode.CONVERSATION_BUSY, message);
    }
}
