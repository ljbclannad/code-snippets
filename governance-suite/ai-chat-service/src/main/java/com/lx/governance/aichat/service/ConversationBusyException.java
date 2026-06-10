package com.lx.governance.aichat.service;

public class ConversationBusyException extends RuntimeException {

    public ConversationBusyException(String message) {
        super(message);
    }
}
