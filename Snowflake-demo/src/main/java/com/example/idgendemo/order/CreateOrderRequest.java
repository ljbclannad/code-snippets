package com.example.idgendemo.order;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public class CreateOrderRequest {

    @NotNull
    public Long userId;

    @NotNull
    @DecimalMin("0.01")
    public BigDecimal amount;
}
