package io.github.messageguard.api.spi;

import io.github.messageguard.api.model.CompensationContext;
import io.github.messageguard.api.model.CompensationResult;

public interface CompensationHandler {

    CompensationResult compensate(CompensationContext context);
}
