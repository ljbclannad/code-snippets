package io.github.messageguard.api.spi;

import io.github.messageguard.api.model.MessageContext;

public interface IdempotentKeyResolver {

    String resolve(MessageContext context, Object payload);
}
